package fi.tukkateatteri.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketSale

@Composable
fun ReservedTicketTypesSummaryCard(
    allocations: List<ReservedTicketAllocation>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.reserved_ticket_types),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = reservedTicketTypesDetails(allocations),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun RemainingReservedTicketTypesSummaryCard(
    remaining: List<ReservedTicketAllocation>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.reserved_ticket_types_remaining),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = remainingReservedTicketTypesDetails(remaining),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun reservedTicketTypesDetails(allocations: List<ReservedTicketAllocation>): String {
    var details = ""
    for ((index, allocation) in allocations.withIndex()) {
        if (index > 0) {
            details += "\n"
        }
        details += "${stringResource(allocation.ticketType.labelResId)} × ${allocation.quantity}"
    }
    return details
}

@Composable
fun remainingReservedTicketTypesDetails(
    remaining: List<ReservedTicketAllocation>
): String {
    var details = ""
    for ((index, remains) in remaining.withIndex()) {
        if (index > 0) {
            details += "\n"
        }
        details += "${stringResource(remains.ticketType.labelResId)} × ${remains.quantity}"
    }
    return details
}