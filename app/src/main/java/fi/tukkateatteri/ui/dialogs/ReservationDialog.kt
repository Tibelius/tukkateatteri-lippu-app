package fi.tukkateatteri.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.ui.components.PaymentMethodSelector
import fi.tukkateatteri.ui.components.SeatCountSelector
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

private const val DIALOG_WIDTH_FRACTION = 0.94f
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.9f
private val primaryActionHeight = 56.dp

@Composable
fun ReservationDialog(
    reservation: Reservation,
    onDismiss: () -> Unit,
    onSave: (Reservation) -> Unit,
    onDelete: () -> Unit
) {
    val isDoorSale = reservation.admissionType == AdmissionType.DOOR_SALE
    val focusManager = LocalFocusManager.current
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * DIALOG_MAX_HEIGHT_FRACTION
    val nextFieldAction = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Next) }
    )
    val nextFieldOptions = KeyboardOptions(imeAction = ImeAction.Next)
    val nextFieldOptionsNames = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
    var lastName by rememberSaveable(reservation.id) { mutableStateOf(reservation.lastName) }
    var firstName by rememberSaveable(reservation.id) { mutableStateOf(reservation.firstName) }
    var contact by rememberSaveable(reservation.id) { mutableStateOf(reservation.contact) }
    var isPresent by rememberSaveable(reservation.id) { mutableStateOf(reservation.isPresent) }
    var selectedPaymentName by rememberSaveable(reservation.id) {
        mutableStateOf(reservation.paymentMethod?.name)
    }
    var isCustomerDetailsEditing by rememberSaveable(reservation.id) { mutableStateOf(false) }
    var seatCount by rememberSaveable(reservation.id) {
        mutableIntStateOf(reservation.seatCount)
    }
    var isDeleteMenuExpanded by remember(reservation.id) { mutableStateOf(false) }
    var showDiscardConfirmation by rememberSaveable(reservation.id) { mutableStateOf(false) }
    val selectedPayment = PaymentMethod.entries.firstOrNull { it.name == selectedPaymentName }
    val displayName = "$lastName $firstName".trim()
    val hasRequiredCustomerDetails = isDoorSale || (lastName.isNotBlank() && firstName.isNotBlank())

    val hasUnsavedChanges =
        lastName != reservation.lastName ||
            firstName != reservation.firstName ||
            contact != reservation.contact ||
            seatCount != reservation.seatCount ||
            isPresent != reservation.isPresent ||
            selectedPayment != reservation.paymentMethod

    val requestDismiss: () -> Unit = {
        if (hasUnsavedChanges) {
            showDiscardConfirmation = true
        } else {
            onDismiss()
        }
    }

    if (showDiscardConfirmation) {
        DiscardChangesDialog(
            onDismiss = { showDiscardConfirmation = false },
            onDiscard = {
                showDiscardConfirmation = false
                onDismiss()
            }
        )
    } else {
        Dialog(
            onDismissRequest = requestDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(DIALOG_WIDTH_FRACTION)
                    .heightIn(max = maxDialogHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ReservationDialogHeader(
                        displayName = displayName,
                        isDeleteMenuExpanded = isDeleteMenuExpanded,
                        onDeleteMenuExpand = { isDeleteMenuExpanded = true },
                        onDeleteMenuDismiss = { isDeleteMenuExpanded = false },
                        onEditCustomerDetails = { isCustomerDetailsEditing = true },
                        onDelete = onDelete,
                        onDismiss = requestDismiss
                    )

                    AnimatedVisibility(visible = isCustomerDetailsEditing) {
                        Column {
                            Text(
                                text = stringResource(R.string.customer_details),
                                modifier = Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedTextField(
                                    value = lastName,
                                    onValueChange = { lastName = it },
                                    modifier = Modifier.weight(1f),
                                    label = {
                                        Text(
                                            stringResource(
                                                if (isDoorSale) R.string.last_name_optional
                                                else R.string.last_name
                                            )
                                        )
                                    },
                                    keyboardOptions = nextFieldOptionsNames,
                                    keyboardActions = nextFieldAction,
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = firstName,
                                    onValueChange = { firstName = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                    label = {
                                        Text(
                                            stringResource(
                                                if (isDoorSale) R.string.first_name_optional
                                                else R.string.first_name
                                            )
                                        )
                                    },
                                    keyboardOptions = nextFieldOptionsNames,
                                    keyboardActions = nextFieldAction,
                                    singleLine = true
                                )
                            }
                            OutlinedTextField(
                                value = contact,
                                onValueChange = { contact = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                label = {
                                    Text(
                                        stringResource(
                                            if (isDoorSale) R.string.contact_information_optional
                                            else R.string.contact_information
                                        )
                                    )
                                },
                                keyboardOptions = nextFieldOptions,
                                keyboardActions = nextFieldAction,
                                singleLine = true
                            )
                        }
                    }

                    if (!isCustomerDetailsEditing && contact.isNotBlank()) {
                        Text(
                            text = contact,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    SeatCountSelector(
                        seatCount = seatCount,
                        onDecrease = { seatCount-- },
                        onIncrease = { seatCount++ }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    if (isDoorSale) {
                        Text(
                            text = stringResource(R.string.admission_type_door_sale),
                            modifier = Modifier.padding(bottom = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        PaymentMethodSelector(
                            selectedPayment = selectedPayment,
                            onPaymentSelected = { paymentMethod ->
                                selectedPaymentName = if (selectedPayment == paymentMethod) {
                                    null
                                } else {
                                    paymentMethod.name
                                }
                            }
                        )
                    } else {
                        PresenceButton(
                            isPresent = isPresent,
                            onClick = {
                                isPresent = !isPresent
                                if (!isPresent) selectedPaymentName = null
                            }
                        )

                        if (isPresent) {
                            PaymentMethodSelector(
                                selectedPayment = selectedPayment,
                                onPaymentSelected = { paymentMethod ->
                                    selectedPaymentName = if (selectedPayment == paymentMethod) {
                                        null
                                    } else {
                                        paymentMethod.name
                                    }
                                }
                            )
                        }
                    }

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .height(primaryActionHeight),
                        enabled = hasRequiredCustomerDetails &&
                            (!isDoorSale || selectedPayment != null),
                        onClick = {
                            onSave(
                                reservation.copy(
                                    lastName = lastName.trim(),
                                    firstName = firstName.trim(),
                                    contact = contact.trim(),
                                    seatCount = seatCount,
                                    isPresent = isDoorSale || isPresent,
                                    paymentMethod = selectedPayment.takeIf {
                                        isDoorSale || isPresent
                                    }
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscardChangesDialog(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit
) {
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

@Composable
private fun ReservationDialogHeader(
    displayName: String,
    isDeleteMenuExpanded: Boolean,
    onDeleteMenuExpand: () -> Unit,
    onDeleteMenuDismiss: () -> Unit,
    onEditCustomerDetails: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayName.ifBlank {
                stringResource(R.string.admission_type_door_sale)
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Box {
            IconButton(onClick = onDeleteMenuExpand) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.more_actions)
                )
            }

            DropdownMenu(
                expanded = isDeleteMenuExpanded,
                onDismissRequest = onDeleteMenuDismiss
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_customer_details)) },
                    onClick = {
                        onDeleteMenuDismiss()
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
                        onDeleteMenuDismiss()
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
private fun PresenceButton(
    isPresent: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isPresent) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isPresent) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(primaryActionHeight),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        )
    ) {
        Icon(
            imageVector = if (isPresent) Icons.Filled.CheckCircle else Icons.Outlined.Person,
            contentDescription = null
        )
        Text(
            text = stringResource(
                if (isPresent) R.string.present else R.string.mark_as_present
            ),
            modifier = Modifier.padding(start = 12.dp),
            fontWeight = FontWeight.SemiBold
        )
    }
}
