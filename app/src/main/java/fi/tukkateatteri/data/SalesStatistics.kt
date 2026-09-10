package fi.tukkateatteri.data

data class SalesStatistics(
    val reservationCount: Int,
    val doorSaleCount: Int,
    val seatCount: Int,
    val redeemedSeatCount: Int,
    val arrivalCount: Int,
    val transactionCount: Int,
    val revenueCents: Int,
    val reservationRevenueCents: Int,
    val doorSaleRevenueCents: Int,
    val expectedReservationRevenueCents: Int,
    val expectedReservationSeatCount: Int,
    val reservationSeatCount: Int,
    val unknownRevenueSeatCount: Int,
    val unknownReservationRevenueSeatCount: Int,
    val ticketTypes: List<TicketTypeStatistics>,
    val paymentMethods: List<PaymentMethodStatistics>
) {
    val redemptionRate: Float
        get() = ratio(redeemedSeatCount, seatCount)

    val arrivalRate: Float
        get() = ratio(arrivalCount, seatCount)

    val hasCompleteRevenueExpectation: Boolean
        get() = expectedReservationSeatCount == reservationSeatCount

    val canCompareReservationRevenue: Boolean
        get() = hasCompleteRevenueExpectation && unknownReservationRevenueSeatCount == 0

    val reservationRevenueDifferenceCents: Int
        get() = reservationRevenueCents - expectedReservationRevenueCents
}

data class TicketTypeStatistics(
    val name: String,
    val label: String,
    val quantity: Int,
    val revenueCents: Int,
    val sortOrder: Int
)

data class PaymentMethodStatistics(
    val name: String,
    val label: String,
    val transactionCount: Int,
    val amountCents: Int,
    val sortOrder: Int
)

data class PerformanceStatistics(
    val performance: Performance,
    val statistics: SalesStatistics
)

data class StatisticsReport(
    val title: String,
    val isActSummary: Boolean,
    val statistics: SalesStatistics,
    val performances: List<PerformanceStatistics> = emptyList()
)

fun calculateSalesStatistics(reservations: List<Reservation>): SalesStatistics {
    val activeReservations = reservations.filterNot {
        it.syncState == ReservationSyncState.PENDING_DELETION
    }
    val reservationAdmissions = activeReservations.filter {
        it.admissionType == AdmissionType.RESERVATION
    }
    val sales = activeReservations.flatMap { reservation ->
        reservation.ticketSales.map { sale -> reservation to sale }
    }
    val paidSales = sales.filter { (_, sale) -> sale.isPaid }

    val ticketTypes = paidSales
        .groupBy { (_, sale) -> sale.ticketType.name }
        .map { (name, entries) ->
            val sample = entries.first().second.ticketType
            TicketTypeStatistics(
                name = name,
                label = sample.label,
                quantity = entries.sumOf { (_, sale) -> sale.quantity },
                revenueCents = entries.sumOf { (_, sale) -> sale.paidAmountCents },
                sortOrder = sample.sortOrder
            )
        }
        .sortedWith(compareBy(TicketTypeStatistics::sortOrder, TicketTypeStatistics::label))

    val paymentMethods = paidSales
        .flatMap { (_, sale) -> sale.payments }
        .groupBy { payment -> payment.method.name }
        .map { (name, payments) ->
            val sample = payments.first().method
            PaymentMethodStatistics(
                name = name,
                label = sample.label,
                transactionCount = payments.size,
                amountCents = payments.sumOf(PaymentAllocation::amountCents),
                sortOrder = sample.sortOrder
            )
        }
        .sortedWith(compareBy(PaymentMethodStatistics::sortOrder, PaymentMethodStatistics::label))

    return SalesStatistics(
        reservationCount = reservationAdmissions.size,
        doorSaleCount = activeReservations.size - reservationAdmissions.size,
        seatCount = activeReservations.sumOf(Reservation::seatCount),
        redeemedSeatCount = activeReservations.sumOf(Reservation::paidSeatCount),
        arrivalCount = activeReservations.sumOf(Reservation::arrivalCount),
        transactionCount = paidSales.size,
        revenueCents = paidSales.sumOf { (_, sale) -> sale.paidAmountCents },
        reservationRevenueCents = paidSales
            .filter { (reservation, _) -> reservation.admissionType == AdmissionType.RESERVATION }
            .sumOf { (_, sale) -> sale.paidAmountCents },
        doorSaleRevenueCents = paidSales
            .filter { (reservation, _) -> reservation.admissionType == AdmissionType.DOOR_SALE }
            .sumOf { (_, sale) -> sale.paidAmountCents },
        expectedReservationRevenueCents = reservationAdmissions.sumOf { reservation ->
            reservation.reservedTicketAllocations.sumOf { allocation ->
                allocation.ticketType.defaultPriceCents * allocation.quantity
            }
        },
        expectedReservationSeatCount = reservationAdmissions.sumOf(Reservation::reservedTicketCount),
        reservationSeatCount = reservationAdmissions.sumOf(Reservation::seatCount),
        unknownRevenueSeatCount = paidSales
            .filter { (_, sale) -> sale.ticketType == TicketType.UNSPECIFIED }
            .sumOf { (_, sale) -> sale.quantity },
        unknownReservationRevenueSeatCount = paidSales
            .filter { (reservation, sale) ->
                reservation.admissionType == AdmissionType.RESERVATION &&
                    sale.ticketType == TicketType.UNSPECIFIED
            }
            .sumOf { (_, sale) -> sale.quantity },
        ticketTypes = ticketTypes,
        paymentMethods = paymentMethods
    )
}

private fun ratio(numerator: Int, denominator: Int): Float =
    if (denominator == 0) 0f else numerator.toFloat() / denominator
