# Kitna

Kitna is an Android expense tracker created by **MT Studio**. Track local bills without an account, or sign in with Google to share a selected group with friends through Supabase. Members of a shared group can record expenses and repayments from their own phones and review the group's balances, budget, receipts and history.

Amounts are INR, stored as integer paise. Kitna records payments made elsewhere; it does not initiate bank transfers or verify that a recorded payment reached a bank account.

## Version 0.6

- Existing Manga Panels colors and styling, with option placement based on the supplied layout references: compact expense lists, colored group headers, expandable member balances and grouped Account settings. Groups is the opening tab. Light is the default when no appearance preference has been saved; saved appearance choices remain available.
- Copy a 12-character group code from a newly shared group or its settings. Friends enter it through **Groups → Join group**. Codes expire after seven days and can be revoked.
- Add an optional Google email to a local friend or invite an existing/new group participant by email. A verified matching Google account gains the group automatically on sync, preserving the participant's existing balances. No email message is sent. Leaving/removal prevents automatic rejoining until explicitly reinvited.
- Categorized Account settings, visible logout, and group settings with members and leave controls. Owners can leave after another joined account can take ownership.
- Optional shared-activity notifications under **Account → Settings → Notifications**. Foreground alerts follow synchronization; Android schedules background checks roughly every 15 minutes or later. These are periodic checks, not instant push delivery. First sync and your own edits stay silent.

The v0.6 verification passed **129 tests (52 core + 77 app)** and **31 separate PostgreSQL tests**. The hosted project reports protocol version 2 after the invitation migration. Real-device Google sign-in, two-phone synchronization, notification permission/delivery and accessibility checks remain necessary. See [the v0.6 notes](docs/releases/v0.6.0.md).

## Version 0.5 (previous release)

