package app.kitna

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/** Runs before the Activity rule so no ViewModel opens a previous test's book. */
class CleanLocalBookRule : ExternalResource() {
    override fun before() {
        app.kitna.data.KitnaRuntime.resetForTests()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("kitna.db")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().putString("theme", "System").commit()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("complete", true).commit()
    }
}
