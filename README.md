# Kitna

Kitna is a single-device Android expense tracker for friends. Create a local profile, add people by name, and track shared bills and repayments. Everything is stored in the app's private database on your phone. No account registration, login, cloud project, server, or paid service is required.

The interface follows the familiar expense-tracker structure: friends, groups, balances, expense details, activity, and account/settings. Kitna records payments you made elsewhere; a recorded payment is not bank verification.

## Version 0.3

- Updates keep the existing local book and retain a private pre-update safety copy. See [update and signing instructions](docs/UPDATES.md).
- Multiple-payer entry shows the exact **Remaining** or **Overpaid** amount, with a **Use remaining** action. A ₹1,000 bill with ₹450 entered leaves ₹550.
- Home search covers expenses, repayments, people and groups, including descriptions, categories, notes and formatted amounts. Clear search returns to the current home tab.
- New expenses suggest a category from description words such as **car**, **train** and **taxi** → **Travel**. Selecting a category yourself turns auto-categorization off; the switch can enable it again. Existing categories stay unchanged when an expense is opened for editing.
- Categories have their own visual symbols, and groups can use twelve icons and ten colors. Choose **Group settings → Choose icon and colour** to change a group's appearance.
- Kitna has a new vector logo and adaptive Android launcher icon, including a monochrome themed-icon version. The [PNG](docs/kitna-logo.png) and [SVG](docs/kitna-logo.svg) are included in `docs/`.

## Build and run

Open this folder in Android Studio and run `app` on Android 8.0 (API 26) or later. The project uses Java 17, Kotlin, Jetpack Compose, Android SDK 35, and Room. Build dependencies need internet on the first build; using the installed app does not.

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed build for testing. A local resource-tool override can be supplied when Google's Maven resource-tool download is unavailable:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug "-Pandroid.aapt2FromMavenOverride=C:\Users\manut\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt2.exe"
```

Use your own installed SDK path for that optional flag. `tools/warm_android_cache.py` is an optional recovery helper for slow dependency downloads. It retrieves official repository artifacts and checks their published hashes; normal builds do not need it.

## Use the app

1. Set your local display name in the profile/settings screen.
2. Add friends by name. These are participants in your local records; they do not need to install Kitna.
3. Add an expense with a friend, or create a group and add a group expense.
4. Choose the payer or payer contributions and the split. Review each participant's share before saving.
5. Open a friend or group to review balances and repayments. Record payments you have already made, including partial payments.
6. Use reports and filters to review spending. Save a JSON backup regularly; export CSV when you want spreadsheet-readable records.

## Data and compatibility

Amounts are INR and are stored as integer paise. Existing v0.1 and v0.2 local books retain schema version 1 and are read with backward-compatible defaults for new fields. Stored records are not bulk-recategorized by the new keyword helper. The database stays private to this app installation. There is no background sync and no login flow.

Normal app updates preserve the installed book. The app keeps the same application ID, signing identity and Room database, loads new group-appearance fields with compatible defaults, and keeps a private per-version safety snapshot before a newer build rewrites existing records. See [updating without losing data](docs/UPDATES.md) for installation instructions and safeguards. Safety copies do not survive uninstalling or clearing app data; export a JSON backup when moving or reinstalling the app.

Keep the same application ID and signing key when installing an update over an existing installation. Uninstalling the app or clearing its Android app data deletes local records. Android automatic app backup is disabled, so keep your own exported backups before replacing a phone or reinstalling.

A JSON backup contains names, expenses, comments, repayments, and history. Store it privately. Restoring validates the backup before replacing current records, and the app asks before replacement. CSV is for viewing/export; use JSON for a complete restore.

## Verification

See [the feature and verification matrix](docs/FEATURES.md) for the requested modules, their implementation locations, and the evidence collected for this revision. Core accounting/domain tests are separate from Android runtime tests. Runtime tests use Robolectric, including an Android framework JAR downloaded on first use. Robolectric is a JVM simulation and does not prove device-specific rendering, keyboard, or operating-system behavior.

There was no connected Android device, emulator, or AVD at the initial environment audit for this revision. The build host reported hardware virtualization disabled. Any phone-specific verification still needs an actual device or a suitably configured emulator. Do not infer a passed device test from a successful APK build.

The completed v0.3 verification run passed **62 tests**: 31 pure Kotlin accounting/domain/search/category tests and 31 Android-side JVM tests. The Android-side cases include six Room persistence cases, five simulated-update cases, five original screen flows, four new-feature flows, three home-search flows, seven filter cases, and one native-render review flow. Android lint passed with no errors; its remaining warnings concern dependency updates and code style. Rendered review PNGs are under `app/build/screenshots/`.

The tested flows include creating friends and groups, multiple payers with a percentage split, direct expenses and edits, partial repayments, a payment before any expense, comments, expense deletion/restoration, report totals, dark theme selection, automatic/manual categories, remaining/overpaid contributions, group-style persistence and cancelled picker changes. Update tests compare all record types and exact stored dates across simulated v0.2 → v0.3 and subsequent version changes. Persistence checks also include old v0.1 data, invalid-restore safety, unsupported database-version protection, concurrent updates, and Unicode books larger than Android's normal cursor window.

These are Robolectric checks. Real Android file-picker interaction for import/export, OS keyboard behavior, and device-specific window/inset behavior still require a phone or emulator. JSON imports currently have a 20 MB safety limit; the app reports that limit when a selected file exceeds it.
