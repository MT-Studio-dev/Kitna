# v0.7 workflow audit and implementation map

The pre-change audit covered README, FEATURES, release/update notes, shared deployment documentation, both deployed migrations, the PostgreSQL harness, all core models/actions/validation/accounting, Room storage/backup/receipts, authentication/HTTP/coordinator, ViewModel, navigation, editors, friend/group/member screens and existing runtime/unit tests.

## Existing architecture retained

* `core` owns serializable Book records and pure integer-paise accounting. Five split methods, exact contribution totals, stable remainder allocation, repayments, revisions, budgets, search and exports remain in use.
* Android is Kotlin/Jetpack Compose with a process-scoped KitnaRuntime, lifecycle ViewModel, StateFlow and saveable editor state. Navigation remains the established route-based Compose structure.
* Room schema 1 stores the aggregate book atomically. Model additions have defaults; no destructive reset or SQL schema bump. Per-version upgrade copies and receipt files remain in place.
* SharedGroups maps canonical server participants to local self/person IDs. Durable commands carry stable operation IDs and expected entity versions. Optimistic visible records and commands remain a single transaction. Failed/conflicting drafts are retained.
* Google OAuth continues using browser PKCE and Android Keystore credentials. Supabase access uses only the publishable key and user session. RLS/security-definer RPCs enforce authorization on the server; private receipt storage remains membership-scoped.

## Friction removed

| Before | v0.7 |
| --- | --- |
| Full payer, split, category, date, photos and notes sections compete with the basic bill | Description and amount lead; payer/split inline; optional properties in sheets |
| Group-oriented entry requires choosing a context first | Context defaults from group/friend; main entry supports multiple friends or personal expense |
| New group requires another person | Name alone creates a local group, followed by add/share/skip |
| Adding members goes through separate friend creation or technical shared tools | One searchable selection flow adds several saved friends together, or creates a name-only person inline |
| Invite sender explains navigation and manual codes | Native sharing includes the actual invitation URL and code fallback |
| Opening an invitation loses context during authentication | App-private pending route survives restart; authentication resumes review before acceptance |
| Local friend and imported account can duplicate balances | Verified connections reuse identities; safe reference migration preserves ledger history; ambiguous shared slots require a verified claim |

## Non-group compatibility

Existing two-person Direct ledgers are reused. Multi-friend and personal expenses use hidden Non-group ledgers with stable participant sets. The atomic save creates a ledger and bill together. These are excluded from visible group lists/search, while a Non-group expenses history provides access and optional collaboration. Personal expenses have no friend debt. Accounting walks each ledger once; friend history includes the relevant group and non-group bills. Existing group records are never converted or moved.

Non-group ledgers are local by default. Users explicitly share one from friend/history sharing controls, using the same secured collaborative record/outbox machinery. Direct ledgers connect the existing second slot by verified email; generic links cannot create a third person. New members added to a multi-person ledger do not alter historical expense allocations.

## Privacy and security

Contact selection reads only the chosen Android contact URI; there is no address-book upload or public account search. Email invitations never reveal whether an arbitrary private account exists. Name-only friends remain valid offline. A received friendship cannot automatically claim a historical local person through a forwarded unaddressed link: matching verified email or the sender's explicit identity confirmation is required. Historical participant claims within shared groups additionally require server-verified owner authorization.

The new protocol-3 migration is additive and prepared only. Production deployment, HTTPS hosting, certificate association, real Google consent, two-account synchronization, receipt exchange, notification delivery and physical TalkBack/keyboard checks remain separate verification steps. See APP_LINKS.md and the v0.7 release notes for exact evidence.
