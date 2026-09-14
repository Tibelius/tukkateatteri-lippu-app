package fi.tukkateatteri.ui.dialogs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import fi.tukkateatteri.ui.components.RemainingReservedTicketTypesSummaryCard
import fi.tukkateatteri.ui.components.ScrollableAppDialog
import fi.tukkateatteri.ui.components.QuantityControls
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
    val gateway = remember { createCardPaymentGateway() }
    val initialQuantity = ticketSale?.quantity ?: 1
    val initialType = initialTicketType(ticketSale, reservedTicketAllocations, ticketSalesList)
    val initialRemaining = (
        initialType.defaultPriceCents * initialQuantity -
            ticketSale?.payments.orEmpty().sumOf { it.amountCents }
        ).coerceAtLeast(0)
    var quantity by rememberSaveable(ticketSale?.id) { mutableIntStateOf(initialQuantity) }
    var typeMenuOpen by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var typeName by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialType.name)
    }
    var storedPayments by rememberSaveable(ticketSale?.id, saver = StoredPaymentsStateSaver) {
        mutableStateOf(ticketSale?.payments.orEmpty().map { it.toStoredPayment() })
    }
    var editingIndex by rememberSaveable(ticketSale?.id) { mutableStateOf<Int?>(null) }
    var selectedMethodName by rememberSaveable(ticketSale?.id) { mutableStateOf<String?>(null) }
    var paymentAmount by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialRemaining.toDecimalInput())
    }
    var confirmationAction by remember { mutableStateOf<PaymentAction?>(null) }
    var showTerminalConfirmation by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var terminalMessageResId by rememberSaveable(ticketSale?.id) { mutableStateOf<Int?>(null) }
    var pendingTypeName by rememberSaveable(ticketSale?.id) { mutableStateOf<String?>(null) }
    var pendingQuantity by rememberSaveable(ticketSale?.id) { mutableIntStateOf(0) }
    var pendingAmount by rememberSaveable(ticketSale?.id) { mutableIntStateOf(0) }
    var pendingBasePayments by rememberSaveable(ticketSale?.id, saver = StoredPaymentsStateSaver) {
        mutableStateOf(emptyList())
    }

    val fallbackTicketType = initialType
    val typeOptions = (availableTicketTypes + listOfNotNull(ticketSale?.ticketType))
        .filterNot { it == TicketType.UNSPECIFIED }
        .distinctBy(TicketType::name)
        .ifEmpty { listOf(fallbackTicketType) }
    val ticketType = typeOptions.firstOrNull { it.name == typeName } ?: typeOptions.first()
    val methodOptions = (availablePaymentMethods + ticketSale?.payments.orEmpty().map { it.method })
        .distinctBy(PaymentMethod::name)
        .ifEmpty { PaymentMethod.entries }
    val hasLockedPayment = storedPayments.any { it.isLocked(methodOptions) }
    val originalStoredPayments = ticketSale?.payments.orEmpty().map { it.toStoredPayment() }
    val basePayments = storedPayments.filterIndexed { index, _ -> index != editingIndex }
    val total = ticketType.defaultPriceCents * quantity
    val remainingBeforeDraft = (total - basePayments.sumOf(StoredPayment::amountCents)).coerceAtLeast(0)
    val selectedMethod = selectedMethodName?.let { name -> methodOptions.firstOrNull { it.name == name } }
    val draftAmount = paymentAmount.toEuroCentsOrNull()
    val isPartialDraft = draftAmount != null && draftAmount < remainingBeforeDraft
    val validDraft = selectedMethod != null && draftAmount != null &&
        draftAmount in 1..remainingBeforeDraft &&
        !(!selectedMethod.allowsPartialPayment && (isPartialDraft || basePayments.isNotEmpty()))
    val draft = if (validDraft) StoredPayment(checkNotNull(selectedMethod).name, checkNotNull(draftAmount), false) else null
    val finalStoredPayments = basePayments + listOfNotNull(draft)
    val finalPayments = finalStoredPayments.mapNotNull { it.toPending(methodOptions) }
    val canSave = selectedMethod != PaymentMethod.CARD && quantity in 1..maximumQuantity && when {
        total == 0 -> true
        remainingBeforeDraft == 0 && editingIndex == null -> ticketSale != null
        else -> validDraft
    }
    val remainingAfterDraft = (total - finalStoredPayments.sumOf(StoredPayment::amountCents)).coerceAtLeast(0)
    val recordedRemaining = (total - storedPayments.sumOf(StoredPayment::amountCents)).coerceAtLeast(0)
    val dismissDialog = {
        val hasNewLockedPayment = storedPayments != originalStoredPayments &&
            storedPayments.any { it.isLocked(methodOptions) }
        if (hasNewLockedPayment) {
            onSave(ticketType, quantity, storedPayments.mapNotNull { it.toPending(methodOptions) })
        } else {
            onDismiss()
        }
    }

    val terminalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pendingType = typeOptions.firstOrNull { it.name == pendingTypeName }
        when (val outcome = gateway.parsePaymentResult(result)) {
            is CardPaymentOutcome.Completed -> {
                if (pendingType == null || pendingQuantity <= 0 || outcome.amountCents != pendingAmount.toLong()) {
                    terminalMessageResId = R.string.card_terminal_payment_result_invalid
                } else {
                    val payments = pendingBasePayments +
                        StoredPayment(PaymentMethod.CARD.name, pendingAmount, true)
                    if (payments.sumOf(StoredPayment::amountCents) < pendingType.defaultPriceCents * pendingQuantity) {
                        storedPayments = payments
                        editingIndex = null
                        selectedMethodName = null
                        paymentAmount = (
                            pendingType.defaultPriceCents * pendingQuantity -
                                payments.sumOf(StoredPayment::amountCents)
                            ).coerceAtLeast(0).toDecimalInput()
                    } else {
                        onSave(
                            pendingType,
                            pendingQuantity,
                            payments.mapNotNull { it.toPending(methodOptions) }
                        )
                    }
                }
            }
            CardPaymentOutcome.Cancelled -> {
                terminalMessageResId = R.string.card_terminal_payment_cancelled
            }

            is CardPaymentOutcome.Failed -> {
                terminalMessageResId = R.string.card_terminal_payment_failed
            }
        }
        pendingTypeName = null
        pendingQuantity = 0
        pendingAmount = 0
        pendingBasePayments = emptyList()
    }

    ScrollableAppDialog(
        onDismissRequest = dismissDialog,
        actions = {
            if (!hasLockedPayment) {
                onDelete?.let { delete ->
                    TextButton(
                        onClick = {
                            if (ticketSale?.isPaid == true) {
                                confirmationAction = PaymentAction.DeleteSale
                            } else {
                                delete()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.delete_ticket_sale), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            CancelSaveActions(
                onCancel = dismissDialog,
                onSave = {
                    if (isPartialDraft) {
                        storedPayments = finalStoredPayments
                        editingIndex = null
                        selectedMethodName = null
                        paymentAmount = remainingAfterDraft.toDecimalInput()
                    } else {
                        onSave(ticketType, quantity, finalPayments)
                    }
                },
                saveEnabled = canSave
            )
        }
    ) {
        Text(
            stringResource(if (ticketSale == null) R.string.add_ticket_sale else R.string.edit_ticket_sale),
            style = MaterialTheme.typography.headlineSmall
        )
        if (reservedTicketAllocations.isNotEmpty()) {
            RemainingReservedTicketTypesSummaryCard(
                remainingTicketAllocations(reservedTicketAllocations, ticketSalesList, ticketSale)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TICKET_SELECTION_SPACING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TicketTypeDropdown(
                    selected = ticketType,
                    options = typeOptions,
                    expanded = typeMenuOpen,
                    enabled = !hasLockedPayment,
                    onExpand = { typeMenuOpen = true },
                    onDismiss = { typeMenuOpen = false },
                    onSelect = {
                        typeName = it.name
                        paymentAmount = (
                            it.defaultPriceCents * quantity -
                                storedPayments.sumOf(StoredPayment::amountCents)
                            ).coerceAtLeast(0).toDecimalInput()
                        typeMenuOpen = false
                    }
                )
            }
            QuantityControls(
                quantity = quantity,
                minimumQuantity = if (hasLockedPayment) quantity else 1,
                maximumQuantity = maximumQuantity,
                onDecrease = {
                    quantity--
                    paymentAmount = (
                        ticketType.defaultPriceCents * quantity -
                            storedPayments.sumOf(StoredPayment::amountCents)
                        ).coerceAtLeast(0).toDecimalInput()
                },
                onIncrease = {
                    quantity++
                    paymentAmount = (
                        ticketType.defaultPriceCents * quantity -
                            storedPayments.sumOf(StoredPayment::amountCents)
                        ).coerceAtLeast(0).toDecimalInput()
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PAYMENT_SUMMARY_SPACING)
        ) {
            PaymentSummaryValue(
                label = stringResource(R.string.payment_total_label),
                value = total.toEuroString(),
                modifier = Modifier.weight(1f)
            )
            if (total > 0) {
                PaymentSummaryValue(
                    label = stringResource(R.string.payment_remaining_label),
                    value = recordedRemaining.toEuroString(),
                    modifier = Modifier.weight(1f),
                    valueColor = if (recordedRemaining == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        if (storedPayments.isNotEmpty()) {
            PaymentAllocationList(
                payments = storedPayments,
                methods = methodOptions,
                editingIndex = editingIndex,
                onEdit = { index ->
                    val action = PaymentAction.Edit(index)
                    if (ticketSale?.isPaid == true) confirmationAction = action
                    else storedPayments.editablePaymentAt(index, methodOptions)?.let { payment ->
                        editingIndex = index
                        selectedMethodName = payment.methodName
                        paymentAmount = payment.amountCents.toDecimalInput()
                    }
                },
                onDelete = { index ->
                    val action = PaymentAction.Delete(index)
                    if (ticketSale?.isPaid == true) confirmationAction = action
                    else storedPayments = storedPayments.filterIndexed { i, _ -> i != index }
                }
            )
        }

        if (total > 0 && remainingBeforeDraft > 0) {
            PaymentEntry(
                selectedMethod = selectedMethod,
                methods = if (basePayments.isNotEmpty() || isPartialDraft) {
                    methodOptions.filter(PaymentMethod::allowsPartialPayment)
                } else methodOptions,
                amount = paymentAmount,
                onMethodSelected = {
                    selectedMethodName = if (selectedMethod == it) null else it.name
                },
                onAmountChanged = { paymentAmount = it }
            )
        } else if (total > 0) {
            Text(
                stringResource(R.string.payment_complete),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        val terminalAvailable = canChargeWithTerminal(
            isGatewayAvailable = gateway.isAvailable,
            hasCardPayment = selectedMethod == PaymentMethod.CARD,
            totalPriceCents = total,
            quantity = quantity,
            maximumQuantity = maximumQuantity,
            paymentAmountCents = draftAmount ?: 0
        )
        if (terminalAvailable) {
            FilledTonalButton(
                onClick = { showTerminalConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.CreditCard, null, Modifier.size(TERMINAL_ICON_SIZE))
                Spacer(Modifier.width(TERMINAL_CONTENT_SPACING))
                Text(stringResource(R.string.charge_card_terminal))
            }
        }
        if (selectedMethod == PaymentMethod.CARD) {
            Text(
                text = stringResource(
                    if (gateway.isAvailable) {
                        R.string.card_payment_terminal_required
                    } else {
                        R.string.card_terminal_unavailable
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    confirmationAction?.let { action ->
        CompletedPaymentWarning(
            onDismiss = { confirmationAction = null },
            onConfirm = {
                when (action) {
                    is PaymentAction.Edit -> storedPayments
                        .editablePaymentAt(action.index, methodOptions)
                        ?.let { payment ->
                            editingIndex = action.index
                            selectedMethodName = payment.methodName
                            paymentAmount = payment.amountCents.toDecimalInput()
                        }
                    is PaymentAction.Delete -> {
                        storedPayments = storedPayments.filterIndexed { i, _ -> i != action.index }
                    }
                    PaymentAction.DeleteSale -> onDelete?.invoke()
                }
                confirmationAction = null
            }
        )
    }

    if (showTerminalConfirmation) {
        TerminalPaymentConfirmation(
            quantity = quantity,
            ticketType = ticketType,
            amount = draftAmount ?: 0,
            onDismiss = { showTerminalConfirmation = false },
            onConfirm = {
                showTerminalConfirmation = false
                terminalMessageResId = null
                val amount = draftAmount ?: return@TerminalPaymentConfirmation
                val intent = gateway.createPaymentIntent(
                    context,
                    CardPaymentRequest(amount, UUID.randomUUID().toString())
                )
                if (intent == null) {
                    terminalMessageResId = R.string.card_terminal_payment_failed
                } else {
                    pendingTypeName = ticketType.name
                    pendingQuantity = quantity
                    pendingAmount = amount
                    pendingBasePayments = basePayments
                    terminalLauncher.launch(intent)
                }
            }
        )
    }

    terminalMessageResId?.let { messageResId ->
        TerminalPaymentResultDialog(
            messageResId = messageResId,
            onDismiss = { terminalMessageResId = null }
        )
    }
}

private fun initialTicketType(
    edited: TicketSale?,
    reservations: List<ReservedTicketAllocation>,
    sales: List<TicketSale>
): TicketType {
    edited?.ticketType?.takeUnless { it == TicketType.UNSPECIFIED }?.let { return it }
    val sold = sales.groupBy(TicketSale::ticketType).mapValues { (_, items) -> items.sumOf(TicketSale::quantity) }
    return reservations.firstOrNull { it.quantity > sold.getOrDefault(it.ticketType, 0) }?.ticketType
        ?: TicketType.BASIC
}

private fun remainingTicketAllocations(
    reservations: List<ReservedTicketAllocation>,
    sales: List<TicketSale>,
    edited: TicketSale?
): List<ReservedTicketAllocation> {
    val sold = sales.filterNot { it.id == edited?.id }
        .groupBy(TicketSale::ticketType)
        .mapValues { (_, items) -> items.sumOf(TicketSale::quantity) }
    return reservations.mapNotNull { allocation ->
        (allocation.quantity - sold.getOrDefault(allocation.ticketType, 0))
            .takeIf { it > 0 }?.let { allocation.copy(quantity = it) }
    }
}

private sealed interface PaymentAction {
    data class Edit(val index: Int) : PaymentAction
    data class Delete(val index: Int) : PaymentAction
    data object DeleteSale : PaymentAction
}

internal fun canChargeWithTerminal(
    isGatewayAvailable: Boolean,
    hasCardPayment: Boolean,
    totalPriceCents: Int,
    quantity: Int,
    maximumQuantity: Int,
    paymentAmountCents: Int
): Boolean = isGatewayAvailable && hasCardPayment && totalPriceCents > 0 &&
    quantity in 1..maximumQuantity && paymentAmountCents in 1..totalPriceCents

private val TERMINAL_ICON_SIZE = 20.dp
private val TERMINAL_CONTENT_SPACING = 8.dp
private val TICKET_SELECTION_SPACING = 8.dp
private val PAYMENT_SUMMARY_SPACING = 16.dp

@Composable
private fun PaymentSummaryValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}
