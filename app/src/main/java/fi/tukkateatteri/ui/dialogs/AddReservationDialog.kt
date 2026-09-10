package fi.tukkateatteri.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.MINIMUM_SEAT_COUNT
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.ui.components.CancelSaveActions
import fi.tukkateatteri.ui.components.CustomerDetailsFields
import fi.tukkateatteri.ui.components.SeatCountSelector
import fi.tukkateatteri.ui.components.ScrollableAppDialog

@Composable
fun AddAdmissionDialog(
    admissionType: AdmissionType,
    availableTicketTypes: List<TicketType>,
    onDismiss: () -> Unit,
    onSave: (
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        reservedTicketAllocations: List<ReservedTicketAllocation>
    ) -> Unit
) {
    val isDoorSale = admissionType == AdmissionType.DOOR_SALE
    var lastName by rememberSaveable(admissionType) { mutableStateOf("") }
    var firstName by rememberSaveable(admissionType) { mutableStateOf("") }
    var contact by rememberSaveable(admissionType) { mutableStateOf("") }
    var seatCount by rememberSaveable(admissionType) { mutableIntStateOf(MINIMUM_SEAT_COUNT) }
    var showCustomerDetails by rememberSaveable(admissionType) { mutableStateOf(false) }
    var reservedTicketAllocations by rememberSaveable(
        admissionType,
        stateSaver = reservedTicketAllocationsSaver
    ) { mutableStateOf(emptyList<ReservedTicketAllocation>()) }
    var showReservedTicketTypesDialog by rememberSaveable(admissionType) { mutableStateOf(false) }

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
                    onSave(lastName, firstName, contact, seatCount, reservedTicketAllocations)
                },
                saveEnabled = isDoorSale || (lastName.isNotBlank() && firstName.isNotBlank())
            )
        }
    ) {
        Text(
            text = stringResource(admissionType.labelResId),
            style = MaterialTheme.typography.headlineSmall
        )

        if (isDoorSale) {
            SeatCountSelector(
                seatCount = seatCount,
                onDecrease = { seatCount-- },
                onIncrease = { seatCount++ }
            )
            Text(
                text = stringResource(R.string.optional_customer_details),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = { showCustomerDetails = !showCustomerDetails }) {
                Text(
                    stringResource(
                        if (showCustomerDetails) R.string.hide_optional_customer_details
                        else R.string.show_optional_customer_details
                    )
                )
                Icon(
                    imageVector = if (showCustomerDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
        }

        AnimatedVisibility(visible = !isDoorSale || showCustomerDetails) {
            CustomerDetailsFields(
                lastName = lastName,
                firstName = firstName,
                contact = contact,
                onLastNameChange = { lastName = it },
                onFirstNameChange = { firstName = it },
                onContactChange = { contact = it },
                fieldsAreOptional = isDoorSale
            )
        }

        if (!isDoorSale) {
            SeatCountSelector(
                seatCount = seatCount,
                minimumSeatCount = reservedTicketAllocations
                    .sumOf(ReservedTicketAllocation::quantity)
                    .coerceAtLeast(MINIMUM_SEAT_COUNT),
                onDecrease = { seatCount-- },
                onIncrease = { seatCount++ }
            )
            TextButton(onClick = { showReservedTicketTypesDialog = true }) {
                Text(
                    stringResource(
                        R.string.label_with_value,
                        stringResource(R.string.reserved_ticket_types),
                        reservedTicketTypesSummary(reservedTicketAllocations)
                    )
                )
            }
        }

    }
}
