package fi.tukkateatteri.data

data class Performance(
    val id: Long,
    val actName: String,
    val date: String,
    val isActive: Boolean
) {
    init {
        require(id > 0) { "Performance ID must be positive." }
        require(actName.isNotBlank()) { "Performance name must not be blank." }
    }

    val displayName: String
        get() = listOf(actName, date).filter(String::isNotBlank).joinToString(" ")
}
