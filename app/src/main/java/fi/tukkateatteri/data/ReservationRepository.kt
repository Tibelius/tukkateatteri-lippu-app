package fi.tukkateatteri.data

import androidx.room.withTransaction
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.GoogleSheetSourceDao
import fi.tukkateatteri.data.local.PerformanceDao
import fi.tukkateatteri.data.local.PerformanceEntity
import fi.tukkateatteri.data.local.ReservationDao
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.ReservedTicketAllocationEntity
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.local.toEntity
import fi.tukkateatteri.data.local.toGoogleSheetSource
import fi.tukkateatteri.data.local.toPerformance
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.GoogleSheetsClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ReservationRepository {
    val performances: Flow<List<Performance>>
    val activePerformance: Flow<Performance?>
    val googleSheetSources: Flow<List<GoogleSheetSource>>

    fun reservationsForPerformance(performanceId: Long): Flow<List<Reservation>>
    suspend fun createPerformance(actName: String, date: String): Long
    suspend fun selectPerformance(performanceId: Long)
    suspend fun deletePerformance(performanceId: Long)
    suspend fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>
    ): Long
    suspend fun updateReservation(reservation: Reservation)
    suspend fun updateArrivalCount(reservationId: Long, arrivalCount: Int)
    suspend fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    )
    suspend fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    )
    suspend fun deleteTicketSale(ticketSaleId: Long)
    suspend fun deleteReservation(reservationId: Long)
    suspend fun deleteAllReservations()
    suspend fun importGoogleSheet(spreadsheetUrl: String, accessToken: String): GoogleSheetImportResult
    suspend fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String
    ): Int
    suspend fun exportGoogleSheet(spreadsheetUrl: String, sheetTitle: String, accessToken: String)
    suspend fun upsertGoogleSheetSource(source: GoogleSheetSource)
    suspend fun deleteGoogleSheetSource(actName: String)
}

data class PendingPaymentAllocation(val method: PaymentMethod, val amountCents: Int)

data class GoogleSheetImportResult(
    val performanceCount: Int,
    val reservationCount: Int
)

class GoogleSheetSourceChangedException : IllegalStateException()

class NoGoogleSheetImportCandidatesException : IllegalStateException()

