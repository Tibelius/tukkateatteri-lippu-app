package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
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
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString
import fi.tukkateatteri.ui.components.SeatCountSelector
import fi.tukkateatteri.ui.components.ScrollableAppDialog
import fi.tukkateatteri.ui.components.reservedTicketTypesDetails
import fi.tukkateatteri.ui.components.ReservedTicketTypesSummaryCard

@Composable
fun ReservationDialog(
    reservation: Reservation,
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
            maximumQuantity = reservation.unpaidSeatCount,
            reservedTicketAllocations = reservation.reservedTicketAllocations,
            ticketSalesList = reservation.ticketSales,
            onDismiss = { showTicketSaleDialog = false },
            onSave = { ticketType, quantity, payments ->
                onAddTicketSale(ticketType, quantity, payments)
                showTicketSaleDialog = false
            }
        )
    }

    reservation.ticketSales.find { ticketSale -> ticketSale.id == ticketSaleToEditId }?.let { ticketSale ->
        TicketSaleDialog(
            maximumQuantity = reservation.unpaidSeatCount + ticketSale.quantity,
            ticketSale = ticketSale,
            reservedTicketAllocations = reservation.reservedTicketAllocations,
            ticketSalesList = reservation.ticketSales,
            onDismiss = { ticketSaleToEditId = null },
            onSave = { ticketType, quantity, payments ->
                onUpdateTicketSale(ticketSale.id, ticketType, quantity, payments)
                ticketSaleToEditId = null
            },
            onDelete = {
                onDeleteTicketSale(ticketSale.id)
                ticketSaleToEditId = null
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
            onDelete = onDelete,
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
            enabled = reservation.unpaidSeatCount > 0,
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

@Composable
private fun ArrivalCountDialog(
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
private fun ReservationEditorDialog(
    reservation: Reservation,
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
        1,
        reservation.paidSeatCount,
        reservation.arrivalCount,
        reservedTicketAllocations.sumOf(ReservedTicketAllocation::quantity)
    )
    val canSave = isDoorSale || (lastName.isNotBlank() && firstName.isNotBlank())

    var showReservedTicketTypesDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    if (showReservedTicketTypesDialog) {
        ReservedTicketTypesDialog(
            initialAllocations = reservedTicketAllocations,
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
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = {
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
                enabled = canSave
            ) {
                Text(stringResource(R.string.save))
            }
        }
    ) {
        Text(
            text = stringResource(R.string.edit_reservation),
            style = MaterialTheme.typography.headlineSmall
        )
        CustomerDetailsFields(
            isDoorSale = isDoorSale,
            lastName = lastName,
            firstName = firstName,
            contact = contact,
            onLastNameChange = { lastName = it },
            onFirstNameChange = { firstName = it },
            onContactChange = { contact = it }
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
private fun ArrivalSection(
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
private fun RealizedPaymentsSection(
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
private fun CustomerDetailsFields(
    isDoorSale: Boolean,
    lastName: String,
    firstName: String,
    contact: String,
    onLastNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onContactChange: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = lastName,
            onValueChange = onLastNameChange,
            modifier = Modifier.weight(1f),
            label = {
                Text(
                    stringResource(
                        if (isDoorSale) R.string.last_name_optional else R.string.last_name
                    )
                )
            },
            singleLine = true
        )
        OutlinedTextField(
            value = firstName,
            onValueChange = onFirstNameChange,
            modifier = Modifier.weight(1f),
            label = {
                Text(
                    stringResource(
                        if (isDoorSale) R.string.first_name_optional else R.string.first_name
                    )
                )
            },
            singleLine = true
        )
    }
    OutlinedTextField(
        value = contact,
        onValueChange = onContactChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                stringResource(
                    if (isDoorSale) {
                        R.string.contact_information_optional
                    } else {
                        R.string.contact_information
                    }
                )
            )
        },
        singleLine = true
    )
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
    val ticketTypeLabel = stringResource(ticketSale.ticketType.labelResId)
    if (ticketSale.ticketType != TicketType.UNSPECIFIED) {
        return "$ticketTypeLabel × ${ticketSale.quantity}"
    }
    val paymentMethod = ticketSale.singlePaymentMethod ?: return "$ticketTypeLabel × ${ticketSale.quantity}"
    return "${stringResource(paymentMethod.labelResId)} × ${ticketSale.quantity}"
}

@Composable
private fun ticketSalePaymentText(ticketSale: TicketSale): String {
    var paymentText = ""
    for ((index, payment) in ticketSale.payments.withIndex()) {
        if (index > 0) {
            paymentText += ", "
        }
        paymentText += "${stringResource(payment.method.labelResId)} ${payment.amountCents.toEuroString()}"
    }
    if (ticketSale.origin != TicketSaleOrigin.IMPORTED) {
        return paymentText.ifBlank { stringResource(R.string.ticket_type_free_ticket) }
    }
    if (ticketSale.ticketType == TicketType.UNSPECIFIED) {
        return stringResource(R.string.imported_payment)
    }
    return "${stringResource(R.string.imported_payment)} · $paymentText"
}

@Composable
private fun ReservationDialogHeader(
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
