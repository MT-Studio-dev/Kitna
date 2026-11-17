# Local feature and verification matrix

Scope: the modules requested in this conversation, adapted to one phone with no authentication or cloud sync. All records and participants belong to one local book. Separate logins, email invitations, initiating bank transfers, bank/card imports, and paid subscriptions are outside this single-device tracker. Repayments made through a bank can still be recorded manually.

This matrix separates reviewed implementation, executed tests, and device-specific checks. The final verification command passed `:core:test`, `:app:testDebugUnitTest`, and `:app:lintDebug`; XML reports contain 20 core tests and 19 app tests, with zero failures, errors, or skips.

| Requested module | Required local behavior | Implementation evidence | Verification status |
|---|---|---|---|
| Accounts | Editable local profile; no login or registration | `KitnaViewModel.kt`, `ui/KitnaApp.kt`, `ui/Editors.kt` | Launch and all four tabs passed; local-profile save path reviewed; account and dark-theme renders generated |
| Friends | Add, rename, remove eligible people; individual expense/balance view | `core/BookActions.kt`, `ui/KitnaApp.kt`, `ui/Editors.kt` | UI creation and direct-ledger flow passed; rename, duplicate-name, empty-direct cleanup and stable-ID retry core cases passed |
| Groups | Create, edit name/members, archive/restore, remove eligible groups | `core/BookActions.kt`, `ui/KitnaApp.kt`, `ui/Editors.kt` | UI creation/member selection passed; archive and history-breaking membership protections passed in core tests; settings implementation reviewed |
| Expenses | Create, edit, delete/restore; dates/categories/notes and history | `core/Models.kt`, `core/BookActions.kt`, `ui/Editors.kt` | Full UI create/edit/delete/restore and durable-history assertions passed; date/category/notes implementation reviewed |
| Payers | Single payer and multiple contributions; exact total validation | `core/Accounting.kt`, `core/BookValidation.kt`, `ui/Editors.kt` | UI contributions ₹100 + ₹50 = ₹150 passed with durable allocations; invalid contributions rejected in core tests |
| Splitting | Equal, exact amounts, percentages, shares, and adjustment input | `core/Accounting.kt`, `ui/Editors.kt` | Equal and 50/30/20 percentage UI paths passed; paise rounding, large weights, adjustments and invalid inputs covered in core tests; remaining mode controls reviewed |
| Balances | Correct friend/group balances after all edits and payments | `core/Accounting.kt`, `core/BookActions.kt`, `ui/KitnaApp.kt` | UI ledger ends at expected ₹12.50 after expense edit, ₹50 payment, deletion/restoration; randomized and cyclic core cases passed |
| Debt simplification | Group setting; repayment suggestions preserve balances | `core/Accounting.kt`, `core/Models.kt`, `ui/Editors.kt` | Simplified/unsimplified net consistency, cycles and archive safety core tests passed; settings toggle and balance presentation reviewed |
| Settlements | Record/edit/delete full or partial external payments with method/date/reference | `core/Models.kt`, `core/BookActions.kt`, `ui/Editors.kt` | UI partial repayment and new-friend advance passed; edit/delete corrections and net-zero pair obligation core cases passed; editor controls reviewed |
| Collaboration | Local comments with author selection and local change history | `core/Models.kt`, `core/BookActions.kt`, `ui/Editors.kt` | UI self-comment survives expense deletion/restoration; durable revision assertions and member-author core validation passed; no remote collaboration implied |
| Offline access | All features run locally; records survive process restart | `data/LocalStore.kt`, `AndroidManifest.xml` | Six Room runtime cases passed: reopen, legacy row, concurrent writes, failed-write safety, oversized Unicode book, oversized legacy single-row recovery |
| Data portability | CSV export; full JSON backup and validated confirmed replacement restore | `core/BookExport.kt`, `core/BookValidation.kt`, `data/LocalStore.kt`, `ui/KitnaApp.kt` | CSV escaping/content and JSON round-trip/invalid-restore tests passed; SAF launch/result code reviewed; actual Android picker remains a device check |
| Reports | Spending by category/month/group/person without counting repayments as expenses | `ui/Screens.kt`, `core/Models.kt` | Populated UI asserts ₹490.00 personal spending and generates report PNG; grouping/calculation implementation reviewed |
| Search and filters | Description/participant search, group/category/date filtering | `ui/KitnaApp.kt`, `ui/Screens.kt` | Seven filter tests passed, including inclusive limits, invalid ranges and participant-search filter-bypass regression |
| Interface and navigation | Splitwise-style screen hierarchy, readable rows, stable forms, clear errors | `MainActivity.kt`, `ui/KitnaApp.kt`, `ui/Editors.kt` | Five full UI cases and one native-render flow passed; eight nonblank PNGs generated; physical keyboard/inset behavior remains a device check |

## Test evidence locations

- Pure Kotlin tests: `core/src/test/kotlin/app/kitna/core/AccountingTest.kt` and `BookActionsTest.kt`; reports under `core/build/reports/tests/test/`.
- Android local runtime tests: `app/src/test/`; reports under `app/build/reports/tests/testDebugUnitTest/`.
- Android lint: `app/build/reports/lint-results-debug.html`, when successfully generated.
- APK: `app/build/outputs/apk/debug/app-debug.apk`; verify with the installed SDK `apksigner`.

Evidence comes from the current source revision's test reports. An earlier revision's passing tests, a build alone, or an implementation filename do not prove a complete feature works. Test coverage is described above rather than claiming every input/device combination was exercised.

## Rendered review artifacts

`app/build/screenshots/` contains `groups-populated-sdk28.png`, `group-details-sdk28.png`, `expense-form-sdk28.png`, `expense-details-sdk28.png`, `friends-populated-sdk28.png`, `account-sdk28.png`, `reports-sdk28.png`, and `account-dark-sdk28.png`. They are drawn from the actual Activity decor view using Robolectric native graphics and a Bitmap/Canvas. Each render asserts a nonempty PNG and nonuniform sampled pixels. They are simulation renders, not photographs or screenshots from a physical phone.

Android lint completed with zero errors and 13 warnings: eight dependency-update suggestions, two Modifier-parameter style warnings, two mutable-collection-state warnings, and one SharedPreferences KTX suggestion. The editor collection updates replace copied lists instead of mutating an existing state list in place.

## Runtime environment audit

The initial audit found no connected device (`adb devices -l` was empty), no Android emulator package/system image, and no AVD. SDK platforms/build-tools 34 and 35 and platform-tools were installed. `Win32_Processor` reported firmware virtualization and second-level address translation unavailable. Robolectric SDK 28 runtime and native-render verification completed as the available local alternative. Real-device validation remains distinct from those JVM tests. Android SAF file-picker behavior, the system keyboard, and API/device-specific edge-to-edge rendering have not been exercised on a phone.
