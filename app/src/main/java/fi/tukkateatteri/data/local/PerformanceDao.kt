package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PerformanceDao {
    @Query(
        "SELECT * FROM performances " +
            "ORDER BY is_active DESC, performance_date DESC, act_name COLLATE NOCASE"
    )
    fun observeAll(): Flow<List<PerformanceEntity>>

    @Query("SELECT * FROM performances WHERE is_active = 1 LIMIT 1")
    fun observeActive(): Flow<PerformanceEntity?>

    @Query("SELECT * FROM performances WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): PerformanceEntity?

    @Query("SELECT * FROM performances WHERE id = :performanceId LIMIT 1")
    suspend fun getById(performanceId: Long): PerformanceEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM performances WHERE id = :performanceId)")
    suspend fun exists(performanceId: Long): Boolean

    @Query(
        "SELECT * FROM performances WHERE act_name = :actName " +
            "AND performance_date = :date LIMIT 1"
    )
    suspend fun findByNameAndDate(actName: String, date: String): PerformanceEntity?

    @Insert
    suspend fun insert(performance: PerformanceEntity): Long

    @Query("UPDATE performances SET source_sheet_title = :sourceSheetTitle WHERE id = :performanceId")
    suspend fun updateSourceSheetTitle(performanceId: Long, sourceSheetTitle: String)

    @Query("DELETE FROM performances WHERE id = :performanceId")
    suspend fun deleteById(performanceId: Long)

    @Query("UPDATE performances SET is_active = 0")
    suspend fun clearActivePerformance()

    @Query("UPDATE performances SET is_active = 1 WHERE id = :performanceId")
    suspend fun markActive(performanceId: Long)

    @Transaction
    suspend fun setActive(performanceId: Long) {
        require(exists(performanceId)) { "Performance does not exist." }
        clearActivePerformance()
        markActive(performanceId)
    }
}
