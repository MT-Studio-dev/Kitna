package app.kitna

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.Accounting
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
class MainActivityRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun tab(name: String) {
        compose.onAllNodesWithText(name).onLast().performClick()
        compose.waitForIdle()
    }
    private fun waitForEditorToClose() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty() }
    }
    private fun addFriend(name: String) {
        compose.onNodeWithContentDescription("Add friend").performClick()
        compose.onNodeWithText("Friend's name").performTextInput(name)
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        waitForText(name)
    }

    @Test fun launchAndNavigateWithoutAnyAccountFlow() {
        waitForText("Groups")
        listOf("Friends", "Activity", "Account", "Groups").forEach(::tab)
        compose.onNodeWithContentDescription("Create group").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Log in").assertDoesNotExist()
    }

    @Test fun friendAndDirectExpenseSaveThenEditThroughTheInterface() {
        waitForText("Groups")
        tab("Friends")
        addFriend("Alex")
        compose.onNodeWithText("Alex").performClick()
        compose.onNodeWithText("Add expense", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Description").performTextInput("Dinner")
        compose.onNodeWithText("Total amount").performTextInput("100.01")
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        waitForText("Dinner")
        compose.onNodeWithText("Dinner").performClick()
        waitForText("Expense details")
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Description").performTextReplacement("Dinner updated")
        compose.onNodeWithText("Total amount").performTextReplacement("125.00")
        compose.onNodeWithText("Save").performClick()
        waitForText("Expense details")
        waitForText("Dinner updated")

        // Reopen the durable database independently; visible form text alone is
        // not evidence that the edit and exact financial allocations persisted.
        val stored = LocalStore(compose.activity)
        try {
            runBlocking { stored.load() }
            val book = stored.book.value!!
            assertEquals(2, book.people.size)
            assertEquals("Direct", book.groups.single().kind)
            assertEquals(12500L, book.expenses.single().amount)
            assertEquals("Dinner updated", book.expenses.single().description)
            assertEquals(12500L, book.expenses.single().shares.values.sum())
            assertEquals(1, book.revisions.size)
        } finally { stored.close() }

        // Continue through the actual repayment/comment/delete/restore screens.
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Settle up").performClick()
        compose.onNodeWithText("Amount · ₹").performScrollTo().performTextReplacement("50.00")
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        compose.onNodeWithText("Dinner updated").performClick()
        waitForText("Expense details")
        compose.onNodeWithText("Write a comment").performScrollTo().performTextInput("Payment checked with Alex")
        compose.onNodeWithText("Add comment").performScrollTo().performClick()
        val vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java]
        compose.waitUntil(10_000) { vm.book.value?.comments?.any { it.text == "Payment checked with Alex" } == true }
        compose.onNodeWithText("Delete expense").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        waitForText("Restore expense")
        compose.onNodeWithText("Restore expense").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.book.value?.expenses?.singleOrNull()?.deleted == false }
        val finalStore = LocalStore(compose.activity)
        try {
            runBlocking { finalStore.load() }
            val book = finalStore.book.value!!
            val alex = book.people.first { it.name == "Alex" }.id
            assertFalse(book.expenses.single().deleted)
            assertEquals(5000L, book.settlements.single().amount)
            assertEquals(alex, book.settlements.single().from)
            assertEquals("self", book.settlements.single().to)
            assertEquals("Payment checked with Alex", book.comments.single().text)
            assertEquals("self", book.comments.single().authorId)
            assertTrue(book.revisions.size >= 3)
            assertEquals(1250L, Accounting.balances(book, book.groups.single().id).getValue("self"))
        } finally { finalStore.close() }
    }

    @Test fun failedSaveKeepsTheFriendDraftVisible() {
        waitForText("Groups")
        tab("Friends")
        compose.onNodeWithContentDescription("Add friend").performClick()
        compose.onNodeWithText("Friend's name").performTextInput("You")
        compose.onNodeWithText("Save").performClick()
        waitForText("A person with this name already exists.")
        compose.onNodeWithText("Friend's name").assertTextContains("You")
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test fun createGroupAndSaveMultiplePayersWithPercentageSplit() {
        waitForText("Groups")
        tab("Friends")
        addFriend("Alex")
        addFriend("Sam")
        tab("Groups")
        compose.onNodeWithContentDescription("Create group").performClick()
        compose.onNodeWithText("Group name").performTextInput("Road trip")
        compose.onNodeWithText("Alex").performScrollTo().performClick()
        compose.onNodeWithText("Sam").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        compose.onNodeWithText("Road trip").performClick()
        compose.onNodeWithText("Add expense", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Description").performTextInput("Shared dinner")
        compose.onNodeWithText("Total amount").performTextInput("150.00")
        compose.onNodeWithContentDescription("Multiple people paid").performClick()
        compose.onNodeWithText("You paid · ₹").performScrollTo().performTextInput("100.00")
        compose.onNodeWithText("Alex paid · ₹").performScrollTo().performTextInput("50.00")
        compose.onNodeWithText("Equal").performScrollTo().performClick()
        compose.onNodeWithText("Percentages").performClick()
        for ((index, percentage) in listOf("50", "30", "20").withIndex()) {
            compose.onAllNodesWithText("Percentage · %")[index].performScrollTo().performTextInput(percentage)
        }
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        waitForText("Shared dinner")
        val stored = LocalStore(compose.activity)
        try {
            runBlocking { stored.load() }
            val book = stored.book.value!!
            val vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java]
            assertEquals("The ViewModel must use the current Application", compose.activity.getDatabasePath("kitna.db"), vm.getApplication<android.app.Application>().getDatabasePath("kitna.db"))
            assertEquals(vm.book.value, book)
            assertEquals("Unexpected persisted people: ${book.people}", listOf("Alex", "Sam", "You"), book.people.map { it.name }.sorted())
            val alex = book.people.first { it.name == "Alex" }.id
            val sam = book.people.first { it.name == "Sam" }.id
            val expense = book.expenses.single()
            assertEquals(3, book.groups.single().members.size)
            assertEquals(mapOf("self" to 10000L, alex to 5000L), expense.payers)
            assertEquals(mapOf("self" to 7500L, alex to 4500L, sam to 3000L), expense.shares)
            assertEquals("Percentages", expense.splitMode)
        } finally { stored.close() }
    }

    @Test fun newFriendCanRecordPaymentBeforeAnyExpenseOrGroup() {
        waitForText("Groups")
        tab("Friends")
        addFriend("Alex")
        compose.onNodeWithText("Alex").performClick()
        compose.onNodeWithText("Settle up").performClick()
        compose.onNodeWithText("Amount · ₹").performScrollTo().performTextInput("10.00")
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        val store = LocalStore(compose.activity)
        try {
            runBlocking { store.load() }
            val book = store.book.value!!
            assertTrue(book.expenses.isEmpty())
            assertEquals("Direct", book.groups.single().kind)
            assertEquals(1000L, book.settlements.single().amount)
            assertEquals("self", book.settlements.single().from)
        } finally { store.close() }
    }
}
