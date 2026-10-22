# Public synthetic #154 case1 diagnostic — NEVER MERGE

Only this NEVER MERGE diagnostic branch's registered temporary workflow invokes this
separate public synthetic controller. Existing normal harness sources and the application
remain unchanged; no image has been built.
The draft now has an actual callable hosted controller, copied browser transport, explicit
case1 browser mode and browser-only Dockerfile. Do not push/dispatch/build before independent
parent review of the exact diagnostic commit. This registered temporary workflow is NEVER MERGE. No actual runtime/build/publication was performed.

Review app source with `git show 04eb:<path>`: this worktree's checked-out app at
678b14f predates the merged fix. The retained public image in
`scripts/browser-benchmark/public_image_ui.py` is the reviewed21cce tree, image9ec1195;
do not substitute a rebuilt or compiled-resource-modified artifact.

## Minimal actual-runtime checklist

1. **Public browse / auth route**: existing owned seeded image, fresh browser profile,
   only `security.allow-public-access=true` in the disposable instance. Observe the original
   auth-provider user-info RPC returning null; use original library27003 and game33000
   views and existing decoded/hash-checked cover proof. Click the actual navbar Sign In
   button (`nav button.gradient-primary:not(.size-8)`); wait for original login form
   `input[name=username]`, `input[name=password]`, enabled `button[type=submit]`.
   Require no catalog RPC after the new login document starts, no admin-list RPC, and
   old-document catalog HTTP requests finished and observed WebSockets closed at the checkpoint. Restore the
   original effective public policy and independently read it back, including failure paths.
   This case is drafted here. Preserve original15s login/90s browser deadlines and all
   proxy request/socket limits; no sleeps, mocked endpoints, auth-cookie manipulation,
   application patches, or widened network origin policy.

   **Important**: `MainLayout.tsx` Sign In sets `window.location.href='/loginredirect'`.
   This is full document navigation. Its browser cancellation is NOT proof of same-document
   React abort/stale-callback fencing. Zero natural pending work is not cancellation proof.
   The draft reports those limits explicitly and does not assert #154 complete.

   The copied inactive `browser_entry.draft.mjs` forwards actual `Page.frameNavigated`
   and `Network.webSocketCreated/Closed` events in addition to every original event.
   Page/Network CDP domains must explicitly be enabled before this case; existing driver
   Runtime/Fetch setup remains unchanged. Additional observations are capped256 and
   overflow fails the case; original Fetch interception events are never dropped by that cap.
   Current `browser_entry.mjs` does not forward those extra events and cannot run this
   case unchanged. The draft requires this bound/enabled pipeline, not an empty event list.
   Admin HTTP checks use actual `UserEndpoint.getAllUsers`,
   `BandwidthMonitoringEndpoint.getActiveSessions` and (if HTTP is attempted)
   `LibraryEndpoint.subscribeToScanProgressEvents`. Hilla WebSocket subscription payloads
   are NOT decoded, so admin subscription absence remains explicitly unproven.
   Catalog absence is observed only from login-document start to the first enabled
   login-form checkpoint, not a quiet period, terminal request count or future absence proof.

   `test_case1_pipeline.mjs` uses the copied actual dispatcher with a synthetic CDP
   emitter/DOM driver. It tests event/domain prerequisites, missing frame/close rejection,
   actual admin HTTP paths, late catalog rejection, observation overflow, preservation
   of original interception events and missing-cover false positives. These are pure
   limited transport/receipt regressions, not actual browser acceptance.

2. **Setup**: a separate owned empty-DB application; `OwnedApp` currently calls
   `FixtureClient.bootstrap()` which registers admin through RPC, so it cannot prove the
   wizard. Add an independently reviewed no-bootstrap lifecycle mode. Visit `/login`,
   observe `/setup`, complete original wizard (Welcome/Theme/User; username/email/password/
   passwordRepeat), then original `/login` submit and identity proof. After setup,
   `/setup` must redirect to `/login`, not recreate admin. Source: `SetupFilter`,
   `SetupEndpoint.registerSuperAdmin`, `SetupView`, `components/wizard/Wizard`.

