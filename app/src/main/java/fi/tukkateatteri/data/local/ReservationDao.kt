package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationDao {
    @Query("SELECT * FROM reservations ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE")
    fun observeAll(): Flow<List<ReservationEntity>>

    @Query("SELECT * FROM reservations ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE")
    suspend fun getAll(): List<ReservationEntity>

    @Insert
    suspend fun insert(reservation: ReservationEntity): Long

    @Insert
    suspend fun insertAll(reservations: List<ReservationEntity>)

    @Update
    suspend fun update(reservation: ReservationEntity)

    @Query("DELETE FROM reservations WHERE id = :reservationId")
    suspend fun deleteById(reservationId: Long)
}
