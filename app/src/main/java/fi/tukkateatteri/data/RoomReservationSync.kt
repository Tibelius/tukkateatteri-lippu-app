package fi.tukkateatteri.data

import androidx.room.withTransaction
import fi.tukkateatteri.data.local.PendingSheetChangeEntity
import fi.tukkateatteri.data.local.PendingSheetChangeStatus
import fi.tukkateatteri.data.local.PendingSheetOperation
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.spreadsheet.ApplicationMutationMetadataState
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.hasSameSheetContentAs
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRowSnapshot
import fi.tukkateatteri.data.spreadsheet.toSnapshotJson
import fi.tukkateatteri.data.spreadsheet.saveSheetSchemas
import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toAbbreviatedId
import fi.tukkateatteri.logging.toLogSummary
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal const val REPOSITORY_LOG_COMPONENT = "Repository"

internal suspend fun <T> RoomReservationRepository.runCloudMutation(
    operation: String,
    accessToken: String?,
    mutation: suspend () -> T,
    affectedReservationIds: suspend (T) -> List<Long>
): T {
    val startedAt = System.nanoTime()
    val target = activeCloudTarget()
    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
        "Starting $operation; cloudTarget=${target != null}, performanceId=${target?.performanceId ?: "none"}"
    }
    val baseRows = target?.let { spreadsheetRowsByReservationId(it.performanceId) }.orEmpty()
    val result = mutation()
    AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Saved $operation locally; capturedBaseRows=${baseRows.size}" }
    if (target != null) {
        val affectedIds = affectedReservationIds(result).distinct()
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Staging $operation; affectedReservationIds=$affectedIds" }
        database.withTransaction {
            ensureSheetRowIds(affectedIds)
            stagePendingChanges(target.performanceId, affectedIds, baseRows)
        }
        if (accessToken != null) {
            flushPendingChangesOrThrow(target, accessToken)
        } else {
            AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                "Deferred $operation Sheet flush; changes remain in the local outbox"
            }
        }
    }
    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
        "Completed $operation in ${AppLog.elapsedMillis(startedAt)} ms"
    }
    return result
}

internal suspend fun RoomReservationRepository.spreadsheetRowsByReservationId(
    performanceId: Long
): Map<Long, ReservationSpreadsheetRow> =
    reservationDao.getByPerformanceWithTicketSales(performanceId)
        .associate { reservation ->
            reservation.reservation.id to ReservationSpreadsheetRow.fromReservation(
                reservation.toReservation()
            )
        }

internal suspend fun RoomReservationRepository.flushPendingChangesOrThrow(
    target: CloudSheetTarget,
    accessToken: String?
) {
    try {
        flushPendingChanges(target, accessToken)
    } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        AppLog.warning(REPOSITORY_LOG_COMPONENT, exception) {
            "Automatic synchronization failed; local changes remain pending for performanceId=${target.performanceId}"
        }
        throw GoogleSheetChangePendingException(exception)
    }
}

internal suspend fun RoomReservationRepository.activeCloudTarget(): CloudSheetTarget? {
    val performance = performanceDao.getActive() ?: return null
    val sheetTitle = performance.sourceSheetTitle ?: return null
    val source = googleSheetSourceDao.findByActName(performance.actName) ?: return null
    return CloudSheetTarget(performance.id, source.spreadsheetUrl, sheetTitle)
}

internal suspend fun RoomReservationRepository.ensureSheetRowIds(reservationIds: List<Long>) {
    reservationIds.distinct().forEach { reservationId ->
        val reservation = reservationDao.getById(reservationId) ?: return@forEach
        if (reservation.sheetRowId.isBlank()) {
            val sheetRowId = UUID.randomUUID().toString()
            reservationDao.update(reservation.copy(sheetRowId = sheetRowId))
            AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                "Assigned sheetRowId=${sheetRowId.toAbbreviatedId()} to reservationId=$reservationId"
            }
        }
    }
}

