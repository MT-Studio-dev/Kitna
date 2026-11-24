package app.kitna

import android.graphics.Bitmap
import android.graphics.Canvas
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Rendered review artifacts, not golden-image comparisons or device screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiPreviewRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val output = File("build/screenshots/$name-sdk28.png")
        checkNotNull(output.parentFile).mkdirs()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            // Compose dialogs live in a separate window. Capturing only the
            // Activity would misleadingly show the obscured editor underneath.
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            val decor = dialog?.window?.decorView ?: compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0) { "Window has not been laid out." }
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
        }
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        check(output.length() > 1000) { "Rendered PNG is empty." }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 10) for (x in 0 until bitmap.width step 10) colors += bitmap.getPixel(x, y)
        check(colors.size > 10) { "Rendered PNG is blank or uniform." }
    }

    @Test fun renderPopulatedGroupsExpenseFormAndDetailsForVisualReview() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Groups").fetchSemanticsNodes().isNotEmpty() }
        val group = Group("goa", "Goa weekend", listOf("self", "alex", "sam"), kind = "Trip", icon = "plane", color = "blue")
        val home = Group("home", "Home & groceries", listOf("self", "alex"), kind = "Home", icon = "home", color = "orange")
        val expenses = listOf(
            Expense("dinner", "goa", "Dinner at the corner café", 90000, mapOf("self" to 90000), Accounting.split(90000, group.members.associateWith { 1L }), "Food", "2026-11-17"),
            Expense("taxi", "goa", "Airport taxi", 45000, mapOf("alex" to 45000), Accounting.split(45000, group.members.associateWith { 1L }), "Travel", "2026-11-16"),
            Expense("groceries", "goa", "Weekend groceries", 12000, mapOf("sam" to 12000), Accounting.split(12000, group.members.associateWith { 1L }), "Groceries", "2026-11-15")
        )
        val sample = Book(people = listOf(Person("self", "You"), Person("alex", "Alex Morgan"), Person("sam", "Sam Rao")), groups = listOf(group, home), expenses = expenses)
        lateinit var vm: KitnaViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java] }
        runBlocking { vm.store.replace(sample) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Goa weekend").fetchSemanticsNodes().isNotEmpty() }
        screenshot("groups-populated")
        compose.onNodeWithText("Goa weekend").performClick()
        screenshot("group-details")
        compose.onNodeWithContentDescription("Group settings").performClick()
        screenshot("group-settings")
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithText("Choose icon and colour").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Group icon plane").assertIsSelected()
        compose.onNodeWithContentDescription("Group colour blue").assertIsSelected()
        screenshot("group-appearance-icons")
        compose.onNodeWithText("Choose a colour").performScrollTo()
        screenshot("group-appearance-colours")
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add expense", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Description").performTextInput("Taxi to the airport")
        compose.onNodeWithText("Travel").assertExists()
        compose.onNodeWithText("Total amount").performTextInput("1000.00")
        screenshot("expense-form")
        compose.onNodeWithContentDescription("Multiple people paid").performScrollTo().performClick()
        compose.onNodeWithText("You paid · ₹").performScrollTo().performTextInput("450.00")
        compose.onNodeWithText("Remaining: ₹550.00").assertExists()
        screenshot("multiple-payer-remaining")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Dinner at the corner café"))
        compose.onNodeWithText("Dinner at the corner café").performClick()
        screenshot("expense-details")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("Friends").onLast().performClick()
        screenshot("friends-populated")
        compose.onAllNodesWithText("Account").onLast().performClick()
        screenshot("account")
        compose.onNodeWithText("Spending insights").performClick()
        compose.onAllNodesWithText("₹490.00").onFirst().assertIsDisplayed()
        screenshot("reports")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Settings"))
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Theme: System").performScrollTo()
        compose.onNodeWithText("Theme: System").performClick()
        compose.onNodeWithText("Dark").performClick()
        screenshot("settings-dark")
        compose.onNodeWithContentDescription("Back").performClick()
        screenshot("account-dark")
        compose.onAllNodesWithText("Groups").onLast().performClick()
        screenshot("groups-dark")
        compose.onNodeWithText("Goa weekend").performClick()
        screenshot("group-details-dark")
        compose.onNodeWithText("Balances").performClick()
        screenshot("balances-dark")
    }
}
