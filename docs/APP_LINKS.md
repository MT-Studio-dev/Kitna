# Invitation links and deployment

Kitna 0.7 handles `app.kitna://join?token=CODE_OR_TOKEN` and `app.kitna://friend?token=TOKEN`. The native share sheet includes the actual link, and group shares include the manual code. Group codes keep their seven-day expiry. Private previews require sign-in and reveal only the group name/count, never balances, receipts or member names. Joining still requires an explicit tap.

The pending invitation kind and token are committed to app-private preferences before starting Google OAuth. They survive activity recreation, process death and restart. Successful acceptance clears them only after local persistence. Reopening a code as an existing member opens the same group. A failed/offline attempt keeps the original link for retry.

## HTTPS App Links

No controlled, hosted invitation domain or published signing association was supplied or verified for this change. The development build therefore shares the supported custom-scheme links. Verified HTTPS App Links and deferred installation are **not operational claims**.

1. Deploy `hosting/invitations/index.html` on an MT Studio-controlled HTTPS host. Configure the host to serve that file at `/invite/group/*` and `/invite/friend/*`, without rewriting `/.well-known/assetlinks.json`.
2. Set `kitna.inviteHost=your.actual.host` in ignored `local.properties` and rebuild. The manifest has an autoVerify HTTPS filter restricted to `/invite/`; URL routing validates the exact configured host and path. Unconfigured builds use the reserved `kitna-links.invalid` manifest host and generate only custom-scheme links.
3. The included `hosting/invitations/.well-known/assetlinks.json` uses the actually verified development APK certificate. Publish it only for builds signed by that key. For another signing identity, generate the JSON from `hosting/invitations/assetlinks.template.json` and replace the fingerprint with the SHA-256 of the certificate signing the installed APK. For Play App Signing, use the app-signing certificate, not the upload certificate. Package identity remains `app.kitna`. Serve the real JSON over HTTPS with application/json and no redirects.
4. Install an APK signed by that certificate, then run `adb shell pm verify-app-links --re-verify app.kitna` and inspect `adb shell pm get-app-links app.kitna`. Test a real link from another app on supported Android versions. Domain verification must succeed before calling links verified.
5. Set a real install/distribution URL in the fallback HTML if desired. It intentionally does not invent a Play listing. The fallback shows a copyable group code/link and an Open Kitna button. After installing, reopen the original link or enter the group code. There is no automatic deferred-deep-link service.

The static fallback validates token shape, uses textContent, does not request private group data and sends no analytics or token-bearing API requests. Avoid third-party scripts and query logging for invitation pages. Configure short cache lifetimes for revocation-sensitive previews; the backend is always authoritative at acceptance.

## Supabase

With explicit user approval, `202611250001_simplified_collaboration.sql` was deployed to Kitna project `hoevqcscyvjgwrqxfevc` on **28 November 2026**, after the two existing migrations. Hosted protocol 3 is available for friendship invitations, explicit acceptance, private previews, batch members and name-only groups. All 11 deployment checks passed and existing group/participant/record/revision/receipt counts were unchanged. New RPCs now reject anonymous calls with permission errors rather than missing-function errors; Google preflight remains healthy. See `cloud-preflight-v071.log`, `cloud-rpc-v071.json` and [CLOUD_DEPLOYMENT.md](CLOUD_DEPLOYMENT.md) for evidence and verification limits. Real two-account invitation acceptance and device synchronization remain unverified.

Protocol 3 lists addressed group invitations in Shared groups. Signing in and synchronizing never accepts them. The recipient explicitly joins only the selected group, using its inbox entry, targeted link or code.

Private friendship polling uses indexes for both friendship endpoints and both invitation inbox directions; sender history and pending-link retries do not require scanning every user's invitations. `supabase/verify_collaboration.sql` checks protocol, grants and these indexes and passed against the deployed project.

Friend lookup never exposes an account directory. An exact email creates a private, expiring invitation whether or not an account currently exists. Only the verified addressed Google identity can accept it. Link invitations without an email can be accepted by the intended recipient receiving the bearer secret. Account display names never establish identity.

General group links create a new participant slot. Connecting historical shares requires an owner-issued verified-email invitation; legacy unbound participant-claim links are refused. Removed members cannot use a general link to bypass the owner's authorization. Use explicit reinvitation. Revocation and expiration are checked on the server.
