package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal class GoogleSheetImporter(
    private val api: GoogleSheetsApiClient
) {
    suspend fun loadAll(
        spreadsheetUrl: String,
        accessToken: String,
        localAliases: Map<String, StoredSheetAlias>
    ): List<GoogleSheetImportData> = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val tabs = loadTabs(spreadsheetId, accessToken)
        val storedAliases = tabs.firstOrNull { it.title == APPLICATION_SHEET_TITLE }
            ?.storedAliases().orEmpty() + localAliases
        tabs.mapNotNull { tab ->
            tab.toImportCandidateOrNull()?.let { candidate ->
                parse(tab, candidate, storedAliases)
            }
        }.sortedWith(
            compareBy<GoogleSheetImportData> { it.candidate.sortDate ?: LocalDate.MAX }
                .thenBy { it.candidate.date }
        ).also { imported ->
            AppLog.info(LOG_COMPONENT) {
                "Parsed importable spreadsheet data; performances=${imported.size}, " +
                    "rows=${imported.sumOf { it.rows.size }}"
            }
        }
    }

    suspend fun loadOne(
        spreadsheetUrl: String,
        accessToken: String,
        sheetTitle: String,
        localAliases: Map<String, StoredSheetAlias>
    ): GoogleSheetImportData = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        AppLog.debug(LOG_COMPONENT) { "Loading import data for tab=$sheetTitle" }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val structure = api.loadSpreadsheetMetadata(spreadsheetId, accessToken).toSpreadsheetStructure()
        val sheet = requireNotNull(structure.sheetsByTitle[sheetTitle]) {
            "Välilehteä '$sheetTitle' ei enää ole."
        }
        val values = api.loadValuesForTabs(
            spreadsheetId,
            listOf(sheetTitle, APPLICATION_SHEET_TITLE),
            accessToken
        )
        val applicationRows = values.getValue(APPLICATION_SHEET_TITLE)
        val tab = GoogleSheetTab(
            title = sheetTitle,
            rows = values.getValue(sheetTitle),
            sheetId = sheet.id,
            rowIdsByRowNumber = structure.rowIdsBySheetId[sheet.id].orEmpty(),
            applicationRowStates = applicationRows.toApplicationRowStateTable().statesFor(sheet.id)
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull()) {
            "Välilehdeltä puuttuu Esitys: tai Pvm: -tieto."
        }
        parse(
            tab = tab,
            candidate = candidate,
            storedAliases = GoogleSheetTab(APPLICATION_SHEET_TITLE, applicationRows).storedAliases() + localAliases
        ).also { data ->
            AppLog.info(LOG_COMPONENT) {
                "Loaded import data; tab=$sheetTitle, rows=${data.rows.size}, " +
                    "durationMs=${AppLog.elapsedMillis(startedAt)}"
            }
        }
    }

    private fun loadTabs(spreadsheetId: String, accessToken: String): List<GoogleSheetTab> {
        val startedAt = System.nanoTime()
        AppLog.debug(LOG_COMPONENT) { "Starting spreadsheet tab discovery" }
        val structure = api.loadSpreadsheetMetadata(spreadsheetId, accessToken).toSpreadsheetStructure()
        val titles = structure.sheetsByTitle.keys.toList()
        val rowsByTitle = api.loadValuesForTabs(spreadsheetId, titles, accessToken)
        val stateTable = rowsByTitle.getValue(APPLICATION_SHEET_TITLE).toApplicationRowStateTable()
        return titles.map { title ->
            val properties = structure.sheetsByTitle.getValue(title)
            GoogleSheetTab(
                title = title,
                rows = rowsByTitle.getValue(title),
                sheetId = properties.id,
                rowIdsByRowNumber = structure.rowIdsBySheetId[properties.id].orEmpty(),
                applicationRowStates = stateTable.statesFor(properties.id)
            )
        }.also { tabs ->
            AppLog.info(LOG_COMPONENT) {
                "Loaded ${tabs.size} spreadsheet tabs in ${AppLog.elapsedMillis(startedAt)} ms"
            }
            AppLog.debug(LOG_COMPONENT) {
                "Received tab values: ${tabs.joinToString { tab -> "${tab.title}=${tab.rows.size} rows" }}"
            }
        }
    }

    private fun parse(
        tab: GoogleSheetTab,
        candidate: GoogleSheetImportCandidate,
        storedAliases: Map<String, StoredSheetAlias>
    ): GoogleSheetImportData {
        val schema = tab.toColumnSchema(storedAliases)
        val rows = tab.toReservationSpreadsheetRows(candidate, schema)
        val manualEditCount = rows.count {
            it.applicationMutationMetadataState == ApplicationMutationMetadataState.INVALID
        }
        AppLog.debug(LOG_COMPONENT) {
            "Parsed performance tab=${tab.title}; rows=${rows.size}, manualEdits=$manualEditCount, " +
                "tickets=${schema.ticketTypes.joinToString { it.displayLabel }}, " +
                "payments=${schema.paymentMethods.joinToString { it.label }}"
        }
        if (manualEditCount > 0) {
            AppLog.info(LOG_COMPONENT) {
                "Detected $manualEditCount authoritative manual row edits; tab=${tab.title}"
            }
        }
        return GoogleSheetImportData(candidate, rows, schema)
    }

    private fun ApplicationRowStateTable.statesFor(sheetId: Int): Map<String, ApplicationRowState> = rows
        .asSequence()
        .filter { state -> state.sheetId == sheetId }
        .associateBy(ApplicationRowState::rowId)

    private companion object {
        const val LOG_COMPONENT = "Sheets"
    }
}
