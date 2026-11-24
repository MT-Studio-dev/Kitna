package app.kitna

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.kitna.core.*
import app.kitna.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReferenceLayoutRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = Group("trip", "Goa weekend", listOf("self", "alex", "sam"), kind = "Trip", color = "purple", icon = "plane")
    private val book = Book(people = listOf(Person("self", "Manu"), Person("alex", "Alex"), Person("sam", "Sam")), groups = listOf(group), expenses = listOf(Expense("taxi", "trip", "Airport taxi", 120000, mapOf("self" to 120000), mapOf("self" to 40000,"alex" to 40000,"sam" to 40000), category = "Travel")))
    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread { val decor = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView; bitmap = Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888);decor.draw(Canvas(bitmap)) }
        val file = File("build/screenshots/$name.png");file.parentFile!!.mkdirs();file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
    @Test fun referenceListsAndColoredGroupHeaderRenderInBothThemes() {
        val dark = mutableStateOf(true)
        compose.setContent { MangaTheme(dark.value) { Surface(Modifier.fillMaxSize()) { LazyColumn { item { OverallSummary(Accounting.friendBalances(book)) };item { GroupListRow(book,group) {} };item { GroupHeader(group,"Manu, Alex, Sam") };expenseRows(book,book.expenses,{}) } } } }
        compose.onNodeWithText("you are owed ₹800.00").assertIsDisplayed()
        screenshot("reference-groups-dark")
        compose.runOnIdle { dark.value = false };screenshot("reference-groups-light")
    }
    @Test fun accountKeepsDataToolsInSettingsAndOpensNotificationControls() {
        val route = mutableStateOf("account")
        compose.setContent { MangaTheme(true) { Surface(Modifier.fillMaxSize()) {
            when(route.value) {
                "settings" -> SettingsScreen("Dark",{}, {}, {}, {}, {}, {}, {route.value="account"}, {route.value="notifications"})
                "notifications" -> NotificationSettingsScreen { route.value="settings" }
                else -> LazyColumn(Modifier.fillMaxSize()) { accountContent(book,"Dark",{}, {}, {}, {}, {}, {}, {}, {},sessionEmail="manu@example.com",onSettings={route.value="settings"},onLogout={},onShared={}) }
            }
        } } }
        compose.onNodeWithText("Save a backup").assertDoesNotExist()
        screenshot("reference-account-dark")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Settings"));compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Theme: Dark").assertIsDisplayed();screenshot("reference-settings-dark")
        compose.onNodeWithText("Notifications").performClick();compose.onNodeWithText("Shared group activity").assertIsDisplayed();screenshot("reference-notifications-dark")
    }
    @Test fun balancesExpandEachMemberAndOfferTheCorrectGroupForSettlement() {
        var settle: Group? = null
        compose.setContent { MangaTheme(true) { BalancesSheet(book,listOf(group),{}, {settle=it}) } }
        compose.onNodeWithText("gets back ₹800.00 in total").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand Alex").performClick()
        compose.onAllNodesWithText("Alex owes ₹400.00 to Manu").onLast().assertExists()
        screenshot("reference-balances-dark")
        compose.onAllNodesWithText("Settle up").onFirst().performClick();assertEquals(group,settle)
    }
}
