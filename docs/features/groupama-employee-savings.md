# Feature: Groupama employee-savings sync

> Last updated: 2026-07-26

## Context

Groupama Épargne Salariale holds employee savings outside the PSD2 bank-account
scope. Picsou therefore needs a dedicated connector to import PEE and PERCOL
plans, including their employee-savings funds and managed-profile allocations.

This is an unofficial, read-only integration against the secure customer
portal. The portal can change without notice, so the connector preserves the
last complete portfolio whenever authentication, parsing or reconciliation
fails.

## Scope

A successful import creates or updates one Picsou account per supported plan:

- `PEE` for a Plan d'Épargne Entreprise and portal labels containing the
  equivalent "épargne entreprise" or "épargne groupe" wording;
- `PERCOL` for a PER Collectif, with legacy PERCO labels normalized to the same
  account type;
- the authoritative plan valuation in EUR;
- every visible fund/support, or every underlying support when a PERCOL managed
  profile exposes a breakdown;
- the number of units and unit value when the support-detail page provides
  them;
- the provider-reported absolute unrealized P&L when available.

Operations, statements, contribution origins and availability/unlock buckets
are intentionally out of scope for the first version. Picsou writes one daily
balance snapshot after each complete import.

Groupama documents PEE and PERCOL as separate, complementary plans and its
annual statement as a per-plan, per-fund view. Those public descriptions define
the connector's domain boundary:

