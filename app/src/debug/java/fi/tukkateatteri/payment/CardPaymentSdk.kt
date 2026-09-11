package fi.tukkateatteri.payment

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ProcessLifecycleOwner
import com.zettle.sdk.ZettleSDK
import com.zettle.sdk.ZettleSDKLifecycle
import com.zettle.sdk.config
import com.zettle.sdk.feature.cardreader.payment.TransactionReference
import com.zettle.sdk.feature.cardreader.ui.CardReaderAction
import com.zettle.sdk.feature.cardreader.ui.payment.CardPaymentResult
import com.zettle.sdk.features.charge
import com.zettle.sdk.ui.ZettleResult
import com.zettle.sdk.ui.zettleResult
import fi.tukkateatteri.logging.AppLog

fun configureCardPaymentSdk(application: Application) {
    if (ZettleSDK.isInitialized) return

    AppLog.info(LOG_COMPONENT) { "Initializing Zettle card payments in developer mode" }
    runCatching {
        ZettleSDK.configure(
            config(application.applicationContext) {
                isDevMode = true
                addFeature(com.zettle.sdk.feature.cardreader.ui.CardReaderFeature)
            }
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(ZettleSDKLifecycle())
    }.onSuccess {
        AppLog.debug(LOG_COMPONENT) { "Zettle developer-mode SDK initialized" }
    }.onFailure { exception ->
        AppLog.error(LOG_COMPONENT, exception) {
            "Zettle developer-mode SDK initialization failed; terminal payments are unavailable"
        }
    }
}

fun createCardPaymentGateway(): CardPaymentGateway = ZettleDeveloperCardPaymentGateway

private object ZettleDeveloperCardPaymentGateway : CardPaymentGateway {
    override val isAvailable: Boolean
        get() = ZettleSDK.isInitialized

    override fun createPaymentIntent(context: Context, request: CardPaymentRequest): Intent? {
        AppLog.info(LOG_COMPONENT) {
            "Starting simulated Zettle card payment; reference=${request.reference}, amountCents=${request.amountCents}"
        }
        val reference = TransactionReference.Builder(request.reference)
            .put(REFERENCE_CONTEXT_KEY, REFERENCE_CONTEXT_VALUE)
            .build()
        return runCatching {
            CardReaderAction.Payment(
                amount = request.amountCents.toLong(),
                reference = reference,
                enableInstallments = false
            ).charge(context)
        }.getOrElse { exception ->
            AppLog.error(LOG_COMPONENT, exception) {
                "Could not create the simulated Zettle card-payment activity"
            }
            null
        }
    }

    override fun parsePaymentResult(result: ActivityResult): CardPaymentOutcome {
        val zettleResult = result.data?.zettleResult()
            ?: return super.parsePaymentResult(result)
        return when (zettleResult) {
            is ZettleResult.Completed<*> -> {
                val payment: CardPaymentResult.Completed =
                    CardReaderAction.fromPaymentResult(zettleResult)
                AppLog.info(LOG_COMPONENT) {
                    "Simulated Zettle card payment completed; " +
                        "reference=${payment.payload.reference?.id}, amountCents=${payment.payload.amount}, " +
                        "transactionId=${payment.payload.transactionId}"
                }
                CardPaymentOutcome.Completed(
                    amountCents = payment.payload.amount,
                    transactionId = payment.payload.transactionId,
                    referenceNumber = payment.payload.referenceNumber
                )
            }

            is ZettleResult.Cancelled -> {
                AppLog.info(LOG_COMPONENT) { "Simulated Zettle card payment was cancelled" }
                CardPaymentOutcome.Cancelled
            }

            is ZettleResult.Failed -> {
                val reason = zettleResult.reason.javaClass.simpleName
                AppLog.warning(LOG_COMPONENT) { "Simulated Zettle card payment failed; reason=$reason" }
                CardPaymentOutcome.Failed(reason)
            }
        }
    }
}

private const val LOG_COMPONENT = "ZettlePayment"
private const val REFERENCE_CONTEXT_KEY = "SOURCE"
private const val REFERENCE_CONTEXT_VALUE = "Tukkateatteri debug"
