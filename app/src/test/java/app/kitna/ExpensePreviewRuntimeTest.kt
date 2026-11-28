package app.kitna

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import app.kitna.core.*
import app.kitna.ui.ExpenseEditor
import app.kitna.ui.MangaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Actual Android native renders; these are simulated windows, not device captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExpensePreviewRuntimeTest {
    @get:Rule(order = 0) val cleanBook = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val file = File("build/screenshots/$name-sdk28.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 1000)
        bitmap.recycle()
    }

    @Test fun renderSimpleGroupEditorInLightAndDark() {
        lateinit var vm: KitnaViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory(compose.activity.application as Application))[KitnaViewModel::class.java] }
        compose.waitUntil(10_000) { vm.book.value != null }
        val group = Group("vrindavan", "Vrindavan", listOf("self", "rahul", "maya"))
        val book = Book(people = listOf(Person("self", "You"), Person("rahul", "Rahul"), Person("maya", "Maya")), groups = listOf(group))
        runBlocking { vm.store.replace(book) }
        val dark = mutableStateOf(false)
        compose.setContent { MangaTheme(dark.value) { ExpenseEditor(book, null, group.id, vm) {} } }
        compose.onNodeWithText("Description").performTextInput("Dinner with friends")
        compose.onNodeWithText("Total amount").performTextInput("1250.00")
        compose.onNodeWithText("All of Vrindavan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose payer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose split method").assertIsDisplayed()
        screenshot("v07-expense-light")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText("Description").assertTextContains("Dinner with friends")
        screenshot("v07-expense-dark")
    }

    @Test @Config(sdk = [28], qualifiers = "w320dp-h640dp-xhdpi")
    fun largeTextOnSmallScreenKeepsSaveSelectionsAndToolbarReachable() {
        lateinit var vm: KitnaViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory(compose.activity.application as Application))[KitnaViewModel::class.java] }
        compose.waitUntil(10_000) { vm.book.value != null }
        val group = Group("small", "Weekend", listOf("self", "alex", "sam"))
        val book = Book(people = listOf(Person("self", "You"), Person("alex", "Alex"), Person("sam", "Sam")), groups = listOf(group))
        runBlocking { vm.store.replace(book) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MangaTheme(true) { ExpenseEditor(book, null, group.id, vm) {} }
            }
        }
        compose.onNodeWithContentDescription("Save expense").assertIsDisplayed()
        val saveBounds = compose.onNodeWithContentDescription("Save expense").getUnclippedBoundsInRoot()
        assertTrue(saveBounds.bottom - saveBounds.top >= 48.dp && saveBounds.right - saveBounds.left >= 48.dp)
        compose.onNodeWithText("All of Weekend").assertIsDisplayed()
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Lunch")
        compose.onNodeWithText("Total amount").performScrollTo().performTextInput("12.01")
        compose.onNodeWithContentDescription("Choose payer").performScrollTo().performClick()
        compose.onNodeWithText("Who paid?").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Choose split method").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Include Sam").performScrollTo().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Receipt photos, 0 attached").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notes").assertIsDisplayed()
        compose.onNode(hasContentDescription("Expense date", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Save expense").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.book.value?.expenses?.isNotEmpty() == true }
        assertTrue(vm.book.value!!.expenses.single().shares.keys == setOf("self", "alex"))
    }
}
