package app.kitna

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.data.LocalStore
import app.kitna.data.ReceiptStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripFeaturesRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForEditorToClose() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty() }
    }

    private fun seed(budget: Long? = null): KitnaViewModel {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Create group").fetchSemanticsNodes().isNotEmpty() }
        val vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java]
        val group = Group("goa", "Goa trip", listOf("self", "alex"), kind = "Trip", icon = "plane", color = "blue", budget = budget)
        val bill = Expense("dinner", "goa", "Dinner", 125000,
            mapOf("self" to 125000), mapOf("self" to 62500, "alex" to 62500), category = "Food", date = "2026-11-17")
        runBlocking {
            vm.store.replace(Book(
                people = listOf(Person("self", "You"), Person("alex", "Alex")), groups = listOf(group),
                expenses = listOf(bill, bill.copy(id = "deleted", description = "Deleted bill", amount = 1000000,
                    payers = mapOf("self" to 1000000), shares = mapOf("self" to 500000, "alex" to 500000), deleted = true)),
                settlements = listOf(Settlement("repayment", "goa", "alex", "self", 50000, "UPI", "2026-11-17"))
            ))
        }
        waitForText("Goa trip")
        compose.onNodeWithText("Goa trip").performClick()
        return vm
    }

    @Test fun groupBudgetPersistsAndTracksExpensesWithoutCountingRepayments() {
        val vm = seed()
        compose.onNodeWithContentDescription("Group settings").performClick()
        compose.onNodeWithContentDescription("Edit group").performClick()
        compose.onNodeWithText("Trip budget · ₹ (optional)").performScrollTo().performTextInput("2000.00")
        compose.onNodeWithText("Save").performClick()
        waitForEditorToClose()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Remaining: ₹750.00").performScrollTo().assertIsDisplayed()

        val extra = Expense("taxi", "goa", "Taxi", 100000,
            mapOf("alex" to 100000), mapOf("self" to 50000, "alex" to 50000), category = "Travel", date = "2026-11-17")
        runBlocking { vm.saveExpense(extra).getOrThrow() }
        waitForText("Over budget: ₹250.00")
        compose.onNodeWithText("Over budget: ₹250.00").performScrollTo().assertIsDisplayed()
        runBlocking { vm.deleteExpense(extra).getOrThrow() }
        waitForText("Remaining: ₹750.00")

        val reopened = LocalStore(compose.activity)
        try {
            runBlocking { reopened.load() }
            assertEquals(200000L, reopened.book.value!!.groups.single().budget)
            assertEquals(125000L, TripReports.budget(reopened.book.value!!, "goa").spent)
        } finally { reopened.close() }
    }

    @Test fun tripSummaryUsesTheCurrentLedgerAndSharesPlainTextThroughTheChooser() {
        val vm = seed(budget = 200000)
        compose.onNodeWithText("Trip summary").performScrollTo().performClick()
        waitForText("Share summary")
        compose.onNodeWithText("Share summary").performClick()

        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertNotNull("Share should open Android's chooser", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("text/plain", target.type)
        val text = target.getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(TripReports.summary(vm.book.value!!, "goa"), text)
        assertTrue(text.contains("Goa trip"))
        assertTrue(text.contains("Total spending: ₹1250.00"))
        assertTrue(text.contains("Recorded repayments: ₹500.00"))
        assertTrue(text.contains("Alex pays You ₹125.00"))
        assertFalse(text.contains("Deleted bill"))
        assertFalse(target.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test fun selectedReceiptIsCopiedPrivatelyAndReopensAfterTheSourceIsRemoved() {
        seed(budget = 200000)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Dinner"))
        compose.onNodeWithText("Dinner").performClick()
        waitForText("Expense details")
        compose.onNodeWithText("Edit").performClick()

        val source = File(compose.activity.cacheDir, "dinner-source.jpg")
        val bitmap = Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
            drawText("KITNA DEMO RECEIPT", 35f, 70f, paint)
            drawText("Dinner                 1100.00", 35f, 180f, paint)
            drawText("Tax                     150.00", 35f, 240f, paint)
            drawLine(35f, 290f, 600f, 290f, paint)
            drawText("TOTAL                  1250.00", 35f, 350f, paint)
        }
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        bitmap.recycle()

        compose.onNodeWithText("Attach receipt photo").performScrollTo().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("The attachment button must launch the system picker", request)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(source)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("View receipt 1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("The test's original gallery file should still exist before removing it", source.delete())
        compose.onNodeWithText("Save").performClick()
        waitForText("Expense details")

        val reopened = LocalStore(compose.activity)
        try {
            runBlocking { reopened.load() }
            val photo = reopened.book.value!!.expenses.first { it.id == "dinner" }.receipts.single()
            val privateFile = ReceiptStore(compose.activity).fileFor(photo)
            assertTrue(privateFile.isFile)
            assertEquals(photo.sizeBytes, privateFile.length())
            assertNotEquals(source.absolutePath, privateFile.absolutePath)
            assertFalse(source.exists())
        } finally { reopened.close() }
        compose.onNodeWithContentDescription("View receipt 1").performScrollTo().performClick()
        compose.onNodeWithText("Close receipt").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Receipt photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Receipt photo").assertExists()
        compose.onNodeWithText("Close receipt").performClick()
        compose.onNodeWithText("Expense details").assertIsDisplayed()
    }
}
