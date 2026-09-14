package fi.tukkateatteri.data.spreadsheet

import java.util.UUID

internal fun String.toApiOperation(): String = when {
    contains("values:batchGet") -> "value batch read"
    contains("values:batchUpdate") -> "value batch update"
    contains(":batchUpdate") -> "spreadsheet batch update"
    contains(":append") -> "row append"
    contains("/values/") -> "value read"
    else -> "spreadsheet metadata read"
}

internal fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

internal data class SourceCustomerIdentity(
    val lastName: String,
    val firstName: String,
    val contact: String?
) {
    fun matches(lastName: String, firstName: String, contact: String): Boolean =
        this.lastName == lastName &&
            this.firstName == firstName &&
            (this.contact == null || this.contact == contact)
}

internal fun String.toCustomerIdentityOrNull(): SourceCustomerIdentity? = split(SOURCE_IDENTITY_SEPARATOR)
    .takeIf { parts ->
        parts.size >= CUSTOMER_IDENTITY_MINIMUM_FIELD_COUNT &&
            parts[CUSTOMER_LAST_NAME_INDEX] != DOOR_SALE_SOURCE_KEY
    }
    ?.let { parts ->
        SourceCustomerIdentity(
            lastName = parts[CUSTOMER_LAST_NAME_INDEX],
            firstName = parts[CUSTOMER_FIRST_NAME_INDEX],
            contact = parts.getOrNull(CUSTOMER_CONTACT_INDEX)
        )
    }

internal fun String.toLegacyDoorSaleDataRowIndexOrNull(): Int? = split(SOURCE_IDENTITY_SEPARATOR)
    .takeIf { parts -> parts.size == LEGACY_DOOR_SALE_ID_FIELD_COUNT && parts[2] == DOOR_SALE_SOURCE_KEY }
    ?.lastOrNull()
    ?.toIntOrNull()

internal fun sheetCellRange(sheetTitle: String, columnIndex: Int, rowNumber: Int): String =
    "${sheetTitle.toQuotedSheetName()}!${columnIndex.toColumnName()}$rowNumber"

internal fun String.toQuotedSheetName(): String = "'${replace("'", "''")}'"

internal fun Int.toColumnName(): String {
    var value = this + 1
    return buildString {
        while (value > 0) {
            value -= 1
            append(('A'.code + (value % ALPHABET_SIZE)).toChar())
            value /= ALPHABET_SIZE
        }
    }.reversed()
}

internal fun String.toSpreadsheetId(): String {
    val match = SPREADSHEET_ID_REGEX.find(this)
        ?: throw IllegalArgumentException("Google Sheets -osoite ei ole kelvollinen.")
    return match.groupValues[1]
}

internal fun String.normalizedHeader(): String = trimSpreadsheetWhitespace()
    .replace(SPREADSHEET_WHITESPACE_REGEX, " ")
    .lowercase()
    .replace("*", "")
    .trim()

internal fun String.canonicalDataHeader(): String {
    val normalized = normalizedHeader()
    return when {
        normalized.startsWith("saapunut") -> HEADER_ARRIVAL_COUNT
        normalized.startsWith("huom") -> HEADER_NOTES
        normalized == "esitys" -> HEADER_PERFORMANCE
        normalized == "pvm" -> HEADER_DATE
        else -> normalized
    }
}

internal fun String.normalizedIdentity(): String = trimSpreadsheetWhitespace()
    .replace(SPREADSHEET_WHITESPACE_REGEX, " ")
    .lowercase()

internal fun String.isReservationSummaryLabel(): Boolean {
    val value = normalizedHeader()
    return value.startsWith("varaukset yhteensä") || value.startsWith("varauksia:")
}

internal fun String.trimSpreadsheetWhitespace(): String = trim { character ->
    character.isWhitespace() || character == NON_BREAKING_SPACE
}

internal fun List<String>.valueAt(index: Int?): String = index?.let(::getOrNull).orEmpty()

