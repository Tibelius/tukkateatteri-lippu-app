package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
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

@Composable
fun ReservationDialog(
    reservation: Reservation,
    onDismiss: () -> Unit,
    onSave: (Reservation) -> Unit,
    onUpdateArrivalCount: (reservationId: Long, arrivalCount: Int) -> Unit,
    onAddTicketSale: (TicketType, Int, List<PendingPaymentAllocation>) -> Unit,
    onDeleteTicketSale: (Long) -> Unit,
    onDelete: () -> Unit
) {
    val isDoorSale = reservation.admissionType == AdmissionType.DOOR_SALE
    var lastName by rememberSaveable(reservation.id) { mutableStateOf(reservation.lastName) }
    var firstName by rememberSaveable(reservation.id) { mutableStateOf(reservation.firstName) }
    var contact by rememberSaveable(reservation.id) { mutableStateOf(reservation.contact) }
    var notes by rememberSaveable(reservation.id) { mutableStateOf(reservation.notes) }
    var seatCount by rememberSaveable(reservation.id) { mutableIntStateOf(reservation.seatCount) }
    var showCustomerDetails by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var showNotes by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var reservedTicketAllocations by rememberSaveable(
        reservation.id,
        stateSaver = reservedTicketAllocationsSaver
    ) {
        mutableStateOf(reservation.reservedTicketAllocations)
    }
    var showTicketSaleDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var showReservedTicketTypesDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var showArrivalDialog by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showDiscardConfirmation by rememberSaveable(reservation.id) { mutableStateOf(false) }
    val hasCustomerDetails = isDoorSale || (lastName.isNotBlank() && firstName.isNotBlank())
    val hasChanges = lastName != reservation.lastName || firstName != reservation.firstName ||
        contact != reservation.contact || notes != reservation.notes || seatCount != reservation.seatCount ||
        reservedTicketAllocations != reservation.reservedTicketAllocations
    val requestDismiss = {
        if (hasChanges) showDiscardConfirmation = true else onDismiss()
    }

    if (showTicketSaleDialog) {
        TicketSaleDialog(
            maximumQuantity = reservation.unpaidSeatCount,
            onDismiss = { showTicketSaleDialog = false },
            onSave = { ticketType, quantity, payments ->
                onAddTicketSale(ticketType, quantity, payments)
                showTicketSaleDialog = false
            }
        )
    }

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

    if (showDiscardConfirmation) {
        DiscardChangesDialog(onDismiss = { showDiscardConfirmation = false }, onDiscard = onDismiss)
        return
    }

    ScrollableAppDialog(onDismissRequest = requestDismiss) {
        ReservationDialogHeader(
            title = reservation.displayName.ifBlank {
                stringResource(R.string.admission_type_door_sale)
            },
            isMenuExpanded = isMenuExpanded,
            onMenuExpand = { isMenuExpanded = true },
            onMenuDismiss = { isMenuExpanded = false },
            onEditCustomerDetails = { showCustomerDetails = true },
            onDelete = onDelete,
            onDismiss = requestDismiss
        )
        Text(
            text = stringResource(
                R.string.payment_overview,
                reservation.paidSeatCount,
                seatCount,
                (seatCount - reservation.paidSeatCount).coerceAtLeast(0)
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )

        CustomerDetailsSection(
            isDoorSale = isDoorSale,
            isExpanded = showCustomerDetails,
            lastName = lastName,
            firstName = firstName,
            contact = contact,
            onLastNameChange = { lastName = it },
            onFirstNameChange = { firstName = it },
            onContactChange = { contact = it },
            onExpandedChange = { showCustomerDetails = it }
        )
        SeatCountSelector(
            seatCount = seatCount,
            minimumSeatCount = maxOf(
                1,
                reservation.paidSeatCount,
                reservation.arrivalCount,
                reservedTicketAllocations.sumOf(ReservedTicketAllocation::quantity)
            ),
            onDecrease = { seatCount-- },
            onIncrease = { seatCount++ }
        )
        if (!isDoorSale) {
            TextButton(onClick = { showReservedTicketTypesDialog = true }) {
                Text(
                    "${stringResource(R.string.reserved_ticket_types)}: " +
                        reservedTicketTypesSummary(reservedTicketAllocations)
                )
            }
        }
        NotesSection(
            isExpanded = showNotes,
            notes = notes,
            onNotesChange = { notes = it },
            onExpandedChange = { showNotes = it }
        )
        HorizontalDivider()
        if (reservation.arrivalCount > 0 || reservation.paidSeatCount > 0) {
            ArrivalSection(
                arrivalCount = reservation.arrivalCount,
                seatCount = seatCount,
                canEdit = !isDoorSale && reservation.arrivalCount < reservation.paidSeatCount,
                onEdit = { showArrivalDialog = true }
            )
            HorizontalDivider()
        }
        RealizedPaymentsSection(
            ticketSales = reservation.ticketSales,
            onDeleteTicketSale = onDeleteTicketSale
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
            enabled = hasCustomerDetails,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save))
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
private fun CustomerDetailsSection(
    isDoorSale: Boolean,
    isExpanded: Boolean,
    lastName: String,
    firstName: String,
    contact: String,
    onLastNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onContactChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit
) {
    if (isExpanded) {
        CustomerDetailsFields(
            isDoorSale = isDoorSale,
            lastName = lastName,
            firstName = firstName,
            contact = contact,
            onLastNameChange = onLastNameChange,
            onFirstNameChange = onFirstNameChange,
            onContactChange = onContactChange
        )
    }
    TextButton(onClick = { onExpandedChange(!isExpanded) }) {
        Text(
            stringResource(
                when {
                    isExpanded -> R.string.hide_customer_details
                    isDoorSale -> R.string.show_optional_customer_details
                    else -> R.string.show_customer_details
                }
            )
        )
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null
        )
    }
}

@Composable
private fun NotesSection(
    isExpanded: Boolean,
    notes: String,
    onNotesChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit
) {
    if (isExpanded) {
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.reservation_notes)) },
            minLines = 2
        )
    }
    TextButton(onClick = { onExpandedChange(!isExpanded) }) {
        Text(stringResource(if (isExpanded) R.string.hide_notes else R.string.show_notes))
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null
        )
    }
}

