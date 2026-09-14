package fi.tukkateatteri.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

fun String.toPerformanceDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim().take(MAXIMUM_DATE_TEXT_LENGTH), PERFORMANCE_DATE_FORMATTER)
} catch (_: DateTimeParseException) {
    null
}

private val PERFORMANCE_DATE_FORMATTER = DateTimeFormatter.ofPattern("d.M.uuuu")
    .withResolverStyle(ResolverStyle.STRICT)
private const val MAXIMUM_DATE_TEXT_LENGTH = 10
