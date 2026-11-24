# Trip tools

Trip tools work in an account-free local book. In v0.5 a selected group can also be shared through Google sign-in and Supabase, so accepted members see its saved expenses, budget, receipts and history on their phones. Shared changes require synchronization; local tools remain usable offline.

## Set a trip budget

Open a group, choose **Edit group**, and enter an optional **Trip budget** in INR. The budget is the whole group's planned spending, not an extra expense and not each person's allowance.

The group view compares that budget with the sum of its active expenses. It shows money remaining or the amount over budget. Repayments do not count as spending. Editing, deleting or restoring an expense updates the budget figures; filters do not change the whole-trip totals. Clear the budget field to remove the budget without deleting expenses.

The Manga Panels design adds labeled risk stages: below 60% spent is green, 60% is amber, 80% is orange and 95% is red. At exactly 100% it says **Budget used**; above 100% it says **Over budget**. Colors are paired with readable amounts/status text. Larger expenses receive stronger tint and bold amounts, while smaller rows keep readable text on softer surfaces; expense size is separate from owing/owed or budget-risk meaning.

## Share the trip summary

Open a group and choose **Trip summary**. Review total spending, category totals, each person's contributions and allocated share, repayments already recorded, and the final repayments required to settle the group. If a budget is set, the summary includes its remaining or over-budget amount.

**Share summary** opens Android's share sheet with plain text. Choose a recipient/app yourself. Kitna does not send messages automatically or verify bank payments. The summary uses all the group's current active records and honours its debt-simplification setting.

Sharing a summary is separate from inviting another account into the live group. A summary is a text copy of this device's currently saved view. In a shared group, review pending/conflict status and synchronize before presenting it as the latest accepted group statement.

## Attach receipt photos

The expense editor can attach existing receipt images through Android's file picker. Kitna copies and compresses each selected image into private local storage so the expense does not depend on continued access to the original gallery URI. Receipt photos can be opened from the saved expense.

The app accepts up to ten images per expense. Input images are bounded at 20 MB; stored copies are JPEGs with a maximum edge of 1600 pixels and a maximum size of 2 MB each. This keeps local storage and portable backups manageable. The app does not scan receipt text or automatically itemize a bill. Duplicating an expense creates a fresh bill/date and deliberately leaves its receipt attachments empty.

In a shared group, photos use a private membership-scoped Supabase bucket and verified local copies. Initial sharing creates membership before uploading existing photos; pending/incomplete transfers remain retryable. A private bucket's name in the health response is not proof that two actual members have successfully uploaded and downloaded a photo; that real-account test remains outstanding.

Removing a photo from the current expense does not immediately delete its private file: older expense revisions and saved history may still reference it. The explicit **Erase all local data** action removes private photos too. Uninstalling or clearing Android app data removes the book and its private photos.

## Back up the photos too

Use the complete ZIP backup for a portable copy of the book and every referenced receipt, including photos referenced by expense history. The ZIP contains `book.json` plus private image copies under `receipts/`.

The importer validates the book, photo sizes and hashes, and archive paths before replacing current records. A failed import retains the current book. Sign out before restoring a backup. Exported/restored books strip cloud links and pending commands: a restore creates local data and cannot replay changes into a live shared group. Legacy JSON backups still work for older books without receipt photos; JSON alone cannot carry image files to another device. For a book with receipts, keep the complete ZIP rather than only `book.json`.

## Verification

The completed v0.5 validation in `verify-v05-complete.log` passed **117 tests: 50 core and 67 Android-side JVM tests**, with no failures/errors/skips, APK assembly and lint with **0 errors/26 warnings**. The backend's 16 local PostgreSQL tests are separate. The historical v0.4 run in `verify-v04-final.log` passed 84 tests (41 core + 43 app) and lint with 0 errors/14 warnings; those figures describe the earlier release.

Trip checks cover durable budgets, summary totals and the exact share-sheet payload, private receipts after deleting their source, and complete ZIP restoration of current and historical photos. Receipt-storage tests reject corrupt images, unsafe paths, missing photos and conflicting IDs, and verify rollback when the database commit fails. Five native renders—budget, over-budget, summary, receipt thumbnails and full receipt viewer—were visually reviewed.

These are Robolectric tests and renders. No physical phone/emulator or two-account shared receipt journey has been verified. The hosted cloud preflight confirms protocol/provider configuration and anonymous group-access denial, not a completed Google sign-in or cross-phone flow. Check Android file-picker providers, installed share targets, synchronization and device-specific behavior on real phones before travelling.
