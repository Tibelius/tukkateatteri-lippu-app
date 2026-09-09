package fi.tukkateatteri.data

import java.math.RoundingMode

fun Int.toEuroString(): String {
    require(this >= 0) { "Amount in cents must not be negative." }
    return "${toDecimalInput()} €"
}

fun Int.toDecimalInput(): String =
    "${this / CENTS_PER_EURO},${(this % CENTS_PER_EURO).toString().padStart(DECIMAL_PLACES, '0')}"

fun String.toEuroCentsOrNull(): Int? {
    val normalizedValue = trim().replace(DECIMAL_COMMA, DECIMAL_POINT)
    if (normalizedValue.isEmpty()) return null
    return runCatching {
        normalizedValue
            .toBigDecimal()
            .movePointRight(DECIMAL_PLACES)
            .setScale(0, RoundingMode.UNNECESSARY)
            .intValueExact()
    }.getOrNull()
}

private const val CENTS_PER_EURO = 100
private const val DECIMAL_PLACES = 2
private const val DECIMAL_COMMA = ','
private const val DECIMAL_POINT = '.'
