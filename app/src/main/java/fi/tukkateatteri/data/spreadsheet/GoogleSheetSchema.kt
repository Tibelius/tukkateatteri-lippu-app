package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroAmount
import fi.tukkateatteri.data.toPerformanceDateOrNull

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

fun GoogleSheetTab.toReservationSpreadsheetRows(
    candidate: GoogleSheetImportCandidate,
    schema: SheetColumnSchema = toColumnSchema()
): List<ReservationSpreadsheetRow> {
    val headerRowIndex = rows.indexOfFirst { row -> row.any { cell -> cell.canonicalDataHeader() == HEADER_LAST_NAME } }
    if (headerRowIndex < 0) return emptyList()
    val headerIndexes = rows[headerRowIndex].canonicalHeaderIndexes()
    val physicalRows = rows.drop(headerRowIndex + 1)
        .withIndex()
        .takeWhile { (_, row) -> !row.isReservationFooterRow() }
        .mapNotNull { (dataRowIndex, row) ->
            val sourceRowNumber = headerRowIndex + dataRowIndex + 2
            val lastName = row.valueAt(headerIndexes[HEADER_LAST_NAME]).trimSpreadsheetWhitespace()
            val firstName = row.valueAt(headerIndexes[HEADER_FIRST_NAME]).trimSpreadsheetWhitespace()
            val sheetRowId = rowIdsByRowNumber[sourceRowNumber].orEmpty()
            if (lastName.isBlank() && firstName.isBlank()) return@mapNotNull null
            val sourceIdentity = if (sheetRowId.isUuid()) {
                "sheet:$sheetRowId"
            } else if (lastName.isDoorSaleSheetLabel()) {
                listOf(
                    candidate.performanceName.normalizedIdentity(),
                    candidate.date.normalizedIdentity(),
                    DOOR_SALE_SOURCE_KEY,
                    dataRowIndex.toString()
                ).joinToString(SOURCE_IDENTITY_SEPARATOR)
            } else {
                listOf(
                    candidate.performanceName.normalizedIdentity(),
                    candidate.date.normalizedIdentity(),
                    lastName.normalizedIdentity(),
                    firstName.normalizedIdentity(),
                    row.valueAt(headerIndexes[HEADER_CONTACT]).normalizedIdentity()
                ).joinToString(SOURCE_IDENTITY_SEPARATOR)
            }
            val parsedRow = ReservationSpreadsheetRow(
                lastName = lastName,
                firstName = firstName,
                contact = row.valueAt(headerIndexes[HEADER_CONTACT]).trimSpreadsheetWhitespace(),
                reservedSeatCount = 1,
                arrivalCount = row.valueAt(headerIndexes[HEADER_ARRIVAL_COUNT]).toTicketCount(),
                reservedTicketCounts = schema.ticketHeaders.mapNotNull { (ticketType, header) ->
                    row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { ticketType to it }
                }.toMap(),
                paymentAmountsCents = schema.paymentHeaders.mapNotNull { (paymentMethod, header) ->
                    row.valueAt(headerIndexes[header]).toPaymentAmountCents(header, sourceRowNumber)
                        ?.takeIf { it > 0 }
                        ?.let { paymentMethod to it }
                }.toMap(),
                notes = row.valueAt(headerIndexes[HEADER_NOTES]).trimSpreadsheetWhitespace(),
                sourceIdentity = sourceIdentity,
                sheetRowId = sheetRowId,
                sourceRowNumber = sourceRowNumber
            )
            parsedRow
        }
    val groupedRows = physicalRows.groupBy { row ->
        row.sheetRowId.takeIf(String::isUuid)
            ?: if (row.isDoorSale) "door:${row.sourceRowNumber}" else "customer:${row.customerKey()}"
    }
    return groupedRows.values.mapNotNull { seatRows ->
        val parent = seatRows.first()
        val realizedTickets = seatRows.mapNotNull(ReservationSpreadsheetRow::toRealizedTicket)
        val logicalRow = parent.copy(
            reservedSeatCount = seatRows.sumOf { it.reservedSeatCount.coerceAtLeast(1) },
            arrivalCount = seatRows.sumOf(ReservationSpreadsheetRow::arrivalCount),
            reservedTicketCounts = seatRows.flatMap { row ->
                row.reservedTicketTypeFromNote(schema)?.let { listOf(it) }
                    ?: row.reservedTicketCounts.entries.flatMap { (type, quantity) -> List(quantity) { type } }
            }.groupingBy { it }.eachCount(),
            paymentAmountsCents = if (realizedTickets.isEmpty()) {
                seatRows.flatMap { it.paymentAmountsCents.entries }
                    .groupingBy { it.key }
                    .fold(0) { total, entry -> total + entry.value }
            } else {
                emptyMap()
            },
            notes = seatRows.map(ReservationSpreadsheetRow::userNotes)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(SHEET_NOTE_LINE_SEPARATOR),
            realizedTickets = realizedTickets
        )
        val appState = applicationRowStates[logicalRow.sheetRowId]
        val metadataState = when {
            appState == null -> ApplicationMutationMetadataState.NONE
            !appState.isValid || appState.contentHash != logicalRow.sheetContentHash() -> {
                ApplicationMutationMetadataState.INVALID
            }
            else -> ApplicationMutationMetadataState.VALID
        }
        if (
            metadataState == ApplicationMutationMetadataState.VALID &&
            appState?.operation?.normalizedHeader() == APP_OPERATION_DELETE.normalizedHeader()
        ) {
            null
        } else {
            logicalRow.copy(applicationMutationMetadataState = metadataState)
        }
    }
}

