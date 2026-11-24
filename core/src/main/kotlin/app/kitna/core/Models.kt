package app.kitna.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
@Serializable data class Person(val id: String = newId(), val name: String)
@Serializable data class Group(
    val id: String = newId(), val name: String, val members: List<String>,
    val archived: Boolean = false, val kind: String = "Group", val simplifyDebts: Boolean = true,
    val icon: String = "group", val color: String = "teal", val budget: Long? = null
)
@Serializable data class ReceiptPhoto(
    val id: String = newId(), val name: String, val mimeType: String = "image/jpeg",
    val sizeBytes: Long = 0, val sha256: String = ""
)
@Serializable data class Expense(
    val id: String = newId(), val groupId: String, val description: String,
    val amount: Long, val payers: Map<String, Long>, val shares: Map<String, Long>,
    val category: String = "General", val date: String = LocalDate.now().toString(),
    val deleted: Boolean = false, val notes: String = "", val splitMode: String = "Exact amounts", val splitWeights: Map<String, Long> = emptyMap(),
    val receipts: List<ReceiptPhoto> = emptyList()
)
@Serializable data class Settlement(
    val id: String = newId(), val groupId: String, val from: String, val to: String,
    val amount: Long, val method: String = "Cash", val date: String = LocalDate.now().toString(), val deleted: Boolean = false, val reference: String = ""
)
@Serializable data class Activity(val id: String = newId(), val groupId: String, val message: String, val time: Long = System.currentTimeMillis())
@Serializable data class Comment(val id: String = newId(), val expenseId: String, val text: String, val time: Long = System.currentTimeMillis(), val authorId: String = "self")
@Serializable data class ExpenseRevision(val id: String = newId(), val expense: Expense, val reason: String, val time: Long = System.currentTimeMillis())
@Serializable data class Book(
    val schemaVersion: Int = 1,
    val people: List<Person> = listOf(Person("self", "You")),
    val groups: List<Group> = emptyList(), val expenses: List<Expense> = emptyList(),
    val settlements: List<Settlement> = emptyList(), val activity: List<Activity> = emptyList(),
    val comments: List<Comment> = emptyList(), val revisions: List<ExpenseRevision> = emptyList(),
    val sharedGroups: List<SharedGroupLink> = emptyList(), val pendingChanges: List<SharedChange> = emptyList()
)
data class Transfer(val from: String, val to: String, val amount: Long)
