package app.kitna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** Root owns the completion preference, profile save and Google sign-in flow. */
@Composable fun OnboardingScreen(
    onComplete: (String) -> Unit,
    onSignIn: () -> Unit,
    busy: Boolean = false,
    error: String? = null
) {
    var learnMore by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = learnMore && !busy) { learnMore = false }
    val beginLocal = {
        val cleaned = name.trim()
        if (cleaned.isBlank() || cleaned.length > 100) nameError = "Enter your name using 1 to 100 characters."
        else { nameError = null; onComplete(cleaned) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("KITNA!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text("MT Studio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer, border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("GOOD TRIPS.\nFAIR SPLITS.", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Text("Split bills with friends. Start on this phone and sign in whenever you're ready to share.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(value = name, onValueChange = { name = it; nameError = null }, label = { Text("Your name") }, singleLine = true, isError = nameError != null, supportingText = if (nameError == null) null else ({ Text(nameError!!, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), shape = RoundedCornerShape(4.dp), enabled = !busy)
            Button(onClick = beginLocal, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text(if (busy) "Please wait…" else "Start with local records") }
            OutlinedButton(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text("Continue with Google") }
            error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { learnMore = !learnMore }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (learnMore) "Hide introduction" else "Learn how Kitna works") }
            if (learnMore) {
                SetupLine("receipt", "Record the bill", "Enter a description and amount, then save. Payer and equal shares are ready for you.")
                SetupLine("groups", "Invite your friends", "Friends need no account for local tracking. Sign in with Google to accept invitations and share groups.")
                SetupLine("activity", "Keep going offline", "Your records stay on this phone. Shared changes synchronize when you're connected.")
                SetupLine("download", "Keep a complete backup", "A ZIP backup includes records, history and receipt photos. Save one before changing phones.")
                Text("Kitna records payments made elsewhere. It does not transfer money.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(1.dp))
                TextButton(onClick = { onComplete("You") }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip for now") }
            }
        }
    }
}

@Composable private fun SetupLine(symbol: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Glyph(symbol, Modifier.padding(top = 3.dp), MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
