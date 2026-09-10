package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SheetFieldDefinitionDao {
    @Query("SELECT * FROM sheet_field_definitions ORDER BY sort_order")
    fun observeAll(): Flow<List<SheetFieldDefinitionEntity>>

    @Query("UPDATE sheet_field_definitions SET active = 0 WHERE spreadsheet_url = :spreadsheetUrl")
    suspend fun deactivateForSpreadsheet(spreadsheetUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(definitions: List<SheetFieldDefinitionEntity>)
}
