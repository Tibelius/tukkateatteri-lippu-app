package fi.tukkateatteri.payment

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult

data class CardPaymentRequest(
    val amountCents: Int,
    val reference: String
) {
    init {
        require(amountCents > 0) { "Card payment amount must be positive." }
        require(reference.isNotBlank()) { "Card payment reference must not be blank." }
    }
}

sealed interface CardPaymentOutcome {
    data class Completed(
        val amountCents: Long,
        val transactionId: String?,
        val referenceNumber: String?
    ) : CardPaymentOutcome

    data object Cancelled : CardPaymentOutcome

    data class Failed(val reason: String) : CardPaymentOutcome
}

interface CardPaymentGateway {
    val isAvailable: Boolean

    fun createPaymentIntent(context: Context, request: CardPaymentRequest): Intent?

    fun parsePaymentResult(result: ActivityResult): CardPaymentOutcome {
        return if (result.resultCode == Activity.RESULT_CANCELED) {
            CardPaymentOutcome.Cancelled
        } else {
            CardPaymentOutcome.Failed("Payment activity returned an unreadable result.")
        }
    }
}