- **Manga Panels:** cream-and-ink light mode, a corresponding dark theme, warm accents, comic panels and clear amount hierarchy. Budget status moves through green, amber, orange and red with text labels. Large expenses use stronger tint and bold amounts; smaller expenses keep readable text on softer surfaces.
- **First-run guidance:** profile setup, an introduction to local/shared records, and a path to Google sign-in. The walkthrough can be reopened from Help.
- **Faster entry:** duplicate an expense with a fresh ID/date and no copied receipt attachments, remember the previous group/payer where valid, and bring validation errors into view.
- **Accessibility:** scalable Material typography, 48 dp controls, descriptive actions and spoken validation/status information. Real-device TalkBack and large-font verification remains necessary.
- **Help, support and About:** split/repayment explanations, privacy information, user-selected diagnostic sharing, and the supplied MT Studio artwork. Support: [official.mtstudio.dev@gmail.com](mailto:official.mtstudio.dev@gmail.com); creator: [MT Studio on GitHub](https://github.com/MT-Studio-dev).
- **Optional shared groups:** browser-based Google OAuth with PKCE, encrypted Android Keystore session storage, explicit invitations/participant claims, private receipt storage and version-checked shared edits.
- **Offline shared drafts:** the visible ledger and durable outbox are saved in one local transaction. Foreground synchronization checks for changed group revisions every 20 seconds and on explicit requests; it does not use WebSocket Realtime. Failed/conflicting drafts remain visible for review.

The completed v0.5 run in `verify-v05-complete.log` passed **117 tests: 50 core and 67 Android-side JVM tests**, with zero failures/errors/skips. Assembly passed and lint reported **0 errors and 26 warnings**. The **16 PostgreSQL backend tests** are a separate result. A successful hosted preflight is not a completed two-account phone test. See [FEATURES.md](docs/FEATURES.md) for evidence and limits.

## Use the app

1. Complete the first-run introduction and set your display name, or continue with an existing local book.
2. Add local friends and create a group. People named in a local book do not need accounts.
3. Add an expense, choose payer contributions and the split, then review the allocations before saving. Duplicate an existing bill when its description/allocations can be reused.
4. Review balances and record repayments already made. Attach receipt images, set a trip budget and share a plain-text trip summary as needed.
5. To collaborate, sign in with Google and create/share a group. Add friends' Google emails to connect them to the existing participant slots automatically. A group code lets other friends join a new slot without copying a full link.
6. A friend signs in with the matching email and syncs, or enters the code through **Groups → Join group**. The owner manages invitations and removals. Members can leave while historical financial references remain; ownership transfers when an owner leaves.
7. Review Pending/Failed/Conflict messages before assuming a draft reached other phones. Use **Sync now** to request an update; shared network operations require internet.
8. Save complete ZIP backups regularly. Sign out before restoring a backup or erasing this device's book. Restoring creates local records and never replays cloud commands into a live group.

## Supabase configuration

The selected project is `https://hoevqcscyvjgwrqxfevc.supabase.co`. The Android client uses its publishable key and the signed-in user's session. Google Client Secrets, database passwords and elevated server keys stay outside the app.

Google's configured callback is `https://hoevqcscyvjgwrqxfevc.supabase.co/auth/v1/callback`; Supabase's app-return allowlist includes `app.kitna://auth/callback`. Browser OAuth returns to the app, which exchanges the code using its saved PKCE verifier.

See [SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md) for provider setup and [CLOUD_DEPLOYMENT.md](docs/CLOUD_DEPLOYMENT.md) for the deployed RPC contract, schema and permission checks. [SHARED_GROUPS_PLAN.md](docs/SHARED_GROUPS_PLAN.md) describes identity projection, persistence and conflict handling.

The hosted preflight recorded in `cloud-preflight.log` returned HTTP 200 and protocol schema 1, reported the receipt-bucket name and Google provider enabled, denied anonymous group RPC/table reads, and redirected the Google authorization request to `accounts.google.com`. It did not complete a user's Google consent flow or exercise private photos with two real accounts.

The Free plan has storage, bandwidth and activity limits; inactive projects can pause. Local records remain usable offline, and shared changes remain pending until the service accepts them. Public growth is not a promise of unlimited free hosting.

## Trip tools and earlier features

Receipt photos, trip budgets, plain-text summaries and portable ZIP photos were introduced in v0.4. See [TRIP_FEATURES.md](docs/TRIP_FEATURES.md) for use and storage limits.

Earlier features include friends/groups, five split methods, multiple payers with Remaining/Overpaid amounts, optional debt simplification, manual repayments, comments/revisions, search/filters, reports, CSV export, automatic categories with manual override, group icon/color choices and the Kitna launcher mark. A ₹1,000 bill with ₹450 entered leaves ₹550.

## Data and updates

Existing v0.1–v0.4 books retain Room schema version 1 and load added fields with compatible defaults. Local groups stay local until explicitly shared. A named friend is not automatically matched to a Google account by name. Sharing preserves the selected group's dates, allocations, comments and revisions, while keeping unrelated groups separate.

Normal APK updates retain application ID `app.kitna`, the database file and the established test signing identity. Install over the existing app to retain local records. Internal per-version safety copies are kept before a newer version rewrites a valid stored book. See [UPDATES.md](docs/UPDATES.md).

Uninstalling, clearing Android app storage or losing the device removes its local book and private photos. Android automatic app backup is disabled. Keep an exported complete ZIP before changing phones or reinstalling. Shared server records are separate from local device erase, sign out and group membership.

ZIP backups include names, expenses, repayments, comments, budgets, history and referenced receipt photos. They contain private information. Exported/restored books strip cloud links and queued commands; restoration cannot silently overwrite a live shared group. Older JSON backups restore local books, but cannot carry missing image bytes between phones. CSV is a viewing/export format.

## Build and run

Open the project in Android Studio and run `app` on Android 8.0/API 26 or later. The current project uses Java 17, Kotlin, Jetpack Compose, Android SDK 35 and Room. Local tools work offline; Google sign-in and shared synchronization use internet.

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. A resource-tool override is available when needed:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug "-Pandroid.aapt2FromMavenOverride=C:\Users\manut\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt2.exe"
```

Use your installed SDK path. Dependencies require internet on their first download. Supabase client settings are read from ignored local build settings; see the setup guide. A public Play release still needs production signing, current Play requirements and the remaining launch checks.

## Verification

The completed v0.5 Gradle verification in `verify-v05-complete.log` passed **117 tests (50 core + 67 app)** with zero failures/errors/skips, APK assembly and lint with **0 errors/26 warnings**. The app uses version code 5/version name 0.5.0 and retains the established test signing certificate. Core/JVM, Android simulation, local PostgreSQL and hosted preflight evidence cover different things.

The historical v0.4 run passed **84 tests: 41 core and 43 Android-side JVM tests**, with no failures/errors/skips. Assembly passed and lint reported 0 errors/14 warnings. Those figures describe v0.4, not a completed v0.5 run.

The shared backend's local PGlite run passed **16 actual PostgreSQL/pgcrypto/RLS tests**. It uses small stand-ins for Supabase's auth session adapter and Storage catalog, and does not prove hosted Storage HTTP or Google login behavior. The hosted preflight checks described above are recorded separately.

Native-render review PNGs are under `app/build/screenshots/`; they are simulated Activity/Dialog renders. Seven Manga screens and updated group views were visually reviewed, including creator artwork in light/dark mode, a 97%-used budget with enlarged text, expense entry and onboarding. No physical phone or emulator, two completed Google sign-ins, or cross-phone expense/receipt flow has been verified in this revision yet. Those checks remain required before a public launch.
