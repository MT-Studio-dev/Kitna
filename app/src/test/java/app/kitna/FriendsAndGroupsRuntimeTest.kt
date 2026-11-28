package app.kitna

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.kitna.core.*
import app.kitna.ui.*
import kotlinx.coroutines.runBlocking
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
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FriendsAndGroupsRuntimeTest {
    @get:Rule(order = 0) val clean = CleanLocalBookRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: KitnaViewModel
    private fun seed(book: Book) {
        vm = KitnaViewModel(compose.activity.application as Application)
        compose.waitUntil(10_000) { vm.book.value != null }
        runBlocking { vm.store.replace(book) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun friends(group: Group? = null, onClose: () -> Unit = {}) {
        compose.setContent { val book by vm.book.collectAsState(); MangaTheme(false) { Surface(Modifier.fillMaxSize()) { AddFriendsScreen(book!!, vm, group, onClose, {}) } } }
    }
    @Test fun nameOnlyFriendIsSavedWithoutAuthenticationAndDuplicateNameReusesIt() {
        seed(Book())
        friends()
        compose.onNodeWithText("Add manually").performClick()
        compose.onNodeWithText("Friend's name").performTextInput("Rahul")
        compose.onNodeWithText("Add friend").performClick()
        compose.waitUntil(10_000) { vm.book.value!!.people.size == 2 }
        compose.onNodeWithText("Rahul").assertExists()
        assertEquals("", vm.book.value!!.people.last().email)
        assertNull(vm.cloud.state.value.session)
        compose.onNodeWithText("Add manually").performClick()
        compose.onNodeWithText("Friend's name").performTextInput("rahul")
        compose.onNodeWithText("Add friend").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rahul is already a friend").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, vm.book.value!!.people.size)
    }
    @Test fun memberSelectionAddsMultiplePeopleInOneSaveAndMarksExistingMembers() {
        val group = Group("home", "Home", listOf("self", "alex"))
        seed(Book(people = listOf(Person("self", "You"), Person("alex", "Alex"), Person("sam", "Sam"), Person("rahul", "Rahul")), groups = listOf(group)))
        var closed = false
        friends(group) { closed = true }
        compose.onNodeWithText("Already in group").assertExists()
        compose.onNodeWithText("Sam").performScrollTo().performClick()
        compose.onNodeWithText("Rahul").performScrollTo().performClick()
        compose.onNodeWithText("Add (2)").performClick()
        compose.waitUntil(10_000) { closed }
        assertEquals(setOf("self", "alex", "sam", "rahul"), vm.book.value!!.groups.single().members.toSet())
        assertEquals(4, vm.book.value!!.groups.single().members.size)
    }
    @Test fun groupCanBeCreatedWithOnlyItsNameAndNoOnlineConfiguration() {
        seed(Book())
        var created: String? = null
        compose.setContent { val book by vm.book.collectAsState(); MangaTheme(false) { GroupEditor(book!!, null, vm, onCreated = { id, share -> assertFalse(share); created = id }) {} } }
        compose.onNodeWithText("Group name").performTextInput("Vrindavan")
        compose.onNodeWithText("Share this group online").assertDoesNotExist()
        compose.onNodeWithText("Trip budget · ₹ (optional)").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { created != null }
        assertEquals(listOf("self"), vm.book.value!!.groups.single().members)
        assertEquals("Vrindavan", vm.book.value!!.groups.single().name)
    }
    @Test fun unifiedFriendsScreenRendersInLightAndDarkThemes() {
        seed(Book(people = listOf(Person("self", "You"), Person("alex", "Alex"), Person("rahul", "Rahul", phone = "+91 9999999999"))))
        val dark = mutableStateOf(false)
        compose.setContent { val book by vm.book.collectAsState(); MangaTheme(dark.value) { Surface(Modifier.fillMaxSize()) { AddFriendsScreen(book!!, vm, onClose = {}, onAccount = {}) } } }
        compose.onNodeWithText("Share invite link").assertIsDisplayed()
        compose.onNodeWithText("Add from contacts").assertIsDisplayed()
        screenshot("v07-add-friends-light")
        compose.runOnIdle { dark.value = true }
        screenshot("v07-add-friends-dark")
    }
    @Test fun directLedgerConnectsItsExistingFriendAndDoesNotOfferANewMemberCode() {
        val group = Group("direct", "Alex", listOf("self", "alex"), kind = "Direct")
        seed(Book(people = listOf(Person("self", "You"), Person("alex", "Alex")), groups = listOf(group)))
        var askedToSignIn = false
        compose.setContent { MangaTheme(false) { Surface { GroupInviteCard(vm.book.value!!, group, vm) { askedToSignIn = true } } } }
        compose.onNodeWithText("Connect Alex").assertIsDisplayed().performClick()
        assertTrue(askedToSignIn)
        compose.onNodeWithText("Share group & invite friends").assertDoesNotExist()
        compose.onNodeWithText("Copy group code").assertDoesNotExist()
    }
}
