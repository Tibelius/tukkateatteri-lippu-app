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

@Composable
fun ReservedTicketTypesSummaryCard(
    allocations: List<ReservedTicketAllocation>
) {
    TicketAllocationsSummaryCard(
        title = stringResource(R.string.reserved_ticket_types),
        allocations = allocations
    )
}

@Composable
fun RemainingReservedTicketTypesSummaryCard(
    remaining: List<ReservedTicketAllocation>
) {
    TicketAllocationsSummaryCard(
        title = stringResource(R.string.reserved_ticket_types_remaining),
        allocations = remaining
    )
}

@Composable
private fun TicketAllocationsSummaryCard(
    title: String,
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
                text = title,
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
internal fun reservedTicketTypesDetails(allocations: List<ReservedTicketAllocation>): String {
    val details = allocations.map { allocation ->
        stringResource(
            R.string.ticket_quantity,
            stringResource(allocation.ticketType.labelResId),
            allocation.quantity
        )
    }
    return details.joinToString(separator = "\n")
}
