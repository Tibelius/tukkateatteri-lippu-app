package fi.tukkateatteri.data

data class GoogleSheetSource(
    val actName: String,
    val spreadsheetUrl: String
) {
    init {
        require(actName.isNotBlank()) { "Act name must not be blank." }
        require(spreadsheetUrl.isNotBlank()) { "Spreadsheet URL must not be blank." }
    }
}
