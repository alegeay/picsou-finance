"""Read-only Groupama Épargne Salariale authentication and portfolio sidecar.

Playwright is required because the login page runs invisible reCAPTCHA
Enterprise and may continue through a strong-authentication step. Credentials,
verification codes, cookies and raw portfolio HTML are never logged.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Literal
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Page,
    Playwright,
    TimeoutError as PlaywrightTimeoutError,
    async_playwright,
)

from portfolio_parser import (
    PortfolioFormatError,
    SUPPORTED_HOST,
    parse_portfolio,
    parse_unit_value,
)


logging.basicConfig(level=logging.INFO)
log = logging.getLogger("groupama-es-auth")

BASE_URL = "https://www.gestion-epargne-salariale.fr"
ROOT_PATH = "/groupama-es/espace-client/fr"
PORTAL_COOKIE_DOMAIN = "gestion-epargne-salariale.fr"
LOGIN_URL = f"{BASE_URL}{ROOT_PATH}/identification/authentification.html"
ACCOUNTS_PATH = f"{ROOT_PATH}/epargnants/mon-epargne/situation-financiere-detaillee/index.html"
ACCOUNTS_URL = f"{BASE_URL}{ACCOUNTS_PATH}"
MFA_PATH_FRAGMENT = "/authentification-forte/"
PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5
AUTH_TIMEOUT_SECONDS = 40
ACCOUNT_TIMEOUT_SECONDS = 45
MAX_DETAIL_REQUESTS = 50
DETAIL_ENRICHMENT_BUDGET_SECONDS = 60
RECAPTCHA_READY_TIMEOUT_MS = 15_000
LAUNCH_ARGS = ["--disable-blink-features=AutomationControlled"]
BROWSER_VIEWPORT = {"width": 1536, "height": 864}
BROWSER_SCREEN = {"width": 1920, "height": 1080}
HUMAN_KEY_DELAY_MS = 35
HUMAN_INTERACTION_PAUSE_MS = 250
_ANTIBOT_INIT_JS = """
Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
Object.defineProperty(navigator, 'languages', { get: () => ['fr-FR', 'fr'] });
if (!window.chrome) { window.chrome = { runtime: {} }; }
"""

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


def _browser_is_headless() -> bool:
    configured = os.getenv("GROUPAMA_ES_HEADLESS")
    if configured is not None:
        return configured.lower() in ("1", "true", "yes")
    # Production starts under xvfb-run. Direct test/debug invocations do not
    # have a virtual display and retain Playwright's headless fallback.
    return not bool(os.getenv("DISPLAY"))


async def _pending_sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        await _cleanup_expired()


@asynccontextmanager
async def lifespan(_: FastAPI):
    sweeper = asyncio.create_task(_pending_sweeper())
    try:
        yield
    finally:
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        await _close_all_pending()


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    started_at = time.monotonic()
    try:
        return await call_next(request)
    finally:
        if request.url.path != "/health":
            log.info(
                "Groupama ES request completed (path=%s; duration=%.2fs)",
                request.url.path,
                time.monotonic() - started_at,
            )


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    code: str = Field(pattern=r"^\d{4,8}$")


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    symbol: str = Field(min_length=1, max_length=30)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    currentPriceEur: Decimal
    currentValueEur: Decimal
    pnlEur: Decimal | None = None


class AccountPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["PEE", "PERCOL"]
    balanceEur: Decimal
    positions: list[PositionPayload]
    snapshotComplete: Literal[True]


class InitiateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str | None
    mfaRequired: bool
    mfaType: str | None
    sessionState: str | None = Field(default=None, max_length=2_000_000)


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(max_length=2_000_000)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    fields = {
        str(error["loc"][-1])
        for error in exc.errors()
        if error.get("loc")
    }
    detail = "INVALID_OTP" if "code" in fields else "INVALID_DATA"
    return JSONResponse(status_code=400, content={"detail": detail})


async def _close_resources(
    context: BrowserContext | None,
    browser: Browser | None,
    playwright: Playwright | None,
) -> None:
    for resource, close_method in (
        (context, "close"),
        (browser, "close"),
        (playwright, "stop"),
    ):
        if resource is None:
            continue
        try:
            await asyncio.wait_for(
                getattr(resource, close_method)(),
                timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS,
            )
        except Exception:
            log.warning("Groupama ES browser resource cleanup failed", exc_info=True)


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    await _close_resources(
        state.get("context"),
        state.get("browser"),
        state.get("playwright"),
    )


async def _close_pending(process_id: str) -> None:
    async with _pending_lock:
        state = _pending.pop(process_id, None)
    if state:
        await _dispose_pending_state(state)


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [
            process_id
            for process_id, state in _pending.items()
            if state["created_at"] < cutoff
        ]
    for process_id in expired:
        await _close_pending(process_id)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _launch_browser(playwright: Playwright) -> Browser:
    return await playwright.chromium.launch(
        headless=_browser_is_headless(),
        args=LAUNCH_ARGS,
        ignore_default_args=["--enable-automation"],
    )


async def _new_context(
    browser: Browser,
    storage_state: dict[str, Any] | None = None,
) -> BrowserContext:
    options: dict[str, Any] = {
        "locale": "fr-FR",
        "timezone_id": "Europe/Paris",
        "viewport": BROWSER_VIEWPORT,
        "screen": BROWSER_SCREEN,
    }
    if storage_state is not None:
        options["storage_state"] = storage_state
    return await browser.new_context(**options)


async def _configure_context(context: BrowserContext) -> None:
    await context.add_init_script(_ANTIBOT_INIT_JS)


async def _wait_for_recaptcha_ready(page: Page) -> None:
    try:
        await page.wait_for_function(
            "() => !!(window.grecaptcha && window.grecaptcha.enterprise "
            "&& typeof window.grecaptcha.enterprise.execute === 'function')",
            timeout=RECAPTCHA_READY_TIMEOUT_MS,
        )
        return
    except PlaywrightTimeoutError:
        pass
    try:
        await page.wait_for_load_state(
            "networkidle",
            timeout=RECAPTCHA_READY_TIMEOUT_MS,
        )
    except PlaywrightTimeoutError:
        pass


async def _first_visible(page: Page, selectors: list[str], timeout: int = 500):
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.is_visible(timeout=timeout):
                return locator
        except PlaywrightError:
            continue
    return None


def _has_portal_session_cookie(state: dict[str, Any]) -> bool:
    return any(
        cookie.get("name") == "IdSes"
        and str(cookie.get("domain") or "").lower().lstrip(".")
        in (PORTAL_COOKIE_DOMAIN, SUPPORTED_HOST)
        for cookie in state.get("cookies", [])
    )


async def _session_cookie_present(context: BrowserContext) -> bool:
    return _has_portal_session_cookie(await context.storage_state())


async def _otp_inputs(page: Page) -> list[Any]:
    selectors = [
        'input[autocomplete="one-time-code"]',
        'input[inputmode="numeric"]',
        'input[name*="otp" i]',
        'input[id*="otp" i]',
        'input[name*="code" i]',
        'input[id*="code" i]',
    ]
    for selector in selectors:
        locator = page.locator(selector)
        visible = []
        try:
            for index in range(min(await locator.count(), 8)):
                candidate = locator.nth(index)
                if await candidate.is_visible(timeout=200):
                    visible.append(candidate)
        except PlaywrightError:
            continue
        if visible:
            return visible
    return []


async def _has_login_error(page: Page) -> bool:
    selectors = [
        "#ident-error-message",
        '[role="alert"]',
        ".ei_error",
        ".ei_appl_error",
        ".ei_message_err",
        ".ei_appl_msg",
        ".ei_appl_message",
        ".blocmsg.err",
        ".erreur",
        ".error",
    ]
    for selector in selectors:
        locator = page.locator(selector)
        try:
            count = min(await locator.count(), 10)
            for index in range(count):
                candidate = locator.nth(index)
                if not await candidate.is_visible(timeout=100):
                    continue
                text = (await candidate.inner_text()).lower()
                if any(marker in text for marker in (
                    "incorrect",
                    "invalide",
                    "identifiant",
                    "mot de passe",
                    "erreur",
                )):
                    return True
        except PlaywrightError:
            continue
    return False


def _is_action_needed_url(url: str) -> bool:
    path = urlsplit(url).path.lower()
    return any(marker in path for marker in (
        "saisir-vos-coordonnees",
        "vos-services",
        "conditions-generales",
        "premiers-pas",
    )) and MFA_PATH_FRAGMENT not in path


async def _auth_outcome(
    context: BrowserContext,
    page: Page,
    timeout_seconds: int = AUTH_TIMEOUT_SECONDS,
) -> str:
    deadline = time.monotonic() + timeout_seconds
    authenticated_root = f"{ROOT_PATH}/epargnants/"
    while time.monotonic() < deadline:
        path = urlsplit(page.url).path.lower()
        inputs = await _otp_inputs(page)
        if MFA_PATH_FRAGMENT in path:
            if inputs:
                return "MFA"
            # IdSes may already exist for the anonymous/login session. Never
            # treat that cookie as proof of authentication while the strong-
            # authentication page is still rendering.
            await asyncio.sleep(0.25)
            continue
        if _is_action_needed_url(page.url):
            return "ACTION_REQUIRED"
        if (
            path.startswith(authenticated_root)
            and await _session_cookie_present(context)
        ):
            return "AUTHENTICATED"
        if "/identification/authentification" in path and await _has_login_error(page):
            return "INVALID_CREDENTIALS"
        await asyncio.sleep(0.25)

    path = urlsplit(page.url).path.lower()
    if MFA_PATH_FRAGMENT in path:
        return "ACTION_REQUIRED"
    return "UPSTREAM_UNAVAILABLE"


async def _dismiss_cookie_banner(page: Page) -> None:
    banner = page.locator("#cookieLB").first
    if await banner.count() == 0:
        return
    try:
        await banner.wait_for(state="visible", timeout=3_000)
    except PlaywrightTimeoutError:
        return

    reject = await _first_visible(page, [
        '#cookieLB [role="button"][aria-label*="Refuser les cookies" i]',
        '#cookieLB [role="button"]:has-text("REFUSER")',
    ])
    if reject is None:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

    await reject.click()
    try:
        await banner.wait_for(state="hidden", timeout=5_000)
    except PlaywrightTimeoutError as exc:
        raise HTTPException(
            status_code=502,
            detail="UPSTREAM_FORMAT_CHANGED",
        ) from exc


async def _submit_login(page: Page, login: str, password: str) -> None:
    await _dismiss_cookie_banner(page)
    login_input = await _first_visible(page, [
        "#_userid",
        'input[name="_cm_user"]',
        'input[autocomplete="username"]',
    ])
    password_input = await _first_visible(page, [
        "#_pwduser",
        'input[name="_cm_pwd"]',
        'input[type="password"]',
    ])
    submit = await _first_visible(page, [
        "#login-submit a",
        "#login-submit",
        'button[type="submit"]',
        'input[type="submit"]',
    ])
    if login_input is None or password_input is None or submit is None:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    await page.wait_for_timeout(HUMAN_INTERACTION_PAUSE_MS)
    await login_input.click()
    await login_input.press_sequentially(login, delay=HUMAN_KEY_DELAY_MS)
    await page.wait_for_timeout(HUMAN_INTERACTION_PAUSE_MS)
    await password_input.click()
    await password_input.press_sequentially(password, delay=HUMAN_KEY_DELAY_MS)
    await _wait_for_recaptcha_ready(page)
    await page.wait_for_timeout(HUMAN_INTERACTION_PAUSE_MS)
    await submit.click(no_wait_after=True, delay=80, steps=8)


async def _storage_state(context: BrowserContext) -> str:
    state = await context.storage_state()
    if not _has_portal_session_cookie(state):
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    return json.dumps(state, separators=(",", ":"))


async def _fill_otp(page: Page, code: str) -> None:
    inputs = await _otp_inputs(page)
    if not inputs:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    if len(inputs) == 1:
        await inputs[0].fill(code)
    elif len(inputs) >= len(code):
        for index, digit in enumerate(code):
            await inputs[index].fill(digit)
    else:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

    submit = await _first_visible(page, [
        'button:has-text("Valider")',
        'a:has-text("Valider")',
        'button:has-text("Continuer")',
        'a:has-text("Continuer")',
        'button:has-text("Confirmer")',
        'a:has-text("Confirmer")',
        'button[type="submit"]',
        'input[type="submit"]',
        ".ei_mainbuttons a",
    ])
    if submit is None:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    await submit.click(no_wait_after=True)


def _safe_internal_url(raw: str | None) -> str | None:
    if not raw:
        return None
    parsed = urlsplit(raw)
    if parsed.scheme != "https" or parsed.hostname != SUPPORTED_HOST:
        return None
    return raw


async def _unit_value_from_details(
    context: BrowserContext,
    details_url: str,
) -> Decimal | None:
    safe_url = _safe_internal_url(details_url)
    if safe_url is None:
        return None
    page = await context.new_page()
    try:
        await page.goto(
            safe_url,
            wait_until="domcontentloaded",
            timeout=15_000,
        )
        if "/identification/" in urlsplit(page.url).path:
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

        savings_link = await _first_visible(page, [
            'a:has-text("Mes avoirs")',
            'a:has-text("Mon épargne")',
        ])
        if savings_link is not None:
            await savings_link.click(no_wait_after=True)
            try:
                await page.wait_for_load_state("domcontentloaded", timeout=10_000)
            except PlaywrightTimeoutError:
                pass
        return parse_unit_value(await page.content())
    except HTTPException:
        raise
    except (PlaywrightError, PortfolioFormatError):
        return None
    finally:
        await page.close()


async def _enrich_positions(
    context: BrowserContext,
    accounts: list[dict[str, Any]],
) -> None:
    positions = [
        position
        for account in accounts
        for position in account["positions"]
        if position.get("_detailsUrl")
    ][:MAX_DETAIL_REQUESTS]
    semaphore = asyncio.Semaphore(4)

    async def enrich(position: dict[str, Any]) -> None:
        async with semaphore:
            unit_value = await _unit_value_from_details(
                context,
                position["_detailsUrl"],
            )
        if unit_value is None or unit_value <= 0:
            return
        valuation = Decimal(position["currentValueEur"])
        quantity = (valuation / unit_value).quantize(
            Decimal("0.00000001"),
            rounding=ROUND_HALF_UP,
        )
        if quantity > 0:
            position["quantity"] = quantity
            position["currentPriceEur"] = unit_value

    try:
        await asyncio.wait_for(
            asyncio.gather(*(enrich(position) for position in positions)),
            timeout=DETAIL_ENRICHMENT_BUDGET_SECONDS,
        )
    except asyncio.TimeoutError:
        # Unit details are optional. The account page already supplied and
        # reconciled every EUR valuation, so a slow details page must not turn
        # a complete snapshot into a backend timeout.
        log.info(
            "Groupama ES support-detail enrichment reached its time budget"
        )


def _public_accounts(accounts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            key: value
            for key, value in account.items()
            if not key.startswith("_")
        } | {
            "positions": [
                {
                    key: value
                    for key, value in position.items()
                    if not key.startswith("_")
                }
                for position in account["positions"]
            ]
        }
        for account in accounts
    ]


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    process_id = str(uuid.uuid4())
    playwright: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        playwright = await async_playwright().start()
        browser = await _launch_browser(playwright)
        context = await _new_context(browser)
        await _configure_context(context)
        page = await context.new_page()
        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)
        await _submit_login(page, req.login, req.password)

        outcome = await _auth_outcome(context, page)
        if outcome == "MFA":
            async with _pending_lock:
                _pending[process_id] = {
                    "playwright": playwright,
                    "browser": browser,
                    "context": context,
                    "page": page,
                    "created_at": time.time(),
                }
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "SMS",
                "sessionState": None,
            }
        if outcome == "INVALID_CREDENTIALS":
            raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
        if outcome == "ACTION_REQUIRED":
            raise HTTPException(status_code=409, detail="ACTION_REQUIRED")
        if outcome != "AUTHENTICATED":
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

        session_state = await _storage_state(context)
        await _close_resources(context, browser, playwright)
        return {
            "processId": None,
            "mfaRequired": False,
            "mfaType": None,
            "sessionState": session_state,
        }
    except HTTPException:
        await _close_resources(context, browser, playwright)
        raise
    except PlaywrightError as exc:
        await _close_resources(context, browser, playwright)
        log.warning("Groupama ES authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        await _close_resources(context, browser, playwright)
        log.exception("Unexpected Groupama ES authentication initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    await _cleanup_expired()
    state = await _take_pending(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if state["created_at"] < time.time() - PENDING_TTL_SECONDS:
        await _dispose_pending_state(state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    page: Page = state["page"]
    try:
        await _fill_otp(page, req.code)
        outcome = await _auth_outcome(state["context"], page)
        if outcome == "AUTHENTICATED":
            return {"sessionState": await _storage_state(state["context"])}
        if outcome == "ACTION_REQUIRED":
            raise HTTPException(status_code=409, detail="ACTION_REQUIRED")
        if outcome in ("MFA", "INVALID_CREDENTIALS"):
            raise HTTPException(status_code=401, detail="INVALID_OTP")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Groupama ES authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Groupama ES authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _dispose_pending_state(state)


@app.post("/accounts", response_model=list[AccountPayload])
async def accounts(req: AccountsRequest) -> list[AccountPayload]:
    try:
        storage_state = json.loads(req.sessionState)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(storage_state, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")

    playwright: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        playwright = await async_playwright().start()
        browser = await _launch_browser(playwright)
        context = await _new_context(browser, storage_state)
        await _configure_context(context)
        page = await context.new_page()
        await page.goto(
            ACCOUNTS_URL,
            wait_until="domcontentloaded",
            timeout=ACCOUNT_TIMEOUT_SECONDS * 1000,
        )
        path = urlsplit(page.url).path.lower()
        if "/identification/" in path:
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
        if _is_action_needed_url(page.url):
            raise HTTPException(status_code=409, detail="ACTION_REQUIRED")
        try:
            await page.locator(
                'th:has-text("Nom du support"), '
                'th:has-text("Nom du profil"), '
                'th:has-text("Nom du compte")'
            ).first.wait_for(state="attached", timeout=15_000)
        except PlaywrightTimeoutError as exc:
            raise HTTPException(
                status_code=502,
                detail="UPSTREAM_FORMAT_CHANGED",
            ) from exc

        parsed = parse_portfolio(await page.content())
        await _enrich_positions(context, parsed)
        public = _public_accounts(parsed)
        log.info(
            "Groupama ES portfolio received (accounts=%d; positions=%d)",
            len(public),
            sum(len(account["positions"]) for account in public),
        )
        return [AccountPayload.model_validate(account) for account in public]
    except PortfolioFormatError as exc:
        log.warning(
            "Groupama ES portfolio rejected by completeness checks (reason=%s)",
            exc,
        )
        raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE") from exc
    except ValidationError as exc:
        issues = sorted({
            ".".join(str(part) for part in error.get("loc", ()))
            + ":"
            + str(error.get("type", "unknown"))
            for error in exc.errors(include_url=False, include_input=False)
        })
        log.warning(
            "Groupama ES normalized portfolio failed validation (issues=%s)",
            ",".join(issues[:20]),
        )
        raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE") from exc
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Groupama ES portfolio browser failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Groupama ES portfolio failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, playwright)
