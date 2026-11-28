package app.kitna

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.ui.MangaTheme
import app.kitna.ui.SettlementEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SimplifiedSettlementRuntimeTest {
    @get:Rule(order = 0) val clean = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = Group("trip", "Vrindavan", listOf("self", "alex", "sam"), simplifyDebts = false)
    private val people = listOf(Person("self", "You"), Person("alex", "Alex"), Person("sam", "Sam"))
    private val sample = Book(people = people, groups = listOf(group), expenses = listOf(
        Expense("unrelated", group.id, "Alex and Sam", 10000, mapOf("alex" to 10000), mapOf("sam" to 10000)),
        Expense("mine", group.id, "Dinner", 6000, mapOf("self" to 6000), mapOf("alex" to 6000))
    ))
    private fun model(book: Book = sample): KitnaViewModel {
        lateinit var vm: KitnaViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory(compose.activity.application as Application))[KitnaViewModel::class.java] }
        compose.waitUntil(10_000) { vm.book.value != null }
        runBlocking { vm.store.replace(book) }
        return vm
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread { val view = compose.activity.window.decorView; bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap)) }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun defaultDebtInvolvingYouNeedsOnlySaveAndRepeatedTapRecordsOnce() {
        val vm = model()
        var closes = 0
        compose.setContent { MangaTheme(false) { SettlementEditor(sample, null, group.id, null, null, vm) { closes++ } } }
        compose.onNodeWithText("Amount · ₹").assertTextContains("60.00")
        compose.onNodeWithText("Payment method").assertDoesNotExist()
        compose.onNodeWithText("Reference or note (optional)").assertDoesNotExist()
        compose.onNodeWithText("Save").performTouchInput { doubleClick() }
        compose.waitUntil(10_000) { vm.book.value!!.settlements.size == 1 }
        compose.runOnIdle { assertEquals(1, closes) }
        val saved = vm.book.value!!.settlements.single()
        assertEquals("alex", saved.from); assertEquals("self", saved.to); assertEquals(6000L, saved.amount)
        assertEquals("Cash", saved.method); assertEquals(LocalDate.now().toString(), saved.date)
    }

    @Test fun friendWithNoGroupCanRecordPaymentAndInvalidDraftCreatesNoLedger() {
        val book = Book(people = people)
        val vm = model(book)
        var closes = 0
        compose.setContent { MangaTheme(false) { SettlementEditor(book, null, "friend:alex", "self", "alex", vm) { closes++ } } }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Amount · ₹").assertIsFocused()
        assertTrue(vm.book.value!!.groups.isEmpty())
        compose.onNodeWithText("Amount · ₹").performTextInput("25.01")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { closes == 1 }
        val saved = vm.book.value!!
        assertEquals("Direct", saved.groups.single().kind)
        assertEquals(setOf("self", "alex"), saved.groups.single().members.toSet())
        assertEquals(2501L, saved.settlements.single().amount)
    }

    @Test fun optionalDetailsAndPeopleKeepAmountAcrossRecreation() {
        val vm = model()
        val restoration = StateRestorationTester(compose)
        var closed = false
        restoration.setContent { MangaTheme(true) { SettlementEditor(sample, null, group.id, null, null, vm) { closed = true } } }
        compose.onNodeWithText("Amount · ₹").performTextReplacement("12.34")
        compose.onNodeWithContentDescription("More repayment options").performClick()
        compose.onNodeWithText("UPI").performClick()
        compose.onNodeWithText("Reference or note (optional)").performTextInput("Dinner refund")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Choose who received payment").performClick()
        compose.onNodeWithText("Sam").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Amount · ₹").assertTextContains("12.34")
        compose.onNodeWithContentDescription("More repayment options").performClick()
        compose.onNodeWithText("UPI").assertIsSelected()
        compose.onNodeWithText("Reference or note (optional)").assertTextContains("Dinner refund")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { closed }
        val saved = vm.book.value!!.settlements.single()
        assertEquals("sam", saved.to); assertEquals("UPI", saved.method); assertEquals("Dinner refund", saved.reference)
        assertEquals(1234L, saved.amount)
    }

    @Test fun staleSharedEditCannotOverwriteLatestRepaymentAndKeepsDraft() {
        val original = Settlement("pay", group.id, "alex", "self", 1000)
        val selfSlot = "33333333-3333-4333-8333-333333333333"
        val shared = SharedGroupLink(group.id, "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", selfSlot, mapOf("self" to selfSlot, "alex" to "44444444-4444-4444-8444-444444444444", "sam" to "55555555-5555-4555-8555-555555555555"))
        val initial = sample.copy(settlements = listOf(original), sharedGroups = listOf(shared))
        val vm = model(initial)
        compose.setContent { MangaTheme(false) { SettlementEditor(initial, original, group.id, null, null, vm) {} } }
        compose.onNodeWithText("Amount · ₹").performTextReplacement("12.00")
        val newer = original.copy(amount = 1500)
        runBlocking { vm.store.replace(initial.copy(settlements = listOf(newer))) }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("This repayment changed on another phone. Reopen the latest repayment before saving.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Amount · ₹").assertTextContains("12.00")
        assertEquals(newer, vm.book.value!!.settlements.single())
        assertTrue(vm.book.value!!.pendingChanges.isEmpty())
    }

    @Test fun editedRepaymentCanBeDeletedFromMoreOptions() {
        val payment = Settlement("pay", group.id, "alex", "self", 1000)
        val book = sample.copy(settlements = listOf(payment))
        val vm = model(book)
        var closed = false
        compose.setContent { MangaTheme(false) { SettlementEditor(book, payment, group.id, null, null, vm) { closed = true } } }
        compose.onNodeWithContentDescription("More repayment options").performClick()
        compose.onNodeWithText("Delete repayment").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) { closed }
        assertTrue(vm.book.value!!.settlements.single().deleted)
    }

    @Test fun renderCompactSettleUpInBothThemes() {
        val vm = model()
        val dark = mutableStateOf(false)
        compose.setContent { MangaTheme(dark.value) { SettlementEditor(sample, null, group.id, null, null, vm) {} } }
        compose.onNodeWithContentDescription("Choose who paid").assertIsDisplayed()
        compose.onNodeWithText("Amount · ₹").assertIsDisplayed()
        screenshot("v07-settle-light")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText("Save").assertIsDisplayed()
        screenshot("v07-settle-dark")
    }
}
