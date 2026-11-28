# Shared groups: implementation and remaining checks

## v0.7 implementation update

Protocol 3 adds explicit friend relationships, private exact-email invitation inboxes, batch member selection, one-person group creation, hidden non-group ledgers and durable invitation routing. Signing in or synchronizing no longer accepts email group invitations: the recipient chooses Join for that group. Verified claims preserve historical participant IDs. A forwarded unaddressed friend link requires sender identity confirmation before attaching local balances. The new migration was deployed on 28 November 2026 after explicit user approval; protocol 3 and all 11 checks are verified, with existing data counts unchanged. See [v0.7 audit](V07_AUDIT.md), [deployment](CLOUD_DEPLOYMENT.md) and [App Links](APP_LINKS.md). The v0.6 behavior/evidence below is historical.

Version 0.6 retains the Manga Panels theme, uses the supplied references for option placement, defaults to Light and uses Google sign-in. Both Supabase migrations are deployed and the protocol-2 hosted preflight passes. Verification passed 129 core/app tests and 31 database tests. Short group codes and verified Google email invitations supplement compatible old invitation tokens. Optional activity notifications share the same process book/auth/sync coordinator across foreground and scheduled jobs, avoiding independent caches overwriting each other. Actual two-account/two-phone behavior and background notification delivery remain unverified.

## User flow

1. Keep an existing group local, or sign in and explicitly share that selected group.
2. The owner adds a friend's Google email to an existing participant or creates a new email invitation. A reusable seven-day code is available for additional members.
3. The friend signs in with the verified matching email and the group appears during sync, or enters the group code. Names are not used to prove identity. General codes create a new participant slot; use email invitations to connect existing balances.
4. Members record expenses/repayments/comments and see balances, budgets, receipts and history. Members can leave; an owner must have another joined member to take ownership. Leaving/removal revokes automatic email reattachment until the owner explicitly reinvites that participant.
5. Offline drafts stay pending on their device until accepted by the server. Conflict/failure messages remain visible; a local save is not evidence that another phone has received the change.

## Identity and persistence

The local accounting/UI model still uses `self`. `SharedBooks` projects the signed-in account's canonical participant to that local identifier and maps every other payer/share/repayment/comment/history reference consistently. Server participant IDs remain stable and group-scoped, including when one local friend appears in several groups. A participant may be an unclaimed named person until explicit invitation acceptance.

`SharedGroupLink` stores the account/group mapping, last accepted snapshot and entity versions. `SharedChange` stores the operation UUID, entity ID, expected version and original payload. `LocalStore` commits the optimistic ledger and queued changes together inside its existing Room transaction. It does not upload the user's entire local book as a last-write-wins cloud row.

The backend separates groups, participants, individual financial/comment records, revisions, activity, receipt metadata, command deduplication and invitations. An expense's payer/share maps are kept within its JSON record and validated together in one server transaction. Google/Supabase account identity comes from the validated session; it is separate from the participant whose contribution is being recorded.

## Authentication

`CloudAuth` opens Google OAuth in the browser with PKCE, accepts only Kitna's expected callback, checks the saved verifier's age, and exchanges the authorization code. Session/refresh data is encrypted using a key held by Android Keystore. Refresh tokens are not part of the book, diagnostics or portable backup. The mobile build contains only the Supabase URL and publishable key, never elevated server credentials.

The configured callback chain is Google → `https://hoevqcscyvjgwrqxfevc.supabase.co/auth/v1/callback` → `app.kitna://auth/callback` → Kitna. The hosted preflight confirms provider configuration and the initial Google authorization redirect; completing consent, the app callback and session restoration still requires real-account/device testing.

## Synchronization and conflict rules

`CloudCoordinator` uses **foreground HTTP polling**, normally every 20 seconds, with manual/reconnect requests and a longer retry interval on failure. `kitna_sync` receives known group revisions and returns only changed snapshots plus the current authorized group IDs. It is not a WebSocket Realtime or always-running background service. Persisted drafts survive closing the app and resume when synchronization runs again.

The server serializes commands per group, verifies active membership and validates dates, integer-paise amounts, exact payer/share totals, receipt bounds and participant references. A stable actor/operation UUID makes an accepted retry idempotent. The original queued request is kept for a retry rather than being silently rewritten.

Each edit carries the last accepted entity/settings version. Stale writes return a conflict. Pending drafts are retained over the latest accepted snapshot for review; a conflict must stop subsequent queued writes for that group until resolved. Soft deletion/restoration keeps financial history. New shared comment authors and audit actors come from the session rather than a client-selected person's name.

Group settings do not silently change membership. Invitations/removal/leave have separate server operations. Shared groups cannot be hard-deleted through the local empty-group deletion path, and shared participant names cannot be represented as remotely changed by a local-only rename.

## Membership and private files

Row policies and server functions check membership for group records, participants, revisions, activity and receipt metadata. Being signed in alone does not grant access to another group. Invitations contain random bearer tokens, are stored hashed, expire and can be revoked. Accepting one binds/consumes it atomically. Owner removal or a member leaving revokes future server reads/writes; historical participant IDs remain for balances. Existing exported copies cannot be retracted.

Receipt files use the private `kitna-receipts` bucket and `<group UUID>/<receipt ID>.jpg` paths. Metadata includes name, MIME, byte size and SHA-256. Files are immutable; retries verify existing bytes rather than replacing them. Local verified copies support offline viewing.

For the first share of a group with receipts, the group/membership is created before private uploads, avoiding an upload-permission dependency loop. Subsequent expenses upload new photos before submitting their metadata. Missing/incomplete transfers remain pending/retryable rather than being treated as a finished photo sync. Retained history can refer to removed attachments, so routine expense edits do not delete those files.

## Existing records and portable backups

- New fields retain compatible defaults and the Room SQL schema stays at version 1. The app does not use destructive migration fallback.
- Sharing imports only the selected group, with stable command/identity mapping, original dates, allocations, comments, deleted records and revisions. A local safety copy is kept before sharing; unrelated groups remain local.
- Imported history is marked as legacy/imported rather than claiming that a later-joined account made its original changes.
- Complete ZIP backups include referenced photos, but strip shared links and queued commands. Restoring requires sign-out and restores local data; it cannot replay edits into a live group.
- Sign out, local erase, leaving a group and deleting a cloud account are different actions. A public launch still needs a completed account-deletion/retention policy; support requests go to MT Studio.

## Evidence and acceptance still needed

The backend's **16 local PostgreSQL/pgcrypto/RLS tests pass**, covering financial validation, authorization, idempotence, conflicts, invitations, private file policies and conditional sync. These are separate from the **117 passing core/app tests** recorded in `verify-v05-complete.log`, which also confirms assembly and lint with 0 errors/26 warnings. `cloud-preflight.log` confirms hosted protocol HTTP 200/schema 1, Google enabled, anonymous group-access denial and the initial Google redirect.

Before describing collaboration as end-to-end verified, use two actual Google accounts on two phones, plus an unrelated account. Check creation/join, concurrent expenses, a retried operation, competing edits, offline deletion, revoked membership, process death, token expiry/account switching, receipt failures and update/backup preservation. Reconcile balances across both devices after reconnection. The hosted preflight alone does not cover these journeys.

See [CLOUD_DEPLOYMENT.md](CLOUD_DEPLOYMENT.md) for the deployed SQL/RPC contract and [SUPABASE_SETUP.md](SUPABASE_SETUP.md) for provider configuration and Free-plan limits.
