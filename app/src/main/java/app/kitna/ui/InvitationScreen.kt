package app.kitna.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** An invitation is never accepted as a side effect of authentication or opening a URL. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun InvitationScreen(vm: KitnaViewModel, onClose: () -> Unit, onFriendAccepted: () -> Unit) {
    val state by vm.cloud.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = state.pendingInvite
    var preview by remember(token, state.session?.id) { mutableStateOf<JsonObject?>(null) }
    var error by rememberSaveable(token) { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val busy = localBusy || state.busy
    fun close() { if (!busy) { vm.cloud.dismissInvitation(); onClose() } }
    suspend fun load() {
        if (token == null) return
        localBusy = true; error = null
        try { vm.cloud.previewInvitation(token, state.pendingInviteKind).onSuccess { preview = it }.onFailure { error = it.message } }
        finally { localBusy = false }
    }
    LaunchedEffect(token, state.session?.id) { if (token != null && state.session != null) load() }
    BackHandler { close() }
    Scaffold(topBar = { TopAppBar(title = { Text("You're invited") }, navigationIcon = { ActionIcon("back", "Back", ::close) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(24.dp))
            val friend = state.pendingInviteKind == "friend"
            Glyph(if (friend) "friends" else "groups", Modifier.size(48.dp), MaterialTheme.colorScheme.primary)
            Text(if (friend) "Connect with a friend" else "Join your friends on Kitna", style = MaterialTheme.typography.headlineSmall)
            if (state.session == null) {
                Text("Continue with Google to see this private invitation. You'll choose whether to accept it after signing in.")
                Button(onClick = { if (!busy) scope.launch {
                    localBusy = true; error = null
                    try { vm.cloud.beginSignIn().onSuccess { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { error = "Enable a browser to continue with Google." } }.onFailure { error = it.message } }
                    finally { localBusy = false }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continue with Google") }
            } else {
                preview?.let { data ->
                    Text(data["name"]?.jsonPrimitive?.contentOrNull ?: "Kitna invitation", style = MaterialTheme.typography.titleLarge)
                    if (!friend) data["memberCount"]?.jsonPrimitive?.intOrNull?.let { Text("$it members", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(if (friend) "Accept to add each other as friends. Your existing expense history stays with the same person." else "Accept to see this group's expenses and balances. Your existing local records stay available.")
                    val existing = data[if (friend) "alreadyAccepted" else "alreadyMember"]?.jsonPrimitive?.booleanOrNull == true
                    Button(onClick = { if (!busy && token != null) scope.launch {
                        localBusy = true; error = null
                        try {
                            val result = if (friend) vm.cloud.acceptFriendInvitation(token) else vm.cloud.join(token)
                            result.onSuccess { if (friend) onFriendAccepted() }.onFailure { error = it.message }
                        } finally { localBusy = false }
                    } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (friend) if (existing) "Open friends" else "Accept invitation" else if (existing) "Open group" else "Join group") }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Opening invitation…") }
            state.message?.takeIf { it != error }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            error?.let { message ->
                Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error)
                if (state.session != null) OutlinedButton({ scope.launch { load() } }, enabled = !busy) { Text("Try again") }
            }
            TextButton(::close, enabled = !busy) { Text("Maybe later") }
        }
    }
}
