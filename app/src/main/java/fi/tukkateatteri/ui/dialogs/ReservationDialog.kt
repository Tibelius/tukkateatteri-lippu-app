package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.ui.components.ScrollableAppDialog
import fi.tukkateatteri.ui.components.ReservedTicketTypesSummaryCard

@Composable
fun ReservationDialog(
    reservation: Reservation,
    availableTicketTypes: List<TicketType>,
    availablePaymentMethods: List<PaymentMethod>,
    onDismiss: () -> Unit,
    onSave: (Reservation) -> Unit,
    onUpdateArrivalCount: (reservationId: Long, arrivalCount: Int) -> Unit,
    onAddTicketSale: (TicketType, Int, List<PendingPaymentAllocation>) -> Unit,
    onUpdateTicketSale: (
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    ) -> Unit,
    onDeleteTicketSale: (Long) -> Unit,
    onDelete: () -> Unit
) {
    val isDoorSale = reservation.admissionType == AdmissionType.DOOR_SALE
    var showTicketSaleDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var ticketSaleToEditId by rememberSaveable(reservation.id) { mutableStateOf<Long?>(null) }
    var showArrivalDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var showReservationEditor by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    if (showTicketSaleDialog) {
        TicketSaleDialog(
            maximumQuantity = reservation.availableTicketSaleSeatCount,
            reservedTicketAllocations = reservation.reservedTicketAllocations,
            ticketSalesList = reservation.ticketSales,
            availableTicketTypes = availableTicketTypes,
            availablePaymentMethods = availablePaymentMethods,
            onDismiss = { showTicketSaleDialog = false },
            onSave = { ticketType, quantity, payments ->
                onAddTicketSale(ticketType, quantity, payments)
                showTicketSaleDialog = false
            }
        )
    }

    reservation.ticketSales.find { ticketSale -> ticketSale.id == ticketSaleToEditId }?.let { ticketSale ->
        TicketSaleDialog(
            maximumQuantity = reservation.availableTicketSaleSeatCount + ticketSale.quantity,
            ticketSale = ticketSale,
            reservedTicketAllocations = reservation.reservedTicketAllocations,
            ticketSalesList = reservation.ticketSales,
            availableTicketTypes = availableTicketTypes,
            availablePaymentMethods = availablePaymentMethods,
            onDismiss = { ticketSaleToEditId = null },
            onSave = { ticketType, quantity, payments ->
                onUpdateTicketSale(ticketSale.id, ticketType, quantity, payments)
                ticketSaleToEditId = null
            },
            onDelete = if (ticketSale.payments.any { it.isLocked }) {
                null
            } else {
                {
                    onDeleteTicketSale(ticketSale.id)
                    ticketSaleToEditId = null
                }
            }
        )
    }

    if (showArrivalDialog) {
        ArrivalCountDialog(
            currentArrivalCount = reservation.arrivalCount,
            maximumArrivalCount = reservation.seatCount,
            onDismiss = { showArrivalDialog = false },
            onSave = { arrivalCount ->
                onUpdateArrivalCount(reservation.id, arrivalCount)
                showArrivalDialog = false
            }
        )
    }

    if (showReservationEditor) {
        ReservationEditorDialog(
            reservation = reservation,
            availableTicketTypes = availableTicketTypes,
            onDismiss = { showReservationEditor = false },
            onSave = { updatedReservation ->
                onSave(updatedReservation)
                showReservationEditor = false
            }
        )
    }

    ScrollableAppDialog(onDismissRequest = onDismiss) {
        ReservationDialogHeader(
            title = reservation.displayName.ifBlank {
                stringResource(R.string.admission_type_door_sale)
            },
            isMenuExpanded = isMenuExpanded,
            onMenuExpand = { isMenuExpanded = true },
            onMenuDismiss = { isMenuExpanded = false },
            onEditReservation = { showReservationEditor = true },
            onDelete = onDelete.takeUnless {
                reservation.ticketSales.any { sale -> sale.payments.any { it.isLocked } }
            },
            onDismiss = onDismiss
        )
        Text(
            text = stringResource(
                R.string.payment_overview,
                reservation.paidSeatCount,
                reservation.seatCount
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )

        if (!isDoorSale && reservation.reservedTicketAllocations.isNotEmpty()) {
            ReservedTicketTypesSummaryCard(reservation.reservedTicketAllocations)
        }

        if (reservation.arrivalCount > 0 || reservation.paidSeatCount > 0) {
            ArrivalSection(
                arrivalCount = reservation.arrivalCount,
                seatCount = reservation.seatCount,
                canEdit = !isDoorSale,
                onEdit = { showArrivalDialog = true }
            )
            HorizontalDivider()
        }
        RealizedPaymentsSection(
            ticketSales = reservation.ticketSales,
            onEditTicketSale = { ticketSale -> ticketSaleToEditId = ticketSale.id }
        )
        Button(
            onClick = { showTicketSaleDialog = true },
            enabled = reservation.availableTicketSaleSeatCount > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(
                text = stringResource(R.string.add_ticket_sale),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
