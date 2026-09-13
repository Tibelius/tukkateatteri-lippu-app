package fi.tukkateatteri.ui.dialogs

import android.os.Bundle
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import fi.tukkateatteri.data.PaymentAllocation
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.isExternallyConfirmed

internal data class StoredPayment(
    val methodName: String,
    val amountCents: Int,
    val zettleSuccessful: Boolean
) {
    fun toPending(methods: List<PaymentMethod>): PendingPaymentAllocation? = methods
        .firstOrNull { it.name == methodName }
        ?.let { PendingPaymentAllocation(it, amountCents, zettleSuccessful) }

    fun isLocked(methods: List<PaymentMethod>): Boolean = methods
        .firstOrNull { it.name == methodName }
        ?.isExternallyConfirmed == true
}

internal fun PaymentAllocation.toStoredPayment(): StoredPayment = StoredPayment(
    methodName = method.name,
    amountCents = amountCents,
    zettleSuccessful = zettleSuccessful
)

internal fun List<StoredPayment>.editablePaymentAt(
    index: Int,
    methods: List<PaymentMethod>
): StoredPayment? = getOrNull(index)?.takeUnless { it.isLocked(methods) }

/**
 * Keeps payment drafts across configuration changes without encoding user-controlled payment
 * identifiers into a delimiter-based string.
 */
internal val StoredPaymentsStateSaver = Saver<MutableState<List<StoredPayment>>, Bundle>(
    save = { state ->
        Bundle().apply {
            putStringArrayList(
                PAYMENT_METHOD_NAMES_KEY,
                ArrayList(state.value.map(StoredPayment::methodName))
            )
            putIntegerArrayList(
                PAYMENT_AMOUNTS_KEY,
                ArrayList(state.value.map(StoredPayment::amountCents))
            )
            putBooleanArray(
                PAYMENT_TERMINAL_RESULTS_KEY,
                state.value.map(StoredPayment::zettleSuccessful).toBooleanArray()
            )
        }
    },
    restore = { bundle ->
        val methods = bundle.getStringArrayList(PAYMENT_METHOD_NAMES_KEY).orEmpty()
        val amounts = bundle.getIntegerArrayList(PAYMENT_AMOUNTS_KEY).orEmpty()
        val terminalResults = bundle.getBooleanArray(PAYMENT_TERMINAL_RESULTS_KEY) ?: booleanArrayOf()
        mutableStateOf(
            methods.indices.mapNotNull { index ->
                StoredPayment(
                    methodName = methods[index],
                    amountCents = amounts.getOrNull(index) ?: return@mapNotNull null,
                    zettleSuccessful = terminalResults.getOrNull(index) ?: false
                )
            }
        )
    }
)

private const val PAYMENT_METHOD_NAMES_KEY = "methods"
private const val PAYMENT_AMOUNTS_KEY = "amounts"
private const val PAYMENT_TERMINAL_RESULTS_KEY = "terminalResults"
