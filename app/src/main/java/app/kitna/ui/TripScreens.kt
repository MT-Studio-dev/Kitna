@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kitna.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kitna.core.*

@Composable internal fun TripBudgetCard(book: Book, group: Group, onEdit: () -> Unit) {
    val status = TripReports.budget(book, group.id)
    val mood = status.budget?.let { budgetMood(status.spent, it) }
    val accent = mood?.ink() ?: MaterialTheme.colorScheme.primary
    MangaPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trip budget", Modifier.padding(vertical=12.dp).semantics { heading() }, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                if (!group.archived) TextButton(onEdit, modifier = Modifier.heightIn(min=48.dp)) { Text(if (status.budget == null) "Set budget" else "Edit budget") }
            }
            if (status.budget == null) {
                Text("Spent: ${money(status.spent)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("Set an optional budget to track your trip's remaining spending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val currentMood = requireNotNull(mood)
                Text(currentMood.label, Modifier.background(accent.copy(alpha=.12f),RoundedCornerShape(3.dp)).padding(horizontal=10.dp,vertical=6.dp), style=MaterialTheme.typography.labelLarge,color=accent)
                Text("Spent ${money(status.spent)} of ${money(status.budget!!)}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (status.spent.toDouble() / status.budget!!).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp).semantics { stateDescription = "${currentMood.label}. ${money(status.spent)} spent of ${money(status.budget!!)}." }, color = accent, trackColor = accent.copy(alpha=.15f))
                Text(if (status.overBudget > 0) "Over budget: ${money(status.overBudget)}" else "Remaining: ${money(status.remaining!!)}",
                    color = accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text("Expenses only · repayments do not increase trip spending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TripSummaryScreen(book: Book, group: Group, onBack: () -> Unit) {
    val context = LocalContext.current
    val expenses = book.expenses.filter { it.groupId == group.id && !it.deleted }
    val status = TripReports.budget(book, group.id)
    val people = TripReports.people(book, group.id)
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Trip summary") }, navigationIcon = { ActionIcon("back", "Back", onBack) }) },
        bottomBar = {
            Surface(shadowElevation = 2.dp) {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${group.name} — trip summary")
                        putExtra(Intent.EXTRA_TEXT, TripReports.summary(book, group.id))
                    }
                    context.startActivity(Intent.createChooser(send, "Share trip summary"))
                }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Share summary") }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaChapter("THE TRIP RECAP", "ONE ADVENTURE. ALL THE DETAILS.")
                    GroupAvatar(group, 56)
                    Text(group.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("${group.members.size} people · ${expenses.size} expenses", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Total trip spending", style = MaterialTheme.typography.labelLarge)
                    Text(money(status.spent), style=MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    if (status.budget != null) {
                        Text("Budget: ${money(status.budget!!)}")
                        Text(if (status.overBudget > 0) "Over budget: ${money(status.overBudget)}" else "Remaining: ${money(status.remaining!!)}", color = budgetMood(status.spent,status.budget!!).ink(),fontWeight=FontWeight.Bold)
                    }
                    Text("Repayments are listed separately from spending. This summary includes the whole group, regardless of screen filters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionLabel("Each person's totals") }
            items(people, key = { it.personId }) { person ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(person.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    SummaryAmount("Paid for expenses", person.paid)
                    SummaryAmount("Share of expenses", person.share)
                    SummaryAmount("Repayments sent", person.repaymentsSent)
                    SummaryAmount("Repayments received", person.repaymentsReceived)
                    Text(if (person.net > 0) "Net to receive: ${money(person.net)}" else if (person.net < 0) "Net to pay: ${money(-person.net)}" else "Net balance: ${money(0)}", color = if (person.net < 0) Owe else Owed, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
            item { SectionLabel("Final repayments") }
            val transfers = Accounting.transfers(book, group.id)
            if (transfers.isEmpty()) item { Text("Everyone is settled up.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.primary) }
            items(transfers) { transfer -> ListItem(headlineContent = { Text("${book.personName(transfer.from)} → ${book.personName(transfer.to)}") }, trailingContent = { Text(money(transfer.amount), fontWeight = FontWeight.Bold) }) }
            item { SectionLabel("Spending by category") }
            items(TripReports.categories(book, group.id).toList()) { (name, amount) -> ListItem(leadingContent = { CategoryIcon(name, size = 32) }, headlineContent = { Text(name) }, trailingContent = { Text(money(amount)) }) }
        }
    }
}

@Composable private fun SummaryAmount(label: String, amount: Long) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, style = MaterialTheme.typography.bodySmall); Text(money(amount), style = MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold) }
}
