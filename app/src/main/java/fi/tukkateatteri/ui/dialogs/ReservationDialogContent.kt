package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.MINIMUM_SEAT_COUNT
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString
import fi.tukkateatteri.ui.components.CancelSaveActions
import fi.tukkateatteri.ui.components.CustomerDetailsFields
import fi.tukkateatteri.ui.components.SeatCountSelector
import fi.tukkateatteri.ui.components.ScrollableAppDialog
import fi.tukkateatteri.ui.components.reservedTicketTypesDetails

@Composable
internal fun ArrivalCountDialog(
    currentArrivalCount: Int,
    maximumArrivalCount: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var arrivalCount by rememberSaveable(currentArrivalCount, maximumArrivalCount) {
        mutableIntStateOf(currentArrivalCount.coerceIn(0, maximumArrivalCount))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_arrival_count)) },
        text = {
            SeatCountSelector(
                seatCount = arrivalCount,
                minimumSeatCount = 0,
                maximumSeatCount = maximumArrivalCount,
                labelResId = R.string.arrival_count,
                onDecrease = { arrivalCount-- },
                onIncrease = { if (arrivalCount < maximumArrivalCount) arrivalCount++ }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(arrivalCount) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun ReservationEditorDialog(
    reservation: Reservation,
    availableTicketTypes: List<TicketType>,
    onDismiss: () -> Unit,
    onSave: (Reservation) -> Unit
) {
    val isDoorSale = reservation.admissionType == AdmissionType.DOOR_SALE
    var lastName by rememberSaveable(reservation.id) { mutableStateOf(reservation.lastName) }
    var firstName by rememberSaveable(reservation.id) { mutableStateOf(reservation.firstName) }
    var contact by rememberSaveable(reservation.id) { mutableStateOf(reservation.contact) }
    var notes by rememberSaveable(reservation.id) { mutableStateOf(reservation.notes) }
    var seatCount by rememberSaveable(reservation.id) { mutableIntStateOf(reservation.seatCount) }
    var reservedTicketAllocations by rememberSaveable(
        reservation.id,
        stateSaver = reservedTicketAllocationsSaver
    ) {
        mutableStateOf(reservation.reservedTicketAllocations)
    }
    val minimumSeatCount = maxOf(
        MINIMUM_SEAT_COUNT,
        reservation.paidSeatCount,
        reservation.arrivalCount,
        reservedTicketAllocations.sumOf(ReservedTicketAllocation::quantity)
    )
    val canSave = isDoorSale || (lastName.isNotBlank() && firstName.isNotBlank())

    var showReservedTicketTypesDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    if (showReservedTicketTypesDialog) {
        ReservedTicketTypesDialog(
            initialAllocations = reservedTicketAllocations,
            availableTicketTypes = availableTicketTypes,
            maximumQuantity = seatCount,
            onDismiss = { showReservedTicketTypesDialog = false },
            onSave = { allocations ->
                reservedTicketAllocations = allocations
                showReservedTicketTypesDialog = false
            }
        )
    }

    ScrollableAppDialog(
        onDismissRequest = onDismiss,
        actions = {
            CancelSaveActions(
                onCancel = onDismiss,
                onSave = {
                    onSave(
                        reservation.copy(
                            lastName = lastName.trim(),
                            firstName = firstName.trim(),
                            contact = contact.trim(),
                            notes = notes.trim(),
                            seatCount = seatCount,
                            reservedTicketAllocations = reservedTicketAllocations
                        )
                    )
                },
                saveEnabled = canSave
            )
        }
    ) {
        Text(
            text = stringResource(R.string.edit_reservation),
            style = MaterialTheme.typography.headlineSmall
        )
        CustomerDetailsFields(
            lastName = lastName,
            firstName = firstName,
            contact = contact,
            onLastNameChange = { lastName = it },
            onFirstNameChange = { firstName = it },
            onContactChange = { contact = it },
            fieldsAreOptional = isDoorSale
        )
        HorizontalDivider()
        SeatCountSelector(
            seatCount = seatCount,
            minimumSeatCount = minimumSeatCount,
            onDecrease = { seatCount-- },
            onIncrease = { seatCount++ }
        )
        if (!isDoorSale) {
            ReservedTicketTypesEditorRow(
                allocations = reservedTicketAllocations,
                onClick = { showReservedTicketTypesDialog = true }
            )
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.reservation_notes)) },
            minLines = 2
        )
    }
}

