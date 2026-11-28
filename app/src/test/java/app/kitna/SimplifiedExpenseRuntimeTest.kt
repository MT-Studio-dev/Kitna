package app.kitna

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.ui.ExpenseEditor
import app.kitna.ui.expenseAllocations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SimplifiedExpenseRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = Group("simple", "Vrindavan", listOf("self", "alex", "sam"))
    private val sample = Book(people = listOf(Person("self", "You"), Person("alex", "Alex"), Person("sam", "Sam")), groups = listOf(group))

    private fun model(): KitnaViewModel {
        lateinit var model: KitnaViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory(compose.activity.application as Application))[KitnaViewModel::class.java] }
        compose.waitUntil(10_000) { model.book.value != null }
        runBlocking { model.store.replace(sample) }
        return model
    }

    @Test fun groupNeedsOnlyDescriptionAmountAndSaveAndRepeatedTapCreatesOneBill() {
        val vm = model()
        var closes = 0
        compose.setContent { MaterialTheme { ExpenseEditor(sample, null, group.id, vm) { closes++ } } }
        compose.onNodeWithText("All of Vrindavan").assertIsDisplayed()
        compose.onNodeWithText("Description").performTextInput("Dinner")
        compose.onNodeWithText("Total amount").performTextInput("100.01")
        compose.onNodeWithText("Notes (optional)").assertDoesNotExist()
        compose.onNodeWithText("Multiple people paid").assertDoesNotExist()
        compose.onNodeWithText("Save").performTouchInput { doubleClick() }
        compose.waitUntil(10_000) { vm.book.value?.expenses?.size == 1 }
        compose.runOnIdle { assertEquals(1, closes) }
        val saved = vm.book.value!!.expenses.single()
        assertEquals(mapOf("self" to 10001L), saved.payers)
        assertEquals(Accounting.split(10001, group.members.associateWith { 1L }), saved.shares)
        assertTrue(vm.book.value!!.revisions.isEmpty())
    }

    @Test fun mainEntrySelectsMultipleFriendsAndSavesWithoutVisibleGroup() {
        val vm = model()
        var closed = false
        compose.setContent { MaterialTheme { ExpenseEditor(sample, null, null, vm) { closed = true } } }
        compose.onNodeWithText("Choose group").assertIsDisplayed()
        compose.onNodeWithText("Choose friends").performClick()
        compose.onNodeWithText("Alex").performClick()
        compose.onNodeWithText("Sam").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Description").performTextInput("Cab")
        compose.onNodeWithText("Total amount").performTextInput("10.01")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { closed }
        val saved = vm.book.value!!.expenses.single()
        val ledger = vm.book.value!!.groups.single { it.id == saved.groupId }
        assertEquals("Non-group", ledger.kind)
        assertEquals(setOf("self", "alex", "sam"), saved.shares.keys)
        assertEquals(1001L, saved.shares.values.sum())
        assertEquals(group, vm.book.value!!.groups.single { it.id == group.id })
    }

    @Test fun draftAndExcludedParticipantSurviveSheetsAndSavedStateRecreation() {
        val vm = model()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ExpenseEditor(sample, null, group.id, vm) {} } }
        compose.onNodeWithText("Description").performTextInput("Train tickets")
        compose.onNodeWithText("Total amount").performTextInput("15.01")
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.onNodeWithText("Notes (optional)").performTextInput("Keep this draft")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Choose split method").performClick()
        compose.onNodeWithContentDescription("Include Sam").performClick()
        compose.onNodeWithContentDescription("Include Sam").assertIsOff()
        compose.onNodeWithText("Done").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Description").assertTextContains("Train tickets")
        compose.onNodeWithText("Total amount").assertTextContains("15.01")
        compose.onNodeWithText("Keep this draft").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.book.value?.expenses?.isNotEmpty() == true }
        assertEquals(setOf("self", "alex"), vm.book.value!!.expenses.single().shares.keys)
        assertEquals("Keep this draft", vm.book.value!!.expenses.single().notes)
    }

    @Test fun addingLocalFriendInsideEditorKeepsExpenseDraft() {
        val vm = model()
        compose.setContent { MaterialTheme { ExpenseEditor(sample, null, null, vm) {} } }
        compose.onNodeWithText("Description").performTextInput("Lunch")
        compose.onNodeWithText("Total amount").performTextInput("200")
        compose.onNodeWithText("Choose friends").performClick()
        compose.onNodeWithText("Add a new friend").performClick()
        compose.onNodeWithText("Friend's name").performTextInput("Rahul")
        compose.onNodeWithText("Add friend").performClick()
        compose.waitUntil(10_000) { vm.book.value?.people?.any { it.name == "Rahul" } == true }
        compose.onNodeWithText("Description").assertTextContains("Lunch")
        compose.onNodeWithText("Total amount").assertTextContains("200")
        compose.onNodeWithText("Rahul").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.book.value?.expenses?.isNotEmpty() == true }
        val rahul = vm.book.value!!.people.single { it.name == "Rahul" }
        assertTrue(rahul.email.isEmpty())
        assertEquals(setOf("self", rahul.id), vm.book.value!!.expenses.single().shares.keys)
    }

    @Test fun backWarnsOnlyAfterMeaningfulChangesAndKeepsDraftWhenCancelled() {
        val vm = model()
        var closes = 0
        compose.setContent { MaterialTheme { ExpenseEditor(sample, null, group.id, vm) { closes++ } } }
        compose.onNodeWithText("Description").performTextInput("Lunch")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Description").assertTextContains("Lunch")
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
        assertTrue(vm.book.value!!.expenses.isEmpty())
    }
    @Test fun choosingTheCurrentSplitMethodKeepsEnteredAmounts() {
        val vm = model()
        compose.setContent { MaterialTheme { ExpenseEditor(sample, null, group.id, vm) {} } }
        compose.onNodeWithText("Description").performTextInput("Breakfast")
        compose.onNodeWithText("Total amount").performTextInput("10.01")
        compose.onNodeWithContentDescription("Choose split method").performClick()
        compose.onNodeWithText("Equal").performClick()
        compose.onNodeWithText("Exact amounts").performClick()
        listOf("3.01", "3.50", "3.50").forEachIndexed { index, value ->
            compose.onAllNodesWithText("Exact amount · ₹")[index].performScrollTo().performTextInput(value)
        }
        compose.onNodeWithText("Exact amounts").performScrollTo().performClick()
        compose.onAllNodesWithText("Exact amounts").onLast().performClick()
        compose.onAllNodesWithText("Exact amount · ₹")[0].assertTextContains("3.01")
        compose.onAllNodesWithText("Exact amount · ₹")[1].assertTextContains("3.50")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.book.value!!.expenses.isNotEmpty() }
        assertEquals(mapOf("self" to 301L, "alex" to 350L, "sam" to 350L), vm.book.value!!.expenses.single().shares)
    }
}
