package fi.tukkateatteri.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState

@Composable
internal fun ReservationListContent(
    reservations: List<Reservation>,
    hasActivePerformance: Boolean,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (reservations.isEmpty()) {
        EmptyReservationList(
            hasActivePerformance = hasActivePerformance,
            modifier = modifier
        )
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = FLOATING_ACTION_BUTTON_CLEARANCE
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = reservations,
                key = Reservation::id
            ) { reservation ->
                ReservationRow(
                    reservation = reservation,
                    onClick = { onReservationClick(reservation) }
                )
            }
        }
    }
}

@Composable
private fun EmptyReservationList(
    hasActivePerformance: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    if (hasActivePerformance) R.string.no_reservations else R.string.select_performance
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    if (hasActivePerformance) {
                        R.string.no_reservations_description
                    } else {
                        R.string.no_active_performance
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReservationRow(
    reservation: Reservation,
    onClick: () -> Unit
) {
    val displayName = reservation.displayName.ifBlank {
        stringResource(R.string.admission_type_door_sale)
    }
    val backgroundColor = when {
        reservation.isCompleted -> MaterialTheme.colorScheme.tertiaryContainer
        reservation.isFullyRedeemed -> MaterialTheme.colorScheme.primaryContainer
        reservation.isPresent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        reservation.isCompleted -> MaterialTheme.colorScheme.onTertiaryContainer
        reservation.isFullyRedeemed -> MaterialTheme.colorScheme.onPrimaryContainer
        reservation.isPresent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val statusText = stringResource(
        R.string.payment_overview,
        reservation.paidSeatCount,
        reservation.seatCount
    )

    val syncUndercard = reservation.syncState.toSyncUndercard()
    if (syncUndercard == null) {
        ReservationForegroundCard(
            reservation = reservation,
            displayName = displayName,
            statusText = statusText,
            backgroundColor = backgroundColor,
            contentColor = contentColor,
            onClick = onClick
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            SyncStatusUndercard(
                undercard = syncUndercard,
                modifier = Modifier.matchParentSize(),
                onClick = onClick
            )
            ReservationForegroundCard(
                reservation = reservation,
                displayName = displayName,
                statusText = statusText,
                backgroundColor = backgroundColor,
                contentColor = contentColor,
                onClick = onClick,
                drawSyncBorder = true,
                modifier = Modifier.padding(start = SYNC_UNDERCARD_HORIZONTAL_OFFSET)
            )
        }
    }
}

@Composable
private fun ReservationForegroundCard(
    modifier: Modifier = Modifier,
    reservation: Reservation,
    displayName: String,
    statusText: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    drawSyncBorder: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        border = if (drawSyncBorder) {
            BorderStroke(
                width = SYNC_CARD_BORDER_WIDTH,
                color = contentColor.copy(alpha = FOREGROUND_CARD_BORDER_ALPHA)
            )
        } else {
            null
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (reservation.contact.isNotBlank()) {
                Text(
                    text = reservation.contact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ReservationStatusRow(
                icon = Icons.Filled.ConfirmationNumber,
                text = stringResource(
                    R.string.reservation_summary,
                    pluralStringResource(
                        R.plurals.seat_count_summary,
                        reservation.seatCount,
                        reservation.seatCount
                    ),
                    statusText
                ),
                modifier = Modifier.padding(top = 8.dp),
                contentColor = contentColor
            )
            if (reservation.hasTicketValueMismatch) {
                ReservationStatusRow(
                    icon = Icons.Filled.WarningAmber,
                    text = stringResource(R.string.reservation_ticket_value_mismatch),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    contentColor = MaterialTheme.colorScheme.error
                )
            }
            if (reservation.arrivalCount > 0) {
                ReservationStatusRow(
                    icon = Icons.Filled.Person,
                    text = stringResource(
                        R.string.arrival_overview,
                        reservation.arrivalCount,
                        reservation.seatCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    contentColor = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SyncStatusUndercard(
    undercard: SyncUndercard,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = undercard.containerColor,
            contentColor = undercard.contentColor
        ),
        border = BorderStroke(
            width = SYNC_CARD_BORDER_WIDTH,
            color = undercard.contentColor.copy(alpha = UNDERCARD_BORDER_ALPHA)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = undercard.icon,
                contentDescription = undercard.contentDescription,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
                tint = undercard.contentColor
            )
        }
    }
}

internal val ReservationSyncState.sortPriority: SyncSortPriority
    get() = when (this) {
        ReservationSyncState.CONFLICT -> SyncSortPriority.CONFLICT
        ReservationSyncState.PENDING,
        ReservationSyncState.PENDING_DELETION -> SyncSortPriority.PENDING
        ReservationSyncState.SYNCED -> SyncSortPriority.SYNCED
    }

internal enum class SyncSortPriority {
    CONFLICT,
    PENDING,
    SYNCED
}

@Composable
private fun ReservationSyncState.toSyncUndercard(): SyncUndercard? = when (this) {
    ReservationSyncState.SYNCED -> null
    ReservationSyncState.PENDING -> SyncUndercard(
        icon = Icons.Filled.CloudUpload,
        contentDescription = stringResource(R.string.sheet_change_pending),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
    ReservationSyncState.CONFLICT -> SyncUndercard(
        icon = Icons.Filled.WarningAmber,
        contentDescription = stringResource(R.string.sheet_change_conflict),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    )
    ReservationSyncState.PENDING_DELETION -> SyncUndercard(
        icon = Icons.Filled.DeleteOutline,
        contentDescription = stringResource(R.string.sheet_deletion_pending),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    )
}

private data class SyncUndercard(
    val icon: ImageVector,
    val contentDescription: String,
    val containerColor: Color,
    val contentColor: Color
)

private const val FOREGROUND_CARD_BORDER_ALPHA = 0.22f
private const val UNDERCARD_BORDER_ALPHA = 0.34f

@Composable
private fun ReservationStatusRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    contentColor: Color
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp),
            tint = contentColor
        )
        Text(
            text = text,
            style = style,
            color = contentColor
        )
    }
}