internal suspend fun RoomReservationRepository.stagePendingChanges(
    performanceId: Long,
    reservationIds: List<Long>,
    baseRows: Map<Long, ReservationSpreadsheetRow>
) {
    reservationIds.forEach { reservationId ->
        val existingChange = pendingSheetChangeDao.findByReservationId(reservationId)
        val reservation = reservationDao.getWithTicketSalesById(reservationId)
        val desiredRow = reservation?.toReservation()?.let(ReservationSpreadsheetRow::fromReservation)
        val isDeletion = reservation?.reservation?.syncState == ReservationSyncState.PENDING_DELETION
        val operation = if (isDeletion) PendingSheetOperation.DELETE else PendingSheetOperation.UPSERT
        val baseRowJson = if (existingChange?.status == PendingSheetChangeStatus.CONFLICT) {
            baseRows[reservationId]?.toSnapshotJson()
        } else {
            existingChange?.baseRowJson ?: baseRows[reservationId]?.toSnapshotJson()
        }
        pendingSheetChangeDao.upsert(
            PendingSheetChangeEntity(
                id = existingChange?.id ?: UUID.randomUUID().toString(),
                reservationId = reservationId,
                performanceId = performanceId,
                operation = operation,
                baseRowJson = baseRowJson,
                desiredRowJson = desiredRow?.toSnapshotJson(),
                status = PendingSheetChangeStatus.PENDING,
                lastError = "",
                createdAt = existingChange?.createdAt ?: System.currentTimeMillis()
            )
        )
        AppLog.debug(REPOSITORY_LOG_COMPONENT) {
            "Stored pending change; reservationId=$reservationId, operation=$operation, " +
                "previousStatus=${existingChange?.status ?: "none"}"
        }
        reservation?.reservation?.let { entity ->
            reservationDao.update(
                entity.copy(
                    syncState = if (isDeletion) {
                        ReservationSyncState.PENDING_DELETION
                    } else {
                        ReservationSyncState.PENDING
                    }
                )
            )
        }
    }
}

/**
 * Reads the selected tab under its lock, imports its current truth, then replays this device's
 * local outbox only when the row has not changed in the meantime.
 */
