package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
@Dao
interface ReservationDao {
    @Transaction
    @Query(
        "SELECT * FROM reservations WHERE performance_id = :performanceId " +
            "ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE"
    )
    fun observeByPerformanceWithTicketSales(
        performanceId: Long
    ): Flow<List<ReservationWithTicketSales>>

    @Query("SELECT * FROM reservations WHERE id = :reservationId")
    suspend fun getById(reservationId: Long): ReservationEntity?

    @Transaction
    @Query("SELECT * FROM reservations WHERE id = :reservationId")
    suspend fun getWithTicketSalesById(reservationId: Long): ReservationWithTicketSales?

    @Transaction
    @Query(
        "SELECT * FROM reservations WHERE performance_id = :performanceId " +
            "ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE"
    )
    suspend fun getByPerformanceWithTicketSales(
        performanceId: Long
    ): List<ReservationWithTicketSales>

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

    @Insert
    suspend fun insertReservedTicketAllocations(allocations: List<ReservedTicketAllocationEntity>)

    @Update
    suspend fun update(reservation: ReservationEntity)

    @Query("DELETE FROM reservations WHERE id = :reservationId")
    suspend fun deleteById(reservationId: Long)

    @Query("DELETE FROM ticket_sales WHERE id = :ticketSaleId")
    suspend fun deleteTicketSaleById(ticketSaleId: Long)

    @Query("SELECT * FROM ticket_sales WHERE id = :ticketSaleId")
    suspend fun getTicketSaleById(ticketSaleId: Long): TicketSaleEntity?

    @Query("DELETE FROM ticket_sales WHERE reservation_id = :reservationId AND origin = 'IMPORTED'")
    suspend fun deleteImportedTicketSalesForReservation(reservationId: Long)

    @Query("DELETE FROM reserved_ticket_allocations WHERE reservation_id = :reservationId")
    suspend fun deleteReservedTicketAllocationsForReservation(reservationId: Long)

    @Query("DELETE FROM reservations WHERE performance_id = :performanceId")
    suspend fun deleteAllByPerformance(performanceId: Long)
}
