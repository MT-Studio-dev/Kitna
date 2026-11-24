# Feature and verification matrix

Kitna 0.6 supports an account-free local book and optional Google-authenticated shared groups. Local friends have optional email addresses. A verified matching Google identity can claim an email invitation; display names do not establish identity. Payments are manually recorded external events, not bank-verified transfers.

## Version 0.6

| Requirement | Implementation | Verification |
|---|---|---|
| Reference-based layout | Existing Manga Panels theme retained, Light default, compact rows, colored group headers, Groups first, per-member expandable balances | Native renders reviewed; default appearance, navigation, settings, balance expansion and settlement callback checks |
| Short group codes | 12 unambiguous characters, owner-only retrieval, seven-day expiry, revocation and per-account attempt limit | Protocol client tests and actual PostgreSQL permission/throttling checks |
| Email membership | Optional email on local friends, targeted shared invitations, verified Google identity matching on sync, stable existing participant IDs | Old model compatibility/financial totals checked; SQL tests reject unverified/profile-spoofed identities and preserve removed-member restrictions |
| Settings, logout and leave | Categorized appearance/data/notification tools, visible signed-in logout, member leave and owner transfer | UI navigation checks; PostgreSQL owner transfer and sole-owner rejection |
| Notifications | User opt-in, Android 13 permission request, private notification previews, account-scoped deduplication, shared process runtime and scheduled background checks | Deduplication/first-sync/own-edit/logout/job cancellation tests; real phone delivery remains unverified |

Final v0.6 evidence: **129 core/app tests (52 + 77)**, **31 separate PostgreSQL tests**, assembly and lint with **0 errors/33 warnings**, recorded in `verify-v06-final.log` and `supabase-v06-tests.log`. Hosted protocol 2 and Google/anonymous-access preflight are in `cloud-preflight-v06.log`. Successful simulation does not replace real-device sign-in, two-account receipt tests, TalkBack or background delivery checks.

## Historical v0.5 verification

The final v0.5 run in `verify-v05-complete.log` passed **117 tests (50 core + 67 app)** with zero failures/errors/skips, APK assembly and lint with **0 errors/26 warnings**. The **16 PostgreSQL backend tests** are separate from those JVM/Android results. This matrix distinguishes executed checks and unverified real-phone behavior.

## Version 0.5

| Requirement | Implementation | Verification scope |
|---|---|---|
| Manga Panels redesign | Native Compose light/dark themes, ink panels, warm accents, scalable typography and familiar navigation | Native-render tests pass; seven Manga PNGs and updated group views were visually reviewed |
| Budget risk and expense size | Green/amber/orange/red budget states with labels; at-limit/over-budget text; larger expenses use stronger surfaces and bold amounts | Helper/runtime cases pass; the 97%-used budget was reviewed with enlarged text; color is paired with readable values and labels |
| First-run guidance | Profile setup and local/shared explanation; Google entry; introduction can be reopened | Onboarding runtime checks pass and its native render was reviewed |
| Faster expense entry | Duplicate with new ID/current date and no copied receipts; remember valid group/payer; inline validation with error visibility | New runtime checks pass; expense render reviewed and repeated-save/draft safety retained |
| Accessibility | Material typography with 14 sp supporting roles, 48 dp controls, meaningful descriptions and announced errors/status | Automated/native checks pass; physical TalkBack, text scaling and keyboard checks remain |
| Help, privacy, support and creator | Help/About screens, supplied MT Studio assets, GitHub and email actions, user-selected diagnostics without ledger attachments | Runtime checks pass and creator artwork was reviewed in both themes; no messages are sent automatically |
| Google sign-in | Browser OAuth PKCE callback, refresh handling and encrypted Android Keystore session persistence | Live preflight confirms Google provider enabled and authorization redirect; actual Google consent/account callback still unverified |
| Shared identity and import | Stable group/participant/record mapping; viewer projected to local `self`; selected-group import with legacy dates/history | SharedBooks domain tests pass; live two-account import not verified |
| Offline pending commands | Optimistic ledger plus durable outbox in one LocalStore transaction, operation IDs, expected entity versions and retained conflicts | Domain/transport checks pass; actual two-phone concurrent edits remain |
| Synchronization | Foreground conditional HTTP revision checks every 20 seconds/manual request/reconnect; unchanged ledgers omitted | `kitna_sync` PostgreSQL test passes; this version does not use WebSocket Realtime |
| Group membership | Owner invitation/claim/removal/revocation; non-owner leave; ledger identities retained for history | Local PostgreSQL permission/invitation tests pass; accepted-account UI flow remains unverified on phones |
| Shared receipts | Private `kitna-receipts` bucket, group-scoped immutable paths, size/hash metadata and local verified copies | Local SQL tests cover membership and metadata immutability; hosted upload/download retry flow not yet verified with members |
| Safe portability | Complete ZIP strips cloud links/outbox; restore requires sign-out and does not replay remote commands | Local backup/runtime checks pass |

