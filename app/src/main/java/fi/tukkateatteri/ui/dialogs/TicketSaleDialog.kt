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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.tukkateatteri.R
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.ui.components.PaymentMethodSelector
import fi.tukkateatteri.ui.components.SeatCountSelector

@Composable
fun TicketSaleDialog(
    maximumQuantity: Int,
    onDismiss: () -> Unit,
    onSave: (TicketType, Int, List<PendingPaymentAllocation>) -> Unit
) {
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    var ticketTypeName by rememberSaveable { mutableStateOf(TicketType.BASIC.name) }
    var quantity by rememberSaveable { mutableIntStateOf(1) }
    var isTicketTypeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedPaymentName by rememberSaveable { mutableStateOf<String?>(null) }
    var isSplitPayment by rememberSaveable { mutableStateOf(false) }
    var firstSplitMethodName by rememberSaveable { mutableStateOf(PaymentMethod.CASH.name) }
    var secondSplitMethodName by rememberSaveable { mutableStateOf(PaymentMethod.CARD.name) }
    var firstSplitAmount by rememberSaveable { mutableStateOf("") }
    var secondSplitAmount by rememberSaveable { mutableStateOf("") }
    val ticketType = TicketType.valueOf(ticketTypeName)
    val totalPriceCents = ticketType.defaultPriceCents * quantity
    val selectedPayment = selectedPaymentName?.let(PaymentMethod::valueOf)
    val firstSplitMethod = PaymentMethod.valueOf(firstSplitMethodName)
    val secondSplitMethod = PaymentMethod.valueOf(secondSplitMethodName)
    val firstSplitCents = firstSplitAmount.toCentsOrNull()
    val secondSplitCents = secondSplitAmount.toCentsOrNull()
    val payments = when {
        totalPriceCents == 0 -> emptyList()
        !isSplitPayment && selectedPayment != null -> listOf(PendingPaymentAllocation(selectedPayment, totalPriceCents))
        isSplitPayment && firstSplitCents != null && secondSplitCents != null -> listOf(
            PendingPaymentAllocation(firstSplitMethod, firstSplitCents),
            PendingPaymentAllocation(secondSplitMethod, secondSplitCents)
        )
        else -> emptyList()
    }
    val isPaymentValid = totalPriceCents == 0 || (payments.isNotEmpty() && payments.sumOf(PendingPaymentAllocation::amountCents) == totalPriceCents)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = maxDialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.add_ticket_sale), style = MaterialTheme.typography.headlineSmall)
                Box {
                    Button(onClick = { isTicketTypeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(ticketType.labelResId))
                    }
                    DropdownMenu(expanded = isTicketTypeMenuExpanded, onDismissRequest = { isTicketTypeMenuExpanded = false }) {
                        TicketType.entries.filterNot { it == TicketType.UNSPECIFIED }.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelResId)) },
                                onClick = { ticketTypeName = option.name; isTicketTypeMenuExpanded = false }
                            )
                        }
                    }
                }
                SeatCountSelector(
                    seatCount = quantity,
                    onDecrease = { quantity-- },
                    onIncrease = { if (quantity < maximumQuantity) quantity++ }
                )
                Text("${stringResource(R.string.payment_amount)}: ${formatCents(totalPriceCents)}", style = MaterialTheme.typography.titleMedium)
                if (totalPriceCents > 0) {
                    if (!isSplitPayment) {
                        PaymentMethodSelector(selectedPayment = selectedPayment, onPaymentSelected = { method -> selectedPaymentName = method.name })
                        TextButton(onClick = { isSplitPayment = true }) { Text(stringResource(R.string.split_payment)) }
                    } else {
                        Text(stringResource(R.string.split_payment), style = MaterialTheme.typography.titleMedium)
                        PaymentMethodDropdown(selected = firstSplitMethod, onSelected = { firstSplitMethodName = it.name })
                        OutlinedTextField(value = firstSplitAmount, onValueChange = { firstSplitAmount = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.payment_amount)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        PaymentMethodDropdown(selected = secondSplitMethod, onSelected = { secondSplitMethodName = it.name })
                        OutlinedTextField(value = secondSplitAmount, onValueChange = { secondSplitAmount = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.payment_amount)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        if (!isPaymentValid) Text(stringResource(R.string.payment_total_mismatch), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { isSplitPayment = false }) { Text(stringResource(R.string.single_payment)) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { onSave(ticketType, quantity, payments) }, enabled = quantity in 1..maximumQuantity && isPaymentValid) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodDropdown(selected: PaymentMethod, onSelected: (PaymentMethod) -> Unit) {
    var expanded by rememberSaveable(selected) { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(selected.labelResId)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PaymentMethod.entries.forEach { method ->
                DropdownMenuItem(text = { Text(stringResource(method.labelResId)) }, onClick = { onSelected(method); expanded = false })
            }
        }
    }
}

private fun String.toCentsOrNull(): Int? {
    val normalizedValue = trim().replace(',', '.')
    if (normalizedValue.isEmpty()) return null
    return normalizedValue.toBigDecimalOrNull()?.movePointRight(2)?.toInt()
}
