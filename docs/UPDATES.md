# Updating Kitna without losing records

Install a newer Kitna APK **over the installed app**. Android keeps an app's private data during a normal package update when the application ID and signing certificate remain the same. Kitna continues using application ID `app.kitna`, the same `kitna.db` file, and Room database schema version 1.

New fields have backward-compatible defaults. Existing groups receive the default group icon and teal color until you choose another style, and start without a budget. Older expenses start without receipt attachments. Stored expense categories, dates, notes, allocations, payments, comments, deleted records and edit history are loaded without being recategorized or replaced. Private receipt files remain in the same installation. The category suggestion helper only changes an expense when its editor's auto-categorization is enabled.

Before the first valid book is opened by a newer installed version, Kitna keeps an exact JSON safety copy in its private `noBackupFilesDir/upgrade-backups/` directory. A complete, synced temporary file is renamed into place; a partially written copy is not treated as a finished backup. Each target version has its own filename, and later edits do not replace that version's original safety copy. If validation or a required schema migration fails, Kitna reports an error and retains the stored data instead of resetting the database.

These internal copies stay on the same phone and are not uploaded. They are protection for app updates, **not** a backup for uninstalling, clearing Android app data, factory-resetting, or replacing a phone. They are removed by the app's explicit **Erase all local data** action too. Use **Account → Save a backup** to keep a complete portable ZIP somewhere you control. It contains the book and every receipt referenced by the current expenses or their history. An internal JSON snapshot alone does not contain the image bytes.

If Android refuses an update because its signing certificate is different, do not uninstall the installed version just to bypass the error. Build or obtain an APK signed with the original key, or save a portable backup from the installed app first. Versions before 0.4 export JSON; version 0.4 exports ZIP so receipt photos travel with the book.

## Developer safeguards

- Keep `applicationId = "app.kitna"` and the original signing key; raise `versionCode` for each release.
- Keep compatible model defaults for newly added fields. The database builder does not use destructive migration fallback.
- Any future change to Room's SQL schema must supply and test an explicit migration before increasing the database schema version.
- Test updates against an existing database, not only a clean installation. `LocalStoreUpdateRuntimeTest` simulates v0.2 → v0.3 and a subsequent update, compares all record types and exact dates, checks per-version safety copies, and verifies corrupt or unsupported old data is retained.
- Old backups that never stored a field cannot supply its original value. Absent fields use the compatible model defaults; an historical value that was never serialized cannot be recovered from that backup. Current v0.2/v0.3 saves include defaults and retain their explicit dates.

## Retaining the original signing certificate

The update key is kept outside this repository at:

```text
~/.kitna-signing/kitna-updates.keystore
```

The local build points to it through ignored `local.properties`:

```properties
kitna.signingStore=C:/Users/<your-user>/.kitna-signing/kitna-updates.keystore
```

Use the actual absolute path on your computer. Keep the original private keystore safe and copy it securely when moving development machines. The private key and `local.properties` must not be committed or published. A newly generated keystore with the same filename or alias is still a different signing identity and will not replace the installed app.

The expected **public certificate SHA-256 fingerprint** for existing Kitna updates is:

```text
897263793402895ab3dd755435bb1545564f46a3ebe37247734896ee63e18895
```

After building, inspect the APK's certificate using the Android SDK tool and compare it with this value:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs "app\build\outputs\apk\debug\app-debug.apk"
```

This fingerprint is public verification metadata, not a private key. Never solve a certificate mismatch by uninstalling an app that holds the only copy of your records.
