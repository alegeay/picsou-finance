import asyncio
import time
import unittest
from decimal import Decimal
from unittest.mock import patch

from fastapi.testclient import TestClient
from playwright.async_api import async_playwright

from main import (
    MFA_PATH_FRAGMENT,
    PENDING_TTL_SECONDS,
    ROOT_PATH,
    _auth_outcome,
    _cleanup_expired,
    _close_all_pending,
    _enrich_positions,
    _fill_otp,
    _has_portal_session_cookie,
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
                """)

                await _submit_login(page, "customer", "secret")

                self.assertEqual(await page.locator("#_userid").input_value(), "customer")
                self.assertEqual(await page.locator("#_pwduser").input_value(), "secret")
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
