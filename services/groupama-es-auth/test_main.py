import asyncio
import os
import time
import unittest
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException
from fastapi.testclient import TestClient
from playwright.async_api import async_playwright

from main import (
    LOGIN_URL,
    MFA_PATH_FRAGMENT,
    PENDING_TTL_SECONDS,
    ROOT_PATH,
    _auth_outcome,
    _browser_is_headless,
    _cleanup_expired,
    _close_all_pending,
    _configure_context,
    _enrich_positions,
    _fill_otp,
    _has_portal_session_cookie,
    _launch_browser,
    _new_context,
    _pending,
    _pending_lock,
    _submit_login,
    app,
)


class FakeResource:
    def __init__(self, method: str):
        self.method = method
        self.calls = 0

    async def close(self):
        if self.method != "close":
            raise AssertionError("unexpected close")
        self.calls += 1

    async def stop(self):
        if self.method != "stop":
            raise AssertionError("unexpected stop")
        self.calls += 1


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await _close_all_pending()

    async def asyncTearDown(self):
        await _close_all_pending()

    async def test_expired_authentication_closes_browser_resources(self):
        context = FakeResource("close")
        browser = FakeResource("close")
        playwright = FakeResource("stop")
        async with _pending_lock:
            _pending["expired"] = {
                "context": context,
                "browser": browser,
                "playwright": playwright,
                "created_at": time.time() - PENDING_TTL_SECONDS - 1,
            }

        await _cleanup_expired()

        self.assertNotIn("expired", _pending)
        self.assertEqual(context.calls, 1)
        self.assertEqual(browser.calls, 1)
        self.assertEqual(playwright.calls, 1)

    async def test_pending_authentication_is_claimed_once(self):
        from main import _take_pending

        state = {"created_at": time.time()}
        async with _pending_lock:
            _pending["process"] = state

        first, second = await asyncio.gather(
            _take_pending("process"),
            _take_pending("process"),
        )

        self.assertEqual(sum(value is state for value in (first, second)), 1)

    async def test_slow_optional_details_keep_the_reconciled_valuation_unit(self):
        accounts = [{
            "positions": [{
                "quantity": Decimal("1"),
                "currentPriceEur": Decimal("250"),
                "currentValueEur": Decimal("250"),
                "_detailsUrl": "https://www.gestion-epargne-salariale.fr/support",
            }],
        }]

        async def slow_detail(*_):
            await asyncio.sleep(1)
            return Decimal("25")

        with (
            patch("main._unit_value_from_details", side_effect=slow_detail),
            patch("main.DETAIL_ENRICHMENT_BUDGET_SECONDS", 0.01),
        ):
            await _enrich_positions(object(), accounts)

        position = accounts[0]["positions"][0]
        self.assertEqual(position["quantity"], Decimal("1"))
        self.assertEqual(position["currentPriceEur"], Decimal("250"))

    async def test_only_the_portal_id_session_cookie_is_accepted(self):
        self.assertFalse(_has_portal_session_cookie({
            "cookies": [{
                "name": "IdSes",
                "value": "third-party",
                "domain": "example.com",
            }],
        }))
        self.assertTrue(_has_portal_session_cookie({
            "cookies": [{
                "name": "IdSes",
                "value": "portal",
                "domain": ".gestion-epargne-salariale.fr",
            }],
        }))