internal suspend fun RoomReservationRepository.flushPendingChanges(target: CloudSheetTarget, accessToken: String?): Int {
    val token = requireNotNull(accessToken) { "Google Sheets -kirjautuminen vaaditaan." }
    val startedAt = System.nanoTime()
    AppLog.info(REPOSITORY_LOG_COMPONENT) {
        "Starting pending-change flush; performanceId=${target.performanceId}, tab=${target.sheetTitle}"
    }
    return googleSheetsClient.withPerformanceLock(
        spreadsheetUrl = target.spreadsheetUrl,
        sheetTitle = target.sheetTitle,
        accessToken = token
    ) {
        val importData = loadAndValidatePerformance(target, token)
        googleSheetsClient.saveSheetSchemas(target.spreadsheetUrl, token, listOf(importData.schema))
        database.withTransaction {
            storeSheetSchemas(target.spreadsheetUrl, listOf(importData.schema))
        }
        AppLog.debug(REPOSITORY_LOG_COMPONENT) {
            "Received ${importData.rows.size} remote rows from tab=${target.sheetTitle}"
        }
        val manuallyManagedRows = importData.rows.filter {
            it.applicationMutationMetadataState == ApplicationMutationMetadataState.INVALID
        }
        googleSheetsClient.clearManualRowStrikethrough(
            spreadsheetUrl = target.spreadsheetUrl,
            sheetTitle = target.sheetTitle,
            accessToken = token,
            rowNumbers = manuallyManagedRows.mapNotNull(ReservationSpreadsheetRow::sourceRowNumber)
        )
        AppLog.debug(REPOSITORY_LOG_COMPONENT) {
            "Ensured manual rows are not struck through; rows=${manuallyManagedRows.size}"
        }
        importData.rows
            .filter {
                it.applicationMutationMetadataState == ApplicationMutationMetadataState.INVALID
            }
            .forEach { row ->
                AppLog.warning(REPOSITORY_LOG_COMPONENT) {
                    "Clearing incomplete or invalid app metadata; ${row.toLogSummary()}"
                }
                googleSheetsClient.clearApplicationMetadata(
                    spreadsheetUrl = target.spreadsheetUrl,
                    sheetTitle = target.sheetTitle,
                    accessToken = token,
                    sheetRowId = row.sheetRowId,
                    sourceIdentity = row.sourceIdentity
                )
            }
        val allChanges = pendingSheetChangeDao.getAllByPerformanceId(target.performanceId)
        val pendingChanges = allChanges.filter { it.status == PendingSheetChangeStatus.PENDING }
        val pendingReservationIds = allChanges.map(PendingSheetChangeEntity::reservationId).toSet()
        AppLog.debug(REPOSITORY_LOG_COMPONENT) {
            "Loaded local sync state; pending=${pendingChanges.size}, " +
                "conflicts=${allChanges.size - pendingChanges.size}"
        }

        database.withTransaction {
            val currentPendingReservationIds = pendingSheetChangeDao
                .getAllByPerformanceId(target.performanceId)
                .map(PendingSheetChangeEntity::reservationId)
                .toSet()
            importSpreadsheetRows(
                rows = importData.rows,
                performanceId = target.performanceId,
                preserveReservationIds = currentPendingReservationIds
            )
            removeMissingSheetReservations(target.performanceId, importData.rows, currentPendingReservationIds)
        }

        pendingChanges.forEach { change ->
            replayPendingChange(target, token, importData.rows, change)
        }
        AppLog.info(REPOSITORY_LOG_COMPONENT) {
            "Completed pending-change flush; performanceId=${target.performanceId}, " +
                "remoteRows=${importData.rows.size}, replayed=${pendingChanges.size}, " +
                "durationMs=${AppLog.elapsedMillis(startedAt)}"
        }
        importData.rows.size
    }
}

internal suspend fun RoomReservationRepository.replayPendingChange(
    target: CloudSheetTarget,
    accessToken: String,
    remoteRows: List<ReservationSpreadsheetRow>,
    change: PendingSheetChangeEntity
) {
    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
        "Replaying pending change; changeId=${change.id.toAbbreviatedId()}, " +
            "reservationId=${change.reservationId}, operation=${change.operation}, status=${change.status}"
    }
    val baseRow = change.baseRowJson?.toReservationSpreadsheetRowSnapshot()
    val desiredRow = change.desiredRowJson?.toReservationSpreadsheetRowSnapshot()
    val remoteRow = remoteRows.find { row -> row.matches(baseRow ?: desiredRow) }
    when (change.operation) {
        PendingSheetOperation.UPSERT -> {
            val desired = requireNotNull(desiredRow)
            when {
                baseRow == null && remoteRow == null -> {
                    val exported = googleSheetsClient.exportRows(
                        target.spreadsheetUrl,
                        target.sheetTitle,
                        accessToken,
                        listOf(desired)
                    ).single()
                    markPendingChangeSynced(change, exported.sheetRowId)
                }
                baseRow == null && remoteRow?.hasSameSheetContentAs(desired) == true -> {
                    markPendingChangeSynced(change, remoteRow.sheetRowId)
                }
                baseRow != null && remoteRow != null -> {
                    val merge = mergePendingSheetRow(baseRow, desired, remoteRow)
                    if (merge.hasConflict) {
                        AppLog.warning(REPOSITORY_LOG_COMPONENT) {
                            "Pending update conflicts with a manual Sheet edit; reservationId=${change.reservationId}"
                        }
                        markPendingChangeConflict(change, remoteRow)
                        return
                    }
                    val exported = googleSheetsClient.exportRows(
                        target.spreadsheetUrl,
                        target.sheetTitle,
                        accessToken,
                        listOf(merge.row)
                    ).single()
                    markPendingChangeSynced(
                        change = change,
                        exportedSheetRowId = exported.sheetRowId,
                        importedSheetRow = merge.row.takeUnless { it.hasSameSheetContentAs(desired) }
                    )
                }
                else -> markPendingChangeConflict(change, remoteRow)
            }
        }
        PendingSheetOperation.DELETE -> {
            when {
                remoteRow == null -> markPendingDeletionSynced(change)
                baseRow != null && remoteRow.hasSameSheetContentAs(baseRow) -> {
                    googleSheetsClient.softDeleteRow(
                        spreadsheetUrl = target.spreadsheetUrl,
                        sheetTitle = target.sheetTitle,
                        accessToken = accessToken,
                        row = remoteRow
                    )
                    markPendingDeletionSynced(change)
                }
                else -> markPendingChangeConflict(change, remoteRow)
            }
        }
    }
}