Creator/support: [MT Studio](https://github.com/MT-Studio-dev), [official.mtstudio.dev@gmail.com](mailto:official.mtstudio.dev@gmail.com). See [brand-guidelines.md](brand-guidelines.md) for the roles of the original Kitna mark and supplied creator artwork.

## Executed cloud/backend evidence

The migration and contract are in [CLOUD_DEPLOYMENT.md](CLOUD_DEPLOYMENT.md). The local PGlite harness passed **16 tests**, using actual PostgreSQL, PL/pgSQL, pgcrypto and row policies with stand-in auth-session/Storage catalogs. Cases cover anonymous/outsider denial, participant claims, idempotent retries, stale edits, amount/date/member validation, immutable receipts/history, removal/re-invitation, leave/revoke operations, conditional sync and safe migration reapplication.

`cloud-preflight.log` records the hosted project check: protocol HTTP 200, `ready:true`, schema version 1, receipt-bucket name, Google provider enabled, denied anonymous group RPC/table reads and an authorization redirect to `accounts.google.com`. These do not prove that a user completed Google sign-in or that two group members exchanged records/photos.

In the deployment SQL verification result, `prosecdef:false` for `kitna_status` is intentional: this public, data-free health function does not need elevated execution rights. It is unrelated to the protocol's `ready:true` field. Shared-data RPCs enforce membership before reading or writing.

## Retained local features

| Module | Behavior | Existing evidence |
|---|---|---|
| Friends/groups | Add local participants; create/edit group appearance/type; archive/restore; delete eligible empty local groups | Earlier creation/style/domain/runtime cases; shared membership uses its separate controls |
| Expenses/payers | Edit/delete/restore bills; single or multiple payers with Remaining/Overpaid and Use remaining | Earlier domain and durable UI cases |
| Splitting | Equal, exact amounts, percentages, shares and signed adjustments | Earlier integer-paise/randomized/percentage/adjustment cases |
| Balances/debt simplification | Group/friend positions; optional simplified or original pairwise debts including cycles | Earlier core ledger/cycle cases |
| Repayments | Record/edit/delete manual payments with method/date/reference | Earlier partial-payment and correction cases |
| Comments/history | Local comments and revisions; shared author/actor comes from the authenticated session | Earlier durable history plus new server actor/CAS tests |
| Local persistence | App-private Room aggregate; compatible model defaults; per-version safety copies | Earlier reopen/concurrency/failure/large-Unicode/update tests |
| Portability | CSV plus complete ZIP/legacy JSON import, validation and confirmed replacement | Earlier CSV/receipt/backup checks; v0.5 adds portable shared-cache separation |
| Reports/search | Personal spending summaries, filters and search across records/names/categories/amounts | Earlier core/home-search/filter/report cases |
| Trip tools | Receipt copies/viewer, group budget and native plain-text summary sharing | See [TRIP_FEATURES.md](TRIP_FEATURES.md) |

The completed **historical v0.4** run passed 84 tests (41 core + 43 app), assembly and lint with 0 errors/14 warnings. Its five trip/receipt renders were visually reviewed. Those results do not establish v0.5 verification.

## Evidence locations and limits

- Final Gradle log: `verify-v05-complete.log` (successful).
- Core XML/HTML: `core/build/test-results/test/`, `core/build/reports/tests/test/`.
- Android-side XML/HTML: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/`.
- Lint: `app/build/reports/lint-results-debug.html` and `.txt`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Native render PNGs: `app/build/screenshots/`.
- Backend: `supabase/tests/verify.mjs`, `supabase-tests.log`.
- Hosted preflight: `tools/cloud_preflight.py`, `cloud-preflight.log`.

Robolectric tests and native rendering simulate Android SDK 28. No physical-phone/emulator, two-account Google sign-in or cross-phone shared expense/receipt test has completed yet. File providers, share targets, system keyboards/insets, token expiry/process death and actual concurrent edits require device checks. Public launch also needs production signing/Play/OAuth setup and a completed cloud account-deletion/retention policy.
