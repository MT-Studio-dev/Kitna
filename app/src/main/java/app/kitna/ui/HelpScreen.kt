package app.kitna.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Intentionally accepts no book, account, session, error body or identifiers. */
internal fun supportDiagnostics(version: String, sdk: Int, shared: Boolean): String = buildString {
    appendLine("Kitna support details")
    appendLine("App version: $version")
    appendLine("Android API: $sdk")
    appendLine("Record mode: ${if (shared) "Shared groups enabled" else "Local records"}")
    appendLine()
    appendLine("What were you trying to do?")
    appendLine()
    appendLine("What happened instead?")
    appendLine()
    append("No expenses, names, receipt photos, email addresses or sign-in tokens are included automatically. Review this message before sending it.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HelpScreen(onBack: () -> Unit, syncStatus: String = "Local records", onShowOnboarding: () -> Unit = {}) {
    val context = LocalContext.current
    var actionError by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { actionError = "No browser could open this link. Try again after enabling a browser." }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Help & support") }, navigationIcon = { ActionIcon("back", "Back", onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("A clear record. A happier crew.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(syncStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HelpPanel("Add an expense") {
                Text("Add friends or create a group first. Enter a description and total, choose who paid, then review everyone's share before saving. Multiple payers must add up exactly to the bill.")
                Text("Duplicate expense makes a new bill with today's date. It copies the amount and split so you can adjust them; photos stay with the original expense.")
            }
            HelpPanel("Choose a split") {
                Text("Equal divides the total between selected people. Exact amounts assigns each person's amount. Percentages must total 100%. Shares use whole-number weights, and Adjustments add or subtract an amount before splitting the remainder equally.")
                Text("Amounts are calculated in paise. A one-paise difference between people can be normal when a bill cannot be divided evenly.")
            }
            HelpPanel("Understand balances and settle up") {
                Text("Paid for expenses is what someone contributed. Their share is what they consumed. A balance combines those figures with repayments already recorded.")
                Text("Settle up records cash, UPI or another payment you made outside Kitna. It does not send money or verify a bank transfer. Review the payer, recipient and amount before saving.")
                Text("Debt simplification can reduce the number of repayments inside a group while keeping each person's net balance unchanged.")
            }
            HelpPanel("Budget and expense colours") {
                Text("Trip budgets compare the whole group's active expenses with its spending limit. Repayments are separate and do not add to trip spending.")
                Text("The budget progresses from green to amber, orange and red as it approaches its limit. Labels and amounts describe the same status. Larger expense cards use stronger emphasis; that does not mean the expense is a debt or an error.")
            }
            HelpPanel("Shared groups and offline records") {
                Text("Google sign-in identifies your account. Joining a shared group requires an invitation; a friend's name by itself does not connect their account.")
                Text("Shared records and receipt photos are available to the group's authorised members. Offline records may be older than the server. Pending changes still need server acceptance; review a conflict instead of assuming it has synced.")
            }
            HelpPanel("Privacy & your data") {
                Text("Kitna is created by MT Studio. For privacy questions or account-data requests, contact official.mtstudio.dev@gmail.com.")
                Text("Kitna stores the names, expenses, allocations, repayments, comments, history and receipt photos you enter. Local records stay in the app's private storage. Sharing a group sends that group's records and photos to Supabase for its members to access.")
                Text("Google sign-in supplies basic account information for authentication. Kitna does not need access to Gmail, Contacts, Drive or your bank account.")
                Text("Complete ZIP backups contain personal records and photos. Keep them somewhere private. Uninstalling or clearing app storage removes local records; erasing local data does not automatically remove records retained by other members of a shared group.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openLink("https://policies.google.com/privacy") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Google privacy") }
                    TextButton(onClick = { openLink("https://supabase.com/privacy") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Supabase privacy") }
                }
            }
            HelpPanel("Report a problem") {
                Text("MT Studio · official.mtstudio.dev@gmail.com")
                Text("Share a short description of what you tried and what happened. The message includes the app version, Android API and record mode. Your book, photos, names and sign-in details are not attached.")
                OutlinedButton(onClick = {
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Kitna support")
                        putExtra(Intent.EXTRA_TEXT, supportDiagnostics(version, Build.VERSION.SDK_INT, syncStatus != "Local records"))
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, "Share Kitna support details")) }
                        .onFailure { actionError = "Could not open sharing. Try again after enabling a sharing app." }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text("Share support details") }
                Text("Choose a recipient yourself and review the message before sending it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:official.mtstudio.dev@gmail.com")).putExtra(Intent.EXTRA_SUBJECT, "Kitna support")) }
                        .onFailure { actionError = "No email app is available. You can write to official.mtstudio.dev@gmail.com from your email service." }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Contact MT Studio") }
                TextButton(onClick = { openLink("https://github.com/MT-Studio-dev") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("MT Studio on GitHub") }
            }
            actionError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onShowOnboarding, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(4.dp)) { Text("Show setup guide") }
        }
    }
}

@Composable private fun HelpPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
