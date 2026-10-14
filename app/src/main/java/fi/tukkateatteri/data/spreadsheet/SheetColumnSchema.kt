package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroCentsOrNull

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
    val priceText = match?.groupValues?.get(2).orEmpty()
    val priceCents = if (priceText.isBlank()) 0 else requireNotNull(priceText.toEuroCentsOrNull()) {
        "Ticket price in column '$header' is outside the supported range."
    }
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
    val match = TICKET_HEADER_PATTERN.matchEntire(header)
    val hasExplicitPrice = match
        ?.groupValues?.get(2)?.isNotBlank() == true
    val parsedLabel = match
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
