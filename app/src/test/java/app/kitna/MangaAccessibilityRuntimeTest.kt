package app.kitna

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.kitna.core.*
import app.kitna.ui.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Isolates real manga components at a narrow width with 160% Android text scaling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MangaAccessibilityRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val destination = File("build/screenshots/$name-sdk28.png")
        destination.parentFile!!.mkdirs()
        destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(destination.length() > 1000)
        bitmap.recycle()
    }

    @Test fun renderOnboardingAndHelpInTheSelectedMangaStyle() {
        val help = mutableStateOf(false)
        compose.setContent {
            MangaTheme(false) {
                if (help.value) HelpScreen(onBack = {})
                else OnboardingScreen(onComplete = {}, onSignIn = {})
            }
        }
        compose.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        screenshot("manga-onboarding")
        compose.runOnIdle { help.value = true }
        compose.onNodeWithText("Help & support").assertIsDisplayed()
        screenshot("manga-help")
    }

    @Test fun mtStudioAboutPreservesBannerRatioAndSupportsLargeTypeInBothThemes() {
        val dark = mutableStateOf(false)
        var returned = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.4f)) {
                MangaTheme(dark.value) { AboutScreen(onBack = { returned = true }) }
            }
        }
        compose.onNodeWithText("About Kitna").assertIsDisplayed()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertExists()
        compose.onNodeWithContentDescription("MT Studio logo").performScrollTo().assertIsDisplayed()
        val bounds = compose.onNodeWithContentDescription("MT Studio logo").getUnclippedBoundsInRoot()
        assertEquals(3f, (bounds.right - bounds.left).value / (bounds.bottom - bounds.top).value, .02f)
        screenshot("manga-about-mt-studio")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithContentDescription("MT Studio logo").performScrollTo().assertIsDisplayed()
        screenshot("manga-about-mt-studio-dark")
        compose.onNodeWithText("Back to Kitna").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(returned)
    }

    @Test fun largeTextBudgetEditAndExpenseRemainReachableInLightAndDarkThemes() {
        val group = Group("manga", "Goa with the gang", listOf("self", "alex"), kind = "Trip", budget = 1000000)
        val bill = Expense("hotel", "manga", "A seaside stay with a long descriptive name", 970000,
            mapOf("self" to 970000), mapOf("self" to 485000, "alex" to 485000), category = "Travel", date = "2026-11-24")
        val book = Book(people = listOf(Person("self", "You"), Person("alex", "Alex")), groups = listOf(group), expenses = listOf(bill))
        val dark = mutableStateOf(false)
        var edited = false
        var opened = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                MangaTheme(dark.value) {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                        LazyColumn(Modifier.fillMaxSize().testTag("manga-accessible-list")) {
                            item { TripBudgetCard(book, group) { edited = true } }
                            expenseRows(book, book.expenses, { opened = true })
                        }
                    }
                }
            }
        }
        fun scrollTo(text: String) { compose.onNodeWithTag("manga-accessible-list").performScrollToNode(hasText(text)) }
        scrollTo("Edit budget")
        compose.onNodeWithText("Edit budget").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(edited)
        scrollTo("Almost at limit")
        compose.onNodeWithText("Almost at limit").assertIsDisplayed()
        scrollTo("Remaining: ₹300.00")
        compose.onNodeWithText("Remaining: ₹300.00").assertIsDisplayed()
        screenshot("manga-budget-large-type")
        scrollTo("Large expense")
        compose.onNodeWithText("Large expense").assertIsDisplayed()
        scrollTo(bill.description)
        compose.onNodeWithText(bill.description).performClick()
        assertTrue(opened)
        screenshot("manga-expense-large-type")

        compose.runOnIdle { dark.value = true }
        scrollTo("Almost at limit")
        compose.onNodeWithText("Almost at limit").assertIsDisplayed()
        screenshot("manga-budget-dark-large-type")
    }
}
