package app.kitna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kitna.core.*

/** Home search is genuinely global; matching records can be opened directly. */
internal fun LazyListScope.searchContent(
    book: Book,
    query: String,
    onExpense: (Expense) -> Unit,
    onGroup: (String) -> Unit,
    onPerson: (String) -> Unit,
    onPayment: (Settlement) -> Unit
) {
    val expenses = book.expenses.filter { !it.deleted && BookSearch.expense(book, it, query) }.sortedByDescending { it.date }
    val payments = book.settlements.filter { !it.deleted && BookSearch.settlement(book, it, query) }.sortedByDescending { it.date }
    val groups = book.groups.filter { it.kind != "Direct" && BookSearch.group(book, it, query) }
    val friends = book.people.filter { it.id != "self" && BookSearch.person(book, it, query) }
    val events = book.activity.filter { BookSearch.activity(book, it, query) }.asReversed()
    val count = expenses.size + payments.size + groups.size + friends.size + events.size
    if (count == 0) {
        item { EmptyState("No matches", "Try an expense, category, person, group or amount. Your records have not changed.") }
        return
    }
    item { Text("$count matching records", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (expenses.isNotEmpty()) {
        item { SectionLabel("Expenses · ${expenses.size}") }
        expenseRows(book, expenses, onExpense, showGroup = true)
    }
    if (payments.isNotEmpty()) {
        item { SectionLabel("Repayments · ${payments.size}") }
        items(payments, key = { "search:payment:${it.id}" }) { payment ->
            ListItem(
                modifier = Modifier.clickable { onPayment(payment) },
                leadingContent = { Glyph("check", color = Owed) },
                headlineContent = { Text("${book.personName(payment.from)} paid ${book.personName(payment.to)}") },
                supportingContent = { Text("${book.groupName(payment.groupId)} · ${payment.method} · ${payment.date}") },
                trailingContent = { Text(money(payment.amount), fontWeight = FontWeight.Medium) }
            )
        }
    }
    if (groups.isNotEmpty()) {
        item { SectionLabel("Groups · ${groups.size}") }
        items(groups, key = { "search:group:${it.id}" }) { group ->
            ListItem(modifier = Modifier.clickable { onGroup(group.id) },
                leadingContent = { GroupAvatar(group) }, headlineContent = { Text(group.name) },
                supportingContent = { Text(if (group.archived) "Archived group" else "${group.members.size} people") },
                trailingContent = { Glyph("chevron") })
        }
    }
    if (friends.isNotEmpty()) {
        item { SectionLabel("Friends · ${friends.size}") }
        items(friends, key = { "search:person:${it.id}" }) { person ->
            ListItem(modifier = Modifier.clickable { onPerson(person.id) },
                leadingContent = { Avatar(person.name) }, headlineContent = { Text(person.name) },
                trailingContent = { Glyph("chevron") })
        }
    }
    if (events.isNotEmpty()) {
        item { SectionLabel("Activity · ${events.size}") }
        items(events, key = { "search:event:${it.id}" }) { event ->
            ListItem(headlineContent = { Text(event.message) }, supportingContent = { Text(book.groupName(event.groupId)) })
        }
    }
}
