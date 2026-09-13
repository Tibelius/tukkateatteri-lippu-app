package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroCentsOrNull
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
    val headerIndexes = rows[headerRowIndex]
        .mapIndexed { index, header -> header.canonicalDataHeader() to index }
        .toMap()
    val physicalRows = rows.drop(headerRowIndex + 1)
        .withIndex()
        .takeWhile { (dataRowIndex, row) ->
            val sourceRowNumber = headerRowIndex + dataRowIndex + 2
            row.valueAt(headerIndexes[HEADER_LAST_NAME]).isNotBlank() ||
                row.valueAt(headerIndexes[HEADER_FIRST_NAME]).isNotBlank() ||
                rowIdsByRowNumber[sourceRowNumber].orEmpty().isUuid()
        }
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
                paymentTicketCounts = schema.paymentHeaders.mapNotNull { (paymentMethod, header) ->
                    row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { paymentMethod to it }
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
        val realizedTickets = seatRows.mapNotNull { it.toRealizedTicket(schema) }
        val logicalRow = parent.copy(
            reservedSeatCount = seatRows.sumOf { it.reservedSeatCount.coerceAtLeast(1) },
            arrivalCount = seatRows.sumOf(ReservationSpreadsheetRow::arrivalCount),
            reservedTicketCounts = seatRows.flatMap { row ->
                row.reservedTicketTypeFromNote(schema)?.let { listOf(it) }
                    ?: row.reservedTicketCounts.entries.flatMap { (type, quantity) -> List(quantity) { type } }
            }.groupingBy { it }.eachCount(),
            paymentTicketCounts = if (realizedTickets.isEmpty()) {
                seatRows.flatMap { it.paymentTicketCounts.entries }
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

private fun ReservationSpreadsheetRow.toRealizedTicket(
    schema: SheetColumnSchema
): RealizedTicketSpreadsheetRow? {
    val ticketType = reservedTicketCounts.entries.singleOrNull()
        ?.takeIf { it.value == 1 }
        ?.key ?: return null
    val notePayments = notes.toPaymentAllocations(schema.paymentHeaders.keys)
    val hasRealization = arrivalCount > 0 || notePayments.isNotEmpty() || paymentTicketCounts.isNotEmpty()
    if (!hasRealization) return null
    val payments = if (notePayments.isNotEmpty()) {
        notePayments
    } else {
        paymentTicketCounts.entries.singleOrNull()
            ?.takeIf { it.value == 1 }
            ?.let { (method, _) ->
                listOf(SpreadsheetPaymentAllocation(method, ticketType.defaultPriceCents))
            }.orEmpty()
    }
    return RealizedTicketSpreadsheetRow(ticketType, payments, arrivalCount > 0)
}

private fun String.toPaymentAllocations(methods: Set<PaymentMethod>): List<SpreadsheetPaymentAllocation> {
    val paymentLine = lineSequence().firstOrNull { it.startsWith("$PARTIAL_PAYMENT_LABEL:", ignoreCase = true) }
        ?: return emptyList()
    return paymentLine.substringAfter(':').split(PAYMENT_NOTE_SEPARATOR).mapNotNull { value ->
        val method = methods.sortedByDescending { it.label.length }
            .firstOrNull { value.trim().startsWith(it.label, ignoreCase = true) } ?: return@mapNotNull null
        val amount = value.trim().substring(method.label.length).trim()
            .removeSuffix("€").trim().toEuroCentsOrNull() ?: return@mapNotNull null
        SpreadsheetPaymentAllocation(method, amount)
    }
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
        line.startsWith("$PARTIAL_PAYMENT_LABEL:", ignoreCase = true) ||
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

internal fun String.isDoorSaleSheetLabel(): Boolean = normalizedIdentity() in setOf(
    DOOR_SALE_SHEET_LABEL.normalizedIdentity(),
    "Ovimyynti".normalizedIdentity(),
    LEGACY_DOOR_SALE_SHEET_LABEL.normalizedIdentity()
)

internal fun ReservationSpreadsheetRow.toSheetCellValues(
    sheetTitle: String,
    rowNumber: Int,
    headers: Map<String, Int>
): List<SheetCellValue> = buildList {
    val schema = SheetColumnSchema.fromHeaders(headers)
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
        val quantity = paymentTicketCounts.entries.firstOrNull { it.key.name == method.name }?.value
        set(header, quantity != null && quantity > 0)
    }
    set(HEADER_NOTES, notes)
}

internal data class SheetCellValue(val range: String, val value: Any) {
    init {
        require(value is String || value is Int || value is Boolean) {
            "Only text, booleans and whole numbers can be written to Google Sheets."
        }
    }
}

data class SheetColumnSchema(
    val ticketHeaders: Map<TicketType, String>,
    val paymentHeaders: Map<PaymentMethod, String>
) {
    val ticketTypes: List<TicketType>
        get() = ticketHeaders.keys.sortedBy(TicketType::sortOrder)

    val paymentMethods: List<PaymentMethod>
        get() = paymentHeaders.keys.sortedBy(PaymentMethod::sortOrder)

    companion object {
        internal fun fromHeaders(headers: Map<String, Int>): SheetColumnSchema =
            fromOrderedHeaders(headers.entries.sortedBy(Map.Entry<String, Int>::value).map(Map.Entry<String, Int>::key))

        internal fun fromOrderedHeaders(
            headers: List<String>,
            storedAliases: Map<String, StoredSheetAlias> = emptyMap()
        ): SheetColumnSchema {
            val normalized = headers.map(String::canonicalDataHeader)
            val arrivalIndex = normalized.indexOf(HEADER_ARRIVAL_COUNT)
            val notesIndex = normalized.indexOf(HEADER_NOTES).takeIf { it >= 0 } ?: normalized.size
            val dataEnd = notesIndex
            val paymentStart = normalized.withIndex()
                .firstOrNull { (index, header) ->
                    index > arrivalIndex && (
                        header in KNOWN_PAYMENT_ALIASES ||
                            storedAliases[header]?.kind == SheetFieldClassification.PAYMENT
                    )
                }
                ?.index ?: run {
                    val candidates = headers.indices
                        .filter { it > arrivalIndex && it < dataEnd }
                        .map { headers[it].trimSpreadsheetWhitespace() }
                        .filter(String::isNotBlank)
                    val unresolved = candidates.filter { header ->
                        header.normalizedHeader() !in storedAliases && !header.isObviousTicketHeader()
                    }
                    if (unresolved.isNotEmpty()) throw UnmappedSheetColumnsException(unresolved)
                    dataEnd
                }
            val ticketRange = normalized.indices.filter { it > arrivalIndex && it < paymentStart }
            val paymentRange = normalized.indices.filter { it >= paymentStart && it < dataEnd }
            val ambiguousTicketHeaders = ticketRange.map { headers[it].trimSpreadsheetWhitespace() }
                .filter(String::isNotBlank)
                .filter { header ->
                    val storedKind = storedAliases[header.normalizedHeader()]?.kind
                    storedKind == null && !header.isObviousTicketHeader()
                }
            if (ambiguousTicketHeaders.isNotEmpty()) {
                throw UnmappedSheetColumnsException(ambiguousTicketHeaders)
            }
            return SheetColumnSchema(
                ticketHeaders = ticketRange.mapNotNull { index ->
                    headers[index]
                        .takeIf(String::isNotBlank)
                        ?.takeUnless {
                            storedAliases[normalized[index]]?.kind == SheetFieldClassification.IGNORE
                        }
                        ?.toTicketDefinition(index)
                        ?.let { it to normalized[index] }
                }.toMap(),
                paymentHeaders = paymentRange.mapNotNull { index ->
                    headers[index]
                        .takeIf(String::isNotBlank)
                        ?.takeUnless {
                            storedAliases[normalized[index]]?.kind == SheetFieldClassification.IGNORE
                        }
                        ?.toPaymentDefinition(index, storedAliases[normalized[index]])
                        ?.let { it to normalized[index] }
                }.toMap()
            )
        }
    }
}

class UnmappedSheetColumnsException(val headers: List<String>) :
    IllegalStateException("Spreadsheet columns need classification: ${headers.joinToString()}")

enum class SheetFieldClassification { TICKET, PAYMENT, IGNORE }

data class SheetFieldMapping(
    val header: String,
    val classification: SheetFieldClassification
)

internal fun GoogleSheetTab.toColumnSchema(
    storedAliases: Map<String, StoredSheetAlias> = emptyMap()
): SheetColumnSchema {
    val headerRow = rows.firstOrNull { row -> row.any { it.canonicalDataHeader() == HEADER_LAST_NAME } }.orEmpty()
    return SheetColumnSchema.fromOrderedHeaders(headerRow, storedAliases)
}

internal data class StoredSheetAlias(
    val kind: SheetFieldClassification,
    val label: String,
    val priceCents: Int?,
    val allowsSplitPayment: Boolean
)

internal fun GoogleSheetTab.storedAliases(): Map<String, StoredSheetAlias> {
    val headerIndex = rows.indexOfFirst { row -> row.any { it.normalizedHeader() == ALIAS_NORMALIZED_HEADER } }
    if (headerIndex < 0) return emptyMap()
    val indexes = rows[headerIndex].mapIndexed { index, value -> value.normalizedHeader() to index }.toMap()
    return rows.drop(headerIndex + 1).mapNotNull { row ->
        val alias = row.valueAt(indexes[ALIAS_NORMALIZED_HEADER]).normalizedHeader()
        val kind = row.valueAt(indexes[ALIAS_KIND_HEADER]).toSheetFieldClassificationOrNull()
        if (alias.isBlank() || kind == null) return@mapNotNull null
        alias to StoredSheetAlias(
            kind = kind,
            label = row.valueAt(indexes[ALIAS_LABEL_HEADER]).trimSpreadsheetWhitespace(),
            priceCents = row.valueAt(indexes[ALIAS_PRICE_HEADER]).toIntOrNull(),
            allowsSplitPayment = !row.valueAt(indexes[ALIAS_OPTIONS_HEADER])
                .contains(DISABLE_SPLIT_PAYMENT_OPTION, ignoreCase = true)
        )
    }.toMap()
}

private fun String.toSheetFieldClassificationOrNull(): SheetFieldClassification? {
    val storedValue = trimSpreadsheetWhitespace().uppercase()
    return SheetFieldClassification.entries.firstOrNull { it.name == storedValue }
}

internal fun String.toTicketDefinition(columnIndex: Int): TicketType {
    val header = normalizedDisplayHeader()
    val match = TICKET_HEADER_PATTERN.matchEntire(header)
    val label = match?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
        ?: header
    val priceText = match?.groupValues?.get(2).orEmpty().replace(',', '.')
    val priceCents = priceText.toBigDecimalOrNull()?.movePointRight(2)?.toInt() ?: 0
    return KNOWN_TICKET_DEFINITIONS[label.normalizedIdentity()]?.copy(
        label = label,
        defaultPriceCents = priceCents,
        sortOrder = columnIndex
    ) ?: TicketType(
        name = "SHEET_TICKET:${label.normalizedIdentity()}",
        label = label,
        defaultPriceCents = priceCents,
        sortOrder = columnIndex
    )
}

private fun String.toPaymentDefinition(columnIndex: Int, storedAlias: StoredSheetAlias? = null): PaymentMethod {
    val normalized = normalizedHeader()
    return KNOWN_PAYMENT_DEFINITIONS[normalized]?.copy(sortOrder = columnIndex)
        ?: PaymentMethod(
            name = "SHEET_PAYMENT:$normalized",
            label = storedAlias?.label?.ifBlank { null } ?: normalizedDisplayHeader(),
            allowsSplitPayment = storedAlias?.allowsSplitPayment ?: true,
            sortOrder = columnIndex
        )
}

private val TICKET_HEADER_PATTERN = Regex(
    pattern = "^(.+?)(?:\\s+(\\d+(?:[,.]\\d{1,2})?)\\s*(?:€|eur))?$",
    option = RegexOption.IGNORE_CASE
)
private fun String.isObviousTicketHeader(): Boolean {
    val normalized = normalizedHeader()
    val header = normalizedDisplayHeader()
    val hasExplicitPrice = TICKET_HEADER_PATTERN.matchEntire(header)
        ?.groupValues?.get(2)?.isNotBlank() == true
    val parsedLabel = TICKET_HEADER_PATTERN.matchEntire(header)
        ?.groupValues?.get(1)?.normalizedIdentity()
    return hasExplicitPrice || normalized in KNOWN_TICKET_DEFINITIONS || parsedLabel in KNOWN_TICKET_DEFINITIONS
}

private fun String.normalizedDisplayHeader(): String = trimSpreadsheetWhitespace()
    .replace(SPREADSHEET_WHITESPACE_REGEX, " ")
private val KNOWN_TICKET_DEFINITIONS = mapOf(
    "perus" to TicketType.BASIC,
    "alennus" to TicketType.DISCOUNT,
    "teatteriala" to TicketType.THEATRE_INDUSTRY,
    "jäsen" to TicketType.MEMBER,
    "ryhmä perus" to TicketType.GROUP_BASIC,
    "ryhmä alennus" to TicketType.GROUP_DISCOUNT,
    "kaikukortti" to TicketType.KAIKUKORTTI,
    "vapaalippu" to TicketType.FREE_TICKET
)
private val KNOWN_PAYMENT_DEFINITIONS = mapOf(
    "kortti" to PaymentMethod.CARD,
    "käteinen" to PaymentMethod.CASH,
    "epassi" to PaymentMethod.EPASSI,
    "lippuagentti" to PaymentMethod.LIPPUAGENTTI
)

internal const val DISABLE_SPLIT_PAYMENT_OPTION = "split=false"
private val KNOWN_PAYMENT_ALIASES = KNOWN_PAYMENT_DEFINITIONS.keys
