package app.kitna

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.data.LocalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class NewFeatureRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: KitnaViewModel
    private val group = Group("weekend", "Weekend", listOf("self", "alex"))

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForEditorToClose() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty() }
    }

    private fun seed(expenses: List<Expense> = emptyList(), withGroup: Boolean = true) {
        waitForText("Groups")
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java] }
        compose.waitUntil(10_000) { vm.book.value != null }
        val sample = Book(
            people = listOf(Person("self", "You"), Person("alex", "Alex")),
            groups = if (withGroup) listOf(group) else emptyList(),
            expenses = expenses
        )
        runBlocking { vm.store.replace(sample) }
        compose.waitForIdle()
        if (withGroup) waitForText("Weekend")
    }

    private fun addExpense() {
        compose.onNodeWithText("Weekend").performClick()
        compose.onNodeWithText("Add expense", useUnmergedTree = true).performClick()
        waitForText("Description")
    }

    private fun durableBook(): Book {
        val store = LocalStore(compose.activity)
        return try {
            runBlocking { store.load() }
            checkNotNull(store.book.value)
        } finally { store.close() }
    }

    @Test fun multiplePayersShowRemainingAndOverpaymentThenFillExactRemainder() {
        seed()
        addExpense()
        compose.onNodeWithText("Description").performTextInput("Shared bill")
        compose.onNodeWithText("Total amount").performScrollTo().performTextInput("1000")
        compose.onNodeWithContentDescription("Multiple people paid").performScrollTo().performClick()
        compose.onNodeWithText("You paid · ₹").performScrollTo().performTextInput("450")
        compose.onNodeWithText("Remaining: ₹550.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Alex paid · ₹").performScrollTo().performTextInput("600")
        compose.onNodeWithText("Overpaid: ₹50.00").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Save").performClick()
        waitForText("Payer contributions must equal the expense total.")
        compose.onNodeWithText("Description").assertTextContains("Shared bill")
        assertTrue(vm.book.value!!.expenses.isEmpty())

        compose.onNodeWithContentDescription("Use remaining for Alex").performScrollTo().performClick()
        compose.onNodeWithText("Alex paid · ₹").assertTextContains("550.00")
        compose.onNodeWithText("Remaining: ₹0.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        val saved = durableBook().expenses.single()
        assertEquals(100000L, saved.amount)
        assertEquals(mapOf("self" to 45000L, "alex" to 55000L), saved.payers)
        assertEquals(100000L, saved.shares.values.sum())
    }

    @Test fun keywordSuggestionCanBeManuallyOverriddenAndEnabledAgain() {
        seed()
        addExpense()
        compose.onNodeWithText("Description").performTextInput("Train ticket")
        compose.onNodeWithText("Travel").assertExists()
        compose.onNodeWithContentDescription("Auto-categorize expenses").assertIsOn()
        compose.onNodeWithText("Travel").performClick()
        compose.onNodeWithText("Food").performClick()
        compose.onNodeWithContentDescription("Auto-categorize expenses").assertIsOff()
        compose.onNodeWithText("Description").performScrollTo().performTextReplacement("Car rental")
        compose.onNodeWithText("Food").assertExists()
        compose.onNodeWithContentDescription("Auto-categorize expenses").performScrollTo().performClick()
        compose.onNodeWithText("Travel").assertExists()
        compose.onNodeWithText("Total amount").performScrollTo().performTextInput("100")
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        assertEquals("Travel", durableBook().expenses.single().category)
    }

    @Test fun editingExistingManualCategoryDoesNotRewriteItFromDescription() {
        val expense = Expense(
            id = "old-manual", groupId = group.id, description = "Old train ticket", amount = 10000,
            payers = mapOf("self" to 10000), shares = mapOf("self" to 5000, "alex" to 5000),
            category = "Food", splitMode = "Equal"
        )
        seed(listOf(expense))
        compose.onNodeWithText("Weekend").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Old train ticket"))
        compose.onNodeWithText("Old train ticket").performClick()
        waitForText("Expense details")
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Food").assertExists()
        compose.onNodeWithContentDescription("Auto-categorize expenses").assertIsOff()
        compose.onNodeWithText("Description").performTextReplacement("Taxi to the station")
        compose.onNodeWithText("Food").assertExists()
        compose.onNodeWithText("Save").performClick()
        waitForText("Expense details")
        compose.waitUntil(10_000) { vm.book.value?.expenses?.singleOrNull()?.description == "Taxi to the station" }
        val saved = durableBook().expenses.single()
        assertEquals("Food", saved.category)
        assertEquals("Taxi to the station", saved.description)
    }

    @Test fun chosenGroupIconAndColourPersistAndPickerCancelKeepsCommittedChoice() {
        seed(withGroup = false)
        compose.onNodeWithContentDescription("Create group").performClick()
        compose.onNodeWithText("Group name").performTextInput("Blue trip")
        compose.onNodeWithText("Alex").performScrollTo().performClick()
        compose.onNodeWithText("Choose icon and colour").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Group icon plane").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Group colour blue").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Plane · Blue").assertExists()

        compose.onNodeWithText("Choose icon and colour").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Group icon car").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Group colour red").performScrollTo().performClick()
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.onNodeWithText("Plane · Blue").assertExists()
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        val saved = durableBook().groups.single()
        assertEquals("Blue trip", saved.name)
        assertEquals("plane", saved.icon)
        assertEquals("blue", saved.color)
        assertEquals(setOf("self", "alex"), saved.members.toSet())
    }
}
