package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReservationDao {
    @Query("SELECT * FROM reservations ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE")
    fun observeAll(): Flow<List<ReservationEntity>>

    @Transaction
    @Query("SELECT * FROM reservations ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE")
    fun observeAllWithTicketSales(): Flow<List<ReservationWithTicketSales>>

    @Query("SELECT * FROM reservations ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE")
    suspend fun getAll(): List<ReservationEntity>

    @Query("SELECT * FROM reservations WHERE id = :reservationId")
    suspend fun getById(reservationId: Long): ReservationEntity?

    @Transaction
    @Query("SELECT * FROM reservations ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE")
    suspend fun getAllWithTicketSales(): List<ReservationWithTicketSales>

    @Insert
    suspend fun insert(reservation: ReservationEntity): Long

    @Insert
    suspend fun insertAll(reservations: List<ReservationEntity>)

    @Query("SELECT * FROM reservations WHERE source_identity = :sourceIdentity LIMIT 1")
    suspend fun findBySourceIdentity(sourceIdentity: String): ReservationEntity?

    @Insert
    suspend fun insertTicketSale(ticketSale: TicketSaleEntity): Long

    @Insert
    suspend fun insertPaymentAllocations(payments: List<PaymentAllocationEntity>)

    @Update
    suspend fun update(reservation: ReservationEntity)

    @Query("DELETE FROM reservations WHERE id = :reservationId")
    suspend fun deleteById(reservationId: Long)

    @Query("DELETE FROM ticket_sales WHERE id = :ticketSaleId")
    suspend fun deleteTicketSaleById(ticketSaleId: Long)

    @Query("DELETE FROM ticket_sales WHERE reservation_id = :reservationId")
    suspend fun deleteTicketSalesForReservation(reservationId: Long)

    @Query("DELETE FROM reservations")
    suspend fun deleteAll()
}
