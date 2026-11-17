# Local feature and verification matrix

Scope: one phone, one local book, no registration or cloud sync. Friends are named participants in local records. Repayments made through cash, UPI or a bank can be recorded manually; the app does not initiate or verify transfers.

The completed v0.3 run in `verify-v03-final.log` passed `:core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug` and `:app:lintDebug`. The XML reports contain **31 core tests and 31 app tests**, with no failures, errors or skips. This matrix distinguishes executed tests from reviewed implementation; it does not claim every control on every Android device was exercised.

## Requested changes in v0.3

| Requirement | Implementation | Evidence |
|---|---|---|
| Keep records when updating | Same application ID, signing certificate, `kitna.db` filename and Room schema 1; compatible model defaults; private per-version safety snapshot before rewriting a valid old book | Five `LocalStoreUpdateRuntimeTest` cases preserve exact stored dates and all record types, retain earlier snapshots, check untracked old installations, reject corrupt/unsupported old data without destructive fallback, and erase safety copies on explicit reset |
| Show remaining payer contributions | `PayerAllocation.summary()` uses integer paise; the editor shows Remaining or Overpaid and can fill a person's amount with Use remaining | Core tests cover ₹1,000 − ₹450 = ₹550, ₹1,000 − ₹350 = ₹650, exact allocation, invalid negative inputs and overpayment. UI test records ₹450 + ₹550, rejects ₹450 + ₹600, and verifies durable allocations |
| Fix home search | `BookSearch` handles trimmed case-insensitive words across record fields, names, categories, notes, dates and normalized currency amounts; home search lists matching records rather than only filtering group names | Four core search cases and three full home-search cases cover `TAXI`, Travel, `Alex pickup`, `₹1,000.50`, repayments, deleted-record exclusion, no-match state, clear/close and opening results |
| Automatic categories with manual choice | `ExpenseCategories.suggest()` is a local whole-word/phrase helper; new expense descriptions update the suggestion while Auto-categorize is on. Choosing another category turns the switch off | Core cases cover car/train/taxi, plurals, mixed case, specific phrases and substring false matches. UI cases verify manual override, re-enabling auto mode, and keeping an existing manual category while changing its description |
| Category visuals | Shared vector category symbols appear in expense rows, category choices and reports | Code review and native-render flow; the populated expense and report PNGs show the actual Activity output |
| Kitna logo | Original vector mark, adaptive launcher foreground/background and Android themed monochrome icon | Assets under `app/src/main/res/`; portable artwork in `docs/kitna-logo.svg` and `docs/kitna-logo.png` |
| Group icon and color choices | Twelve stable icon IDs and ten color IDs; style fields stored with the group and included in JSON backups | Core save/rename/serialization tests and UI picker test confirm a Plane/Blue group persists, while a cancelled Car/Red change leaves the committed choice intact |

Update identity, private signing-key location and the public certificate fingerprint are documented in [UPDATES.md](UPDATES.md). Internal snapshots are not protection against uninstalling, Android Clear storage, or losing the phone; portable JSON backups remain available in Account.

## Original local modules

| Module | Local behavior | Evidence |
|---|---|---|
| Profile | Editable name; no login or registration | Launch/all tabs pass; profile save implementation reviewed; account and dark-theme renders generated |
| Friends | Add, rename and remove eligible participants; direct expense and balance view | UI creation and direct-ledger flow; duplicate-name, stable-ID retry, direct-ledger reuse and rename core cases |
| Groups | Create/edit name, members, type and appearance; archive/restore; delete empty groups | UI creation/member selection/style cases; core membership/history/archive protection cases |
| Expenses | Create/edit/delete/restore; dates, categories, notes and full revision snapshots | UI create/edit/delete/restore and durable-history checks; domain validations |
| Payers | One payer or multiple exact contributions | UI multiple-payer percentage expense, remaining/overpayment case; core contribution checks |
| Splitting | Equal, exact amounts, percentages, shares and signed adjustments | Equal and 50/30/20 percentage UI flows; core paise, large-weight, adjustment and invalid-input cases; remaining controls reviewed |
| Balances | Friend/group totals after edits and payments | Durable UI ledger ends at ₹12.50 after an expense edit, ₹50 payment and deletion/restoration; randomized and cyclic core cases |
| Debt simplification | Optional group setting; pairwise debts remain when disabled | Core simplified/unsimplified net consistency, cycles and archive safety; setting implementation reviewed |
| Settlements | Record/edit/delete external payments with method, date and reference | UI partial repayment and new-friend advance; core correction/deletion and net-zero pair obligation cases; editor controls reviewed |
| Comments/history | Local author selection and expense comments; local activity and revision history | UI comment survives deletion/restoration; durable snapshots and member-author core checks |
| Offline access | Records stay in app-private Room storage | Six Room runtime cases: reopen, legacy row, concurrent writes, failed-write safety, large Unicode book and oversized legacy row recovery; merged manifest has no internet permission |
| Portability | CSV export; full JSON backup and validated confirmed replacement restore | Core CSV escaping/content and JSON round-trip; failed-restore runtime safety; Android picker launch/result code reviewed |
| Reports | Personal share by category, month, group and person; repayments excluded from spending | Populated render flow asserts ₹490 personal spending; report grouping/calculation implementation reviewed |
| Search/filters | Description, notes, names, categories, dates and amounts; category/person/date/amount filters | Seven filter tests plus v0.3 core/home-search tests; query matches cannot bypass active filters |
| UI/navigation | Friends, Groups, Activity and Account; full-screen saved drafts; light/dark themes | Five original complete UI cases, four new-feature cases and actual native-render review flow |

## Evidence locations

- Core XML/HTML reports: `core/build/test-results/test/`, `core/build/reports/tests/test/`.
- Android-side XML/HTML reports: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/`.
- Lint: `app/build/reports/lint-results-debug.html` and `.txt`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Rendered review PNGs: `app/build/screenshots/`. The flow captures populated groups, group details, icon/color choices, expense entry, multiple-payer remainder, expense details, friends, account, reports and dark theme.

The render test draws the active Activity or Dialog decor view into a bitmap using Robolectric native graphics. It checks for nonempty, nonuniform PNG output. These are simulation renders, not screenshots from a physical phone.

## Verification limits

Robolectric SDK 28 runs the Android-side tests. No physical phone or emulator was connected during this work. Android file-picker interaction, system keyboard behavior and device/API-specific insets still need a device check. JSON imports have a 20 MB safety limit. Lint currently has zero errors; remaining warnings are dependency-update and code-style suggestions, not a claim that phone-specific behavior has been tested.
