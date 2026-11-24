# Deploying Kitna shared groups

This backend is separate from Google provider configuration. The Android publishable key cannot create tables, policies or server functions. No database password, service-role key or personal access token belongs in the Android app or a chat message.

Status on 24 November 2026: the user deployed the migration in the selected project. `cloud-preflight.log` records protocol HTTP 200/`ready:true`/schema 1, the receipt-bucket name, Google provider enabled, denied anonymous group RPC/table reads and a Google authorization redirect to `accounts.google.com`. Actual two-account Google consent, app callback and private photo/financial exchange are not yet verified. The deployment steps below are for review/recreation, not a request to reset or create a second project.

## Deploy

1. Open the intended Kitna project in Supabase Dashboard and confirm its project reference is `hoevqcscyvjgwrqxfevc`.
2. Open **SQL Editor → New query**.
3. Paste the complete contents of [`202611240001_shared_groups.sql`](../supabase/migrations/202611240001_shared_groups.sql), then run it. It creates the Kitna tables, atomic RPCs, member-access policies and private receipt bucket in one transaction.
4. Run [`verify_deployment.sql`](../supabase/verify_deployment.sql). Check `schemaVersion: 1`, all six public Kitna tables with row security enabled, the private `kitna-receipts` bucket capped at 2 MB JPEG objects, and both `*_must_be_false` values false.
5. Test Google login and group sharing with two actual accounts. Also test an unrelated third account: it must not read that group's rows or photos or write to it.

The migration can be re-run: it replaces its functions/policies and retains existing Kitna records. It does not drop or reset the database. Run it only in the dedicated intended project. An unrelated pre-existing permissive Storage policy could broaden access to any bucket, so review all Storage policies if the project has been used for other apps.

In the SQL verification output, `prosecdef:false` for `kitna_status` is intentional: this public health function returns protocol constants and needs no elevated execution rights. That metadata column is separate from the JSON `ready:true` field. The shared-data functions use controlled elevated execution and verify session membership before accessing data. A preflight reporting the bucket name does not independently verify all hosted Storage object policies; complete the member/outsider checks too.

## Android request contract

POST to `https://<project-ref>.supabase.co/rest/v1/rpc/<function>` with JSON arguments named below. Headers are `apikey: <publishable-key>`, `Authorization: Bearer <signed-in-user-access-token>` and `Content-Type: application/json`. The publishable key is not a user bearer token. The unauthenticated health function needs only the publishable key.

| RPC | Arguments | Response |
|---|---|---|
| `kitna_status` | `{}` | `{schemaVersion:1,ready:true,receiptBucket:"kitna-receipts"}` |
| `kitna_list_groups` | `{}` | Array of complete snapshots for the current user's active memberships |
| `kitna_sync` | `p_revisions`: object mapping group UUID to last accepted numeric revision | `{groups:[changed snapshots only],memberGroupIds:[all active group UUIDs]}` |
| `kitna_share_group` | `p_command_id`, `p_group`, `p_participants`, `p_records` | Snapshot after an atomic initial import |
| `kitna_apply` | `p_command_id`, `p_group_id`, `p_kind`, `p_entity_id`, `p_expected_version`, `p_payload` | Snapshot after one atomic command |
| `kitna_invite` | `p_group_id`, `p_participant_id` (nullable) | `{token,expires_at,groupId,participantId}` |
| `kitna_accept_invite` | `p_token` | Snapshot; the current account is explicitly bound to the invited ledger participant |
| `kitna_revoke_invites` | `p_group_id` | `{revoked:true}`; revokes all unused invitations |
| `kitna_remove_member` | `p_group_id`, `p_participant_id` | Owner-only removal followed by a snapshot retaining historical identities |
| `kitna_leave_group` | `p_group_id` | `{left:true,groupId}`; the owner cannot leave their own group |

`p_kind` for a live command is `expense`, `settlement`, `comment` or `group`. Record IDs and group/participant IDs are canonical UUIDs. New records use expected version **0**. Existing records and settings use their last accepted version; a stale command returns SQLSTATE `40001`, which the client must present as a reviewable conflict rather than retrying an overwrite.

An operation UUID belongs to one authenticated actor and one exact request. Retrying the same accepted operation does not create another record, history entry or activity. Its response is the latest authorized group snapshot. Reusing the UUID with different data returns `22023`. Unauthorized operations return `42501`.

The global snapshot `revision` changes after ledger/settings/membership changes. It is separate from `group.version` and individual record versions. Send the latest accepted revision to `kitna_sync`; unchanged groups do not retransmit their complete history. If a cached group disappears from `memberGroupIds`, mark its cached view as access-ended and stop its pending writes. Server revocation cannot retract previously exported or downloaded copies. A snapshot revision is not a receipt-download cursor; cache immutable receipt files by group and receipt ID.

