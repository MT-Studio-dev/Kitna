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
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = step > 0 && !busy) { step-- }
    val beginLocal = {
        val cleaned = name.trim()
        if (cleaned.isBlank() || cleaned.length > 100) nameError = "Enter your name using 1 to 100 characters."
        else { nameError = null; onComplete(cleaned) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("KITNA!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text("Step ${step + 1} of 3", style = MaterialTheme.typography.bodyMedium)
            }
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer, border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Glyph(when (step) { 0 -> "groups"; 1 -> "friends"; else -> "check" }, Modifier.size(40.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(when (step) { 0 -> "GOOD TRIPS.\nFAIR SPLITS."; 1 -> "YOUR CREW.\nONE LEDGER."; else -> "MAKE IT\nYOURS." }, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            when (step) {
                0 -> {
                    Text("Keep the good memories. Split the bills.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    SetupLine("receipt", "Record the bill", "Add the total, who paid and who took part. Keep a receipt photo with the expense.")
                    SetupLine("report", "Know where you stand", "See balances, track your trip budget and share a summary with your friends.")
                    Text("Kitna tracks payments made elsewhere. It does not transfer money or confirm a bank payment.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> {
                    SetupLine("groups", "Invite your friends", "Sign in with Google and use an invitation to join the same shared group. Adding a friend's name alone does not invite their account.")
                    SetupLine("activity", "Keep going offline", "Cached records stay readable. Shared changes show Pending until the server accepts them; a conflict needs your review.")
                    SetupLine("download", "Keep a complete backup", "A ZIP backup includes records, history and receipt photos. Keep a copy before changing phones or clearing app data.")
                }
                else -> {
                    Text("Choose how to begin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Google sign-in connects you with shared groups. You can also start with local records and connect an account later.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text(if (busy) "Please wait…" else "Continue with Google") }
                    HorizontalDivider()
                    OutlinedTextField(value = name, onValueChange = { name = it; nameError = null }, label = { Text("Your name") }, singleLine = true, isError = nameError != null, supportingText = if (nameError == null) null else ({ Text(nameError!!, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), shape = RoundedCornerShape(4.dp), enabled = !busy)
                    OutlinedButton(onClick = beginLocal, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text("Start with local records") }
                }
            }
            error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (step < 2) Button(onClick = { step++ }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text(if (step == 0) "Meet your crew" else "Choose how to begin") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
                else Spacer(Modifier.width(1.dp))
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
