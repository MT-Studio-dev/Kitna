package app.kitna.core

import java.util.Locale

/** Offline keyword suggestions. A saved/manual category is never rewritten here. */
object ExpenseCategories {
    private data class Rule(val category: String, val phrases: List<List<String>>)
    private fun rule(category: String, vararg phrases: String) = Rule(category, phrases.map { it.split(' ') })
    private val rules = listOf(
        rule("Travel", "car rental", "rental car", "rent a car", "rent car", "rent a taxi", "rent taxi", "taxi fare", "train fare", "bus fare", "train ticket", "train tickets", "bus ticket", "bus tickets", "flight ticket", "flight tickets", "metro card", "car", "cars", "train", "trains", "taxi", "taxis", "cab", "cabs", "uber", "ola", "auto", "rickshaw", "bus", "buses", "flight", "flights", "airfare", "airport", "rail", "railway", "metro", "petrol", "diesel", "fuel", "toll", "tolls", "parking", "ferry", "ferries", "hotel", "hotels", "hostel", "travel"),
        rule("Groceries", "grocery store", "grocery shopping", "grocery", "groceries", "supermarket", "vegetable", "vegetables", "fruit", "fruits", "carrot", "carrots", "milk", "bread", "rice", "eggs", "flour", "provisions"),
        rule("Food", "food delivery", "restaurant", "restaurants", "cafe", "café", "coffee", "tea", "breakfast", "lunch", "dinner", "brunch", "pizza", "burger", "burgers", "biryani", "sandwich", "sandwiches", "snack", "snacks", "food", "swiggy", "zomato", "takeaway", "dessert", "desserts"),
        rule("Rent", "house rent", "apartment rent", "flat rent", "monthly rent", "room rent", "rent", "lease", "landlord"),
        rule("Utilities", "electricity bill", "water bill", "gas bill", "internet bill", "phone bill", "mobile recharge", "electricity", "electric", "internet", "broadband", "wifi", "wi fi", "utility", "utilities", "recharge", "plumbing"),
        rule("Health", "health insurance", "medical bill", "hospital", "clinic", "doctor", "dentist", "medicine", "medicines", "medical", "pharmacy", "health", "therapy", "surgery", "checkup", "check up"),
        rule("Entertainment", "movie ticket", "movie tickets", "cinema ticket", "cinema tickets", "movie", "movies", "cinema", "concert", "concerts", "netflix", "spotify", "game", "games", "gaming", "bowling", "theatre", "theater", "entertainment", "museum"),
        rule("Shopping", "online shopping", "shopping", "clothes", "clothing", "shirt", "shirts", "shoes", "dress", "dresses", "amazon", "flipkart", "electronics", "furniture", "gift", "gifts", "book", "books", "stationery")
    )
    private val words = Regex("[\\p{L}\\p{N}]+")

    fun suggest(description: String): String? {
        val tokens = words.findAll(description.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        data class Match(val category: String, val length: Int, val start: Int, val priority: Int)
        val matches = buildList {
            rules.forEachIndexed { priority, rule ->
                rule.phrases.forEach { phrase ->
                    for (start in 0..tokens.size - phrase.size) {
                        if (phrase.indices.all { tokens[start + it] == phrase[it] }) add(Match(rule.category, phrase.size, start, priority))
                    }
                }
            }
        }
        // A specific phrase beats an isolated word. Otherwise the first keyword
        // in the user's description decides; stable rule order breaks any tie.
        return matches.sortedWith(compareByDescending<Match> { it.length }.thenBy { it.start }.thenBy { it.priority }).firstOrNull()?.category
    }
}
