package app.kitna.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kitna.BuildConfig
import app.kitna.R

/** MT Studio artwork is displayed from the supplied originals, without tinting or cropping. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var actionError by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("About Kitna") }, navigationIcon = { ActionIcon("back", "Back", onBack) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MangaChapter("THE STUDIO BEHIND KITNA", "GOOD TRIPS. FAIR SPLITS.")
            MangaPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    KitnaMark(size = 56)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Kitna", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Record shared expenses, review balances and keep trip receipts together.", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Created by MT Studio", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            // The source banner is 2172 × 724. Its existing white clear space is part of the artwork.
            Surface(color = Color.White, shape = RectangleShape) {
                Image(painter = painterResource(R.drawable.mt_studio_banner), contentDescription = "MT Studio logo",
                    modifier = Modifier.fillMaxWidth().aspectRatio(2172f / 724f), contentScale = ContentScale.Fit)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painter = painterResource(R.drawable.mt_studio_monogram), contentDescription = "MT Studio monogram",
                    modifier = Modifier.size(64.dp), contentScale = ContentScale.Fit)
                Text("Made for your crew's everyday bills and shared adventures.", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            MangaPanel {
                Text("Find MT Studio", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    actionError = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MT-Studio-dev"))) }
                        .onFailure { actionError = "No browser could open GitHub. Visit github.com/MT-Studio-dev in a browser." }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("MT Studio on GitHub") }
                OutlinedButton(onClick = {
                    actionError = null
                    val address = Uri.parse("mailto:official.mtstudio.dev@gmail.com?subject=${Uri.encode("Kitna ${BuildConfig.VERSION_NAME} support")}")
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, address)) }
                        .onFailure { actionError = "No email app could open. Contact official.mtstudio.dev@gmail.com from your email service." }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Contact MT Studio") }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text("official.mtstudio.dev@gmail.com", style = MaterialTheme.typography.bodySmall)
                }
                actionError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back to Kitna") }
        }
    }
}
