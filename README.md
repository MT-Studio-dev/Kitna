# Kitna

Kitna is MT Studio's Android expense tracker. Keep local records without an account, or sign in with Google to collaborate on selected expense ledgers through Supabase. Kitna records external repayments; it does not move money or verify bank transfers.

## Version 0.7.1

- Replaced brown/warm base colors with cool indigo/slate light and dark themes. Larger expenses use visual emphasis without a caption. Settle up now focuses on payer, recipient and amount, with optional details hidden.
- Fixed the live invitation error by applying the explicitly approved protocol-3 migration on 28 November 2026. Existing expense/history/receipt counts were preserved; all 11 deployment checks pass.
- Add a normal group bill with **description, amount, Save**. Group members, your payment, equal split, today's date and General category are defaults. Payer and split controls sit in one sentence; category, group, date, camera/receipt and notes use compact selectors.
- Split bills with one or several friends without creating a visible group. Friend history includes these bills and their repayments. Personal spending stays separate from friend debt.
- Add a name-only friend, import one selected contact, search saved friends immediately, or send a private exact-email invitation. No phone authentication, SMS service, public account directory or address-book upload is implied.
- Create a group using only its name. Add several existing friends together from **Add members**, create a person inline, or share an invitation.
- Share actual invitation links through Android's share sheet. Group codes remain a fallback. Pending invitations survive Google OAuth and app restarts; joining requires explicit acceptance.
- Reuse historical participant identities through verified email or explicit sender confirmation. Group-slot claims additionally require server authorization. Forwarding an unaddressed link does not silently assign a historical person's balances.
- Keep the Manga Panels identity, saved light/dark appearance, receipts, budgets, settlements, backups, exports, notifications, ownership transfer, durable outbox and version-checked shared edits.

See [the v0.7.1 release notes](docs/releases/v0.7.1.md), [feature matrix](docs/FEATURES.md), [implementation audit](docs/V07_AUDIT.md) and [invitation deployment guide](docs/APP_LINKS.md). Earlier release evidence remains in [v0.6](docs/releases/v0.6.0.md) and [v0.5](docs/releases/v0.5.0.md).

## Use the app

1. Set your name and start local records, or continue with Google for collaboration. The introductory explanation is optional.
2. Open Add expense from Groups/Friends, a group, a friend or Non-group expenses. Context selects the right people. Enter a description and INR amount, then save.
3. Tap **you** to change payer or enter several contributions. Tap **equally** for equal, exact, percentage, shares or adjustment splits and participant exclusions. Every allocation reconciles in integer paise.
4. Use **Add friends** for name-only people, selected contacts and invitations. In a group, **Settings -> Add members** lets you select several people in one action.
5. Sharing a ledger requires Google sign-in and internet. Invite links/code acceptance opens the authorized group. Verified-email invitations appear in Shared groups for explicit acceptance; individual friend invitations appear in Add friends.
6. Offline local records remain usable. Shared edits save locally with durable retryable operations. Review retained pending/conflicting drafts in Shared groups when another member changes the same record.
7. Export complete ZIP backups from Account. Sign out before restoring or erasing this device's book. Restores create local records and do not replay cloud commands.

## Backend and invitation setup

The Android build contains only a Supabase project URL, publishable key and user session. Elevated keys, database passwords and Google secrets must stay outside the app. Existing Google OAuth remains browser-based PKCE, with Keystore-encrypted sessions and callback `app.kitna://auth/callback`.

The configured project uses Google callback `https://hoevqcscyvjgwrqxfevc.supabase.co/auth/v1/callback`. See [SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md) and [CLOUD_DEPLOYMENT.md](docs/CLOUD_DEPLOYMENT.md).

Apply migrations in order:

1. `202611240001_shared_groups.sql` (existing)
2. `202611240002_invitation_codes.sql` (existing)
3. `202611250001_simplified_collaboration.sql` (new protocol 3)

The new migration was **deployed to the configured production project on 28 November 2026 with explicit user approval**. Hosted status is protocol 3; the missing invitation functions are now present. It preserves financial records, IDs, private receipts, command idempotence, versions and existing codes. Protocol 3 is required for new friendship, preview, explicit group-email acceptance and batch-member RPCs. The older migrations are unchanged.

No hosted invitation domain/signing association has been verified. Current development links use `app.kitna://join?...` and `app.kitna://friend?...`. A configurable HTTPS filter, strict routing, safe static browser fallback and assetlinks template are included. Follow [APP_LINKS.md](docs/APP_LINKS.md) to deploy a controlled host and signing fingerprints. Verified App Links and automatic deferred installation are not claimed. Reopen the original link after installation or use its group code.

## Data and updates

Application identity stays `app.kitna`; version code is 8, version name 0.7.1. Room remains schema 1 with compatible serialized defaults. Update safety copies, existing database and receipt locations remain intact. Hidden non-group ledgers reuse the established integer-paise accounting and backup format without adding visible groups. Sharing is explicit and selected-ledger only.

Install the newer APK over the existing app using the same signing identity. Uninstalling or clearing app data deletes local records/photos; Android automatic backup is disabled. Keep a complete portable ZIP before changing devices. See [UPDATES.md](docs/UPDATES.md) and [TRIP_FEATURES.md](docs/TRIP_FEATURES.md).

## Build and verify

Java 17, Kotlin, Jetpack Compose, SDK 35; minimum Android 8/API 26. Use the existing Gradle wrapper:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cd supabase/tests
npm test
```

SDK/build-tool paths and existing development signing settings stay in ignored `local.properties`. First-time dependencies require internet. The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`. A distribution copy and actual verification totals are recorded in the v0.7.1 release notes.

**Final v0.7.1 verification:** 180 core/Android tests (68 + 112) passed, with 50 separate local PostgreSQL tests retained. Debug assembly succeeded; lint reported 0 errors and 52 warnings. Production now reports protocol 3 and all 11 deployment checks pass, with existing data counts unchanged. The signed development APK is `releases/Kitna-0.7.1-dev.apk`; see the [release notes](docs/releases/v0.7.1.md).

Native screenshot artifacts are simulated Android windows, not physical-device evidence. Real Google consent, two-account expense/receipt synchronization, App Link domain verification, physical TalkBack, contact/camera provider behavior and background notification delivery still require device checks. The Supabase Free plan retains its normal quotas and inactivity behavior; local/offline data remains available.

Support: [official.mtstudio.dev@gmail.com](mailto:official.mtstudio.dev@gmail.com). Creator: [MT Studio](https://github.com/MT-Studio-dev).
