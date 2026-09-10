package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun GoogleSheetsClient.saveSheetSchemas(
    spreadsheetUrl: String,
    accessToken: String,
    schemas: Collection<SheetColumnSchema>
) = withContext(Dispatchers.IO) {
    val aliases = schemas.flatMap { schema ->
        schema.ticketHeaders.map { (type, alias) ->
            AliasRow(alias, "TICKET", type.label, type.defaultPriceCents, "")
        } + schema.paymentHeaders.map { (method, alias) ->
            AliasRow(
                alias = alias,
                kind = "PAYMENT",
                label = method.label,
                priceCents = null,
                options = if (method.allowsSplitPayment) "" else "split=false"
            )
        }
    }
    saveAliasRows(spreadsheetUrl, accessToken, aliases)
}

internal suspend fun GoogleSheetsClient.saveFieldMappings(
    spreadsheetUrl: String,
    accessToken: String,
    mappings: List<SheetFieldMapping>
) = withContext(Dispatchers.IO) {
    val aliases = mappings.map { mapping ->
        when (mapping.classification) {
            SheetFieldClassification.TICKET -> {
                val type = mapping.header.toTicketDefinition(Int.MAX_VALUE)
                AliasRow(mapping.header, "TICKET", type.label, type.defaultPriceCents, "")
            }
            SheetFieldClassification.PAYMENT ->
                AliasRow(mapping.header, "PAYMENT", mapping.header.trim(), null, "")
            SheetFieldClassification.IGNORE ->
                AliasRow(mapping.header, "IGNORE", mapping.header.trim(), null, "")
        }
    }
    saveAliasRows(spreadsheetUrl, accessToken, aliases)
}

private suspend fun GoogleSheetsClient.saveAliasRows(
    spreadsheetUrl: String,
    accessToken: String,
    aliases: List<AliasRow>
) {
    val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
    api.ensureApplicationSheet(spreadsheetId, accessToken)
    val existingRows = api.loadValues(spreadsheetId, APPLICATION_SHEET_TITLE, accessToken)
    val knownAliases = existingRows.drop(1)
        .mapNotNull { row -> row.getOrNull(ALIAS_NORMALIZED_COLUMN)?.normalizedHeader()?.takeIf(String::isNotBlank) }
        .toMutableSet()
    val newAliases = aliases.distinctBy(AliasRow::normalizedAlias)
        .filter { knownAliases.add(it.normalizedAlias) }
    if (newAliases.isEmpty()) return

    val availableRows = buildList {
        var rowNumber = FIRST_DATA_ROW
        while (size < newAliases.size) {
            if (existingRows.getOrNull(rowNumber - 1)?.getOrNull(ALIAS_NORMALIZED_COLUMN).isNullOrBlank()) {
                add(rowNumber)
            }
            rowNumber += 1
        }
    }
    api.updateCells(
        spreadsheetId = spreadsheetId,
        accessToken = accessToken,
        values = newAliases.flatMapIndexed { index, alias -> alias.toCellValues(availableRows[index]) }
    )
    AppLog.info(LOG_COMPONENT) { "Stored ${newAliases.size} new Sheet field aliases" }
}

private data class AliasRow(
    val alias: String,
    val kind: String,
    val label: String,
    val priceCents: Int?,
    val options: String
) {
    val normalizedAlias = alias.normalizedHeader()

    fun toCellValues(rowNumber: Int): List<SheetCellValue> =
        listOf(alias, normalizedAlias, kind, label, priceCents ?: "", options)
            .mapIndexed { columnIndex, value ->
                SheetCellValue(sheetCellRange(APPLICATION_SHEET_TITLE, columnIndex, rowNumber), value)
            }
}

private const val LOG_COMPONENT = "SheetAliases"
private const val ALIAS_NORMALIZED_COLUMN = 1
private const val FIRST_DATA_ROW = 2
