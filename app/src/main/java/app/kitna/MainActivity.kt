package app.kitna

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kitna.ui.KitnaApp
import app.kitna.ui.MangaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { model().receiveCloudIntent(it); intent.data = null }
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "Light") ?: "Light") }
            val model: KitnaViewModel = viewModel(factory = remember(application) {
                ViewModelProvider.AndroidViewModelFactory(application)
            })
            val dark = theme == "Dark" || (theme == "System" && isSystemInDarkTheme())
            MangaTheme(dark) {
                val background = MaterialTheme.colorScheme.background.toArgb()
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = background
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = background
                    WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
                }
                KitnaApp(theme = theme, onTheme = { theme = it; preferences.edit().putString("theme", it).apply() }, vm = model)
            }
        }
    }
    private fun model() = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[KitnaViewModel::class.java]
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.data?.let { model().receiveCloudIntent(it); intent.data = null } }
    override fun onStart() { super.onStart(); model().cloud.setForeground(true) }
    override fun onStop() { model().cloud.setForeground(false); super.onStop() }
}
