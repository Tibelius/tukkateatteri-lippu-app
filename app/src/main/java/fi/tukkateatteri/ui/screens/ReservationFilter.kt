package fi.tukkateatteri.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState

internal enum class ReservationFilter {
    ALL,
    INCOMPLETE;

    fun includes(reservation: Reservation): Boolean = this == ALL ||
        !reservation.isCompleted || reservation.hasTicketValueMismatch ||
        reservation.syncState != ReservationSyncState.SYNCED
}

@Composable
internal fun ReservationFilterSelector(
    selected: ReservationFilter,
    totalCount: Int,
    incompleteCount: Int,
    onSelect: (ReservationFilter) -> Unit
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        ReservationFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index, ReservationFilter.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface
                ),
                icon = {
                    Icon(
                        imageVector = if (filter == ReservationFilter.ALL) {
                            Icons.AutoMirrored.Filled.ListAlt
                        } else {
                            Icons.Filled.PendingActions
                        },
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = stringResource(
                            if (filter == ReservationFilter.ALL) R.string.filter_all_reservations
                            else R.string.filter_incomplete_reservations,
                            if (filter == ReservationFilter.ALL) totalCount else incompleteCount
                        ),
                        fontWeight = if (selected == filter) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            )
        }
    }
}
