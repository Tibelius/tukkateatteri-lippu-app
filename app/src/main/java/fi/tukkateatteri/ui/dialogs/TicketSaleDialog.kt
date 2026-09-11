package fi.tukkateatteri.ui.dialogs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toDecimalInput
import fi.tukkateatteri.data.toEuroCentsOrNull
import fi.tukkateatteri.data.toEuroString
import fi.tukkateatteri.payment.CardPaymentOutcome
import fi.tukkateatteri.payment.CardPaymentRequest
import fi.tukkateatteri.payment.createCardPaymentGateway
import fi.tukkateatteri.ui.components.CancelSaveActions
import fi.tukkateatteri.ui.components.PaymentMethodSelector
import fi.tukkateatteri.ui.components.SeatCountSelector
import fi.tukkateatteri.ui.components.ScrollableAppDialog
import fi.tukkateatteri.ui.components.RemainingReservedTicketTypesSummaryCard
import java.util.UUID

@Composable
fun TicketSaleDialog(
    maximumQuantity: Int,
    ticketSale: TicketSale? = null,
    reservedTicketAllocations: List<ReservedTicketAllocation> = emptyList(),
    ticketSalesList: List<TicketSale> = emptyList(),
    availableTicketTypes: List<TicketType> = TicketType.entries.filterNot { it == TicketType.UNSPECIFIED },
    availablePaymentMethods: List<PaymentMethod> = PaymentMethod.entries,
    onDismiss: () -> Unit,
    onSave: (TicketType, Int, List<PendingPaymentAllocation>) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val cardPaymentGateway = remember { createCardPaymentGateway() }
    val initialFirstPayment = ticketSale?.payments?.getOrNull(0)
    val initialSecondPayment = ticketSale?.payments?.getOrNull(1)
    var quantity by rememberSaveable(ticketSale?.id) {
        mutableIntStateOf(ticketSale?.quantity ?: 1)
    }
    var isTicketTypeMenuExpanded by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var selectedPaymentName by rememberSaveable(ticketSale?.id) {
        mutableStateOf(ticketSale?.singlePaymentMethod?.name)
    }
    var isSplitPayment by rememberSaveable(ticketSale?.id) {
        mutableStateOf(ticketSale?.isSplitPayment == true)
    }
    var firstSplitMethodName by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialFirstPayment?.method?.name ?: PaymentMethod.CASH.name)
    }
    var secondSplitMethodName by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialSecondPayment?.method?.name ?: PaymentMethod.CARD.name)
    }
    var firstSplitAmount by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialFirstPayment?.amountCents?.toDecimalInput().orEmpty())
    }
    var secondSplitAmount by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialSecondPayment?.amountCents?.toDecimalInput().orEmpty())
    }
    var showCardPaymentConfirmation by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var pendingCardTicketTypeName by rememberSaveable(ticketSale?.id) { mutableStateOf<String?>(null) }
    var pendingCardPaymentMethodName by rememberSaveable(ticketSale?.id) { mutableStateOf<String?>(null) }
    var pendingCardQuantity by rememberSaveable(ticketSale?.id) { mutableIntStateOf(0) }
    var pendingCardAmountCents by rememberSaveable(ticketSale?.id) { mutableIntStateOf(0) }
    var cardPaymentError by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }

    val redeemedByType = ticketSalesList
        .filterNot { sale -> sale.id == ticketSale?.id }
        .groupBy(TicketSale::ticketType)
        .mapValues { (_, sales) -> sales.sumOf(TicketSale::quantity) }

    val remainingAllocations = reservedTicketAllocations.mapNotNull { allocation ->
        val remainingQuantity = allocation.quantity -
            redeemedByType.getOrDefault(allocation.ticketType, 0)

        remainingQuantity
            .takeIf { it > 0 }
            ?.let { allocation.copy(quantity = it) }
    }

    val initialTicketType = ticketSale?.ticketType?.takeUnless { type -> type == TicketType.UNSPECIFIED }
        ?: remainingAllocations.firstOrNull()?.ticketType
        ?: TicketType.BASIC

    var ticketTypeName by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialTicketType.name)
    }
    val ticketOptions = (availableTicketTypes + listOfNotNull(ticketSale?.ticketType)).distinctBy(TicketType::name)
    val ticketType = ticketOptions.firstOrNull { it.name == ticketTypeName } ?: ticketOptions.first()
    val totalPriceCents = ticketType.defaultPriceCents * quantity
    val paymentOptions = (availablePaymentMethods + ticketSale?.payments.orEmpty().map { it.method })
        .distinctBy(PaymentMethod::name)
        .ifEmpty { PaymentMethod.entries }
    val splitPaymentOptions = paymentOptions.filter(PaymentMethod::allowsSplitPayment)
    val selectedPayment = selectedPaymentName?.let { name -> paymentOptions.firstOrNull { it.name == name } }
    val firstSplitMethod = paymentOptions.firstOrNull { it.name == firstSplitMethodName } ?: paymentOptions.first()
    val secondSplitMethod = paymentOptions.firstOrNull { it.name == secondSplitMethodName } ?: paymentOptions.last()
    val firstSplitCents = firstSplitAmount.toEuroCentsOrNull()
    val secondSplitCents = secondSplitAmount.toEuroCentsOrNull()
    val payments = when {
        totalPriceCents == 0 -> emptyList()
        !isSplitPayment && selectedPayment != null -> listOf(PendingPaymentAllocation(selectedPayment, totalPriceCents))
        isSplitPayment && firstSplitCents != null && secondSplitCents != null -> listOf(
            PendingPaymentAllocation(firstSplitMethod, firstSplitCents),
            PendingPaymentAllocation(secondSplitMethod, secondSplitCents)
        )
        else -> emptyList()
    }
    val isPaymentValid = totalPriceCents == 0 || (
        payments.isNotEmpty() &&
            payments.sumOf(PendingPaymentAllocation::amountCents) == totalPriceCents &&
            (!isSplitPayment || (
                firstSplitMethod != secondSplitMethod &&
                    firstSplitMethod.allowsSplitPayment &&
                    secondSplitMethod.allowsSplitPayment
                ))
        )
    val canChargeWithTerminal = canChargeWithTerminal(
        isGatewayAvailable = cardPaymentGateway.isAvailable,
        isNewSale = ticketSale == null,
        isSplitPayment = isSplitPayment,
        selectedPayment = selectedPayment,
        totalPriceCents = totalPriceCents,
        quantity = quantity,
        maximumQuantity = maximumQuantity,
        isPaymentValid = isPaymentValid
    )
    val cardPaymentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pendingTicketType = ticketOptions.firstOrNull { it.name == pendingCardTicketTypeName }
        val pendingPaymentMethod = paymentOptions.firstOrNull { it.name == pendingCardPaymentMethodName }
        when (val outcome = cardPaymentGateway.parsePaymentResult(result)) {
            is CardPaymentOutcome.Completed -> {
                if (
                    pendingTicketType == null ||
                    pendingPaymentMethod == null ||
                    pendingCardQuantity <= 0 ||
                    outcome.amountCents != pendingCardAmountCents.toLong()
                ) {
                    cardPaymentError = true
                } else {
                    onSave(
                        pendingTicketType,
                        pendingCardQuantity,
                        listOf(
                            PendingPaymentAllocation(
                                method = pendingPaymentMethod,
                                amountCents = pendingCardAmountCents
                            )
                        )
                    )
                }
            }

            CardPaymentOutcome.Cancelled -> Unit
            is CardPaymentOutcome.Failed -> cardPaymentError = true
        }
        pendingCardTicketTypeName = null
        pendingCardPaymentMethodName = null
        pendingCardQuantity = 0
        pendingCardAmountCents = 0
    }

    ScrollableAppDialog(
        onDismissRequest = onDismiss,
        actions = {
            onDelete?.let { delete ->
                TextButton(onClick = delete) {
                    Text(
                        text = stringResource(R.string.delete_ticket_sale),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            CancelSaveActions(
                onCancel = onDismiss,
                onSave = { onSave(ticketType, quantity, payments) },
                saveEnabled = quantity in 1..maximumQuantity && isPaymentValid
            )
        }
    ) {
        Text(
            text = stringResource(
                if (ticketSale == null) R.string.add_ticket_sale else R.string.edit_ticket_sale
            ),
            style = MaterialTheme.typography.headlineSmall
        )

        if (reservedTicketAllocations.isNotEmpty()) {
            RemainingReservedTicketTypesSummaryCard(remainingAllocations)
        }

        Text(
            text = stringResource(R.string.ticket_type),
            style = MaterialTheme.typography.titleMedium
        )
        TicketTypeDropdown(
            selectedTicketType = ticketType,
            expanded = isTicketTypeMenuExpanded,
            onExpand = { isTicketTypeMenuExpanded = true },
            onDismiss = { isTicketTypeMenuExpanded = false },
            onSelected = { option ->
                ticketTypeName = option.name
                isTicketTypeMenuExpanded = false
            },
            options = ticketOptions
        )
        SeatCountSelector(
            seatCount = quantity,
            maximumSeatCount = maximumQuantity,
            onDecrease = { quantity-- },
            onIncrease = { if (quantity < maximumQuantity) quantity++ }
        )
        Text(
            text = stringResource(
                R.string.label_with_value,
                stringResource(R.string.payment_amount),
                totalPriceCents.toEuroString()
            ),
            style = MaterialTheme.typography.titleMedium
        )
        if (totalPriceCents > 0) {
            if (isSplitPayment) {
                SplitPaymentFields(
                    firstMethod = firstSplitMethod,
                    secondMethod = secondSplitMethod,
                    firstAmount = firstSplitAmount,
                    secondAmount = secondSplitAmount,
                    onFirstMethodSelected = { firstSplitMethodName = it.name },
                    onSecondMethodSelected = { secondSplitMethodName = it.name },
                    onFirstAmountChange = { firstSplitAmount = it },
                    onSecondAmountChange = { secondSplitAmount = it },
                    isPaymentValid = isPaymentValid,
                    onUseSinglePayment = { isSplitPayment = false },
                    paymentMethods = splitPaymentOptions
                )
            } else {
                PaymentMethodSelector(
                    selectedPayment = selectedPayment,
                    paymentMethods = paymentOptions,
                    onPaymentSelected = { method -> selectedPaymentName = method.name }
                )
                if (splitPaymentOptions.size >= MINIMUM_SPLIT_PAYMENT_METHODS) {
                    TextButton(onClick = { isSplitPayment = true }) {
                        Text(stringResource(R.string.split_payment))
                    }
                }
            }
        }
        if (canChargeWithTerminal) {
            FilledTonalButton(
                onClick = { showCardPaymentConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.CreditCard,
                    contentDescription = null,
                    modifier = Modifier.size(CARD_PAYMENT_ICON_SIZE)
                )
                Spacer(Modifier.width(CARD_PAYMENT_BUTTON_CONTENT_SPACING))
                Text(stringResource(R.string.charge_card_terminal))
            }
        }
        if (cardPaymentError) {
            Text(
                text = stringResource(R.string.card_terminal_payment_failed),
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showCardPaymentConfirmation) {
        AlertDialog(
            onDismissRequest = { showCardPaymentConfirmation = false },
            title = { Text(stringResource(R.string.confirm_card_terminal_payment_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.confirm_card_terminal_payment_message,
                        quantity,
                        ticketType.displayLabel,
                        totalPriceCents.toEuroString()
                    )
                )
            },
            dismissButton = {
                TextButton(onClick = { showCardPaymentConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCardPaymentConfirmation = false
                        cardPaymentError = false
                        val method = selectedPayment ?: return@Button
                        val pending = PendingCardPayment(
                            ticketType = ticketType,
                            quantity = quantity,
                            paymentMethod = method,
                            amountCents = totalPriceCents
                        )
                        val intent = cardPaymentGateway.createPaymentIntent(
                            context,
                            CardPaymentRequest(
                                amountCents = pending.amountCents,
                                reference = UUID.randomUUID().toString()
                            )
                        )
                        if (intent == null) {
                            cardPaymentError = true
                        } else {
                            pendingCardTicketTypeName = pending.ticketType.name
                            pendingCardPaymentMethodName = pending.paymentMethod.name
                            pendingCardQuantity = pending.quantity
                            pendingCardAmountCents = pending.amountCents
                            cardPaymentLauncher.launch(intent)
                        }
                    }
                ) {
                    Text(stringResource(R.string.charge_card_terminal_confirm))
                }
            }
        )
    }
}

private const val MINIMUM_SPLIT_PAYMENT_METHODS = 2
private val CARD_PAYMENT_ICON_SIZE = 20.dp
private val CARD_PAYMENT_BUTTON_CONTENT_SPACING = 8.dp

private data class PendingCardPayment(
    val ticketType: TicketType,
    val quantity: Int,
    val paymentMethod: PaymentMethod,
    val amountCents: Int
)

internal fun canChargeWithTerminal(
    isGatewayAvailable: Boolean,
    isNewSale: Boolean,
    isSplitPayment: Boolean,
    selectedPayment: PaymentMethod?,
    totalPriceCents: Int,
    quantity: Int,
    maximumQuantity: Int,
    isPaymentValid: Boolean
): Boolean = isGatewayAvailable &&
    isNewSale &&
    !isSplitPayment &&
    selectedPayment == PaymentMethod.CARD &&
    totalPriceCents > 0 &&
    quantity in 1..maximumQuantity &&
    isPaymentValid

@Composable
private fun TicketTypeDropdown(
    selectedTicketType: TicketType,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelected: (TicketType) -> Unit,
    options: List<TicketType>
) {
    Box {
        Button(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
            Text(selectedTicketType.displayLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            options
                .filterNot { it == TicketType.UNSPECIFIED }
                .forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayLabel) },
                        onClick = { onSelected(option) }
                    )
                }
        }
    }
}

@Composable
private fun SplitPaymentFields(
    firstMethod: PaymentMethod,
    secondMethod: PaymentMethod,
    firstAmount: String,
    secondAmount: String,
    onFirstMethodSelected: (PaymentMethod) -> Unit,
    onSecondMethodSelected: (PaymentMethod) -> Unit,
    onFirstAmountChange: (String) -> Unit,
    onSecondAmountChange: (String) -> Unit,
    isPaymentValid: Boolean,
    onUseSinglePayment: () -> Unit,
    paymentMethods: List<PaymentMethod> = PaymentMethod.entries
) {
    Text(stringResource(R.string.split_payment), style = MaterialTheme.typography.titleMedium)
    PaymentMethodDropdown(
        selected = firstMethod,
        excludedMethod = secondMethod,
        onSelected = onFirstMethodSelected,
        options = paymentMethods
    )
    PaymentAmountField(value = firstAmount, onValueChange = onFirstAmountChange)
    PaymentMethodDropdown(
        selected = secondMethod,
        excludedMethod = firstMethod,
        onSelected = onSecondMethodSelected,
        options = paymentMethods
    )
    PaymentAmountField(value = secondAmount, onValueChange = onSecondAmountChange)
    if (!isPaymentValid) {
        Text(
            text = stringResource(R.string.payment_total_mismatch),
            color = MaterialTheme.colorScheme.error
        )
    }
    TextButton(onClick = onUseSinglePayment) {
        Text(stringResource(R.string.single_payment))
    }
}

@Composable
private fun PaymentMethodDropdown(
    selected: PaymentMethod,
    excludedMethod: PaymentMethod,
    onSelected: (PaymentMethod) -> Unit,
    options: List<PaymentMethod>
) {
    var expanded by rememberSaveable(selected) { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options
                .filterNot { method ->
                    method == excludedMethod || !method.allowsSplitPayment
                }
                .forEach { method ->
                    DropdownMenuItem(
                        text = { Text(method.label) },
                        onClick = {
                            onSelected(method)
                            expanded = false
                        }
                    )
                }
        }
    }
}

@Composable
private fun PaymentAmountField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.payment_amount)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}
