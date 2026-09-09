package fi.tukkateatteri

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.PendingSheetChangeEntity
import fi.tukkateatteri.data.local.PendingSheetChangeStatus
import fi.tukkateatteri.data.local.PendingSheetOperation
import fi.tukkateatteri.data.local.PerformanceEntity
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.ReservedTicketAllocationEntity
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.toReservation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReservationDatabaseIntegrationTest {
    private lateinit var database: ReservationDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ReservationDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomLoadsTheCompleteReservationPaymentGraph() = runBlocking {
        val performanceId = database.performanceDao().insert(
            PerformanceEntity(actName = "Yön Vuodenaika", date = "24.10.2026", isActive = true)
        )
        val reservationId = database.reservationDao().insert(
            ReservationEntity(
                performanceId = performanceId,
                lastName = "Kippari",
                firstName = "Kalle",
                contact = "kippari@example.com",
                seatCount = 2,
                admissionType = AdmissionType.RESERVATION
            )
        )
        database.reservationDao().insertReservedTicketAllocations(
            listOf(
                ReservedTicketAllocationEntity(reservationId, TicketType.BASIC, 1),
                ReservedTicketAllocationEntity(reservationId, TicketType.DISCOUNT, 1)
            )
        )
        val ticketSaleId = database.reservationDao().insertTicketSale(
            TicketSaleEntity(
                reservationId = reservationId,
                ticketType = TicketType.BASIC,
                quantity = 1,
                unitPriceCents = TicketType.BASIC.defaultPriceCents,
                origin = TicketSaleOrigin.MANUAL
            )
        )
        database.reservationDao().insertPaymentAllocations(
            listOf(
                PaymentAllocationEntity(ticketSaleId = ticketSaleId, paymentMethod = PaymentMethod.CASH, amountCents = 1_000),
                PaymentAllocationEntity(ticketSaleId = ticketSaleId, paymentMethod = PaymentMethod.CARD, amountCents = 1_200)
            )
        )

        val reservation = requireNotNull(
            database.reservationDao().getWithTicketSalesById(reservationId)
        ).toReservation()

        assertEquals("Kippari Kalle", reservation.displayName)
        assertEquals(2, reservation.reservedTicketCount)
        assertEquals(1, reservation.paidSeatCount)
        assertTrue(reservation.ticketSales.single().isSplitPayment)
        assertEquals(2_200, reservation.ticketSales.single().paidAmountCents)
    }

    @Test
    fun roomKeepsReservationsAndActivePerformanceScopedToTheirPerformance() = runBlocking {
        val firstPerformanceId = database.performanceDao().insert(
            PerformanceEntity(actName = "Yön Vuodenaika", date = "24.10.2026")
        )
        val secondPerformanceId = database.performanceDao().insert(
            PerformanceEntity(actName = "Yön Vuodenaika", date = "27.10.2026")
        )
        database.reservationDao().insert(
            ReservationEntity(
                performanceId = firstPerformanceId,
                lastName = "Ensimmäinen",
                firstName = "Esitys",
                contact = "",
                seatCount = 1
            )
        )
        database.reservationDao().insert(
            ReservationEntity(
                performanceId = secondPerformanceId,
                lastName = "Toinen",
                firstName = "Esitys",
                contact = "",
                seatCount = 1
            )
        )

        database.performanceDao().setActive(secondPerformanceId)

        assertEquals(
            listOf("Ensimmäinen"),
            database.reservationDao().getByPerformanceWithTicketSales(firstPerformanceId)
                .map { it.reservation.lastName }
        )
        assertEquals(
            listOf("Toinen"),
            database.reservationDao().getByPerformanceWithTicketSales(secondPerformanceId)
                .map { it.reservation.lastName }
        )
        assertEquals(secondPerformanceId, database.performanceDao().getActive()?.id)
    }

    @Test
    fun deletingAReservationRemovesItsCompleteRoomGraph() = runBlocking {
        val performanceId = database.performanceDao().insert(
            PerformanceEntity(actName = "Yön Vuodenaika", date = "24.10.2026")
        )
        val reservationId = database.reservationDao().insert(
            ReservationEntity(
                performanceId = performanceId,
                lastName = "Poistettava",
                firstName = "Varaus",
                contact = "",
                seatCount = 1
            )
        )
        val ticketSaleId = database.reservationDao().insertTicketSale(
            TicketSaleEntity(
                reservationId = reservationId,
                ticketType = TicketType.BASIC,
                quantity = 1,
                unitPriceCents = TicketType.BASIC.defaultPriceCents
            )
        )
        database.reservationDao().insertPaymentAllocations(
            listOf(PaymentAllocationEntity(ticketSaleId = ticketSaleId, paymentMethod = PaymentMethod.CARD, amountCents = 2_200))
        )

        database.reservationDao().deleteById(reservationId)

        assertEquals(null, database.reservationDao().getWithTicketSalesById(reservationId))
        assertFalse(database.reservationDao().getByPerformanceWithTicketSales(performanceId).isNotEmpty())
    }

    @Test
    fun deletingAPerformanceAfterItsReservationsRemovesThePerformance() = runBlocking {
        val performanceId = database.performanceDao().insert(
            PerformanceEntity(
                actName = "Yön Vuodenaika",
                date = "24.10.2026",
                sourceSheetTitle = "24.10"
            )
        )
        database.reservationDao().insert(
            ReservationEntity(
                performanceId = performanceId,
                lastName = "Poistettava",
                firstName = "Esitys",
                contact = "",
                seatCount = 1
            )
        )

        database.reservationDao().deleteAllByPerformance(performanceId)
        database.performanceDao().deleteById(performanceId)

        assertEquals(null, database.performanceDao().getById(performanceId))
        assertFalse(database.reservationDao().getByPerformanceWithTicketSales(performanceId).isNotEmpty())
    }

    @Test
    fun pendingSheetChangeIsStoredLocallyAndDeletedWithItsReservation() = runBlocking {
        val performanceId = database.performanceDao().insert(
            PerformanceEntity(actName = "Yön Vuodenaika", date = "24.10.2026")
        )
        val reservationId = database.reservationDao().insert(
            ReservationEntity(
                performanceId = performanceId,
                lastName = "Kippari",
                firstName = "Kalle",
                contact = "",
                seatCount = 1
            )
        )
        database.pendingSheetChangeDao().upsert(
            PendingSheetChangeEntity(
                id = "pending-change",
                reservationId = reservationId,
                performanceId = performanceId,
                operation = PendingSheetOperation.UPSERT,
                baseRowJson = null,
                desiredRowJson = "local-only-payload",
                status = PendingSheetChangeStatus.PENDING,
                createdAt = 1L
            )
        )

        assertEquals(1, database.pendingSheetChangeDao().getByPerformanceId(performanceId).size)

        database.reservationDao().deleteById(reservationId)

        assertTrue(database.pendingSheetChangeDao().getAllByPerformanceId(performanceId).isEmpty())
    }
}
