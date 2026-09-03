package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.tukkateatteri.R
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType

private const val DIALOG_WIDTH_FRACTION = 0.94f
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.9f

internal val reservedTicketAllocationsSaver = listSaver<List<ReservedTicketAllocation>, String>(
    save = { allocations ->
        allocations.flatMap { allocation -> listOf(allocation.ticketType.name, allocation.quantity.toString()) }
    },
    restore = { values ->
        values.chunked(2).map { (ticketTypeName, quantity) ->
            ReservedTicketAllocation(TicketType.valueOf(ticketTypeName), quantity.toInt())
        }
    }
)

@Composable
fun ReservedTicketTypesDialog(
    initialAllocations: List<ReservedTicketAllocation>,
    maximumQuantity: Int,
    onDismiss: () -> Unit,
    onSave: (List<ReservedTicketAllocation>) -> Unit
) {
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * DIALOG_MAX_HEIGHT_FRACTION
    var quantities by rememberSaveable(initialAllocations, maximumQuantity) {
        mutableStateOf(initialAllocations.associate { it.ticketType.name to it.quantity })
    }
    var isTicketTypeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val allocatedQuantity = quantities.values.sum()
    val availableTicketTypes = TicketType.entries.filter { ticketType ->
        ticketType != TicketType.UNSPECIFIED && ticketType.name !in quantities
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(DIALOG_WIDTH_FRACTION).heightIn(max = maxDialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.reserved_ticket_types), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.reserved_ticket_types_total, allocatedQuantity, maximumQuantity),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                quantities.entries
                    .sortedBy { (ticketTypeName, _) -> TicketType.valueOf(ticketTypeName).ordinal }
                    .forEach { (ticketTypeName, quantity) ->
                        val ticketType = TicketType.valueOf(ticketTypeName)
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
                                quantities = quantities.toMutableMap().apply { put(ticketTypeName, quantity + 1) }
                            }
                        )
                    }
                if (quantities.isNotEmpty()) HorizontalDivider()
                if (availableTicketTypes.isNotEmpty()) {
                    Box {
                        Button(
                            onClick = { isTicketTypeMenuExpanded = true },
                            enabled = allocatedQuantity < maximumQuantity,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.add_reserved_ticket_type), modifier = Modifier.padding(start = 8.dp))
                        }
                        DropdownMenu(
                            expanded = isTicketTypeMenuExpanded,
                            onDismissRequest = { isTicketTypeMenuExpanded = false }
                        ) {
                            availableTicketTypes.forEach { ticketType ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(ticketType.labelResId)) },
                                    onClick = {
                                        quantities = quantities + (ticketType.name to 1)
                                        isTicketTypeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            onSave(
                                quantities.map { (ticketTypeName, quantity) ->
                                    ReservedTicketAllocation(TicketType.valueOf(ticketTypeName), quantity)
                                }
                            )
                        }
                    ) { Text(stringResource(R.string.save)) }
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
            text = stringResource(ticketType.labelResId),
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )
        IconButton(onClick = onDecrease) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease_seat_count))
        }
        Text(quantity.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onIncrease, enabled = canIncrease) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase_seat_count))
        }
    }
}

@Composable
fun reservedTicketTypesSummary(allocations: List<ReservedTicketAllocation>): String = when (allocations.size) {
    0 -> stringResource(R.string.no_reserved_ticket_types)
    1 -> stringResource(allocations.single().ticketType.labelResId) + " × " + allocations.single().quantity
    else -> stringResource(R.string.reserved_ticket_types_summary, allocations.sumOf(ReservedTicketAllocation::quantity))
}
