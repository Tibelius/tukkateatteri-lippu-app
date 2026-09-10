package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import java.util.Base64

/** Stable local serialization used by the Room-only Google Sheets outbox. */
fun ReservationSpreadsheetRow.toSnapshotJson(): String = listOf(
    lastName.encoded(),
    firstName.encoded(),
    contact.encoded(),
    reservedSeatCount.toString(),
    arrivalCount.toString(),
    reservedTicketCounts.encodedTicketTypeCounts(),
    paymentTicketCounts.encodedPaymentMethodCounts(),
    notes.encoded(),
    sourceIdentity.encoded(),
    sheetRowId.encoded()
).joinToString(SNAPSHOT_FIELD_SEPARATOR)

fun String.toReservationSpreadsheetRowSnapshot(): ReservationSpreadsheetRow {
    val values = split(SNAPSHOT_FIELD_SEPARATOR)
    require(values.size == SNAPSHOT_FIELD_COUNT) { "Virheellinen paikallisen synkronointimuutoksen muoto." }
    return ReservationSpreadsheetRow(
        lastName = values[0].decoded(),
        firstName = values[1].decoded(),
        contact = values[2].decoded(),
        reservedSeatCount = values[3].toInt(),
        arrivalCount = values[4].toInt(),
        reservedTicketCounts = values[5].toTicketTypeCounts(),
        paymentTicketCounts = values[6].toPaymentMethodCounts(),
        notes = values[7].decoded(),
        sourceIdentity = values[8].decoded(),
        sheetRowId = values[9].decoded()
    )
}

/**
 * Identity is deliberately excluded: legacy rows may gain a Sovellus-ID during export without
 * changing the actual reservation. All operational values must still match exactly.
 */
fun ReservationSpreadsheetRow.hasSameSheetContentAs(other: ReservationSpreadsheetRow): Boolean =
    lastName == other.lastName &&
        firstName == other.firstName &&
        contact == other.contact &&
        reservedSeatCount == other.reservedSeatCount &&
        arrivalCount == other.arrivalCount &&
        reservedTicketCounts == other.reservedTicketCounts &&
        paymentTicketCounts == other.paymentTicketCounts &&
        notes == other.notes

private fun String.encoded(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(toByteArray(Charsets.UTF_8))

private fun String.decoded(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)

private fun Map<TicketType, Int>.encodedTicketTypeCounts(): String = entries
    .sortedBy { (type, _) -> type.name }
    .joinToString(SNAPSHOT_COUNT_SEPARATOR) { (type, quantity) ->
        listOf(type.name, type.label, type.defaultPriceCents, type.sortOrder)
            .joinToString(SNAPSHOT_CONFIG_SEPARATOR)
            .encoded() + ":$quantity"
    }

private fun Map<PaymentMethod, Int>.encodedPaymentMethodCounts(): String = entries
    .sortedBy { (method, _) -> method.name }
    .joinToString(SNAPSHOT_COUNT_SEPARATOR) { (method, quantity) ->
        listOf(method.name, method.label, method.allowsSplitPayment, method.sortOrder)
            .joinToString(SNAPSHOT_CONFIG_SEPARATOR)
            .encoded() + ":$quantity"
    }

private fun String.toTicketTypeCounts(): Map<TicketType, Int> = configCountPairs().mapNotNull { (encoded, quantity) ->
    val values = encoded.decoded().split(SNAPSHOT_CONFIG_SEPARATOR)
    if (values.size != 4) return@mapNotNull null
    TicketType(values[0], values[1], values[2].toInt(), values[3].toInt()) to quantity
}.toMap()

private fun String.toPaymentMethodCounts(): Map<PaymentMethod, Int> = configCountPairs().mapNotNull { (encoded, quantity) ->
    val values = encoded.decoded().split(SNAPSHOT_CONFIG_SEPARATOR)
    if (values.size != 4) return@mapNotNull null
    PaymentMethod(values[0], values[1], values[2].toBoolean(), values[3].toInt()) to quantity
}.toMap()

private fun String.configCountPairs(): List<Pair<String, Int>> = takeIf(String::isNotBlank)
    ?.split(SNAPSHOT_COUNT_SEPARATOR)
    ?.map { entry ->
        val (encoded, quantity) = entry.split(":", limit = 2)
        encoded to quantity.toInt()
    }
    .orEmpty()

private const val SNAPSHOT_FIELD_SEPARATOR = "|"
private const val SNAPSHOT_COUNT_SEPARATOR = ","
private const val SNAPSHOT_CONFIG_SEPARATOR = "\u001F"
private const val SNAPSHOT_FIELD_COUNT = 10
