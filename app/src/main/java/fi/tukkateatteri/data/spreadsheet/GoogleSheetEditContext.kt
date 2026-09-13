package fi.tukkateatteri.data.spreadsheet

internal data class GoogleSheetEditContext(
    val sheet: SheetProperties,
    val rows: List<List<String>>,
    val headerRowIndex: Int,
    val headers: Map<String, Int>,
    val rowIdsByRowNumber: Map<Int, String>,
    val stateTable: ApplicationRowStateTable
) {
    val lastColumnIndex: Int = headers.values.maxOrNull() ?: 0

    val reservationRows: List<NumberedSheetRow>
        get() = rows.drop(headerRowIndex + 1).mapIndexed { index, cells ->
            NumberedSheetRow(
                rowNumber = headerRowIndex + index + 2,
                cells = cells
            )
        }

    fun rowNumberForId(rowId: String): Int? = rowId
        .takeIf(String::isNotBlank)
        ?.let { expectedId ->
            rowIdsByRowNumber.entries.firstOrNull { (_, actualId) -> actualId == expectedId }?.key
        }
}

internal data class NumberedSheetRow(
    val rowNumber: Int,
    val cells: List<String>
)

internal fun NumberedSheetRow.customerIdentity(headers: Map<String, Int>): List<String> = listOf(
    cells.valueAt(headers[HEADER_LAST_NAME]),
    cells.valueAt(headers[HEADER_FIRST_NAME]),
    cells.valueAt(headers[HEADER_CONTACT])
).map(String::normalizedIdentity)

internal suspend fun GoogleSheetsApiClient.loadEditContext(
    spreadsheetId: String,
    sheetTitle: String,
    accessToken: String
): GoogleSheetEditContext {
    val structure = loadSpreadsheetMetadata(spreadsheetId, accessToken).toSpreadsheetStructure()
    val sheet = structure.sheetsByTitle[sheetTitle]
        ?: throw GoogleSheetTabUnavailableException(sheetTitle, "tab does not exist")
    val values = loadValuesForTabs(
        spreadsheetId,
        listOf(sheetTitle, APPLICATION_SHEET_TITLE),
        accessToken
    )
    val rows = values.getValue(sheetTitle)
    val headerRowIndex = rows.indexOfFirst { row ->
        row.any { cell -> cell.canonicalDataHeader() == HEADER_LAST_NAME }
    }
    require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
    val headers = rows[headerRowIndex]
        .mapIndexed { index, header -> header.canonicalDataHeader() to index }
        .toMap()
    return GoogleSheetEditContext(
        sheet = sheet,
        rows = rows,
        headerRowIndex = headerRowIndex,
        headers = headers,
        rowIdsByRowNumber = structure.rowIdsBySheetId[sheet.id].orEmpty(),
        stateTable = values.getValue(APPLICATION_SHEET_TITLE).toApplicationRowStateTable()
    )
}
