package fi.tukkateatteri.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.MINIMUM_SEAT_COUNT
import fi.tukkateatteri.ui.theme.LocalQuantityButtonColors

@Composable
fun SeatCountSelector(
    seatCount: Int,
    minimumSeatCount: Int = MINIMUM_SEAT_COUNT,
    maximumSeatCount: Int = Int.MAX_VALUE,
    @StringRes labelResId: Int = R.string.seat_count,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val decreaseEnabled = seatCount > minimumSeatCount
    val increaseEnabled = seatCount < maximumSeatCount

    val appColors = LocalQuantityButtonColors.current
    val buttonColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = appColors.container,
        contentColor = appColors.content,
        disabledContainerColor = appColors.disabledContainer,
        disabledContentColor = appColors.disabledContent
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(labelResId),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))

        QuantityIconButton(
            imageVector = Icons.Filled.Remove,
            contentDescription = stringResource(R.string.decrease_seat_count),
            enabled = decreaseEnabled,
            colors = buttonColors,
            onClick = onDecrease
        )
        Text(
            text = seatCount.toString(),
            modifier = Modifier.widthIn(min = 40.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        QuantityIconButton(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.increase_seat_count),
            enabled = increaseEnabled,
            colors = buttonColors,
            onClick = onIncrease
        )
    }
}

@Composable
fun QuantityIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        enabled = enabled,
        colors = colors,
        onClick = onClick
    ) {
        Icon(imageVector = imageVector, contentDescription = contentDescription)
    }
}
