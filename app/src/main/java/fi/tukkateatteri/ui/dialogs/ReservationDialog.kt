package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.ui.components.SeatCountSelector

private const val DIALOG_WIDTH_FRACTION = 0.94f
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.9f

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
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * DIALOG_MAX_HEIGHT_FRACTION
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

    Dialog(onDismissRequest = requestDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION).heightIn(max = maxDialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReservationDialogHeader(
                    title = reservation.displayName.ifBlank { stringResource(R.string.admission_type_door_sale) },
                    isMenuExpanded = isMenuExpanded,
                    onMenuExpand = { isMenuExpanded = true },
                    onMenuDismiss = { isMenuExpanded = false },
                    onEditCustomerDetails = { showCustomerDetails = true },
                    onDelete = onDelete,
                    onDismiss = requestDismiss
                )
                Text(
                    stringResource(
                        R.string.payment_overview,
                        reservation.paidSeatCount,
                        seatCount,
                        (seatCount - reservation.paidSeatCount).coerceAtLeast(0)
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (showCustomerDetails) {
                    CustomerDetailsFields(
                        isDoorSale = isDoorSale,
                        lastName = lastName,
                        firstName = firstName,
                        contact = contact,
                        onLastNameChange = { lastName = it },
                        onFirstNameChange = { firstName = it },
                        onContactChange = { contact = it }
                    )
                    TextButton(onClick = { showCustomerDetails = false }) {
                        Text(stringResource(R.string.hide_customer_details))
                        Icon(Icons.Filled.ExpandLess, null)
                    }
                } else {
                    TextButton(onClick = { showCustomerDetails = true }) {
                        Text(stringResource(if (isDoorSale) R.string.show_optional_customer_details else R.string.show_customer_details))
                        Icon(Icons.Filled.ExpandMore, null)
                    }
                }

                SeatCountSelector(
                    seatCount = seatCount,
                    minimumSeatCount = maxOf(
                        1,
                        reservation.paidSeatCount,
                        reservation.arrivalCount,
                        reservedTicketAllocations.sumOf { allocation -> allocation.quantity }
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
                if (showNotes) {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.reservation_notes)) },
                        minLines = 2
                    )
                    TextButton(onClick = { showNotes = false }) {
                        Text(stringResource(R.string.hide_notes))
                        Icon(Icons.Filled.ExpandLess, null)
                    }
                } else {
                    TextButton(onClick = { showNotes = true }) {
                        Text(stringResource(R.string.show_notes))
                        Icon(Icons.Filled.ExpandMore, null)
                    }
                }
                HorizontalDivider()
                if (reservation.arrivalCount > 0 || reservation.paidSeatCount > 0) {
                    Text(stringResource(R.string.arrivals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.arrival_overview, reservation.arrivalCount, seatCount),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isDoorSale && reservation.arrivalCount < reservation.paidSeatCount) {
                            TextButton(onClick = { showArrivalDialog = true }) {
                                Text(stringResource(R.string.edit))
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Text(stringResource(R.string.realized_payments), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (reservation.ticketSales.isEmpty()) {
                    Text(stringResource(R.string.no_realized_payments), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    reservation.ticketSales.forEach { ticketSale ->
                        TicketSaleRow(ticketSale = ticketSale, onDelete = { onDeleteTicketSale(ticketSale.id) })
                    }
                }
                Button(
                    onClick = { showTicketSaleDialog = true },
                    enabled = reservation.unpaidSeatCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, null)
                    Text(stringResource(R.string.add_ticket_sale), modifier = Modifier.padding(start = 8.dp))
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
                ) { Text(stringResource(R.string.save)) }
            }
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
        OutlinedTextField(value = lastName, onValueChange = onLastNameChange, modifier = Modifier.weight(1f), label = { Text(stringResource(if (isDoorSale) R.string.last_name_optional else R.string.last_name)) }, singleLine = true)
        OutlinedTextField(value = firstName, onValueChange = onFirstNameChange, modifier = Modifier.weight(1f), label = { Text(stringResource(if (isDoorSale) R.string.first_name_optional else R.string.first_name)) }, singleLine = true)
    }
    OutlinedTextField(value = contact, onValueChange = onContactChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(if (isDoorSale) R.string.contact_information_optional else R.string.contact_information)) }, singleLine = true)
}

@Composable
private fun TicketSaleRow(ticketSale: TicketSale, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val title = if (ticketSale.origin == TicketSaleOrigin.IMPORTED) {
                    ticketSale.payments.singleOrNull()?.let { payment ->
                        "${stringResource(payment.method.labelResId)} × ${ticketSale.quantity}"
                    } ?: "${stringResource(ticketSale.ticketType.labelResId)} × ${ticketSale.quantity}"
                } else {
                    "${stringResource(ticketSale.ticketType.labelResId)} × ${ticketSale.quantity}"
                }
                Text(title, fontWeight = FontWeight.SemiBold)
                val paymentText = if (ticketSale.payments.isEmpty()) {
                    stringResource(R.string.ticket_type_free_ticket)
                } else if (ticketSale.origin == TicketSaleOrigin.IMPORTED) {
                    stringResource(R.string.imported_payment)
                } else {
                    buildString {
                        ticketSale.payments.forEachIndexed { index, payment ->
                            if (index > 0) append(", ")
                            append(stringResource(payment.method.labelResId))
                            append(' ')
                            append(formatCents(payment.amountCents))
                        }
                    }
                }
                Text(paymentText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun ReservationDialogHeader(title: String, isMenuExpanded: Boolean, onMenuExpand: () -> Unit, onMenuDismiss: () -> Unit, onEditCustomerDetails: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = onMenuExpand) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
            DropdownMenu(expanded = isMenuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(text = { Text(stringResource(R.string.edit_customer_details)) }, onClick = { onMenuDismiss(); onEditCustomerDetails() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_reservation_action), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onMenuDismiss(); onDelete() })
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
    }
}

@Composable
private fun DiscardChangesDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.discard_changes_title)) }, text = { Text(stringResource(R.string.discard_changes_message)) }, confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard_changes)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.continue_editing)) } })
}

internal fun formatCents(amountCents: Int): String = "${amountCents / 100},${(amountCents % 100).toString().padStart(2, '0')} €"