class BrowserSelectorsTest(unittest.IsolatedAsyncioTestCase):
    async def test_virtual_display_uses_headed_chromium(self):
        with patch.dict(os.environ, {"DISPLAY": ":99"}, clear=True):
            self.assertFalse(_browser_is_headless())

    async def test_missing_display_keeps_headless_debug_fallback(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertTrue(_browser_is_headless())

    async def test_explicit_headless_override_wins_over_display(self):
        with patch.dict(
            os.environ,
            {"DISPLAY": ":99", "GROUPAMA_ES_HEADLESS": "true"},
            clear=True,
        ):
            self.assertTrue(_browser_is_headless())

    async def test_browser_omits_playwright_automation_flag(self):
        playwright = MagicMock()
        expected_browser = object()
        playwright.chromium.launch = AsyncMock(return_value=expected_browser)

        with patch("main._browser_is_headless", return_value=False):
            browser = await _launch_browser(playwright)

        self.assertIs(browser, expected_browser)
        playwright.chromium.launch.assert_awaited_once_with(
            headless=False,
            args=["--disable-blink-features=AutomationControlled"],
            ignore_default_args=["--enable-automation"],
        )

    async def test_browser_context_exposes_consistent_screen_geometry(self):
        browser = MagicMock()
        expected_context = object()
        browser.new_context = AsyncMock(return_value=expected_context)

        context = await _new_context(browser)

        self.assertIs(context, expected_context)
        browser.new_context.assert_awaited_once_with(
            locale="fr-FR",
            timezone_id="Europe/Paris",
            viewport={"width": 1536, "height": 864},
            screen={"width": 1920, "height": 1080},
        )

    async def test_browser_masks_automation_signals(self):
        async with async_playwright() as playwright:
            browser = await _launch_browser(playwright)
            context = await browser.new_context(locale="fr-FR")
            await _configure_context(context)
            page = await context.new_page()
            try:
                fingerprint = await page.evaluate("""
                  () => ({
                    webdriver: navigator.webdriver,
                    languages: navigator.languages,
                    hasChrome: Boolean(window.chrome),
                  })
                """)

                self.assertIsNone(fingerprint["webdriver"])
                self.assertEqual(fingerprint["languages"], ["fr-FR", "fr"])
                self.assertTrue(fingerprint["hasChrome"])
            finally:
                await context.close()
                await browser.close()

    async def test_login_timeout_without_visible_error_is_not_credentials(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            context = await browser.new_context()
            page = await context.new_page()
            await page.route(
                LOGIN_URL,
                lambda route: route.fulfill(
                    status=200,
                    content_type="text/html",
                    body="<p>Authentication is still in progress</p>",
                ),
            )
            try:
                await page.goto(LOGIN_URL)

                outcome = await _auth_outcome(
                    context,
                    page,
                    timeout_seconds=0.1,
                )

                self.assertEqual(outcome, "UPSTREAM_UNAVAILABLE")
            finally:
                await context.close()
                await browser.close()

    async def test_visible_login_error_is_invalid_credentials(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            context = await browser.new_context()
            page = await context.new_page()
            await page.route(
                LOGIN_URL,
                lambda route: route.fulfill(
                    status=200,
                    content_type="text/html",
                    body=(
                        '<div role="alert">'
                        "Identifiant ou mot de passe invalide"
                        "</div>"
                    ),
                ),
            )
            try:
                await page.goto(LOGIN_URL)

                outcome = await _auth_outcome(
                    context,
                    page,
                    timeout_seconds=0.5,
                )

                self.assertEqual(outcome, "INVALID_CREDENTIALS")
            finally:
                await context.close()
                await browser.close()

    async def test_current_portal_login_error_is_invalid_credentials(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            context = await browser.new_context()
            page = await context.new_page()
            await page.route(
                LOGIN_URL,
                lambda route: route.fulfill(
                    status=200,
                    content_type="text/html",
                    body=(
                        '<div id="ident-error-message">'
                        "Votre identifiant est inconnu ou votre mot de passe "
                        "est faux."
                        "</div>"
                    ),
                ),
            )
            try:
                await page.goto(LOGIN_URL)

                outcome = await _auth_outcome(
                    context,
                    page,
                    timeout_seconds=0.5,
                )

                self.assertEqual(outcome, "INVALID_CREDENTIALS")
            finally:
                await context.close()
                await browser.close()

    async def test_unknown_visible_cookie_banner_fails_safely(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                await page.set_content("""
                  <div id="cookieLB">
                    <button type="button">Unexpected consent action</button>
                  </div>
                  <input id="_userid">
                  <input id="_pwduser" type="password">
                  <span id="login-submit"><a href="#">Se connecter</a></span>
                """)

                with self.assertRaises(HTTPException) as raised:
                    await _submit_login(page, "customer", "secret")

                self.assertEqual(raised.exception.status_code, 502)
                self.assertEqual(
                    raised.exception.detail,
                    "UPSTREAM_FORMAT_CHANGED",
                )
                self.assertEqual(await page.locator("#_userid").input_value(), "")
            finally:
                await browser.close()

    async def test_cookie_banner_is_rejected_before_login_submission(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                await page.set_content("""
                  <style>
                    #cookieLB {
                      position: fixed;
                      inset: 0;
                      z-index: 100;
                      background: white;
                    }
                  </style>
                  <div id="cookieLB">
                    <a href="#" role="button"
                       aria-label="Refuser les cookies">REFUSER</a>
                  </div>
                  <form id="bloc_ident">
                    <input id="_userid" name="_cm_user">
                    <input id="_pwduser" name="_cm_pwd" type="password">
                    <span id="login-submit">
                      <a href="#">Se connecter</a>
                    </span>
                  </form>
                  <script>
                    window.loginSubmitted = false;
                    setTimeout(() => {
                      window.grecaptcha = {
                        enterprise: {
                          execute: () => Promise.resolve("token"),
                        },
                      };
                    }, 100);
                    document.querySelector(
                      '[aria-label="Refuser les cookies"]'
                    ).addEventListener("click", event => {
                      event.preventDefault();
                      document.querySelector("#cookieLB").style.display =
                        "none";
                    });
                    document.querySelector("#login-submit a")
                      .addEventListener("click", event => {
                        event.preventDefault();
                        window.loginSubmitted = Boolean(
                          window.grecaptcha?.enterprise?.execute
                        );
                      });
                  </script>
                """)

                await _submit_login(page, "customer", "secret")

                self.assertTrue(await page.locator("#cookieLB").is_hidden())
                self.assertTrue(await page.evaluate("window.loginSubmitted"))
            finally:
                await browser.close()

    async def test_current_groupama_login_form_is_supported(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                await page.set_content("""
                  <form id="bloc_ident">
                    <input id="_userid" name="_cm_user">
                    <input id="_pwduser" name="_cm_pwd" type="password">
                    <span id="login-submit"><a href="#">Se connecter</a></span>
                  </form>
                  <script>
                    window.grecaptcha = {
                      enterprise: {
                        execute: () => Promise.resolve("token"),
                      },
                    };
                    window.loginInputEvents = 0;
                    window.passwordInputEvents = 0;
                    document.querySelector("#_userid")
                      .addEventListener("input", () => {
                        window.loginInputEvents += 1;
                      });
                    document.querySelector("#_pwduser")
                      .addEventListener("input", () => {
                        window.passwordInputEvents += 1;
                      });
                  </script>
                """)

                await _submit_login(page, "customer", "secret")

                self.assertEqual(await page.locator("#_userid").input_value(), "customer")
                self.assertEqual(await page.locator("#_pwduser").input_value(), "secret")
                self.assertEqual(
                    await page.evaluate("window.loginInputEvents"),
                    len("customer"),
                )
                self.assertEqual(
                    await page.evaluate("window.passwordInputEvents"),
                    len("secret"),
                )
            finally:
                await browser.close()

    async def test_single_field_sms_code_is_supported(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                await page.set_content("""
                  <input id="security-code" inputmode="numeric">
                  <button type="submit">Valider</button>
                """)

                await _fill_otp(page, "123456")

                self.assertEqual(
                    await page.locator("#security-code").input_value(),
                    "123456",
                )
            finally:
                await browser.close()

    async def test_anonymous_session_cookie_does_not_bypass_strong_authentication(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            context = await browser.new_context()
            page = await context.new_page()
            mfa_url = (
                "https://www.gestion-epargne-salariale.fr"
                f"{ROOT_PATH}/epargnants/premiers-pas"
                f"{MFA_PATH_FRAGMENT}index.html"
            )
            await context.add_cookies([{
                "name": "IdSes",
                "value": "anonymous-session",
                "domain": "www.gestion-epargne-salariale.fr",
                "path": "/",
            }])
            await page.route(
                mfa_url,
                lambda route: route.fulfill(
                    status=200,
                    content_type="text/html",
                    body="<p>Chargement de l'authentification forte</p>",
                ),
            )
            try:
                await page.goto(mfa_url)

                outcome = await _auth_outcome(
                    context,
                    page,
                    timeout_seconds=0.5,
                )

                self.assertEqual(outcome, "ACTION_REQUIRED")
            finally:
                await context.close()
                await browser.close()


class RequestContractTest(unittest.TestCase):
    def test_accounts_rejects_non_object_storage_state(self):
        with TestClient(app) as client:
            response = client.post("/accounts", json={"sessionState": "[]"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_malformed_otp_is_mapped_to_invalid_otp(self):
        with TestClient(app) as client:
            response = client.post(
                "/complete",
                json={"processId": "process", "code": "12ab"},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_OTP")

    def test_invalid_credentials_contract_is_mapped_to_invalid_data(self):
        with TestClient(app) as client:
            response = client.post(
                "/initiate",
                json={"login": "", "password": "secret"},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")


if __name__ == "__main__":
    unittest.main()