3. **Reset / invitation**: same owned completed instance, separate synthetic accounts,
   fresh anonymous browser contexts. Existing authenticated fixture-admin may generate
   tokens through `/connect/PasswordResetEndpoint/createPasswordResetTokenForUser`
   (`username`) and `/connect/RegistrationEndpoint/createInvitation` (`email`); this is
   preparation, not a substitute for original form submission. SMTP need not be enabled.
   Hold generated token secrets only in memory, never receipts/URLs/logs/artifacts.
   `/reset-password?token=...`: original password/passwordRepeat form -> successful reset
   -> normal login with new password -> identity. Old password and token replay refused.
   `/accept-invitation?token=...`: original recipient lookup and username/password/
   passwordRepeat form -> success -> normal login for new non-admin account; token replay
   refused. Verify unknown/expired tokens fail closed without catalog/admin bootstrap.
   Source: `PasswordResetView`, `InvitationRegistrationView`, corresponding endpoints/services.
   Existing driver selectors/identity proof hardcode fixture-admin and need a narrow
   reviewed synthetic-identity variant. Before enabling token cases, redact query strings
   and request bodies at every diagnostic boundary; do not export token-bearing CDP data.

4. **OIDC**: NOT implemented. Requires actual owned synthetic authorization-code issuer,
   signed ID token, nonce/state validation, JWKS and token endpoints; pre-start OIDC
   configuration and exact redirect URI `/login/oauth2/code/oidc`. `SsoEnabledCondition`
   reads encrypted persisted configuration before Spring startup, so toggling a setting
   after startup is not equivalent. Current browser proxy/CDP gate permits only exact
   application origin; no second issuer authority may be added without review. Real flow:
   `/loginredirect?continue=<local>` -> `/oauth2/authorization/oidc` -> issuer -> callback ->
   local landing -> original profile identity and browse. Also prove `direct=1` retains
   password login and an invalid callback does not authenticate. A redirect GET alone is
   not OIDC acceptance. Source: `SecurityConfig`, `LoginRedirectController`,
   `SsoAuthenticationSuccessHandler`, `SsoEnabledCondition`.

`/confirm-email` is also in the merged auth-only classifier. It requires separate valid
confirmation-token preparation and actual `EmailConfirmationEndpoint.confirmEmail` proof;
an invalid/missing-token render alone must not stand in for that behavior.

## Ownership / evidence boundaries

Keep existing `OwnedApp.inspect` identity/mount/network checks, browser cleanup ledger,
retained artifact identity, source-hash checks, no-scan gate, health/OOM checks and exact
owned cleanup. Never read a live H2 database. Do not touch production, use local Docker,
publish an image, or promote latest. Receipts contain fixed labels, counts and booleans
only. Config restoration proves effective policy only: ConfigEndpoint returns effective
values and cannot distinguish an absent row from an explicitly stored default.

`OwnedPublicPolicy` is a controller-only context manager, not browser RPC or credential
transport. `runPublicAuthCase` accepts the already created original driver/CDP; it creates
no browser, server, fixture, listener, image or network. No current entry point imports this
draft. The callable draft path is:

`controller_public_case1.py` -> existing retained-artifact download/inventory -> existing
`OwnedApp(ui_only=True)` -> verified policy lease -> copied `hosted_browser_case1.py` ->
real Docker attach/stdin -> pinned-Puppeteer `browser_entry.draft.mjs --public-auth-case1`
-> actual Chrome/proxy/original UI -> strict receipt -> exact browser cleanup -> policy
restore/readback -> unchanged source/no-scan/health gates -> exact app/input cleanup.

The browser receives only the fixed `public-auth-case1` command, never admin credentials.
The Dockerfile-specific ignore allowlist excludes controller state, other private drafts,
application artifacts and credentials from its build context; only public browser modules
and the3 explicitly named draft browser modules are admitted. No image is exported here.
Its metadata/field receipts are validated independently by JavaScript and Python; full154,
same-document cancellation and WS-admin-absence flags must remain false. Original90s
browser watchdog/Chrome deadline,15s DOM gates,256-request cap and original socket/capability/
mount/network limits remain in place. No benchmark delay or widened authority is added.
The copied actual controller receipt reader and ownership/cap/cleanup routines are tested
identical to their originals. The only new browser-image capability is the reviewed mode
and bounded event observation, not a fake UI or transport.

After parent review only, a hosted workflow could build the **browser-only**
`Dockerfile.browser.draft` with repository-root context (no application build), inspect its
immutable ID, then call `controller_public_case1.py --parent-reviewed-case1 --browser-image
<that immutable browser ID> --output <owned report path>`. Recovery uses the same draft
controller's `--cleanup-only`; it accepts no other runtime inputs in that mode. This text
is not a dispatch request. No browser/app image publication or latest promotion is allowed.
