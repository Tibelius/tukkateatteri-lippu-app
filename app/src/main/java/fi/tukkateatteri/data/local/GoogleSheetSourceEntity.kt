package fi.tukkateatteri.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.GoogleSheetSource

@Entity(tableName = "google_sheet_sources")
data class GoogleSheetSourceEntity(
    @PrimaryKey
    val actName: String,
    val spreadsheetUrl: String
)

fun GoogleSheetSourceEntity.toGoogleSheetSource() = GoogleSheetSource(actName, spreadsheetUrl)

fun GoogleSheetSource.toEntity() = GoogleSheetSourceEntity(actName.trim(), spreadsheetUrl.trim())