@Composable
private fun ReservedTicketTypesEditorRow(
    allocations: List<ReservedTicketAllocation>,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reserved_ticket_types))
            Text(
                text = reservedTicketTypesDetails(allocations),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Icon(Icons.Filled.ExpandMore, contentDescription = null)
    }
}

@Composable
internal fun ArrivalSection(
    arrivalCount: Int,
    seatCount: Int,
    canEdit: Boolean,
    onEdit: () -> Unit
) {
    DetailSectionTitle(
        icon = Icons.Filled.HowToReg,
        text = stringResource(R.string.arrivals)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.arrival_overview, arrivalCount, seatCount),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (canEdit) {
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.edit))
            }
        }
    }
}

@Composable
internal fun RealizedPaymentsSection(
    ticketSales: List<TicketSale>,
    onEditTicketSale: (TicketSale) -> Unit
) {
    DetailSectionTitle(
        icon = Icons.Filled.Payments,
        text = stringResource(R.string.realized_payments)
    )
    if (ticketSales.isEmpty()) {
        Text(
            text = stringResource(R.string.no_realized_payments),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        ticketSales.forEach { ticketSale ->
            TicketSaleRow(
                ticketSale = ticketSale,
                onEdit = { onEditTicketSale(ticketSale) }
            )
        }
    }
}

@Composable
private fun DetailSectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TicketSaleRow(ticketSale: TicketSale, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ticketSaleTitle(ticketSale),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = ticketSalePaymentText(ticketSale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.edit_ticket_sale)
                )
            }
        }
    }
}

@Composable
private fun ticketSaleTitle(ticketSale: TicketSale): String {
    val ticketTypeLabel = ticketSale.ticketType.displayLabel
    if (ticketSale.ticketType != TicketType.UNSPECIFIED) {
        return stringResource(R.string.ticket_quantity, ticketTypeLabel, ticketSale.quantity)
    }
    val paymentMethod = ticketSale.singlePaymentMethod
        ?: return stringResource(R.string.ticket_quantity, ticketTypeLabel, ticketSale.quantity)
    return stringResource(
        R.string.ticket_quantity,
        paymentMethod.label,
        ticketSale.quantity
    )
}

@Composable
private fun ticketSalePaymentText(ticketSale: TicketSale): String {
    val paymentText = ticketSale.payments.map { payment ->
        stringResource(
            R.string.payment_allocation,
            payment.method.label,
            payment.amountCents.toEuroString()
        )
    }.joinToString(separator = ", ")
    if (ticketSale.origin != TicketSaleOrigin.IMPORTED) {
        return paymentText.ifBlank { stringResource(R.string.ticket_type_free_ticket) }
    }
    if (ticketSale.ticketType == TicketType.UNSPECIFIED) {
        return stringResource(R.string.imported_payment)
    }
    return stringResource(
        R.string.imported_payment_details,
        stringResource(R.string.imported_payment),
        paymentText
    )
}

@Composable
internal fun ReservationDialogHeader(
    title: String,
    isMenuExpanded: Boolean,
    onMenuExpand: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEditReservation: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Box {
            IconButton(onClick = onMenuExpand) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.more_actions)
                )
            }
            DropdownMenu(expanded = isMenuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_reservation)) },
                    onClick = {
                        onMenuDismiss()
                        onEditReservation()
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.delete_reservation_action),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        onMenuDismiss()
                        onDelete()
                    }
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.close)
            )
        }
    }
}
