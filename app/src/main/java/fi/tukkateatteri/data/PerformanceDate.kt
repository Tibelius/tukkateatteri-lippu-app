package fi.tukkateatteri.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun String.toPerformanceDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim().take(MAXIMUM_DATE_TEXT_LENGTH), PERFORMANCE_DATE_FORMATTER)
} catch (_: DateTimeParseException) {
    null
}

private val PERFORMANCE_DATE_FORMATTER = DateTimeFormatter.ofPattern("d.M.uuuu")
private const val MAXIMUM_DATE_TEXT_LENGTH = 10
