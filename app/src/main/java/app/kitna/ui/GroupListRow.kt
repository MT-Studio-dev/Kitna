package app.kitna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kitna.core.*

@Composable internal fun GroupListRow(book: Book, group: Group, onClick: () -> Unit) {
    val net = Accounting.balances(book, group.id)["self"] ?: 0
    val transfer = Accounting.transfers(book, group.id).firstOrNull { it.from == "self" || it.to == "self" }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupAvatar(group, 76)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text(if (group.archived) "Archived" else if (net > 0) "you are owed ${money(net)}" else if (net < 0) "you owe ${money(-net)}" else if (book.expenses.none { it.groupId == group.id && !it.deleted }) "no expenses" else "settled up", color = if (net > 0) Owed else if (net < 0) Owe else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, fontWeight = if (net != 0L) FontWeight.Medium else FontWeight.Normal)
            if (transfer != null) Text(if (transfer.to == "self") "${book.personName(transfer.from)} owes you ${money(transfer.amount)}" else "You owe ${book.personName(transfer.to)} ${money(transfer.amount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
