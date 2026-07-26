# ADR: Groupama employee savings uses an isolated browser and authoritative EUR snapshots

> Date: 2026-07-26
> Status: ✅ Active

## Context

Picsou needs to import PEE and PERCOL valuations and fund allocations from the
Groupama Épargne Salariale customer portal. These plans are not PSD2 accounts,
and Groupama does not expose a supported public aggregation API for this use
case. The secure portal uses browser-side reCAPTCHA Enterprise and may require
strong authentication.

Employee-savings supports are often FCPEs without a reliable public ticker.
A partial page, missing managed-profile allocation or failed detail lookup must
not erase the previous portfolio or turn an unpriced fund into a zero-valued
asset.

## Decision

1. Run all Groupama portal automation in a dedicated, internal-only
   FastAPI/Playwright sidecar with Chromium and a non-root runtime.
2. Keep credentials and OTPs ephemeral. Persist only Playwright storage state,
   encrypted by the Java backend with AES-256-GCM.
3. Model supported plans explicitly as `PEE` and `PERCOL`, not as generic
   savings or brokerage accounts.
4. Normalize portal data into a strict, EUR-only `GroupamaEsPort` contract.
5. Prove portfolio completeness by reconciling each plan total with all
   normalized position values before any financial write.
6. Treat reconciled Groupama plan and support valuations as authoritative.
   Do not send synthetic FCPE identifiers to public quote providers.
7. Queue imports, persist observable job status and fence every job by its
   originating database session ID.
8. Perform upstream I/O outside the database transaction, then replace holdings
   and write snapshots atomically only after all plans validate.
9. Keep operations, documents and unlock/availability buckets outside the first
   connector version.

## Alternatives considered

### Use plain Java HTTP requests

- **Pros**: no Python service or browser image.
- **Cons**: does not execute the current reCAPTCHA/browser flow and couples
  Spring code directly to unstable portal markup.

### Embed Playwright in the main application container

- **Pros**: one deployable image and no internal HTTP boundary.
- **Cons**: makes every Picsou deployment carry Python and Chromium, mixes
  browser failures with the Java runtime and enlarges the main attack surface.

### Depend directly on Woob's Groupama ES/CMES module

- **Pros**: existing knowledge of Euro-Information employee-savings markup.
- **Cons**: couples Picsou's domain and release schedule to another
  application's models, does not provide Picsou's encrypted session/job
  lifecycle, and does not remove the need to validate the current strong-auth
  flow. Its semantic selectors can be used as public research without making
  it a runtime dependency.

### Accept and merge a partially parsed portfolio

- **Pros**: exposes some fresh values during a portal change.
- **Cons**: cannot distinguish a closed support from an omitted one and can
  persist mutually inconsistent plan balance, holdings and P&L.

### Resolve every support through Yahoo Finance or OpenFIGI

- **Pros**: fits the existing public-market live-price pipeline.
- **Cons**: many FCPEs and managed-profile entries have no resolvable public
  ticker. Missing quotes would create partial totals, while guessed mappings
  could price the wrong share class.

### Represent PEE/PERCOL as `OTHER`, `SAVINGS` or `PEA`

- **Pros**: avoids a database enum migration and frontend mappings.
- **Cons**: loses the legal/product distinction, misclassifies investment
  behavior and makes later availability or retirement features ambiguous.

## Reasoning

The browser sidecar is an anti-corruption boundary around an unofficial,
interactive portal. The Java port remains a small financial contract that can
be validated independently of HTML. Reconciliation and one atomic write phase
prefer the last known-good state over misleading partial freshness.

Provider-side EUR valuations are the only consistently available price source
for FCPEs. Keeping them explicit is safer than manufacturing public tickers or
treating unresolved funds as zero. Asynchronous status gives the browser enough
time without holding a user-facing request or database transaction open.

## Trade-offs accepted

- Deployment gains one internal Chromium container.
- The unofficial connector needs maintenance when Groupama changes the portal.
- Pending OTP contexts are process-local, so the sidecar remains single-replica.
- A successful authentication may return `QUEUED` before accounts appear.
- Public CI cannot prove a private live login; release validation includes a
  local human-assisted smoke test.
- The first version does not expose transaction history or unlock dates.

## Consequences

- `GroupamaEsPort` is the backend's only dependency on the sidecar contract.
- `groupama_es_session` stores encrypted browser state and typed sync status.
- Migration V64 adds `PEE` and `PERCOL` to `account_type`.
- Groupama positions carry synthetic stable `GES_` identifiers and explicit EUR
  valuations/P&L.
- `AccountService`, `PriceService`, `SecurityInsightService`, history services
  and frontend hooks bypass public quote/insight lookup for Groupama accounts
  and `GES_` identifiers.
- Any incomplete or inconsistent fetch leaves existing financial rows
  unchanged.
- Sidecar, backend and frontend tests verify deterministic boundaries; live
  portal smoke testing remains a release task.
