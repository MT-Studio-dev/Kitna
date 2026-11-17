# Updating Kitna without losing records

Install a newer Kitna APK **over the installed app**. Android keeps an app's private data during a normal package update when the application ID and signing certificate remain the same. Kitna continues using application ID `app.kitna`, the same `kitna.db` file, and Room database schema version 1.

The new group icon and color fields have backward-compatible defaults. Existing groups receive the default group icon and teal color until you choose another style. Existing expense categories, dates, notes, allocations, payments, comments, deleted records and edit history are loaded without being recategorized or replaced.

Before the first valid book is opened by a newer installed version, Kitna keeps an exact JSON safety copy in its private `noBackupFilesDir/upgrade-backups/` directory. A complete, synced temporary file is renamed into place; a partially written copy is not treated as a finished backup. Each target version has its own filename, and later edits do not replace that version's original safety copy. If validation or a required schema migration fails, Kitna reports an error and retains the stored data instead of resetting the database.

These internal copies stay on the same phone and are not uploaded. They are protection for app updates, **not** a backup for uninstalling, clearing Android app data, factory-resetting, or replacing a phone. They are removed by the app's explicit **Erase all local data** action too. Use **Account → Save a backup** to keep a portable JSON copy somewhere you control.

If Android refuses an update because its signing certificate is different, do not uninstall the installed version just to bypass the error. Build or obtain an APK signed with the original key, or save a portable JSON backup from the installed app first.

## Developer safeguards

- Keep `applicationId = "app.kitna"` and the original signing key; raise `versionCode` for each release.
- Keep compatible model defaults for newly added fields. The database builder does not use destructive migration fallback.
- Any future change to Room's SQL schema must supply and test an explicit migration before increasing the database schema version.
- Test updates against an existing database, not only a clean installation. `LocalStoreUpdateRuntimeTest` simulates v0.2 → v0.3 and a subsequent update, compares all record types and exact dates, checks per-version safety copies, and verifies corrupt or unsupported old data is retained.
- Old backups that never stored a field cannot supply its original value. For example, absent fields use the compatible model defaults; Kitna does not silently invent an historical date.