class RoomReservationRepository(
    private val database: ReservationDatabase,
    private val reservationDao: ReservationDao,
    private val performanceDao: PerformanceDao,
    private val googleSheetSourceDao: GoogleSheetSourceDao,
    private val googleSheetsClient: GoogleSheetsClient = GoogleSheetsClient()
) : ReservationRepository {
    override val performances: Flow<List<Performance>> = performanceDao.observeAll()
        .map { performances -> performances.map { performance -> performance.toPerformance() } }
    override val activePerformance: Flow<Performance?> = performanceDao.observeActive()
        .map { performance -> performance?.toPerformance() }
    override val googleSheetSources: Flow<List<GoogleSheetSource>> = googleSheetSourceDao.observeAll()
        .map { sources -> sources.map { source -> source.toGoogleSheetSource() } }

    override fun reservationsForPerformance(performanceId: Long): Flow<List<Reservation>> =
        reservationDao.observeByPerformanceWithTicketSales(performanceId)
            .map { reservations -> reservations.map { reservation -> reservation.toReservation() } }

    override suspend fun createPerformance(actName: String, date: String): Long = database.withTransaction {
        val normalizedActName = actName.trim()
        val normalizedDate = date.trim()
        require(normalizedActName.isNotBlank()) { "Performance name must not be blank." }
        require(normalizedDate.isNotBlank()) { "Performance date must not be blank." }
        val performanceId = performanceDao.findByNameAndDate(normalizedActName, normalizedDate)?.id
            ?: performanceDao.insert(
                PerformanceEntity(
                    actName = normalizedActName,
                    date = normalizedDate
                )
            )
        performanceDao.setActive(performanceId)
        performanceId
    }

    override suspend fun selectPerformance(performanceId: Long) {
        performanceDao.setActive(performanceId)
    }

    override suspend fun deletePerformance(performanceId: Long) {
        database.withTransaction {
            requireNotNull(performanceDao.getById(performanceId)) { "Performance does not exist." }
            reservationDao.deleteAllByPerformance(performanceId)
            performanceDao.deleteById(performanceId)
        }
    }

    override suspend fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>
    ): Long = database.withTransaction {
        val activePerformance = requireNotNull(performanceDao.getActive()) {
            "Select a performance before adding reservations."
        }
        validateReservedTicketAllocations(reservedTicketAllocations, seatCount)
        val reservationId = reservationDao.insert(
            ReservationEntity(
                performanceId = activePerformance.id,
                lastName = lastName.trim(),
                firstName = firstName.trim(),
                contact = contact.trim(),
                seatCount = seatCount,
                admissionType = admissionType,
                arrivalCount = if (admissionType == AdmissionType.DOOR_SALE) seatCount else 0,
                isPresent = admissionType == AdmissionType.DOOR_SALE
            )
        )
        replaceReservedTicketAllocations(reservationId, reservedTicketAllocations)
        reservationId
    }

    override suspend fun updateReservation(reservation: Reservation) {
        database.withTransaction {
            val existingReservation = requireNotNull(reservationDao.getWithTicketSalesById(reservation.id))
            val existingReservationEntity = existingReservation.reservation
            val existingPaidSeatCount = existingReservation.toReservation().paidSeatCount
            require(reservation.seatCount >= maxOf(existingReservationEntity.arrivalCount, existingPaidSeatCount)) {
                "Seat count must not be lower than arrived or redeemed tickets."
            }
            validateReservedTicketAllocations(reservation.reservedTicketAllocations, reservation.seatCount)
            reservationDao.update(
                ReservationEntity(
                    id = reservation.id,
                    performanceId = existingReservationEntity.performanceId,
                    lastName = reservation.lastName.trim(),
                    firstName = reservation.firstName.trim(),
                    contact = reservation.contact.trim(),
                    seatCount = reservation.seatCount,
                    notes = reservation.notes.trim(),
                    sourceIdentity = existingReservationEntity.sourceIdentity,
                    admissionType = reservation.admissionType,
                    arrivalCount = if (reservation.admissionType == AdmissionType.DOOR_SALE) {
                        reservation.seatCount
                    } else {
                        existingReservationEntity.arrivalCount
                    },
                    isPresent = reservation.admissionType == AdmissionType.DOOR_SALE || reservation.isPresent,
                    paymentMethod = null
                )
            )
            replaceReservedTicketAllocations(reservation.id, reservation.reservedTicketAllocations)
        }
    }

    override suspend fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    ) {
        require(quantity > 0) { "Ticket quantity must be positive." }
        require(ticketType != TicketType.UNSPECIFIED) { "Ticket sales need a ticket type." }
        require(payments.all { it.amountCents >= 0 }) { "Payment amounts must not be negative." }
        require(payments.sumOf(PendingPaymentAllocation::amountCents) == ticketType.defaultPriceCents * quantity) {
            "Payment total must match the ticket price."
        }
        database.withTransaction {
            val currentReservation = requireNotNull(reservationDao.getWithTicketSalesById(reservationId))
            val reservation = currentReservation.reservation
            require(quantity <= currentReservation.toReservation().unpaidSeatCount) {
                "Ticket quantity exceeds the number of unredeemed seats."
            }
            val ticketSaleId = reservationDao.insertTicketSale(
                TicketSaleEntity(
                    reservationId = reservationId,
                    ticketType = ticketType,
                    quantity = quantity,
                    unitPriceCents = ticketType.defaultPriceCents,
                    origin = TicketSaleOrigin.MANUAL,
                    countsAsArrival = true
                )
            )
            reservationDao.insertPaymentAllocations(
                payments.map { payment ->
                    PaymentAllocationEntity(
                        ticketSaleId = ticketSaleId,
                        paymentMethod = payment.method,
                        amountCents = payment.amountCents
                    )
                }
            )
            reservationDao.update(
                reservation.copy(
                    arrivalCount = (reservation.arrivalCount + quantity).coerceAtMost(reservation.seatCount),
                    isPresent = true
                )
            )
        }
    }

    override suspend fun updateArrivalCount(reservationId: Long, arrivalCount: Int) {
        require(arrivalCount >= 0) { "Arrival count must not be negative." }
        database.withTransaction {
            val reservation = requireNotNull(reservationDao.getById(reservationId))
            val boundedArrivalCount = if (reservation.admissionType == AdmissionType.DOOR_SALE) {
                reservation.seatCount
            } else {
                arrivalCount.coerceAtMost(reservation.seatCount)
            }
            reservationDao.update(
                reservation.copy(
                    arrivalCount = boundedArrivalCount,
                    isPresent = boundedArrivalCount > 0
                )
            )
        }
    }

    override suspend fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    ) {
        require(quantity > 0) { "Ticket quantity must be positive." }
        require(ticketType != TicketType.UNSPECIFIED) { "Ticket sales need a ticket type." }
        require(payments.all { it.amountCents >= 0 }) { "Payment amounts must not be negative." }
        require(payments.sumOf(PendingPaymentAllocation::amountCents) == ticketType.defaultPriceCents * quantity) {
            "Payment total must match the ticket price."
        }
        database.withTransaction {
            val existingTicketSale = requireNotNull(reservationDao.getTicketSaleById(ticketSaleId))
            val reservationWithSales = requireNotNull(
                reservationDao.getWithTicketSalesById(existingTicketSale.reservationId)
            )
            val reservation = reservationWithSales.reservation
            val otherPaidSeatCount = reservationWithSales.toReservation().paidSeatCount - existingTicketSale.quantity
            require(quantity <= reservation.seatCount - otherPaidSeatCount) {
                "Ticket quantity exceeds the number of unredeemed seats."
            }
            reservationDao.updateTicketSale(
                existingTicketSale.copy(
                    ticketType = ticketType,
                    quantity = quantity,
                    unitPriceCents = ticketType.defaultPriceCents
                )
            )
            reservationDao.deletePaymentAllocationsForTicketSale(ticketSaleId)
            reservationDao.insertPaymentAllocations(
                payments.map { payment ->
                    PaymentAllocationEntity(
                        ticketSaleId = ticketSaleId,
                        paymentMethod = payment.method,
                        amountCents = payment.amountCents
                    )
                }
            )
            if (existingTicketSale.countsAsArrival) {
                val arrivalDifference = quantity - existingTicketSale.quantity
                reservationDao.update(
                    reservation.copy(
                        arrivalCount = (reservation.arrivalCount + arrivalDifference)
                            .coerceIn(0, reservation.seatCount),
                        isPresent = reservation.arrivalCount + arrivalDifference > 0
                    )
                )
            }
        }
    }

    override suspend fun deleteTicketSale(ticketSaleId: Long) {
        database.withTransaction {
            val ticketSale = reservationDao.getTicketSaleById(ticketSaleId) ?: return@withTransaction
            reservationDao.deleteTicketSaleById(ticketSaleId)
            if (ticketSale.countsAsArrival) {
                reservationDao.getById(ticketSale.reservationId)?.let { reservation ->
                    val updatedArrivalCount = (reservation.arrivalCount - ticketSale.quantity).coerceAtLeast(0)
                    reservationDao.update(
                        reservation.copy(
                            arrivalCount = updatedArrivalCount,
                            isPresent = updatedArrivalCount > 0
                        )
                    )
                }
            }
        }
    }
    override suspend fun deleteReservation(reservationId: Long) = reservationDao.deleteById(reservationId)
    override suspend fun deleteAllReservations() {
        val activePerformance = performanceDao.getActive() ?: return
        reservationDao.deleteAllByPerformance(activePerformance.id)
    }

    private suspend fun exportSpreadsheetRows(performanceId: Long): List<ReservationSpreadsheetRow> =
        ReservationSpreadsheetRow.fromReservations(
            reservationDao.getByPerformanceWithTicketSales(performanceId)
                .map { reservation -> reservation.toReservation() }
        )

    private suspend fun importSpreadsheetRows(
        rows: List<ReservationSpreadsheetRow>,
        performanceId: Long
    ) {
        rows.forEach { row ->
            val existingReservation = if (row.sourceIdentity.isNotBlank()) {
                reservationDao.findBySourceIdentity(row.sourceIdentity)
            } else {
                null
            }
            val reservationId = existingReservation?.id ?: reservationDao.insert(
                ReservationEntity(
                    performanceId = performanceId,
                    lastName = row.lastName.trim(),
                    firstName = row.firstName.trim(),
                    contact = row.contact.trim(),
                    seatCount = row.reservedSeatCount,
                    notes = row.notes,
                    sourceIdentity = row.sourceIdentity,
                    admissionType = AdmissionType.RESERVATION,
                    arrivalCount = row.arrivalCount,
                    isPresent = row.arrivalCount > 0
                )
            )
            if (existingReservation != null) {
                reservationDao.update(
                    existingReservation.copy(
                        lastName = row.lastName.trim(),
                        firstName = row.firstName.trim(),
                        contact = row.contact.trim(),
                        seatCount = row.reservedSeatCount,
                        notes = row.notes,
                        arrivalCount = maxOf(existingReservation.arrivalCount, row.arrivalCount),
                        isPresent = existingReservation.arrivalCount > 0 || row.arrivalCount > 0
                    )
                )
            }
            replaceReservedTicketAllocations(
                reservationId,
                row.reservedTicketCounts.map { (ticketType, quantity) ->
                    ReservedTicketAllocation(ticketType, quantity)
                }
            )
            reservationDao.deleteImportedTicketSalesForReservation(reservationId)
            row.paymentTicketCounts.forEach { (paymentMethod, quantity) ->
                if (quantity > 0) {
                    val ticketSaleId = reservationDao.insertTicketSale(
                        TicketSaleEntity(
                            reservationId = reservationId,
                            ticketType = TicketType.UNSPECIFIED,
                            quantity = quantity,
                            unitPriceCents = 0,
                            origin = TicketSaleOrigin.IMPORTED,
                            countsAsArrival = false
                        )
                    )
                    reservationDao.insertPaymentAllocations(
                        listOf(
                            PaymentAllocationEntity(
                                ticketSaleId = ticketSaleId,
                                paymentMethod = paymentMethod,
                                amountCents = 0
                            )
                        )
                    )
                }
            }
        }
    }

    override suspend fun importGoogleSheet(
        spreadsheetUrl: String,
        accessToken: String
    ): GoogleSheetImportResult {
        val importedData = googleSheetsClient.loadImportData(spreadsheetUrl, accessToken)
        if (importedData.isEmpty()) throw NoGoogleSheetImportCandidatesException()
        val performanceIds = database.withTransaction {
            val importedPerformanceIds = importedData.map { data ->
                val performanceId = findOrCreateImportedPerformance(
                    actName = data.candidate.performanceName,
                    date = data.candidate.date,
                    sourceSheetTitle = data.candidate.sheetTitle
                )
                importSpreadsheetRows(data.rows, performanceId)
                performanceId
            }
            performanceDao.setActive(importedPerformanceIds.first())
            importedData
                .map { it.candidate.performanceName }
                .distinct()
                .forEach { actName ->
                    googleSheetSourceDao.upsert(GoogleSheetSource(actName, spreadsheetUrl).toEntity())
                }
            importedPerformanceIds
        }
        return GoogleSheetImportResult(
            performanceCount = performanceIds.distinct().size,
            reservationCount = importedData.sumOf { it.rows.size }
        )
    }

    override suspend fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String
    ): Int {
        val performance = requireNotNull(performanceDao.getById(performanceId)) {
            "Performance does not exist."
        }
        val sourceSheetTitle = performance.sourceSheetTitle ?: throw GoogleSheetSourceChangedException()
        val importData = try {
            googleSheetsClient.loadImportDataForTab(
                spreadsheetUrl = spreadsheetUrl,
                accessToken = accessToken,
                sheetTitle = sourceSheetTitle
            )
        } catch (_: IllegalArgumentException) {
            throw GoogleSheetSourceChangedException()
        }
        if (
            importData.candidate.performanceName != performance.actName ||
            importData.candidate.date != performance.date
        ) {
            throw GoogleSheetSourceChangedException()
        }
        database.withTransaction {
            importSpreadsheetRows(importData.rows, performanceId)
        }
        return importData.rows.size
    }

    override suspend fun exportGoogleSheet(spreadsheetUrl: String, sheetTitle: String, accessToken: String) {
        val activePerformance = requireNotNull(performanceDao.getActive()) {
            "Select a performance before exporting."
        }
        googleSheetsClient.exportRows(
            spreadsheetUrl = spreadsheetUrl,
            sheetTitle = sheetTitle,
            accessToken = accessToken,
            rows = exportSpreadsheetRows(activePerformance.id)
        )
    }

    override suspend fun upsertGoogleSheetSource(source: GoogleSheetSource) {
        googleSheetSourceDao.upsert(source.toEntity())
    }

    override suspend fun deleteGoogleSheetSource(actName: String) {
        googleSheetSourceDao.deleteByActName(actName)
    }

    private suspend fun findOrCreateImportedPerformance(
        actName: String,
        date: String,
        sourceSheetTitle: String
    ): Long {
        val normalizedActName = actName.trim()
        val normalizedDate = date.trim()
        val existingPerformance = performanceDao.findByNameAndDate(normalizedActName, normalizedDate)
        return if (existingPerformance != null) {
            performanceDao.updateSourceSheetTitle(existingPerformance.id, sourceSheetTitle)
            existingPerformance.id
        } else {
            performanceDao.insert(
                PerformanceEntity(
                    actName = normalizedActName,
                    date = normalizedDate,
                    sourceSheetTitle = sourceSheetTitle
                )
            )
        }
    }

    private suspend fun replaceReservedTicketAllocations(
        reservationId: Long,
        allocations: List<ReservedTicketAllocation>
    ) {
        reservationDao.deleteReservedTicketAllocationsForReservation(reservationId)
        reservationDao.insertReservedTicketAllocations(
            allocations.map { allocation ->
                ReservedTicketAllocationEntity(
                    reservationId = reservationId,
                    ticketType = allocation.ticketType,
                    quantity = allocation.quantity
                )
            }
        )
    }

    private fun validateReservedTicketAllocations(
        allocations: List<ReservedTicketAllocation>,
        seatCount: Int
    ) {
        require(allocations.sumOf(ReservedTicketAllocation::quantity) <= seatCount) {
            "Reserved ticket quantities must not exceed the seat count."
        }
        require(allocations.map(ReservedTicketAllocation::ticketType).distinct().size == allocations.size) {
            "Each reserved ticket type may only appear once."
        }
    }
}
