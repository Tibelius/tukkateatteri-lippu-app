package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingSheetChangeDao {
    @Query("SELECT * FROM pending_sheet_changes WHERE reservation_id = :reservationId LIMIT 1")
    suspend fun findByReservationId(reservationId: Long): PendingSheetChangeEntity?

    @Query(
        "SELECT * FROM pending_sheet_changes WHERE performance_id = :performanceId " +
            "AND status = 'PENDING' " +
            "ORDER BY created_at, id"
    )
    suspend fun getByPerformanceId(performanceId: Long): List<PendingSheetChangeEntity>

    @Query(
        "SELECT * FROM pending_sheet_changes WHERE performance_id = :performanceId " +
            "ORDER BY created_at, id"
    )
    suspend fun getAllByPerformanceId(performanceId: Long): List<PendingSheetChangeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(change: PendingSheetChangeEntity)

    @Query("DELETE FROM pending_sheet_changes WHERE reservation_id = :reservationId")
    suspend fun deleteByReservationId(reservationId: Long)

    @Query("DELETE FROM pending_sheet_changes WHERE performance_id = :performanceId")
    suspend fun deleteByPerformanceId(performanceId: Long)
}
