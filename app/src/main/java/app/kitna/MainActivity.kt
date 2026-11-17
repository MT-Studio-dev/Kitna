package app.kitna

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kitna.ui.KitnaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
            val model: KitnaViewModel = viewModel(factory = remember(application) {
                ViewModelProvider.AndroidViewModelFactory(application)
            })
            val dark = theme == "Dark" || (theme == "System" && isSystemInDarkTheme())
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF68D8BE), secondary = Color(0xFFFFA081), secondaryContainer = Color(0xFF254F44), onSecondaryContainer = Color(0xFFB4F1DF), background = Color(0xFF121A19), surface = Color(0xFF1C2523))
                else lightColorScheme(primary = Color(0xFF119B80), secondary = Color(0xFFE76642), secondaryContainer = Color(0xFFE0F3EE), onSecondaryContainer = Color(0xFF115B4B), background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF2F5F4))) {
                KitnaApp(theme = theme, onTheme = { theme = it; preferences.edit().putString("theme", it).apply() }, vm = model)
            }
        }
    }
}