The v0.5 Android coordinator requests this conditional HTTP sync while foregrounded, normally every 20 seconds and on manual/reconnect requests, with a longer interval after failures. It does not use WebSocket Realtime or an always-running background service. The durable outbox resumes when the app can synchronize again.

## Snapshot shape

```json
{
  "group": {
    "id": "group-uuid",
    "ownerId": "auth-user-uuid",
    "name": "Goa trip",
    "kind": "Trip",
    "archived": false,
    "simplifyDebts": true,
    "icon": "plane",
    "color": "orange",
    "budget": 200000,
    "version": 1,
    "members": ["participant-uuid"]
  },
  "revision": 1,
  "myParticipantId": "participant-uuid",
  "participants": [{"id":"participant-uuid","name":"You","userId":"auth-user-uuid","active":true}],
  "records": [{"kind":"expense","id":"expense-uuid","payload":{},"version":1,"actorId":"auth-user-uuid","imported":false}],
  "revisions": [{"id":"revision-uuid","expense":{},"reason":"Before edit","time":1795536000000,"actorId":"auth-user-uuid","imported":false}],
  "activity": [{"id":"activity-uuid","message":"Added dinner","time":1795536000000,"actorId":"auth-user-uuid","imported":false}]
}
```

`payload` uses the existing Expense, Settlement and Comment field names, with all person references converted to canonical participant UUIDs. Money stays integer paise. All participants, including removed participants, remain in the snapshot for financial history. `active` controls account access, and `userId:null` denotes an unclaimed named participant. The current viewer's participant must be projected to the local `self` identifier without rewriting server identities.

On initial sharing, send group settings with its UUID, and participants as `{id,name,isSelf}`. Exactly one participant must have `isSelf:true`; the server binds it to the session user. Other accounts are bound only by explicit invitation acceptance, never by matching display names.

Initial `p_records` entries are `{kind,id,payload}`. Expenses must be included before their comments/revisions logically, but the server orders import validation itself. In addition to live record kinds, import accepts `revision` (payload ExpenseRevision) and `activity` (payload Activity). Imported history is marked as imported; it does not claim that a later-joined account performed the historical action. IDs, amounts, references and dates are validated atomically before the import commits.

New comment authors and timestamps are taken from the authenticated member on the server. Existing comment authors/timestamps remain immutable; only that author or the group owner can edit the comment. Financial edits create server-generated prior-expense revisions and activity attribution.

## Receipt storage

Bucket: **`kitna-receipts`**, private. Object path:

```text
<canonical-group-uuid>/<safe-receipt-id>.jpg
```

Group members can insert and download immutable objects. Client overwrite/update/delete has no Kitna policy. A retried upload can receive an already-exists response; verify the existing bytes against the saved checksum rather than overwriting them. The bucket caps each object at 2 MB and accepts JPEG only. Metadata includes the existing ID/name/MIME/size/SHA-256 fields; one ID cannot be changed to different metadata within a group, including its history.

When sharing an existing group with local photos, create the shared group/membership first, then upload its private files. Display Pending until all referenced files finish. Retrying the same share operation returns the existing group; it does not duplicate the group. For subsequent expense edits, upload new photos before submitting their metadata. A bucket object is not required before the initial share RPC, avoiding a membership/upload dependency loop. Missing uploads must remain visible/retryable on the client.

## Executed verification

Run the local PostgreSQL harness:

```powershell
npm ci --prefix supabase/tests --ignore-scripts --no-audit --no-fund
npm test --prefix supabase/tests
```

The completed local run passed **16 tests**, with zero failures or skips. It uses PGlite's actual PostgreSQL engine and pgcrypto extension, with small stand-ins for Supabase's `auth.uid()` session adapter and Storage catalog. It does not use real Google accounts, contact the hosted project, or prove Supabase Storage HTTP behavior. The tests exercise anonymous/outsider denial, row policies, explicit invite claims, command retries, stale edits, money validation, immutable receipts/history, member removal/re-invitation, leave/revoke behavior, conditional sync and safe migration reapplication. The output is recorded in `supabase-tests.log`.

The final v0.5 Gradle run in `verify-v05-complete.log` passed **117 tests (50 core + 67 app)**, APK assembly and lint with **0 errors/26 warnings**; the **16 backend tests** are a separate result. Remaining launch work includes actual two-account/two-phone verification, device-level process-death/offline/conflict testing, cloud account-deletion policy, ongoing backup/usage monitoring and required Google/Play production setup. No automatic public launch is performed by this migration.

Implementation references: [Supabase database functions](https://supabase.com/docs/guides/database/functions), [row-level security](https://supabase.com/docs/guides/database/postgres/row-level-security), [Storage access policies](https://supabase.com/docs/guides/storage/security/access-control).
