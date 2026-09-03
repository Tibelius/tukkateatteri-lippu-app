package fi.tukkateatteri.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import java.text.Collator
import java.util.Locale

private val FLOATING_ACTION_BUTTON_CLEARANCE = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationListScreen(
    activePerformance: Performance?,
    reservations: List<Reservation>,
    onOpenPerformanceMenu: () -> Unit,
    onReservationClick: (Reservation) -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onManageGoogleSheetSourcesClick: () -> Unit,
    onChangeGoogleAccountClick: () -> Unit,
    onDeleteAllClick: () -> Unit
) {
    val redeemedCount = reservations.count(Reservation::isFullyRedeemed)
    val totalSeatCount = reservations.sumOf(Reservation::seatCount)
    var isDataMenuExpanded by remember { mutableStateOf(false) }
    val sortedReservations = remember(reservations) {
        val collator = Collator.getInstance(FINNISH_LOCALE)
        reservations.sortedWith { first, second ->
            val lastNameComparison = collator.compare(first.lastName, second.lastName)
            if (lastNameComparison != 0) {
                lastNameComparison
            } else {
                collator.compare(first.firstName, second.firstName)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenPerformanceMenu) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.open_performance_menu)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = activePerformance?.displayName
                                ?: stringResource(R.string.select_performance)
                        )
                        Text(
                            text = stringResource(
                                R.string.reservation_overview,
                                pluralStringResource(
                                    R.plurals.reservation_progress,
                                    reservations.size,
                                    redeemedCount,
                                    reservations.size
                                ),
                                pluralStringResource(
                                    R.plurals.total_seat_count,
                                    totalSeatCount,
                                    totalSeatCount
                                )
                            ),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { isDataMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = isDataMenuExpanded,
                            onDismissRequest = { isDataMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_spreadsheet)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onImportClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_spreadsheet)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onExportClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_google_sheet_sources)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onManageGoogleSheetSourcesClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.change_google_account)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onChangeGoogleAccountClick()
                                }
                            )
                            if (reservations.isNotEmpty()) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.delete_all_reservations_action),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        isDataMenuExpanded = false
                                        onDeleteAllClick()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_admission_title)
                )
            }
        }
    ) { contentPadding ->
        if (sortedReservations.isEmpty()) {
            EmptyReservationList(
                hasActivePerformance = activePerformance != null,
                modifier = Modifier.padding(contentPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = FLOATING_ACTION_BUTTON_CLEARANCE
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = sortedReservations,
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
}

private val FINNISH_LOCALE = Locale.forLanguageTag("fi-FI")

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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        )
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
private fun ReservationStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    contentColor: androidx.compose.ui.graphics.Color
) {
    androidx.compose.foundation.layout.Row(
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
