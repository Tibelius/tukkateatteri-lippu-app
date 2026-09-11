package fi.tukkateatteri.payment

import android.app.Application
import android.content.Context
import android.content.Intent

fun configureCardPaymentSdk(@Suppress("UNUSED_PARAMETER") application: Application) = Unit

fun createCardPaymentGateway(): CardPaymentGateway = DisabledCardPaymentGateway

private object DisabledCardPaymentGateway : CardPaymentGateway {
    override val isAvailable: Boolean = false

    override fun createPaymentIntent(context: Context, request: CardPaymentRequest): Intent? = null
}
