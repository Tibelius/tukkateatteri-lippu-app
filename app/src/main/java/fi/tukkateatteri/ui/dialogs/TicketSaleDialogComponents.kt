package fi.tukkateatteri.ui.dialogs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toEuroString
import fi.tukkateatteri.ui.components.PaymentMethodSelector

@Composable
internal fun PaymentEntry(
    selectedMethod: PaymentMethod?,
    methods: List<PaymentMethod>,
    amount: String,
    onMethodSelected: (PaymentMethod) -> Unit,
    onAmountChanged: (String) -> Unit
) {
    PaymentMethodSelector(selectedMethod, methods, onMethodSelected)
    OutlinedTextField(
        value = amount,
        onValueChange = onAmountChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.payment_amount)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}

@Composable
internal fun PaymentAllocationList(
    payments: List<StoredPayment>,
    methods: List<PaymentMethod>,
    editingIndex: Int?,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(PAYMENT_LIST_SPACING)) {
        payments.forEachIndexed { index, payment ->
            val method = methods.firstOrNull { it.name == payment.methodName }
            val methodLabel = method?.label ?: payment.methodName
            val displayedMethodLabel = if (method == PaymentMethod.CARD) {
                stringResource(R.string.zettle_payment_method, methodLabel)
            } else {
                methodLabel
            }
            val paymentIsLocked = payment.isLocked(methods)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (paymentIsLocked) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = PAYMENT_CARD_HORIZONTAL_PADDING,
                            vertical = PAYMENT_CARD_VERTICAL_PADDING
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.payment_allocation,
                            displayedMethodLabel,
                            payment.amountCents.toEuroString()
                        ),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (paymentIsLocked) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.terminal_payment_locked)
                        )
                    } else if (editingIndex != index) {
                        IconButton(onClick = { onEdit(index) }) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.edit_payment))
                        }
                        IconButton(onClick = { onDelete(index) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete_payment))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompletedPaymentWarning(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_completed_payment_title)) },
        text = { Text(stringResource(R.string.change_completed_payment_message)) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.continue_action)) } }
    )
}

@Composable
internal fun TerminalPaymentConfirmation(
    quantity: Int,
    ticketType: TicketType,
    amount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_card_terminal_payment_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TERMINAL_CONFIRMATION_CONTENT_SPACING)) {
                Text(
                    text = stringResource(
                        R.string.confirm_card_terminal_payment_message_ticket,
                        quantity,
                        ticketType.displayLabel
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.confirm_card_terminal_payment_message_total,
                        amount.toEuroString()
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.confirm_card_terminal_payment_message_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.charge_card_terminal_confirm))
            }
        }
    )
}

@Composable
internal fun TerminalPaymentResultDialog(
    @StringRes messageResId: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_terminal_payment_not_recorded)) },
        text = { Text(stringResource(messageResId)) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
internal fun TicketTypeDropdown(
    selected: TicketType,
    options: List<TicketType>,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (TicketType) -> Unit
) {
    Box {
        Button(
            onClick = onExpand,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected.displayLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayLabel) },
                    onClick = { onSelect(option) }
                )
            }
        }
    }
}

private val PAYMENT_LIST_SPACING = 8.dp
private val PAYMENT_CARD_HORIZONTAL_PADDING = 12.dp
private val PAYMENT_CARD_VERTICAL_PADDING = 8.dp
private val TERMINAL_CONFIRMATION_CONTENT_SPACING = 12.dp
