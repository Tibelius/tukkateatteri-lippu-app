package fi.tukkateatteri.ui.dialogs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import fi.tukkateatteri.ui.components.SeatCountSelector
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
    var quantity by rememberSaveable(ticketSale?.id) { mutableIntStateOf(ticketSale?.quantity ?: 1) }
    var typeMenuOpen by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var typeName by rememberSaveable(ticketSale?.id) {
        mutableStateOf(initialTicketType(ticketSale, reservedTicketAllocations, ticketSalesList).name)
    }
    var storedPayments by rememberSaveable(ticketSale?.id, saver = StoredPaymentsStateSaver) {
        mutableStateOf(ticketSale?.payments.orEmpty().map { it.toStoredPayment() })
    }
    var editingIndex by rememberSaveable(ticketSale?.id) { mutableStateOf<Int?>(null) }
    var selectedMethodName by rememberSaveable(ticketSale?.id) { mutableStateOf<String?>(null) }
    var customAmountEnabled by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var customAmount by rememberSaveable(ticketSale?.id) { mutableStateOf("") }
    var confirmationAction by remember { mutableStateOf<PaymentAction?>(null) }
    var showTerminalConfirmation by rememberSaveable(ticketSale?.id) { mutableStateOf(false) }
    var terminalMessageResId by rememberSaveable(ticketSale?.id) { mutableStateOf<Int?>(null) }
    var pendingTypeName by rememberSaveable(ticketSale?.id) { mutableStateOf<String?>(null) }
    var pendingQuantity by rememberSaveable(ticketSale?.id) { mutableIntStateOf(0) }
    var pendingAmount by rememberSaveable(ticketSale?.id) { mutableIntStateOf(0) }
    var pendingBasePayments by rememberSaveable(ticketSale?.id, saver = StoredPaymentsStateSaver) {
        mutableStateOf(emptyList())
    }

    val fallbackTicketType = initialTicketType(ticketSale, reservedTicketAllocations, ticketSalesList)
    val typeOptions = (availableTicketTypes + listOfNotNull(ticketSale?.ticketType))
        .filterNot { it == TicketType.UNSPECIFIED }
        .distinctBy(TicketType::name)
        .ifEmpty { listOf(fallbackTicketType) }
    val ticketType = typeOptions.firstOrNull { it.name == typeName } ?: typeOptions.first()
    val methodOptions = (availablePaymentMethods + ticketSale?.payments.orEmpty().map { it.method })
        .distinctBy(PaymentMethod::name)
        .ifEmpty { PaymentMethod.entries }
    val hasLockedPayment = storedPayments.any { it.isLocked(methodOptions) }
    val basePayments = storedPayments.filterIndexed { index, _ -> index != editingIndex }
    val total = ticketType.defaultPriceCents * quantity
    val remainingBeforeDraft = (total - basePayments.sumOf(StoredPayment::amountCents)).coerceAtLeast(0)
    val selectedMethod = selectedMethodName?.let { name -> methodOptions.firstOrNull { it.name == name } }
    val draftAmount = when {
        selectedMethod == null -> null
        customAmountEnabled -> customAmount.toEuroCentsOrNull()
        else -> remainingBeforeDraft
    }
    val isPartialDraft = draftAmount != null && draftAmount < remainingBeforeDraft
    val validDraft = selectedMethod != null && draftAmount != null &&
        draftAmount in 1..remainingBeforeDraft &&
        !(!selectedMethod.allowsPartialPayment && (isPartialDraft || basePayments.isNotEmpty()))
    val draft = if (validDraft) StoredPayment(selectedMethod.name, draftAmount, false) else null
    val finalStoredPayments = basePayments + listOfNotNull(draft)
    val finalPayments = finalStoredPayments.mapNotNull { it.toPending(methodOptions) }
    val editingPayment = editingIndex != null || selectedMethod != null
    val canSave = selectedMethod != PaymentMethod.CARD && quantity in 1..maximumQuantity && when {
        total == 0 -> true
        editingPayment -> validDraft
        ticketSale != null -> finalPayments.isNotEmpty()
        else -> false
    }
    val remainingAfterDraft = (total - finalStoredPayments.sumOf(StoredPayment::amountCents)).coerceAtLeast(0)

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
                    onSave(
                        pendingType,
                        pendingQuantity,
                        payments.mapNotNull { it.toPending(methodOptions) }
                    )
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
        onDismissRequest = onDismiss,
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
                onCancel = onDismiss,
                onSave = { onSave(ticketType, quantity, finalPayments) },
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
        Text(stringResource(R.string.ticket_type), style = MaterialTheme.typography.titleMedium)
        TicketTypeDropdown(
            selected = ticketType,
            options = typeOptions,
            expanded = typeMenuOpen,
            enabled = !hasLockedPayment,
            onExpand = { typeMenuOpen = true },
            onDismiss = { typeMenuOpen = false },
            onSelect = {
                typeName = it.name
                typeMenuOpen = false
            }
        )
        SeatCountSelector(
            seatCount = quantity,
            minimumSeatCount = if (hasLockedPayment) quantity else 1,
            maximumSeatCount = maximumQuantity,
            onDecrease = { quantity-- },
            onIncrease = { if (quantity < maximumQuantity) quantity++ }
        )
        Text(
            stringResource(R.string.payment_total, total.toEuroString()),
            style = MaterialTheme.typography.titleMedium
        )

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
                        customAmount = payment.amountCents.toDecimalInput()
                        customAmountEnabled = true
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
                methods = if (
                    basePayments.isNotEmpty() || (
                        customAmountEnabled &&
                            (customAmount.toEuroCentsOrNull() ?: 0) < remainingBeforeDraft
                        )
                ) {
                    methodOptions.filter(PaymentMethod::allowsPartialPayment)
                } else methodOptions,
                customAmountEnabled = customAmountEnabled,
                customAmount = customAmount,
                remaining = remainingAfterDraft,
                onMethodSelected = { selectedMethodName = it.name },
                onToggleCustomAmount = {
                    customAmountEnabled = !customAmountEnabled
                    if (customAmountEnabled && selectedMethod?.allowsPartialPayment == false) {
                        selectedMethodName = null
                    }
                    if (!customAmountEnabled) customAmount = ""
                },
                onAmountChanged = { customAmount = it }
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
                            customAmount = payment.amountCents.toDecimalInput()
                            customAmountEnabled = true
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