internal fun String.toTicketCount(): Int {
    val normalizedValue = trimSpreadsheetWhitespace().lowercase()
    return if (normalizedValue.isBlank() || normalizedValue in UNCHECKED_MARKERS) 0 else 1
}

internal fun performanceLockKey(spreadsheetId: String, sheetTitle: String): String =
    listOf(spreadsheetId, sheetTitle.trimSpreadsheetWhitespace()).joinToString(LOCK_KEY_SEPARATOR)

internal val SPREADSHEET_ID_REGEX = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)")
internal val SPREADSHEET_WHITESPACE_REGEX = Regex("[\\s\\u00A0]+")
internal val UNCHECKED_MARKERS = setOf("false", "0")
internal const val NON_BREAKING_SPACE = '\u00A0'

internal const val APPLICATION_SHEET_TITLE = "Sovellus"
internal const val LOCK_SHEET_TITLE = APPLICATION_SHEET_TITLE
internal const val ALIAS_HEADER = "alias"
internal const val ALIAS_NORMALIZED_HEADER = "normalized_alias"
internal const val ALIAS_KIND_HEADER = "field_type"
internal const val ALIAS_LABEL_HEADER = "display_name"
internal const val ALIAS_PRICE_HEADER = "price_cents"
internal const val ALIAS_OPTIONS_HEADER = "options"
internal val REQUIRED_ALIAS_HEADERS = listOf(
    ALIAS_HEADER,
    ALIAS_NORMALIZED_HEADER,
    ALIAS_KIND_HEADER,
    ALIAS_LABEL_HEADER,
    ALIAS_PRICE_HEADER,
    ALIAS_OPTIONS_HEADER
)
internal const val HEADER_LAST_NAME = "sukunimi"
internal const val HEADER_FIRST_NAME = "etunimi"
internal const val HEADER_CONTACT = "yhteystiedot"
internal const val HEADER_ARRIVAL_COUNT = "saapunut esitykseen eli lunastettujen lippujen lukumäärä"
internal const val HEADER_NOTES =
    "huom! (merkitse tähän esim. vapaalipun peruste, joka voi olla työryhmävapaalippu, " +
        "kaikukortti, kutsu tms. sekä muut huomioitavat asiat)"
internal const val HEADER_PERFORMANCE = "esitys:"
internal const val HEADER_DATE = "pvm:"
internal const val LEGACY_DOOR_SALE_SHEET_LABEL = "Ovelta"
internal const val LOCK_HEADER_PERFORMANCE_ID = "performance_id"
internal const val LOCK_HEADER_UUID = "lock_uuid"
internal const val LOCK_HEADER_LOCKED_AT = "locked_at"
internal const val LOCK_HEADER_EXPIRES_AT = "expires_at"
internal const val LOCK_HEADER_DEVICE_LABEL = "device_label"
internal val REQUIRED_LOCK_HEADERS = listOf(
    LOCK_HEADER_PERFORMANCE_ID,
    LOCK_HEADER_UUID,
    LOCK_HEADER_LOCKED_AT,
    LOCK_HEADER_EXPIRES_AT,
    LOCK_HEADER_DEVICE_LABEL
)
internal const val LOCK_TABLE_START_COLUMN = 7
internal const val SOURCE_IDENTITY_SEPARATOR = "|"
internal const val DOOR_SALE_SOURCE_KEY = "ovelta"

private const val ALPHABET_SIZE = 26
private const val CUSTOMER_IDENTITY_MINIMUM_FIELD_COUNT = 4
private const val CUSTOMER_LAST_NAME_INDEX = 2
private const val CUSTOMER_FIRST_NAME_INDEX = 3
private const val CUSTOMER_CONTACT_INDEX = 4
private const val LEGACY_DOOR_SALE_ID_FIELD_COUNT = 4
private const val LOCK_KEY_SEPARATOR = "|"
