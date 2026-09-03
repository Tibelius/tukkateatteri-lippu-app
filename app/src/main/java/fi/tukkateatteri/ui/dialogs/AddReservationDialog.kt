package fi.tukkateatteri.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.ui.components.SeatCountSelector

private const val DIALOG_WIDTH_FRACTION = 0.94f
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.9f

@Composable
fun AddAdmissionDialog(
    admissionType: AdmissionType,
    onDismiss: () -> Unit,
    onSave: (
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int
    ) -> Unit
) {
    val isDoorSale = admissionType == AdmissionType.DOOR_SALE
    val focusManager = LocalFocusManager.current
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * DIALOG_MAX_HEIGHT_FRACTION
    val nextFieldAction = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Next) }
    )
    val nextFieldOptions = KeyboardOptions(imeAction = ImeAction.Next)
    val nextFieldOptionsNames = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
    var lastName by rememberSaveable(admissionType) { mutableStateOf("") }
    var firstName by rememberSaveable(admissionType) { mutableStateOf("") }
    var contact by rememberSaveable(admissionType) { mutableStateOf("") }
    var seatCount by rememberSaveable(admissionType) { mutableIntStateOf(1) }
    var showCustomerDetails by rememberSaveable(admissionType) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
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
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            imageVector = if (showCustomerDetails) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = null
                        )
                    }
                }

                AnimatedVisibility(visible = !isDoorSale || showCustomerDetails) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = lastName,
                                onValueChange = { lastName = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.last_name)) },
                                keyboardOptions = nextFieldOptionsNames,
                                keyboardActions = nextFieldAction,
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = firstName,
                                onValueChange = { firstName = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.first_name)) },
                                keyboardOptions = nextFieldOptionsNames,
                                keyboardActions = nextFieldAction,
                                singleLine = true
                            )
                        }

                        OutlinedTextField(
                            value = contact,
                            onValueChange = { contact = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.contact_information)) },
                            keyboardOptions = nextFieldOptions,
                            keyboardActions = nextFieldAction,
                            singleLine = true
                        )
                    }
                }

                if (!isDoorSale) {
                    SeatCountSelector(
                        seatCount = seatCount,
                        onDecrease = { seatCount-- },
                        onIncrease = { seatCount++ }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        enabled = isDoorSale || lastName.isNotBlank() && firstName.isNotBlank(),
                        onClick = {
                            onSave(lastName, firstName, contact, seatCount)
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}
