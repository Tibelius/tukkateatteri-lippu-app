package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.ui.components.CancelSaveActions
import fi.tukkateatteri.ui.components.QuantityIconButton
import fi.tukkateatteri.ui.components.ScrollableAppDialog

internal val reservedTicketAllocationsSaver = listSaver<List<ReservedTicketAllocation>, String>(
    save = { allocations ->
        allocations.flatMap { allocation ->
            listOf(
                allocation.ticketType.name,
                allocation.ticketType.label,
                allocation.ticketType.defaultPriceCents.toString(),
                allocation.ticketType.sortOrder.toString(),
                allocation.quantity.toString()
            )
        }
    },
    restore = { values ->
        values.chunked(5).map { (name, label, price, sortOrder, quantity) ->
            ReservedTicketAllocation(
                TicketType(name, label, price.toInt(), sortOrder.toInt()),
                quantity.toInt()
            )
        }
    }
)

@Composable
fun ReservedTicketTypesDialog(
    initialAllocations: List<ReservedTicketAllocation>,
    availableTicketTypes: List<TicketType> = TicketType.entries.filterNot { it == TicketType.UNSPECIFIED },
    maximumQuantity: Int,
    onDismiss: () -> Unit,
    onSave: (List<ReservedTicketAllocation>) -> Unit
) {
    var quantities by rememberSaveable(initialAllocations, maximumQuantity) {
        mutableStateOf(initialAllocations.associate { it.ticketType.name to it.quantity })
    }
    var isTicketTypeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val ticketTypesByName = (availableTicketTypes + initialAllocations.map { it.ticketType })
        .associateBy(TicketType::name)
    val allocatedQuantity = quantities.values.sum()
    val selectableTicketTypes = availableTicketTypes.filter { ticketType ->
        ticketType != TicketType.UNSPECIFIED && ticketType.name !in quantities
    }

    ScrollableAppDialog(
        onDismissRequest = onDismiss,
        actions = {
            CancelSaveActions(
                onCancel = onDismiss,
                onSave = {
                    onSave(
                        quantities.map { (ticketTypeName, quantity) ->
                            ReservedTicketAllocation(requireNotNull(ticketTypesByName[ticketTypeName]), quantity)
                        }
                    )
                }
            )
        }
    ) {
        Text(
            text = stringResource(R.string.reserved_ticket_types),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = pluralStringResource(
                R.plurals.reserved_ticket_types_total,
                allocatedQuantity,
                allocatedQuantity,
                maximumQuantity
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        quantities.entries
            .sortedBy { (ticketTypeName, _) -> ticketTypesByName[ticketTypeName]?.sortOrder }
            .forEach { (ticketTypeName, quantity) ->
                val ticketType = requireNotNull(ticketTypesByName[ticketTypeName])
                ReservedTicketTypeRow(
                    ticketType = ticketType,
                    quantity = quantity,
                    canIncrease = allocatedQuantity < maximumQuantity,
                    onDecrease = {
                        quantities = quantities.toMutableMap().apply {
                            if (quantity == 1) remove(ticketTypeName) else put(ticketTypeName, quantity - 1)
                        }
                    },
                    onIncrease = {
                        quantities = quantities.toMutableMap().apply {
                            put(ticketTypeName, quantity + 1)
                        }
                    }
                )
            }
        if (quantities.isNotEmpty()) {
            HorizontalDivider()
        }
        if (selectableTicketTypes.isNotEmpty()) {
            Box {
                Button(
                    onClick = { isTicketTypeMenuExpanded = true },
                    enabled = allocatedQuantity < maximumQuantity,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.add_reserved_ticket_type),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                DropdownMenu(
                    expanded = isTicketTypeMenuExpanded,
                    onDismissRequest = { isTicketTypeMenuExpanded = false }
                ) {
                    selectableTicketTypes.forEach { ticketType ->
                        DropdownMenuItem(
                            text = { Text(ticketType.displayLabel) },
                            onClick = {
                                quantities = quantities + (ticketType.name to 1)
                                isTicketTypeMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReservedTicketTypeRow(
    ticketType: TicketType,
    quantity: Int,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ticketType.displayLabel,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )
        QuantityIconButton(
            imageVector = Icons.Filled.Remove,
            contentDescription = stringResource(R.string.decrease_seat_count),
            onClick = onDecrease
        )
        Text(quantity.toString(), style = MaterialTheme.typography.titleMedium)
        QuantityIconButton(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.increase_seat_count),
            enabled = canIncrease,
            onClick = onIncrease
        )
    }
}

@Composable
fun reservedTicketTypesSummary(allocations: List<ReservedTicketAllocation>): String = when (allocations.size) {
    0 -> stringResource(R.string.no_reserved_ticket_types)
    1 -> stringResource(
        R.string.ticket_quantity,
        allocations.single().ticketType.displayLabel,
        allocations.single().quantity
    )
    else -> {
        val quantity = allocations.sumOf(ReservedTicketAllocation::quantity)
        pluralStringResource(R.plurals.reserved_ticket_types_summary, quantity, quantity)
    }
}
