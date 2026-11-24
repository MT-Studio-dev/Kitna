package app.kitna.core

/** Pure book mutations. LocalStore validates and atomically persists each result. */
object BookActions {
    private fun name(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 100) { "Enter a name between 1 and 100 characters." } }
    private fun event(book: Book, group: String = "", message: String) = book.copy(activity = book.activity + Activity(groupId = group, message = message))
    private fun activeGroup(book: Book, id: String) = book.groups.firstOrNull { it.id == id }?.also { require(!it.archived) { "Restore this archived group first." } } ?: throw IllegalArgumentException("Group no longer exists.")

    fun addPerson(book: Book, value: String): Book {
        return savePerson(book, Person(name = value))
    }
    fun savePerson(book: Book, person: Person): Book {
        val label = name(person.name)
        val old = book.people.firstOrNull { it.id == person.id }
        if (old != null) return if (old.name == label) book else renamePerson(book, person.id, label)
        require(book.people.none { it.name.equals(label, true) }) { "A person with this name already exists." }
        return event(book.copy(people = book.people + person.copy(name = label)), message = "Added $label")
    }
    fun renamePerson(book: Book, id: String, value: String): Book {
        val old = book.people.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Person no longer exists.")
        val label = name(value)
        require(book.people.none { it.id != id && it.name.equals(label, true) }) { "A person with this name already exists." }
        return event(book.copy(people = book.people.map { if (it.id == id) it.copy(name = label) else it },
            groups = book.groups.map { if (it.kind == "Direct" && id != "self" && id in it.members) it.copy(name = label) else it }), message = "Renamed ${old.name} to $label")
    }
    fun deletePerson(book: Book, id: String): Book {
        require(id != "self") { "Your own profile cannot be deleted. Use Erase local data to start over." }
        val person = book.people.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Person no longer exists.")
        val emptyDirect = book.groups.filter { g -> g.kind == "Direct" && id in g.members && book.expenses.none { it.groupId == g.id } && book.settlements.none { it.groupId == g.id } }.map { it.id }.toSet()
        require(book.groups.none { id in it.members && it.id !in emptyDirect }) { "Remove this person from groups before deleting them. People used in expense history must be kept." }
        require(book.comments.none { it.authorId == id }) { "This person's comments are part of your history." }
        return event(book.copy(people = book.people.filterNot { it.id == id }, groups = book.groups.filterNot { it.id in emptyDirect }, activity = book.activity.filterNot { it.groupId in emptyDirect }), message = "Removed ${person.name}")
    }
    fun saveGroup(book: Book, group: Group): Book {
        val old = book.groups.firstOrNull { it.id == group.id }
        val clean = group.copy(name = name(group.name), members = group.members.distinct())
        require(clean.members.size >= 2 && "self" in clean.members) { "Include yourself and at least one friend." }
        require(clean.members.all { id -> book.people.any { it.id == id } }) { "A selected person no longer exists." }
        require(clean.kind in setOf("Group", "Trip", "Home", "Couple", "Direct")) { "Unknown group type." }
        require(clean.kind != "Direct" || clean.members.size == 2) { "A direct friendship must contain exactly two people." }
        GroupAppearance.validate(clean.icon, clean.color)
        BookValidation.budget(clean.budget)
        if (old != null) {
            require(!old.archived || !clean.archived || clean == old) { "Restore this archived group before editing it." }
            require((old.kind == "Direct") == (clean.kind == "Direct")) { "A friendship cannot be converted to a group." }
            val removed = old.members - clean.members.toSet()
            require(book.expenses.filter { it.groupId == group.id }.none { e -> (e.payers.keys + e.shares.keys).any { it in removed } } &&
                book.revisions.filter { it.expense.groupId == group.id }.none { r -> (r.expense.payers.keys + r.expense.shares.keys).any { it in removed } } &&
                book.settlements.filter { it.groupId == group.id }.none { it.from in removed || it.to in removed } &&
                book.comments.none { c -> c.authorId in removed && book.expenses.any { it.id == c.expenseId && it.groupId == group.id } }) { "Members used in expense, comment, or repayment history cannot be removed." }
            if (clean.archived && !old.archived) require(Accounting.transfers(book, group.id).isEmpty()) { "Settle this group's balances before archiving." }
        }
        if (old == clean) return book
        return event(book.copy(groups = book.groups.filterNot { it.id == clean.id } + clean), clean.id, if (old == null) "Created ${clean.name}" else "Updated ${clean.name}")
    }
    fun directGroup(book: Book, personId: String): Pair<Book, String> {
        require(personId != "self" && book.people.any { it.id == personId }) { "Choose a friend." }
        val found = book.groups.firstOrNull { it.kind == "Direct" && it.members.toSet() == setOf("self", personId) }
        if (found != null) return (if (found.archived) saveGroup(book, found.copy(archived = false)) else book) to found.id
        val group = Group(name = book.people.first { it.id == personId }.name, members = listOf("self", personId), kind = "Direct")
        return saveGroup(book, group) to group.id
    }
    fun deleteGroup(book: Book, id: String): Book {
        val group = book.groups.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Group no longer exists.")
        require(book.expenses.none { it.groupId == id } && book.settlements.none { it.groupId == id }) { "Groups with history cannot be deleted. Archive a settled group instead." }
        return event(book.copy(groups = book.groups.filterNot { it.id == id }, activity = book.activity.filterNot { it.groupId == id }), message = "Deleted empty group ${group.name}")
    }
    fun archive(book: Book, group: Group): Book {
        val current = book.groups.firstOrNull { it.id == group.id } ?: throw IllegalArgumentException("Group no longer exists.")
        return saveGroup(book, current.copy(archived = !current.archived))
    }
    fun saveExpense(book: Book, expense: Expense): Book {
        val clean = expense.copy(description = expense.description.trim(), notes = expense.notes.trim())
        Accounting.validate(clean, activeGroup(book, clean.groupId))
        require(!clean.deleted) { "Use Restore to recover a deleted expense." }
        val old = book.expenses.firstOrNull { it.id == clean.id }
        require(old == null || old.groupId == clean.groupId) { "An expense cannot be moved between groups." }
        require(old?.deleted != true) { "Restore this expense before editing it." }
        if (old == clean) return book
        return event(book.copy(expenses = book.expenses.filterNot { it.id == clean.id } + clean,
            revisions = if (old == null) book.revisions else book.revisions + ExpenseRevision(expense = old, reason = "Before edit")), clean.groupId, "${if (old == null) "Added" else "Updated"} ${clean.description}")
    }
    fun deleteExpense(book: Book, id: String): Book {
        val old = book.expenses.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Expense no longer exists.")
        activeGroup(book, old.groupId)
        require(!old.deleted) { "Expense is already deleted." }
        return event(book.copy(expenses = book.expenses.map { if (it.id == id) it.copy(deleted = true) else it }, revisions = book.revisions + ExpenseRevision(expense = old, reason = "Before deletion")), old.groupId, "Deleted ${old.description}")
    }
    fun restoreExpense(book: Book, id: String): Book {
        val old = book.expenses.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Expense no longer exists.")
        activeGroup(book, old.groupId)
        require(old.deleted) { "Expense is not deleted." }
        return event(book.copy(expenses = book.expenses.map { if (it.id == id) it.copy(deleted = false) else it }, revisions = book.revisions + ExpenseRevision(expense = old, reason = "Before restore")), old.groupId, "Restored ${old.description}")
    }
    fun saveSettlement(book: Book, settlement: Settlement): Book {
        val group = activeGroup(book, settlement.groupId)
        BookValidation.settlement(settlement, group)
        require(!settlement.deleted) { "A deleted repayment cannot be saved." }
        val old = book.settlements.firstOrNull { it.id == settlement.id }
        require(old == null || old.groupId == settlement.groupId) { "A repayment cannot be moved between groups." }
        if (old == settlement.copy(reference = settlement.reference.trim())) return book
        return event(book.copy(settlements = book.settlements.filterNot { it.id == settlement.id } + settlement.copy(reference = settlement.reference.trim())), group.id, if (old == null) "Recorded repayment · ${settlement.method}" else "Updated repayment · ${settlement.method}")
    }
    fun deleteSettlement(book: Book, id: String): Book {
        val old = book.settlements.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Repayment no longer exists.")
        activeGroup(book, old.groupId)
        require(!old.deleted) { "Repayment is already deleted." }
        return event(book.copy(settlements = book.settlements.map { if (it.id == id) it.copy(deleted = true) else it }), old.groupId, "Deleted repayment · ${old.method}")
    }
    fun comment(book: Book, expenseId: String, text: String, authorId: String): Book {
        return saveComment(book, Comment(expenseId = expenseId, text = text, authorId = authorId))
    }
    fun saveComment(book: Book, comment: Comment): Book {
        val expense = book.expenses.firstOrNull { it.id == comment.expenseId } ?: throw IllegalArgumentException("Expense no longer exists.")
        activeGroup(book, expense.groupId)
        require(!expense.deleted) { "Restore this expense before adding comments." }
        require(comment.text.trim().length in 1..2000) { "Enter a comment between 1 and 2,000 characters." }
        require(comment.authorId in book.groups.first { it.id == expense.groupId }.members) { "Choose a group member as the comment author." }
        val clean = comment.copy(text = comment.text.trim())
        val old = book.comments.firstOrNull { it.id == comment.id }
        require(old == null || old.expenseId == clean.expenseId) { "A comment cannot be moved between expenses." }
        if (old == clean) return book
        return event(book.copy(comments = book.comments.filterNot { it.id == clean.id } + clean), expense.groupId, "${if (old == null) "Commented on" else "Updated comment on"} ${expense.description}")
    }
}
