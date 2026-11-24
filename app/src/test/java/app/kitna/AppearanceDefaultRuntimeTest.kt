package app.kitna

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppearanceDefaultRuntimeTest {
    @get:Rule(order = 0) val clean = CleanLocalBookRule()
    @get:Rule(order = 1) val unsetAppearance = object : ExternalResource() { override fun before() { ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit() } }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun anUnsetAppearanceStartsInLightAndKeepsSettingsReachable() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Groups").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Account").onLast().performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Settings"))
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Theme: Light").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread { val decor = compose.activity.window.decorView;bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888);decor.draw(Canvas(bitmap)) }
        val file=File("build/screenshots/account-default-light.png");file.parentFile!!.mkdirs();file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
