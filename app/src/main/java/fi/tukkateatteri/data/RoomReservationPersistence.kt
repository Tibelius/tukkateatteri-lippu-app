package fi.tukkateatteri.data

import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.PerformanceEntity
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.ReservationWithTicketSales
import fi.tukkateatteri.data.local.ReservedTicketAllocationEntity
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toLogSummary

internal suspend fun RoomReservationRepository.importSpreadsheetRows(
    rows: List<ReservationSpreadsheetRow>,
    performanceId: Long,
    preserveReservationIds: Set<Long> = emptySet()
) {
    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
        "Applying ${rows.size} imported rows to Room; performanceId=$performanceId, " +
            "preservedPending=${preserveReservationIds.size}"
    }
    var insertedCount = 0
    var updatedCount = 0
    var skippedCount = 0
    rows.forEach { row ->
        val existingReservation = if (row.sheetRowId.isNotBlank()) {
            reservationDao.findBySheetRowId(row.sheetRowId)
        } else {
            null
        } ?: if (row.sourceIdentity.isNotBlank()) {
            reservationDao.findBySourceIdentity(row.sourceIdentity)
        } else {
            null
        }
        if (existingReservation?.id in preserveReservationIds) {
            skippedCount += 1
            AppLog.verbose(REPOSITORY_LOG_COMPONENT) {
                "Preserving pending local row instead of importing remote data; reservationId=${existingReservation?.id}"
            }
            return@forEach
        }
        val preserveDetailedSales = existingReservation
            ?.let { reservationDao.getWithTicketSalesById(it.id) }
            ?.toReservation()
            ?.takeIf { localReservation ->
                localReservation.ticketSales.any { sale -> sale.ticketType != TicketType.UNSPECIFIED } &&
                    ReservationSpreadsheetRow.fromReservation(localReservation).paymentTicketCounts ==
                    row.paymentTicketCounts
            } != null
        val reservationId = existingReservation?.id ?: reservationDao.insert(
            ReservationEntity(
                performanceId = performanceId,
                lastName = row.lastName.trim(),
                firstName = row.firstName.trim(),
                contact = row.contact.trim(),
                seatCount = row.reservedSeatCount,
                notes = row.notes,
                sourceIdentity = row.sourceIdentity,
                sheetRowId = row.sheetRowId,
                syncState = ReservationSyncState.SYNCED,
                admissionType = AdmissionType.RESERVATION,
                arrivalCount = row.arrivalCount,
                isPresent = row.arrivalCount > 0
            )
        ).also {
            insertedCount += 1
            AppLog.verbose(REPOSITORY_LOG_COMPONENT) { "Inserted imported ${row.toLogSummary()}, reservationId=$it" }
        }
        if (existingReservation != null) {
            reservationDao.update(
                existingReservation.copy(
                    lastName = row.lastName.trim(),
                    firstName = row.firstName.trim(),
                    contact = row.contact.trim(),
                    seatCount = row.reservedSeatCount,
                    notes = row.notes,
                    sourceIdentity = row.sourceIdentity.ifBlank { existingReservation.sourceIdentity },
                    sheetRowId = row.sheetRowId.ifBlank { existingReservation.sheetRowId },
                    syncState = ReservationSyncState.SYNCED,
                    arrivalCount = row.arrivalCount,
                    isPresent = row.arrivalCount > 0
                )
            )
            updatedCount += 1
            AppLog.verbose(REPOSITORY_LOG_COMPONENT) {
                "Updated imported ${row.toLogSummary()}, reservationId=${existingReservation.id}"
            }
        }
        replaceReservedTicketAllocations(
            reservationId,
            row.reservedTicketCounts.map { (ticketType, quantity) ->
                ReservedTicketAllocation(ticketType, quantity)
            }
        )
        if (!preserveDetailedSales) {
            reservationDao.deleteAllTicketSalesForReservation(reservationId)
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
    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
        "Applied imported rows; inserted=$insertedCount, updated=$updatedCount, skipped=$skippedCount"
    }
}

internal suspend fun RoomReservationRepository.removeMissingSheetReservations(
    performanceId: Long,
    rows: List<ReservationSpreadsheetRow>,
    preserveReservationIds: Set<Long>
) {
    val remoteIdentities = rows.flatMap { row -> listOf(row.sheetRowId, row.sourceIdentity) }
        .filter(String::isNotBlank)
        .toSet()
    val missingReservations = reservationDao.getByPerformanceWithTicketSales(performanceId)
        .map(ReservationWithTicketSales::reservation)
        .filter { reservation ->
            reservation.id !in preserveReservationIds &&
                (reservation.sheetRowId.isNotBlank() || reservation.sourceIdentity.isNotBlank())
        }
        .filter { reservation ->
            reservation.sheetRowId !in remoteIdentities && reservation.sourceIdentity !in remoteIdentities
        }
    missingReservations.forEach { reservationDao.deleteById(it.id) }
    if (missingReservations.isNotEmpty()) {
        AppLog.info(REPOSITORY_LOG_COMPONENT) {
            "Removed ${missingReservations.size} local rows missing from authoritative Sheet; performanceId=$performanceId"
        }
    }
}

internal suspend fun RoomReservationRepository.findOrCreateImportedPerformance(
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

internal suspend fun RoomReservationRepository.replaceReservedTicketAllocations(
    reservationId: Long,
    allocations: List<ReservedTicketAllocation>
) {
    AppLog.verbose(REPOSITORY_LOG_COMPONENT) {
        "Replacing reserved ticket allocations; reservationId=$reservationId, allocations=${allocations.size}"
    }
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

internal suspend fun RoomReservationRepository.replacePaymentAllocations(
    ticketSaleId: Long,
    payments: List<PendingPaymentAllocation>
) {
    AppLog.verbose(REPOSITORY_LOG_COMPONENT) {
        "Replacing payment allocations; ticketSaleId=$ticketSaleId, allocations=${payments.size}"
    }
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
}

internal fun RoomReservationRepository.validateReservedTicketAllocations(
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

internal fun RoomReservationRepository.validateTicketSale(
    ticketType: TicketType,
    quantity: Int,
    payments: List<PendingPaymentAllocation>
) {
    require(quantity > 0) { "Ticket quantity must be positive." }
    require(ticketType != TicketType.UNSPECIFIED) { "Ticket sales need a ticket type." }
    require(payments.all { payment -> payment.amountCents >= 0 }) {
        "Payment amounts must not be negative."
    }
    require(
        payments.sumOf(PendingPaymentAllocation::amountCents) ==
            ticketType.defaultPriceCents * quantity
    ) {
        "Payment total must match the ticket price."
    }
}