@Composable
private fun ArrivalSection(
    arrivalCount: Int,
    seatCount: Int,
    canEdit: Boolean,
    onEdit: () -> Unit
) {
    Text(
        text = stringResource(R.string.arrivals),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
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
    onDeleteTicketSale: (Long) -> Unit
) {
    Text(
        text = stringResource(R.string.realized_payments),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
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
                onDelete = { onDeleteTicketSale(ticketSale.id) }
            )
        }
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
private fun TicketSaleRow(ticketSale: TicketSale, onDelete: () -> Unit) {
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
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete)
                )
            }
        }
    }
}

@Composable
private fun ticketSaleTitle(ticketSale: TicketSale): String {
    val ticketTypeLabel = stringResource(ticketSale.ticketType.labelResId)
    if (ticketSale.origin != TicketSaleOrigin.IMPORTED) {
        return "$ticketTypeLabel × ${ticketSale.quantity}"
    }
    val paymentMethod = ticketSale.singlePaymentMethod ?: return "$ticketTypeLabel × ${ticketSale.quantity}"
    return "${stringResource(paymentMethod.labelResId)} × ${ticketSale.quantity}"
}

@Composable
private fun ticketSalePaymentText(ticketSale: TicketSale): String {
    if (ticketSale.payments.isEmpty()) {
        return stringResource(R.string.ticket_type_free_ticket)
    }
    if (ticketSale.origin == TicketSaleOrigin.IMPORTED) {
        return stringResource(R.string.imported_payment)
    }

    var paymentText = ""
    for ((index, payment) in ticketSale.payments.withIndex()) {
        if (index > 0) {
            paymentText += ", "
        }
        paymentText += "${stringResource(payment.method.labelResId)} ${payment.amountCents.toEuroString()}"
    }
    return paymentText
}

@Composable
private fun ReservationDialogHeader(
    title: String,
    isMenuExpanded: Boolean,
    onMenuExpand: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEditCustomerDetails: () -> Unit,
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
                    text = { Text(stringResource(R.string.edit_customer_details)) },
                    onClick = {
                        onMenuDismiss()
                        onEditCustomerDetails()
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

@Composable
private fun DiscardChangesDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discard_changes_title)) },
        text = { Text(stringResource(R.string.discard_changes_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.discard_changes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.continue_editing))
            }
        }
    )
}