- [Groupama employee-savings plans](https://www.groupama-es.fr/entreprises/plans-epargne-salariale/)
- [Groupama annual employee-savings statement](https://www.groupama-es.fr/actualite/votre-releve-annuel-2025-depargne-salariale/)

## Authentication and strong authentication

`services/groupama-es-auth` is an internal FastAPI/Playwright sidecar. A real
Chromium browser is required because the official login page executes
reCAPTCHA Enterprise and may continue through strong authentication.

1. `POST /initiate` opens the official Groupama Épargne Salariale login page,
   fills the customer number and password, and waits for either a session,
   an OTP form or an actionable portal state.
2. If an OTP is required, the sidecar retains the browser context in memory
   for at most ten minutes and returns a one-use random `processId`.
3. `POST /complete` atomically claims the process and submits a four-to-eight
   digit code.
4. The sidecar returns Playwright's complete JSON storage state. The Java
   backend encrypts it with `CryptoEncryption` before persisting
   `groupama_es_session`.

Before submitting credentials, the sidecar detects the portal's cookie-consent
dialog and explicitly chooses "Refuser les cookies". It never forces a click
through a visible overlay; an unknown consent dialog fails safely as
`UPSTREAM_FORMAT_CHANGED`.

The login control invokes Enterprise reCAPTCHA asynchronously. The sidecar
waits until `grecaptcha.enterprise.execute()` is available before clicking and
normalizes the browser's automation signals used by the portal's risk score.
Production runs Playwright's pinned headed browser build under Xvfb so the
browser does not expose an obsolete or `HeadlessChrome` User-Agent or Client
Hint. The Playwright automation launch flag is omitted, screen and viewport
dimensions remain internally consistent, and credentials are entered through
ordinary keyboard events before a multi-step pointer click.
`HOME`, `XDG_CACHE_HOME` and `XDG_CONFIG_HOME` point to writable storage so
Chrome's NSS, font and crashpad caches initialize under the non-root runtime.
`tini` remains PID 1 so `xvfb-run` receives its display-ready signal and Chrome
subprocesses are reaped. Direct test and debug invocations without a `DISPLAY`
retain a headless fallback;
`GROUPAMA_ES_HEADLESS` can override that choice explicitly.
Remaining on the login page without an explicit visible credential error is
reported as `UPSTREAM_UNAVAILABLE`, never as `INVALID_CREDENTIALS`.

The login, password, OTP and raw portfolio HTML are never logged or stored.
Cookies are never logged and are persisted only inside the encrypted Playwright
storage state. Pending browser resources are closed after success, failure,
expiry, sidecar shutdown and by a periodic sweep. A second completion attempt
cannot reuse a claimed process.

If the portal requests an interaction the connector cannot complete safely,
such as first-time strong-authentication enrolment, it fails with
`ACTION_REQUIRED` and directs the user to the official customer portal:

<https://www.gestion-epargne-salariale.fr/groupama-es/espace-client/fr/identification/authentification.html>

## Portfolio normalization

The portal uses generated element identifiers, so
`portfolio_parser.py` locates data through stable semantic headings such as
`Nom du support`, `Nom du profil`, `Nom du compte` and `Montant total`.
French and English number formats are parsed strictly; a malformed required
amount is never converted to zero.

For each supported plan, the sidecar:

- classifies the plan as PEE or PERCOL from its label;
- expands a managed-profile popup only when its underlying support values
  reconcile with the profile value;
- follows only HTTPS support-detail links on the expected Groupama portal host;
- enriches at most 50 support details per request within a 60-second optional
  enrichment budget;
- creates stable SHA-256-derived account and support identifiers;
- never uses or returns the customer number in identifiers or logs;
- rejects duplicate account identifiers and incomplete monetary fields;
- proves `sum(position currentValueEur) ~= plan balanceEur`.

The allowed tolerance is the greater of EUR 0.05 and 0.1% of the expected
amount. When no unit value is available, the sidecar uses one neutral valuation
unit whose price equals the support valuation. This retains an exact, explicit
EUR value without inventing a number of fund units.

The public Woob `groupamaes`/`cmes` modules informed the stable semantic labels,
but Picsou owns its sidecar contract and persistence rules:

- [Woob Groupama ES browser](https://gitlab.com/woob/woob/-/blob/master/modules/groupamaes/browser.py)
- [Woob CMES pages](https://gitlab.com/woob/woob/-/blob/master/modules/cmes/pages.py)

## Asynchronous and atomic import

Authentication persists the encrypted browser state in a short transaction and
queues portfolio work on the single-threaded `groupamaEsSyncExecutor`.
`POST /api/groupama-es/sync` returns `202 Accepted`; the UI polls
`GET /api/groupama-es/status` while work is in flight.

```text
IDLE -> QUEUED -> RUNNING -> SUCCESS
                         -> FAILED
```

Only one job can be queued or running per member. Every job carries the
database session ID that created it. Clearing or replacing the session fences
the old job and prevents it from committing a stale result. Jobs interrupted
by a backend restart are marked `FAILED` during startup.

All browser and network work happens before the write transaction. The Java
service then validates every account and position again before it:

- upserts accounts under stable `ges_` external IDs;
- sets provider `Groupama Épargne Salariale`, currency `EUR` and
  `isManual=false`;
- preserves soft-deleted accounts rather than recreating them;
- replaces holdings only after the entire response is complete;
- stores the provider EUR value and P&L on each holding;
- writes the daily balance/invested snapshot;
- marks the session `SUCCESS`.

Any validation or persistence failure rolls back the write phase. Existing
accounts, holdings and snapshots remain coherent.

## Valuation behavior

Groupama supports are commonly FCPEs with provider-side identifiers rather than
public Yahoo tickers. `AccountService` therefore treats the reconciled Groupama
plan total and position values as authoritative EUR values and does not query
public price providers for synthetic `GES_` symbols.

This rule is enforced centrally as well as in the account view:

- `PriceService` rejects `GES_` identifiers for spot, bulk, historical
  backfill and intraday requests before invoking CoinGecko or Yahoo;
- `SecurityInsightService` returns `UNKNOWN` without querying classification or
  composition providers;
- frontend account hooks do not request live prices, price history or security
  insights for these identifiers;
- the 24-hour history carries the last reconciled Groupama plan value and
  invested amount as constant provider data instead of inventing an intraday
  market series.

When Groupama supplies absolute P&L, Picsou derives the position cost basis as
`current value - P&L`. When it is absent, cost basis remains neutral at the
current valuation so Picsou does not invent a gain. The same invested amount is
used for the daily snapshot.

## API and errors

Authenticated application endpoints:

| Endpoint | Purpose |
|----------|---------|
| `POST /api/groupama-es/auth/initiate` | Start portal authentication |
| `POST /api/groupama-es/auth/complete` | Complete the pending OTP step |
| `POST /api/groupama-es/sync` | Queue a new portfolio import |
| `GET /api/groupama-es/status` | Read member-scoped session/job status |
| `DELETE /api/groupama-es/session` | Remove the encrypted session |

Authentication attempts are limited to five per IP per 15 minutes. Errors use
stable RFC 7807 `code` values:

`INVALID_CREDENTIALS`, `INVALID_OTP`, `AUTH_ATTEMPT_EXPIRED`,
`SESSION_EXPIRED`, `ACTION_REQUIRED`, `PORTFOLIO_INCOMPLETE`,
`UPSTREAM_FORMAT_CHANGED`, `UPSTREAM_UNAVAILABLE`, `INVALID_DATA` and
`INTERNAL_ERROR`.

Only `SESSION_EXPIRED` deactivates a stored session. Other portfolio failures
remain retryable without requiring credentials again.

## User interface

The connector is available from:

- the Accounts page's Add Account modal;
- the dedicated Sync page;
- the first-launch integration catalog;
- Admin integration settings.

The same `GroupamaEsPanel` implements credentials, OTP, observable background
status, retry, manual sync and disconnect behavior in every entry point.
Passwords and OTPs are cleared immediately after use. The Add Account modal
closes only after the background import reaches `SUCCESS`.

PEE and PERCOL appear as investment accounts, use the holdings views and are
grouped under the Accounts page's stock/investment category.

Because operations are outside the first connector version, their account
detail pages do not expose realized-P&L or investment-transaction CSV import
controls.

## Deployment

Both Compose stacks include `groupama-es-auth` as an internal-only service. The
image is based on `python:3.12-slim-bookworm`, installs Chromium only and runs
as a dedicated non-root user.

- production backend URL:
  `GROUPAMA_ES_AUTH_URL=http://groupama-es-auth:8001`;
- local Spring profile URL: `http://127.0.0.1:8003`;
- required outbound destination:
  `https://www.gestion-epargne-salariale.fr`;
- no sidecar ingress or published host port is required.

Pending authentication contexts are process-local, so the sidecar must remain
at one replica unless sticky routing or shared pending state is introduced.
The Java authentication request timeout is 100 seconds and both Nginx variants
allow 120 seconds, leaving room for reCAPTCHA and portal redirects without
cutting off the browser flow.

## Verification boundaries

- Sidecar tests run against the real Chromium runtime and cover the current
  login form, OTP selectors, pending-session lifecycle, French monetary values,
  PEE/PERCOL parsing, managed-profile expansion and reconciliation failures.
- Backend tests cover the typed adapter contract, encrypted storage,
  member-scoped endpoints, rate limiting, guarded state transitions, atomic
  persistence, stale-session fencing and the no-public-provider rule for
  `GES_` identifiers.
- Frontend tests cover credentials, variable-length OTPs, actionable errors,
  secret clearing, waiting for background success and suppression of public
  price/history/insight requests.

Public CI cannot authenticate to a live private account. A maintainer must run a
credential-and-OTP smoke test before release and after a reported portal change.
Credentials must be entered only through the local Picsou UI, never in a ticket,
commit, test fixture or chat.

## Key files

- `services/groupama-es-auth/main.py` — browser authentication and collection
- `services/groupama-es-auth/portfolio_parser.py` — strict semantic parser
- `backend/src/main/java/com/picsou/port/GroupamaEsPort.java` — sidecar contract
- `backend/src/main/java/com/picsou/service/GroupamaEsSyncService.java` — job
  lifecycle, validation and atomic persistence
- `backend/src/main/java/com/picsou/controller/GroupamaEsController.java` — REST
  endpoints and auth throttling
- `frontend/src/components/sync/GroupamaEsPanel.tsx` — shared connection UI
- `backend/src/main/resources/db/migration/V64__groupama_employee_savings.sql`
  — PEE/PERCOL types and encrypted session state

## Related decisions

- [Groupama employee savings uses an isolated browser and authoritative EUR snapshots](../decisions/2026-07-26-groupama-employee-savings-isolated-browser-sync.md)
- [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- [Mandatory encryption key](../decisions/2026-04-08-mandatory-encryption-key.md)
