package app.kitna

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.data.ReceiptStore
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

/** Populated, actual Android Activity/Dialog renders for root's visual review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TripPreviewRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            val view = dialog?.window?.decorView ?: compose.activity.window.decorView
            check(view.width > 0 && view.height > 0)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val output = File("build/screenshots/$name-sdk28.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(output.length() > 1000) { "Empty rendered preview" }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 10) for (x in 0 until bitmap.width step 10) colors += bitmap.getPixel(x, y)
        check(colors.size > 10) { "Blank or uniform preview" }
        bitmap.recycle()
    }

    private fun createReceipt(): ReceiptPhoto {
        val source = File(compose.activity.cacheDir, "demo-trip-receipt.jpg")
        val bitmap = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f; isFakeBoldText = true }
            val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 27f }
            drawText("KITNA DEMO RECEIPT", 35f, 75f, bold)
            drawText("Trip dinner · 17 Nov 2026", 35f, 130f, normal)
            drawLine(35f, 165f, 605f, 165f, normal)
            drawText("Dinner                 1100.00", 35f, 235f, normal)
            drawText("Tax                     150.00", 35f, 295f, normal)
            drawLine(35f, 335f, 605f, 335f, normal)
            drawText("TOTAL                 1250.00", 35f, 410f, bold)
            drawText("Paid by You", 35f, 490f, normal)
            drawText("Thank you!", 35f, 610f, normal)
        }
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        bitmap.recycle()
        return runBlocking { ReceiptStore(compose.activity).importPhoto(Uri.fromFile(source)) }.copy(name = "Dinner receipt")
    }

    @Test fun renderBudgetSummaryAndLocalReceiptPhotos() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Create group").fetchSemanticsNodes().isNotEmpty() }
        val vm = ViewModelProvider(compose.activity)[KitnaViewModel::class.java]
        val group = Group("goa", "Goa weekend", listOf("self", "alex", "sam"), kind = "Trip", icon = "plane", color = "blue", budget = 400000)
        val photo = createReceipt()
        val expenses = listOf(
            Expense("dinner", "goa", "Dinner by the beach", 125000, mapOf("self" to 125000), Accounting.split(125000, group.members.associateWith { 1L }), "Food", "2026-11-17", receipts = listOf(photo)),
            Expense("taxi", "goa", "Airport taxi", 45000, mapOf("alex" to 45000), Accounting.split(45000, group.members.associateWith { 1L }), "Travel", "2026-11-16"),
            Expense("groceries", "goa", "Trip groceries", 30000, mapOf("sam" to 30000), Accounting.split(30000, group.members.associateWith { 1L }), "Groceries", "2026-11-17")
        )
        runBlocking { vm.store.replace(Book(people = listOf(Person("self", "You"), Person("alex", "Alex Morgan"), Person("sam", "Sam Rao")), groups = listOf(group), expenses = expenses)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Goa weekend").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Goa weekend").performClick()
        compose.onNodeWithText("Remaining: ₹2,000.00").performScrollTo().assertIsDisplayed()
        screenshot("trip-budget")

        compose.onNodeWithText("Trip summary").performScrollTo().performClick()
        compose.onNodeWithText("Share summary").assertIsDisplayed()
        compose.onNodeWithText("₹2,000.00").assertExists()
        screenshot("trip-summary")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Dinner by the beach"))
        compose.onNodeWithText("Dinner by the beach").performClick()
        compose.onNodeWithContentDescription("View receipt 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("View receipt 1").performClick()
        compose.onNodeWithText("Close receipt").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Receipt photo").fetchSemanticsNodes().isNotEmpty() }
        screenshot("receipt-full")
        compose.onNodeWithText("Close receipt").performClick()
        screenshot("receipt-thumbnails")
        compose.onNodeWithText("Cancel").performClick()

        runBlocking { vm.saveGroup(group.copy(budget = 150000)).getOrThrow() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Over budget: ₹500.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Over budget: ₹500.00").performScrollTo().assertIsDisplayed()
        screenshot("trip-over-budget")
    }
}
