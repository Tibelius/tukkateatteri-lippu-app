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
import com.zettle.sdk.feature.cardreader.ui.CardReaderFeature
import com.zettle.sdk.feature.cardreader.ui.payment.CardPaymentResult
import com.zettle.sdk.features.charge
import com.zettle.sdk.ui.ZettleResult
import com.zettle.sdk.ui.zettleResult
import fi.tukkateatteri.BuildConfig
import fi.tukkateatteri.logging.AppLog

fun configureCardPaymentSdk(application: Application) {
    if (ZettleSDK.isInitialized) return
    if (!BuildConfig.ZETTLE_DEVELOPER_MODE && BuildConfig.ZETTLE_CLIENT_ID.isBlank()) {
        AppLog.warning(LOG_COMPONENT) {
            "Zettle production client ID is missing; terminal payments are unavailable"
        }
        return
    }

    val environment = if (BuildConfig.ZETTLE_DEVELOPER_MODE) "developer" else "production"
    AppLog.info(LOG_COMPONENT) { "Initializing Zettle card payments; environment=$environment" }
    runCatching {
        ZettleSDK.configure(
            config(application.applicationContext) {
                isDevMode = BuildConfig.ZETTLE_DEVELOPER_MODE
                auth {
                    clientId = BuildConfig.ZETTLE_CLIENT_ID
                    redirectUrl = BuildConfig.ZETTLE_REDIRECT_URL
                }
                addFeature(CardReaderFeature)
            }
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(ZettleSDKLifecycle())
    }.onSuccess {
        AppLog.info(LOG_COMPONENT) { "Zettle SDK initialized; environment=$environment" }
    }.onFailure { exception ->
        AppLog.error(LOG_COMPONENT, exception) {
            "Zettle SDK initialization failed; terminal payments are unavailable"
        }
    }
}

fun createCardPaymentGateway(): CardPaymentGateway = ZettleCardPaymentGateway

private object ZettleCardPaymentGateway : CardPaymentGateway {
    override val isAvailable: Boolean
        get() = ZettleSDK.isInitialized

    override fun createPaymentIntent(context: Context, request: CardPaymentRequest): Intent? {
        AppLog.info(LOG_COMPONENT) {
            "Starting Zettle card payment; reference=${request.reference}, " +
                "amountCents=${request.amountCents}"
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
            AppLog.error(LOG_COMPONENT, exception) { "Could not create Zettle payment activity" }
            null
        }
    }

    override fun parsePaymentResult(result: ActivityResult): CardPaymentOutcome = runCatching {
        val zettleResult = result.data?.zettleResult()
            ?: return super.parsePaymentResult(result)
        when (zettleResult) {
            is ZettleResult.Completed<*> -> zettleResult.toCompletedPayment()
            is ZettleResult.Cancelled -> {
                AppLog.info(LOG_COMPONENT) { "Zettle card payment was cancelled" }
                CardPaymentOutcome.Cancelled
            }

            is ZettleResult.Failed -> {
                val reason = zettleResult.reason.javaClass.simpleName
                AppLog.warning(LOG_COMPONENT) { "Zettle card payment failed; reason=$reason" }
                CardPaymentOutcome.Failed(reason)
            }
        }
    }.getOrElse { exception ->
        AppLog.error(LOG_COMPONENT, exception) { "Could not parse Zettle payment result" }
        CardPaymentOutcome.Failed("Payment result could not be parsed.")
    }
}

private fun ZettleResult.Completed<*>.toCompletedPayment(): CardPaymentOutcome.Completed {
    val payment: CardPaymentResult.Completed = CardReaderAction.fromPaymentResult(this)
    AppLog.info(LOG_COMPONENT) {
        "Zettle card payment completed; reference=${payment.payload.reference?.id}, " +
            "amountCents=${payment.payload.amount}, transactionId=${payment.payload.transactionId}"
    }
    return CardPaymentOutcome.Completed(
        amountCents = payment.payload.amount,
        transactionId = payment.payload.transactionId,
        referenceNumber = payment.payload.referenceNumber
    )
}

private const val LOG_COMPONENT = "ZettlePayment"
private const val REFERENCE_CONTEXT_KEY = "SOURCE"
private const val REFERENCE_CONTEXT_VALUE = "Tukkateatteri"
