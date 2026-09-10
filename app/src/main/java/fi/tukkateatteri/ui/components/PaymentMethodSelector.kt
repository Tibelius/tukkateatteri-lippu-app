package fi.tukkateatteri.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.PaymentMethod

private const val PAYMENT_OPTIONS_PER_ROW = 2

@Composable
fun PaymentMethodSelector(
    selectedPayment: PaymentMethod?,
    paymentMethods: List<PaymentMethod> = PaymentMethod.entries,
    onPaymentSelected: (PaymentMethod) -> Unit
) {
    Text(
        text = stringResource(R.string.payment_method),
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paymentMethods.chunked(PAYMENT_OPTIONS_PER_ROW).forEach { rowMethods ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowMethods.forEach { paymentMethod ->
                    PaymentOptionCard(
                        paymentMethod = paymentMethod,
                        isSelected = selectedPayment == paymentMethod,
                        modifier = Modifier.weight(1f),
                        onClick = { onPaymentSelected(paymentMethod) }
                    )
                }
                if (rowMethods.size < PAYMENT_OPTIONS_PER_ROW) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PaymentOptionCard(
    paymentMethod: PaymentMethod,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = paymentMethod.icon,
                contentDescription = null,
                modifier = Modifier.height(28.dp),
                tint = contentColor
            )
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = paymentMethod.label,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val PaymentMethod.icon: ImageVector
    get() = when (this) {
        PaymentMethod.CARD -> Icons.Filled.CreditCard
        PaymentMethod.CASH -> Icons.Filled.Payments
        PaymentMethod.EPASSI -> Icons.Filled.Redeem
        PaymentMethod.LIPPUAGENTTI -> Icons.Filled.ConfirmationNumber
        else -> Icons.Filled.MoreHoriz
    }