internal suspend fun RoomReservationRepository.markPendingChangeSynced(
    change: PendingSheetChangeEntity,
    exportedSheetRowId: String,
    importedSheetRow: ReservationSpreadsheetRow? = null
) {
    database.withTransaction {
        reservationDao.getById(change.reservationId)?.let { reservation ->
            reservationDao.update(
                reservation.copy(
                    sheetRowId = exportedSheetRowId.ifBlank { reservation.sheetRowId },
                    syncState = ReservationSyncState.SYNCED
                )
            )
            importedSheetRow?.let { row ->
                importSpreadsheetRows(
                    rows = listOf(row.copy(sheetRowId = exportedSheetRowId)),
                    performanceId = change.performanceId
                )
            }
        }
        pendingSheetChangeDao.deleteByReservationId(change.reservationId)
    }
    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
        "Marked pending update synchronized; reservationId=${change.reservationId}, " +
            "sheetRowId=${exportedSheetRowId.toAbbreviatedId()}"
    }
}

internal suspend fun RoomReservationRepository.markPendingDeletionSynced(change: PendingSheetChangeEntity) {
    database.withTransaction {
        pendingSheetChangeDao.deleteByReservationId(change.reservationId)
        reservationDao.deleteById(change.reservationId)
    }
    AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Marked pending deletion synchronized; reservationId=${change.reservationId}" }
}

internal suspend fun RoomReservationRepository.markPendingChangeConflict(
    change: PendingSheetChangeEntity,
    remoteRow: ReservationSpreadsheetRow?
) {
    database.withTransaction {
        remoteRow?.let { row ->
            importSpreadsheetRows(
                rows = listOf(row),
                performanceId = change.performanceId
            )
        }
        pendingSheetChangeDao.upsert(
            change.copy(
                status = PendingSheetChangeStatus.CONFLICT,
                lastError = "Google Sheetsissä on uudempi muutos."
            )
        )
        reservationDao.getById(change.reservationId)?.let { reservation ->
            reservationDao.update(reservation.copy(syncState = ReservationSyncState.CONFLICT))
        }
    }
    AppLog.warning(REPOSITORY_LOG_COMPONENT) {
        "Stored synchronization conflict; reservationId=${change.reservationId}, remoteRowFound=${remoteRow != null}"
    }
}

internal suspend fun RoomReservationRepository.loadAndValidatePerformance(
    target: CloudSheetTarget,
    accessToken: String
) = googleSheetsClient.loadImportDataForTab(
    spreadsheetUrl = target.spreadsheetUrl,
    accessToken = accessToken,
    sheetTitle = target.sheetTitle,
    localAliases = storedSheetAliases()
).also { importData ->
    val performance = requireNotNull(performanceDao.getById(target.performanceId))
    if (importData.candidate.performanceName != performance.actName || importData.candidate.date != performance.date) {
        AppLog.warning(REPOSITORY_LOG_COMPONENT) {
            "Sheet metadata does not match local performance; performanceId=${target.performanceId}, " +
                "localDate=${performance.date}, remoteDate=${importData.candidate.date}, tab=${target.sheetTitle}"
        }
        throw GoogleSheetSourceChangedException()
    }
}
