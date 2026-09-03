package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GoogleSheetSourceDao {
    @Query("SELECT * FROM google_sheet_sources ORDER BY actName COLLATE NOCASE")
    fun observeAll(): Flow<List<GoogleSheetSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: GoogleSheetSourceEntity)

    @Query("DELETE FROM google_sheet_sources WHERE actName = :actName")
    suspend fun deleteByActName(actName: String)
}
