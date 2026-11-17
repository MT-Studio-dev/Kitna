package app.kitna.core

/** Stable identifiers are persisted; Android draws the matching vector symbol. */
object GroupAppearance {
    val icons: List<String> = listOf(
        "group", "home", "plane", "car", "food", "heart",
        "work", "beach", "gift", "games", "shopping", "coffee"
    )
    val colors: Map<String, Long> = linkedMapOf(
        "teal" to 0xFF087F69L,
        "blue" to 0xFF2468BEL,
        "purple" to 0xFF7950ADL,
        "orange" to 0xFFC65B26L,
        "red" to 0xFFBE3A40L,
        "pink" to 0xFFB3447BL,
        "indigo" to 0xFF4D56ADL,
        "amber" to 0xFF9B6A0DL,
        "green" to 0xFF3D803BL,
        "slate" to 0xFF526979L
    )

    fun validate(icon: String, color: String) {
        require(icon in icons) { "Choose a valid group icon." }
        require(color in colors) { "Choose a valid group color." }
    }
}
