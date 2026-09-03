package fi.tukkateatteri.data

fun Int.toEuroString(): String {
    require(this >= 0) { "Amount in cents must not be negative." }
    return "${this / 100},${(this % 100).toString().padStart(2, '0')} €"
}
