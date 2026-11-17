package app.kitna

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeSearchRuntimeTest {
    @get:Rule(order = 0) val clean = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun seed(deleted: Boolean = false) {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Create group").fetchSemanticsNodes().isNotEmpty() }
        val vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java]
        runBlocking {
            vm.store.replace(Book(
                people = listOf(Person("self", "You"), Person("alex", "Alex Rao")),
                groups = listOf(Group("weekend", "Weekend", listOf("self", "alex"))),
                expenses = listOf(Expense("taxi", "weekend", "Airport taxi", 100050,
                    mapOf("self" to 100050), mapOf("self" to 50025, "alex" to 50025),
                    "Travel", "2026-11-17", deleted = deleted, notes = "Night pickup")),
                settlements = listOf(Settlement("payment", "weekend", "alex", "self", 45000,
                    method = "UPI", reference = "Trip payment"))
            ))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Weekend").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search").performClick()
    }

    @Test fun homeSearchFindsExpenseDescriptionCategoryPeopleAndAmounts() {
        seed()
        for (query in listOf("  TAXI  ", "Travel", "Alex pickup", "₹1,000.50")) {
            compose.onNodeWithText("Search all records").performTextReplacement(query)
            compose.onNodeWithText("Airport taxi").assertIsDisplayed()
        }
        compose.onNodeWithText("Airport taxi").performClick()
        compose.onNodeWithText("Expense details").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Search all records").assertTextContains("₹1,000.50")
    }

    @Test fun noMatchesAndClearSearchDoNotMasqueradeAsAnEmptyBook() {
        seed()
        compose.onNodeWithText("Search all records").performTextInput("not-a-real-record")
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Your groups").assertIsDisplayed()
        compose.onNodeWithText("Weekend").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithContentDescription("Create group").assertIsDisplayed()
    }

    @Test fun paymentsAreSearchableAndDeletedExpensesAreExcluded() {
        seed(deleted = true)
        compose.onNodeWithText("Search all records").performTextInput("Airport taxi")
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("Search all records").performTextReplacement("UPI 450")
        compose.onNodeWithText("Alex Rao paid You").assertIsDisplayed()
        compose.onNodeWithText("Alex Rao paid You").performClick()
        compose.onNodeWithText("Edit repayment").assertIsDisplayed()
        compose.onNodeWithText("Amount · ₹").assertTextContains("450.00")
    }
}
