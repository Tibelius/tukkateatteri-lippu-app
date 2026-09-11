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
    val sortOrder: Int,
    val paymentMethods: List<PaymentMethodStatistics>
)

data class PaymentMethodStatistics(
    val name: String,
    val label: String,
    val ticketCount: Int,
    val splitPaymentCount: Int,
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
            val ticketType = entries.first().second.ticketType

            TicketTypeStatistics(
                name = name,
                label = ticketType.label,
                quantity = entries.sumOf { (_, sale) -> sale.quantity },
                revenueCents = entries.sumOf { (_, sale) -> sale.paidAmountCents },
                sortOrder = ticketType.sortOrder,
                paymentMethods = entries
                    .map { (_, sale) -> sale }
                    .toPaymentMethodStatistics()
            )
        }
        .sortedWith(compareBy(TicketTypeStatistics::sortOrder, TicketTypeStatistics::label))

    val paymentMethods = paidSales
        .map { (_, sale) -> sale }
        .toPaymentMethodStatistics()

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

private fun List<TicketSale>.toPaymentMethodStatistics(): List<PaymentMethodStatistics> =
    flatMap { sale -> sale.payments.map { payment -> SalePayment(sale, payment) } }
        .groupBy { it.payment.method.name }
        .map { (name, salePayments) ->
            val method = PaymentMethod.entries.firstOrNull { it.name == name }
                ?: salePayments.minWith(
                    compareBy<SalePayment> { it.payment.method.sortOrder }
                        .thenBy { it.payment.method.label }
                ).payment.method

            PaymentMethodStatistics(
                name = name,
                label = method.label,
                ticketCount = salePayments
                    .filterNot { it.sale.isSplitPayment }
                    .sumOf { it.sale.quantity },
                splitPaymentCount = salePayments
                    .filter { it.sale.isSplitPayment }
                    .distinctBy { it.sale.id }
                    .size,
                amountCents = salePayments.sumOf { it.payment.amountCents },
                sortOrder = PaymentMethod.displaySortOrder(method)
            )
        }
        .sortedWith(
            compareBy(PaymentMethodStatistics::sortOrder, PaymentMethodStatistics::label)
        )

private data class SalePayment(
    val sale: TicketSale,
    val payment: PaymentAllocation
)