private fun ReservationSpreadsheetRow.customerKey(): String = listOf(lastName, firstName, contact)
    .joinToString(SOURCE_IDENTITY_SEPARATOR) { it.normalizedIdentity() }

private fun ReservationSpreadsheetRow.toRealizedTicket(): RealizedTicketSpreadsheetRow? {
    val ticketType = reservedTicketCounts.entries.singleOrNull()
        ?.takeIf { it.value == 1 }
        ?.key ?: return null
    val hasRealization = arrivalCount > 0 || paymentAmountsCents.isNotEmpty()
    if (!hasRealization) return null
    require(paymentAmountsCents.values.sumOf(Int::toLong) <= ticketType.defaultPriceCents) {
        "Rivin $sourceRowNumber maksusumma ylittää lipun hinnan."
    }
    val payments = paymentAmountsCents.map { (method, amountCents) ->
        SpreadsheetPaymentAllocation(method, amountCents)
    }
    return RealizedTicketSpreadsheetRow(ticketType, payments, arrivalCount > 0)
}

private fun ReservationSpreadsheetRow.reservedTicketTypeFromNote(schema: SheetColumnSchema): TicketType? {
    val label = notes.lineSequence()
        .firstOrNull { it.startsWith("$RESERVED_TICKET_LABEL:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
        ?: return null
    return schema.ticketHeaders.keys.firstOrNull { it.label.equals(label, ignoreCase = true) }
}

private fun ReservationSpreadsheetRow.userNotes(): String = notes.lineSequence()
    .filterNot { line ->
        line.startsWith("$RESERVED_TICKET_LABEL:", ignoreCase = true)
    }
    .joinToString(SHEET_NOTE_LINE_SEPARATOR)
    .trim()

internal fun GoogleSheetTab.valueRightOfLabel(label: String): String? = rows.firstNotNullOfOrNull { row ->
    row.indexOfFirst { value -> value.canonicalDataHeader() == label }
        .takeIf { index -> index >= 0 }
        ?.let { index -> row.getOrNull(index + 1)?.trimSpreadsheetWhitespace() }
        ?.takeIf(String::isNotBlank)
}

internal fun String.isDoorSaleSheetLabel(): Boolean {
    val identity = normalizedIdentity()
    return identity == DOOR_SALE_SHEET_LABEL.normalizedIdentity() ||
        identity == DOOR_SALE_CUSTOMER_LABEL.normalizedIdentity()
}

internal fun ReservationSpreadsheetRow.toSheetCellValues(
    sheetTitle: String,
    rowNumber: Int,
    headers: Map<String, Int>,
    schema: SheetColumnSchema = SheetColumnSchema.fromHeaders(headers)
): List<SheetCellValue> = buildList {
    fun set(header: String, value: Any) {
        headers[header]?.let { columnIndex ->
            add(SheetCellValue(sheetCellRange(sheetTitle, columnIndex, rowNumber), value))
        }
    }
    set(HEADER_LAST_NAME, lastName)
    set(HEADER_FIRST_NAME, firstName)
    set(HEADER_CONTACT, contact)
    set(HEADER_ARRIVAL_COUNT, arrivalCount > 0)
    schema.ticketHeaders.forEach { (type, header) ->
        val quantity = reservedTicketCounts.entries.firstOrNull { it.key.name == type.name }?.value
        set(header, quantity != null && quantity > 0)
    }
    schema.paymentHeaders.forEach { (method, header) ->
        val amountCents = paymentAmountsCents.entries.firstOrNull { it.key.name == method.name }?.value
        set(header, amountCents?.takeIf { it > 0 }?.toEuroAmount() ?: "")
    }
    set(HEADER_NOTES, notes)
}

internal data class SheetCellValue(val range: String, val value: Any) {
    init {
        require(value is String || value is Number || value is Boolean) {
            "Only text, booleans and numbers can be written to Google Sheets."
        }
    }
}
