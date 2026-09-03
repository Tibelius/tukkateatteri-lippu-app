package fi.tukkateatteri.ui.screens

import java.text.Collator
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
import androidx.compose.material.icons.filled.MoreVert
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
import fi.tukkateatteri.data.Reservation

private val floatingActionButtonClearance = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationListScreen(
    reservations: List<Reservation>,
    onReservationClick: (Reservation) -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onManageGoogleSheetSourcesClick: () -> Unit,
    onDeleteAllClick: () -> Unit
) {
    val completedCount = reservations.count(Reservation::isCompleted)
    val totalSeatCount = reservations.sumOf(Reservation::seatCount)
    var isDataMenuExpanded by remember { mutableStateOf(false) }
    val sortedReservations = remember(reservations) {
        val collator = Collator.getInstance()
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
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = stringResource(
                                R.string.reservation_overview,
                                pluralStringResource(
                                    R.plurals.reservation_progress,
                                    reservations.size,
                                    completedCount,
                                    reservations.size
                                ),
                                pluralStringResource(
                                    R.plurals.total_seat_count,
                                    totalSeatCount,
                                    totalSeatCount
                                )
                            ),
                            style = MaterialTheme.typography.labelMedium
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
            EmptyReservationList(modifier = Modifier.padding(contentPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = floatingActionButtonClearance
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

@Composable
private fun EmptyReservationList(modifier: Modifier = Modifier) {
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
                text = stringResource(R.string.no_reservations),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.no_reservations_description),
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
        reservation.isPresent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        reservation.isCompleted -> MaterialTheme.colorScheme.onTertiaryContainer
        reservation.isPresent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val statusText = stringResource(
        R.string.redeemed_overview,
        reservation.redeemedSeatCount,
        reservation.seatCount,
        reservation.remainingSeatCount
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
            Text(
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
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
        }
    }
}
