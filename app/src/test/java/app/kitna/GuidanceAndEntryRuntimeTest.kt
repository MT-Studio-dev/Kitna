package app.kitna

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.ui.ExpenseEditor
import app.kitna.ui.HelpScreen
import app.kitna.ui.OnboardingScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class GuidanceAndEntryRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun resetEntryPreferences() {
        compose.activity.getSharedPreferences("expense_entry", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val group = Group("trip-entry", "Goa weekend", listOf("self", "alex"))
    private fun book(expense: Expense? = null) = Book(
        people = listOf(Person("self", "You"), Person("alex", "Alex")),
        groups = listOf(group), expenses = listOfNotNull(expense)
    )

    private fun model(book: Book): KitnaViewModel {
        lateinit var model: KitnaViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity,
                ViewModelProvider.AndroidViewModelFactory(compose.activity.application as Application))[KitnaViewModel::class.java]
        }
        compose.waitUntil(10_000) { model.book.value != null }
        runBlocking { model.store.replace(book) }
        return model
    }

    @Test fun setupExplainsSharingAndOffersBothGoogleAndLocalStart() {
        var chosenName: String? = null
        var signIns = 0
        compose.setContent { MaterialTheme { OnboardingScreen(onComplete = { chosenName = it }, onSignIn = { signIns++ }) } }
        compose.onNodeWithText("Your name").assertIsDisplayed()
        compose.onNodeWithText("Start with local records").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertIsDisplayed()
        compose.onNodeWithText("Step 1 of 3").assertDoesNotExist()
        compose.onNodeWithText("Learn how Kitna works").performScrollTo().performClick()
        compose.onNodeWithText("Invite your friends").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hide introduction").performScrollTo().performClick()
        compose.onNodeWithText("Continue with Google").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, signIns); assertNull(chosenName) }
        compose.onNodeWithText("Your name").performScrollTo().performTextInput("Maya")
        compose.onNodeWithText("Start with local records").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Maya", chosenName) }
    }

    @Test fun duplicateCreatesANewDatedExpenseAndLeavesSourceAndReceiptsUntouched() {
        val photo = ReceiptPhoto("original-photo", "Original receipt", sizeBytes = 120, sha256 = "ab".repeat(32))
        val original = Expense("original-bill", group.id, "Dinner", 12345,
            mapOf("alex" to 12345), mapOf("self" to 6172, "alex" to 6173),
            category = "Food", date = "2026-10-05", notes = "Review before copying", receipts = listOf(photo))
        val book = book(original)
        val vm = model(book)
        var finished = false
        compose.setContent { MaterialTheme { ExpenseEditor(book, original, group.id, vm, duplicate = true) { finished = true } } }
        compose.onNodeWithText("Duplicate expense").assertIsDisplayed()
        compose.onNodeWithContentDescription("Receipt photos, 0 attached").performClick()
        compose.onNodeWithText("0 of 10 photos · stored on this device").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { finished }
        val saved = vm.book.value!!
        assertEquals(2, saved.expenses.size)
        assertEquals(original, saved.expenses.first { it.id == original.id })
        val duplicate = saved.expenses.first { it.id != original.id }
        assertEquals(LocalDate.now().toString(), duplicate.date)
        assertEquals(original.description, duplicate.description)
        assertEquals(original.amount, duplicate.amount)
        assertEquals(original.payers, duplicate.payers)
        assertEquals(original.shares, duplicate.shares)
        assertEquals(original.notes, duplicate.notes)
        assertTrue(duplicate.receipts.isEmpty())
        assertTrue(saved.revisions.isEmpty())
    }

    @Test fun invalidExpenseFocusesAndExplainsTheFirstInvalidFieldWithoutDiscardingDraft() {
        val book = book()
        val vm = model(book)
        compose.setContent { MaterialTheme { ExpenseEditor(book, null, group.id, vm) {} } }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Enter a description up to 500 characters.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Description").assertIsFocused().performTextInput("Train ticket")
        compose.onNodeWithText("Save").performClick()
        val expected = "Enter a valid amount with no more than two decimal places."
        compose.waitUntil(10_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Total amount").assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, expected))
        compose.onNodeWithText("Description").assertTextContains("Train ticket")
        assertTrue(vm.book.value!!.expenses.isEmpty())
    }

    @Test fun helpSharesOnlyUserReviewedBasicDiagnosticsThroughTheChooser() {
        compose.setContent { MaterialTheme { HelpScreen(onBack = {}, syncStatus = "Synced") } }
        compose.onNodeWithText("Share support details").performScrollTo().performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val message = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, message.action)
        assertEquals("text/plain", message.type)
        val text = message.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains("Android API: 28"))
        assertTrue(text.contains("Record mode: Shared groups enabled"))
        assertFalse(message.hasExtra(Intent.EXTRA_STREAM))
        assertFalse(message.hasExtra(Intent.EXTRA_EMAIL))
        assertFalse(text.contains("sb_publishable_"))
        assertFalse(text.contains("access_token"))
    }
}
