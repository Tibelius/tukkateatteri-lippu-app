package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.MINIMUM_SEAT_COUNT
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toPerformanceDateOrNull
import java.time.Instant
import java.util.UUID

internal fun String.toApiOperation(): String = when {
    contains(":batchUpdate") -> "spreadsheet batch update"
    contains(":append") -> "row append"
    contains("values:batchGet") -> "value batch read"
    contains("values:batchUpdate") -> "value batch update"
    contains("/values/") -> "value read"
    else -> "spreadsheet metadata read"
}

fun GoogleSheetTab.toImportCandidateOrNull(): GoogleSheetImportCandidate? {
    val performanceName = valueRightOfLabel(HEADER_PERFORMANCE) ?: return null
    val date = valueRightOfLabel(HEADER_DATE) ?: return null
    return GoogleSheetImportCandidate(
        sheetTitle = title,
        performanceName = performanceName,
        date = date,
        sortDate = date.toPerformanceDateOrNull()
    )
}

fun GoogleSheetTab.toReservationSpreadsheetRows(candidate: GoogleSheetImportCandidate): List<ReservationSpreadsheetRow> {
    val headerRowIndex = rows.indexOfFirst { row -> row.any { cell -> cell.normalizedHeader() == HEADER_LAST_NAME } }
    if (headerRowIndex < 0) return emptyList()
    val headerIndexes = rows[headerRowIndex].mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    return rows.drop(headerRowIndex + 1).takeWhile { row ->
        row.valueAt(headerIndexes[HEADER_LAST_NAME]).isNotBlank() || row.valueAt(headerIndexes[HEADER_FIRST_NAME]).isNotBlank()
    }.mapIndexedNotNull { dataRowIndex, row ->
        val metadataState = row.applicationMutationMetadataState(headerIndexes)
        if (
            metadataState == ApplicationMutationMetadataState.VALID &&
            row.valueAt(headerIndexes[HEADER_APP_OPERATION]).normalizedHeader() == APP_OPERATION_DELETE.normalizedHeader()
        ) {
            return@mapIndexedNotNull null
        }
        val lastName = row.valueAt(headerIndexes[HEADER_LAST_NAME]).trimSpreadsheetWhitespace()
        val firstName = row.valueAt(headerIndexes[HEADER_FIRST_NAME]).trimSpreadsheetWhitespace()
        if (lastName.isBlank() && firstName.isBlank()) return@mapIndexedNotNull null
        val sheetRowId = row.valueAt(headerIndexes[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace()
        val sourceIdentity = if (sheetRowId.isUuid()) {
            "sheet:$sheetRowId"
        } else if (lastName.isDoorSaleSheetLabel()) {
            "${candidate.performanceName.normalizedIdentity()}|${candidate.date.normalizedIdentity()}|ovelta|$dataRowIndex"
        } else {
            "${candidate.performanceName.normalizedIdentity()}|${candidate.date.normalizedIdentity()}|${lastName.normalizedIdentity()}|${firstName.normalizedIdentity()}"
        }
        ReservationSpreadsheetRow(
            lastName = lastName,
            firstName = firstName,
            contact = row.valueAt(headerIndexes[HEADER_CONTACT]).trimSpreadsheetWhitespace(),
            reservedSeatCount = row.valueAt(headerIndexes[HEADER_RESERVED_COUNT])
                .toTicketCount()
                .coerceAtLeast(MINIMUM_SEAT_COUNT),
            arrivalCount = row.valueAt(headerIndexes[HEADER_ARRIVAL_COUNT]).toTicketCount(),
            reservedTicketCounts = ticketHeaders.mapNotNull { (ticketType, header) ->
                row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { ticketType to it }
            }.toMap(),
            paymentTicketCounts = paymentHeaders.mapNotNull { (paymentMethod, header) ->
                row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { paymentMethod to it }
            }.toMap(),
            notes = row.valueAt(headerIndexes[HEADER_NOTES]).trimSpreadsheetWhitespace(),
            sourceIdentity = sourceIdentity,
            sheetRowId = sheetRowId,
            sourceRowNumber = headerRowIndex + dataRowIndex + 2,
            applicationMutationMetadataState = metadataState
        )
    }
}

internal fun List<String>.applicationMutationMetadataState(headers: Map<String, Int>): ApplicationMutationMetadataState {
    val sheetRowId = valueAt(headers[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace()
    val operation = valueAt(headers[HEADER_APP_OPERATION]).normalizedHeader()
    val modifiedAt = valueAt(headers[HEADER_APP_MODIFIED_AT]).trimSpreadsheetWhitespace()
    val mutationId = valueAt(headers[HEADER_APP_MUTATION_ID]).trimSpreadsheetWhitespace()
    val values = listOf(sheetRowId, operation, modifiedAt, mutationId)
    if (values.all(String::isBlank)) return ApplicationMutationMetadataState.NONE

    val validOperation = operation.isBlank() || operation in VALID_APPLICATION_OPERATIONS
    val validTimestamp = runCatching { Instant.parse(modifiedAt) }.isSuccess
    val validSheetRowId = sheetRowId.isUuid()
    val validMutationId = mutationId.isUuid()
    return if (validOperation && validTimestamp && validSheetRowId && validMutationId) {
        ApplicationMutationMetadataState.VALID
    } else {
        ApplicationMutationMetadataState.INVALID
    }
}

internal fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

internal fun GoogleSheetTab.valueRightOfLabel(label: String): String? = rows.firstNotNullOfOrNull { row ->
    row.indexOfFirst { value -> value.normalizedHeader() == label }
        .takeIf { index -> index >= 0 }
        ?.let { index -> row.getOrNull(index + 1)?.trimSpreadsheetWhitespace() }
        ?.takeIf(String::isNotBlank)
}

internal fun String.isDoorSaleSheetLabel(): Boolean = normalizedIdentity() in setOf(
    DOOR_SALE_SHEET_LABEL.normalizedIdentity(),
    LEGACY_DOOR_SALE_SHEET_LABEL.normalizedIdentity()
)

internal fun ReservationSpreadsheetRow.toSheetCellValues(
    sheetTitle: String,
    rowNumber: Int,
    headers: Map<String, Int>,
    sheetRowId: String,
    operation: String?
): List<SheetCellValue> = buildList {
    fun set(header: String, value: Any) {
        headers[header]?.let { columnIndex ->
            add(SheetCellValue(sheetCellRange(sheetTitle, columnIndex, rowNumber), value))
        }
    }
    set(HEADER_LAST_NAME, lastName)
    set(HEADER_FIRST_NAME, firstName)
    set(HEADER_CONTACT, contact)
    set(HEADER_RESERVED_COUNT, reservedSeatCount)
    set(HEADER_ARRIVAL_COUNT, arrivalCount)
    ticketHeaders.forEach { (type, header) -> set(header, reservedTicketCounts[type] ?: "") }
    paymentHeaders.forEach { (method, header) -> set(header, paymentTicketCounts[method] ?: "") }
    set(HEADER_NOTES, notes)
    set(HEADER_SHEET_ROW_ID, sheetRowId)
    operation?.let { set(HEADER_APP_OPERATION, it) }
    set(HEADER_APP_MODIFIED_AT, Instant.now().toString())
    set(HEADER_APP_MUTATION_ID, UUID.randomUUID().toString())
}

internal fun deletedRowCellValues(
    sheetTitle: String,
    rowNumber: Int,
    headers: Map<String, Int>,
    mutationId: String
): List<SheetCellValue> = buildList {
    fun set(header: String, value: Any) {
        headers[header]?.let { columnIndex ->
            add(SheetCellValue(sheetCellRange(sheetTitle, columnIndex, rowNumber), value))
        }
    }
    set(HEADER_RESERVED_COUNT, 0)
    set(HEADER_ARRIVAL_COUNT, 0)
    ticketHeaders.values.forEach { set(it, 0) }
    paymentHeaders.values.forEach { set(it, 0) }
    set(HEADER_APP_OPERATION, APP_OPERATION_DELETE)
    set(HEADER_APP_MODIFIED_AT, Instant.now().toString())
    set(HEADER_APP_MUTATION_ID, mutationId)
}

internal data class SheetCellValue(val range: String, val value: Any) {
    init {
        require(value is String || value is Int) { "Only text and whole numbers can be written to Google Sheets." }
    }
}

internal data class LockRow(
    val performanceKey: String,
    val rowNumber: Int,
    val lockId: String,
    val expiresAt: Instant?,
    val deviceId: String
)

internal data class LockTable(
    val headers: Map<String, Int>,
    val rows: List<LockRow>,
    val rowsByPerformanceKey: Map<String, LockRow>
) {
    fun activeLockFor(performanceKey: String, now: Instant): LockRow? = rows.lastOrNull { lock ->
        lock.performanceKey == performanceKey && lock.expiresAt?.isAfter(now) == true
    }

    fun valuesFor(
        rowNumber: Int,
        performanceKey: String,
        lockId: String,
        lockedAt: String,
        expiresAt: String,
        deviceId: String = ""
    ): List<SheetCellValue> = buildList {
        fun set(header: String, value: Any) {
            headers[header]?.let { columnIndex ->
                add(SheetCellValue(sheetCellRange(LOCK_SHEET_TITLE, columnIndex, rowNumber), value))
            }
        }
        set(LOCK_HEADER_PERFORMANCE_ID, performanceKey)
        set(LOCK_HEADER_UUID, lockId)
        set(LOCK_HEADER_LOCKED_AT, lockedAt)
        set(LOCK_HEADER_EXPIRES_AT, expiresAt)
        set(LOCK_HEADER_DEVICE_LABEL, deviceId)
    }

    fun rowValuesFor(
        performanceKey: String,
        lockId: String,
        lockedAt: String,
        expiresAt: String,
        deviceId: String
    ): List<String> = MutableList((headers.values.maxOrNull() ?: -1) + 1) { "" }.apply {
        fun set(header: String, value: String) {
            headers[header]?.let { columnIndex -> this@apply[columnIndex] = value }
        }
        set(LOCK_HEADER_PERFORMANCE_ID, performanceKey)
        set(LOCK_HEADER_UUID, lockId)
        set(LOCK_HEADER_LOCKED_AT, lockedAt)
        set(LOCK_HEADER_EXPIRES_AT, expiresAt)
        set(LOCK_HEADER_DEVICE_LABEL, deviceId)
    }

    fun clearValuesFor(rowNumber: Int): List<SheetCellValue> = REQUIRED_LOCK_HEADERS.mapNotNull { header ->
        headers[header]?.let { columnIndex ->
            SheetCellValue(sheetCellRange(LOCK_SHEET_TITLE, columnIndex, rowNumber), "")
        }
    }
}

internal fun List<List<String>>.toLockTable(): LockTable {
    val headerRowIndex = indexOfFirst { row -> row.any { it.normalizedHeader() == LOCK_HEADER_PERFORMANCE_ID } }
    require(headerRowIndex >= 0) { "Sovelluslukot-välilehdeltä puuttuu performance_id-sarake." }
    val headers = get(headerRowIndex).mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    require(REQUIRED_LOCK_HEADERS.all(headers::containsKey)) {
        "Sovelluslukot-välilehden otsikot eivät vastaa sovittua muotoa."
    }
    val rows = drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
        val performanceKey = row.valueAt(headers[LOCK_HEADER_PERFORMANCE_ID]).trimSpreadsheetWhitespace()
        performanceKey.takeIf(String::isNotBlank)?.let {
            LockRow(
                performanceKey = performanceKey,
                rowNumber = headerRowIndex + index + 2,
                lockId = row.valueAt(headers[LOCK_HEADER_UUID]).trimSpreadsheetWhitespace(),
                expiresAt = runCatching {
                    Instant.parse(row.valueAt(headers[LOCK_HEADER_EXPIRES_AT]).trimSpreadsheetWhitespace())
                }.getOrNull(),
                deviceId = row.valueAt(headers[LOCK_HEADER_DEVICE_LABEL]).trimSpreadsheetWhitespace()
            )
        }
    }
    return LockTable(
        headers = headers,
        rows = rows,
        rowsByPerformanceKey = rows.associateBy(LockRow::performanceKey)
    )
}

internal fun String.toNameKeyOrNull(): Pair<String, String>? = split("|")
    .takeIf { it.size >= 4 }
    ?.let { parts -> parts[parts.lastIndex - 1] to parts.last() }

internal fun String.toLegacyDoorSaleDataRowIndexOrNull(): Int? = split("|")
    .takeIf { parts -> parts.size == 4 && parts[2] == "ovelta" }
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
            append(('A'.code + (value % 26)).toChar())
            value /= 26
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

internal fun String.normalizedIdentity(): String = trimSpreadsheetWhitespace()
    .replace(SPREADSHEET_WHITESPACE_REGEX, " ")
    .lowercase()

internal fun String.trimSpreadsheetWhitespace(): String = trim { character ->
    character.isWhitespace() || character == NON_BREAKING_SPACE
}

internal fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

internal fun String.toTicketCount(): Int {
    val normalizedValue = trimSpreadsheetWhitespace().lowercase()
    return normalizedValue.toIntOrNull() ?: if (normalizedValue in TICKET_MARKERS) 1 else 0
}

internal val SPREADSHEET_ID_REGEX = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)")
internal val SPREADSHEET_WHITESPACE_REGEX = Regex("[\\s\\u00A0]+")
internal val TICKET_MARKERS = setOf("x", "✓", "k")
internal const val NON_BREAKING_SPACE = '\u00A0'

internal fun performanceLockKey(spreadsheetId: String, sheetTitle: String): String =
    "$spreadsheetId|${sheetTitle.trimSpreadsheetWhitespace()}"

internal const val LOCK_SHEET_TITLE = "Sovelluslukot"
internal const val HEADER_LAST_NAME = "sukunimi"
internal const val HEADER_FIRST_NAME = "etunimi"
internal const val HEADER_CONTACT = "yhteystiedot"
internal const val HEADER_RESERVED_COUNT = "varatut liput kpl"
internal const val HEADER_ARRIVAL_COUNT = "saapunut esitykseen eli lunastettujen lippujen lukumäärä"
internal const val HEADER_NOTES = "huom! (merkitse tähän esim. vapaalipun peruste, joka voi olla työryhmävapaalippu, kaikukortti, kutsu tms. sekä muut huomioitavat asiat)"
internal const val HEADER_SHEET_ROW_ID = "sovellus-id"
internal const val HEADER_APP_OPERATION = "sovellus-toiminto"
internal const val HEADER_APP_MODIFIED_AT = "sovellus-muokattu"
internal const val HEADER_APP_MUTATION_ID = "sovellus-muokkaus-id"
internal const val HEADER_PERFORMANCE = "esitys:"
internal const val HEADER_DATE = "pvm:"
internal const val HEADER_RESERVATION_TOTAL = "varaukset yhteensä"
internal const val APP_OPERATION_ADD = "Lisäys"
internal const val APP_OPERATION_DELETE = "Poisto"
internal val VALID_APPLICATION_OPERATIONS = setOf(
    APP_OPERATION_ADD.normalizedHeader(),
    APP_OPERATION_DELETE.normalizedHeader()
)
internal val APPLICATION_MUTATION_METADATA_HEADERS = listOf(
    HEADER_APP_OPERATION,
    HEADER_APP_MODIFIED_AT,
    HEADER_APP_MUTATION_ID
)
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

internal val ticketHeaders = mapOf(
    TicketType.BASIC to "perus 22 €",
    TicketType.DISCOUNT to "alennus 13 €",
    TicketType.THEATRE_INDUSTRY to "teatteriala 10 €",
    TicketType.MEMBER to "jäsen 5 €",
    TicketType.GROUP_BASIC to "ryhmä perus 20 €",
    TicketType.GROUP_DISCOUNT to "ryhmä alennus 12 €",
    TicketType.KAIKUKORTTI to "kaikukortti",
    TicketType.FREE_TICKET to "vapaalippu"
)

internal val paymentHeaders = mapOf(
    PaymentMethod.CARD to "kortti",
    PaymentMethod.CASH to "käteinen",
    PaymentMethod.EPASSI to "epassi",
    PaymentMethod.LIPPUAGENTTI to "lippuagentti"
)

