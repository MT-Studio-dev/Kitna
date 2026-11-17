package app.kitna.core

object BookValidation {
    private fun unique(ids: List<String>) { require(ids.size == ids.toSet().size && ids.none { it.isBlank() || it.length > 200 }) { "Backup has duplicate or invalid identifiers." } }
    fun settlement(value: Settlement, group: Group) {
        require(value.from != value.to && value.from in group.members && value.to in group.members) { "Choose two different group members." }
        require(value.amount in 1..Accounting.MAX_AMOUNT) { "Enter a positive repayment amount." }
        require(value.method.isNotBlank() && value.method.length <= 100) { "Choose a repayment method." }
        require(value.reference.length <= 1000) { "Payment reference must be at most 1,000 characters." }
        Accounting.validDate(value.date)
    }
    fun validate(book: Book) {
        require(book.schemaVersion == 1) { "Unsupported backup version." }
        require(book.people.any { it.id == "self" }) { "Backup is missing your profile." }
        unique(book.people.map { it.id }); unique(book.groups.map { it.id }); unique(book.expenses.map { it.id })
        unique(book.settlements.map { it.id }); unique(book.comments.map { it.id }); unique(book.activity.map { it.id }); unique(book.revisions.map { it.id })
        require(book.people.all { it.name.isNotBlank() && it.name.length <= 100 }) { "Every person needs a name up to 100 characters." }
        val people = book.people.map { it.id }.toSet()
        val groups = book.groups.associateBy { it.id }
        book.groups.forEach { g ->
            require(g.name.isNotBlank() && g.name.length <= 100 && g.members.size >= 2 && "self" in g.members && g.members.all { it in people }) { "A group has invalid members or a missing name." }
            unique(g.members)
            require(g.kind in setOf("Group", "Trip", "Home", "Couple", "Direct") && (g.kind != "Direct" || g.members.size == 2)) { "A group has an invalid type." }
            GroupAppearance.validate(g.icon, g.color)
        }
        fun validateExpense(expense: Expense) { Accounting.validate(expense, requireNotNull(groups[expense.groupId]) { "An expense references a missing group." }.copy(archived = false)) }
        book.expenses.forEach(::validateExpense)
        book.revisions.forEach { revision ->
            require(book.expenses.any { it.id == revision.expense.id && it.groupId == revision.expense.groupId }) { "History references a missing expense." }
            validateExpense(revision.expense)
            require(revision.reason.length in 1..500 && revision.time >= 0) { "Invalid expense history." }
        }
        book.settlements.forEach { s -> settlement(s, requireNotNull(groups[s.groupId]) { "A repayment references a missing group." }) }
        require(book.activity.all { it.groupId.isEmpty() || it.groupId in groups }) { "Activity references a missing group." }
        require(book.activity.all { it.message.isNotBlank() && it.message.length <= 2000 && it.time >= 0 }) { "Invalid activity entry." }
        book.comments.forEach { c ->
            val expense = requireNotNull(book.expenses.firstOrNull { it.id == c.expenseId }) { "A comment references a missing expense." }
            require(c.text.trim().length in 1..2000 && c.authorId in groups.getValue(expense.groupId).members && c.time >= 0) { "Invalid comment or author." }
        }
        // Check the complete ledger before it becomes visible to the UI.
        book.groups.forEach { Accounting.balances(book, it.id); Accounting.transfers(book, it.id) }
        Accounting.friendBalances(book)
    }
}
