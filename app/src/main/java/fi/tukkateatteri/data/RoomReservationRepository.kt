package fi.tukkateatteri.data

import androidx.room.withTransaction
import fi.tukkateatteri.data.local.GoogleSheetSourceDao
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.PendingSheetChangeDao
import fi.tukkateatteri.data.local.PendingSheetChangeEntity
import fi.tukkateatteri.data.local.PendingSheetChangeStatus
import fi.tukkateatteri.data.local.PendingSheetOperation
import fi.tukkateatteri.data.local.PerformanceDao
import fi.tukkateatteri.data.local.PerformanceEntity
import fi.tukkateatteri.data.local.ReservationDao
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.ReservationWithTicketSales
import fi.tukkateatteri.data.local.ReservedTicketAllocationEntity
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.toEntity
import fi.tukkateatteri.data.local.toGoogleSheetSource
import fi.tukkateatteri.data.local.toPerformance
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.spreadsheet.GoogleSheetsClient
import fi.tukkateatteri.data.spreadsheet.ApplicationMutationMetadataState
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.hasSameSheetContentAs
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRowSnapshot
import fi.tukkateatteri.data.spreadsheet.toSnapshotJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private data class CloudSheetTarget(
    val performanceId: Long,
    val spreadsheetUrl: String,
    val sheetTitle: String
)

class RoomReservationRepository(
    private val database: ReservationDatabase,
    private val reservationDao: ReservationDao,
    private val performanceDao: PerformanceDao,
    private val googleSheetSourceDao: GoogleSheetSourceDao,
    private val pendingSheetChangeDao: PendingSheetChangeDao,
    private val googleSheetsClient: GoogleSheetsClient = GoogleSheetsClient()
) : ReservationRepository {
    override val performances: Flow<List<Performance>> = performanceDao.observeAll()
        .map { performances -> performances.map { performance -> performance.toPerformance() } }
    override val activePerformance: Flow<Performance?> = performanceDao.observeActive()
        .map { performance -> performance?.toPerformance() }
    override val googleSheetSources: Flow<List<GoogleSheetSource>> = googleSheetSourceDao.observeAll()
        .map { sources -> sources.map { source -> source.toGoogleSheetSource() } }

    override fun reservationsForPerformance(performanceId: Long): Flow<List<Reservation>> =
        reservationDao.observeByPerformanceWithTicketSales(performanceId)
            .map { reservations -> reservations.map { reservation -> reservation.toReservation() } }

    private suspend fun <T> runCloudMutation(
        accessToken: String?,
        mutation: suspend () -> T,
        affectedReservationIds: suspend (T) -> List<Long>
    ): T {
        val target = activeCloudTarget()
        val baseRows = target?.let { spreadsheetRowsByReservationId(it.performanceId) }.orEmpty()
        val result = mutation()
        if (target != null) {
            val affectedIds = affectedReservationIds(result).distinct()
            database.withTransaction {
                ensureSheetRowIds(affectedIds)
                stagePendingChanges(target.performanceId, affectedIds, baseRows)
            }
            flushPendingChangesOrThrow(target, accessToken)
        }
        return result
    }

    private suspend fun spreadsheetRowsByReservationId(
        performanceId: Long
    ): Map<Long, ReservationSpreadsheetRow> =
        reservationDao.getByPerformanceWithTicketSales(performanceId)
            .associate { reservation ->
                reservation.reservation.id to ReservationSpreadsheetRow.fromReservation(
                    reservation.toReservation()
                )
            }

    private suspend fun flushPendingChangesOrThrow(
        target: CloudSheetTarget,
        accessToken: String?
    ) {
        try {
            flushPendingChanges(target, accessToken)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            throw GoogleSheetChangePendingException()
        }
    }

    private suspend fun activeCloudTarget(): CloudSheetTarget? {
        val performance = performanceDao.getActive() ?: return null
        val sheetTitle = performance.sourceSheetTitle ?: return null
        val source = googleSheetSourceDao.findByActName(performance.actName) ?: return null
        return CloudSheetTarget(performance.id, source.spreadsheetUrl, sheetTitle)
    }

    private suspend fun ensureSheetRowIds(reservationIds: List<Long>) {
        reservationIds.distinct().forEach { reservationId ->
            val reservation = reservationDao.getById(reservationId) ?: return@forEach
            if (reservation.sheetRowId.isBlank()) {
                reservationDao.update(reservation.copy(sheetRowId = UUID.randomUUID().toString()))
            }
        }
    }

    private suspend fun stagePendingChanges(
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
    private suspend fun flushPendingChanges(target: CloudSheetTarget, accessToken: String?): Int {
        val token = requireNotNull(accessToken) { "Google Sheets -kirjautuminen vaaditaan." }
        return googleSheetsClient.withPerformanceLock(
            spreadsheetUrl = target.spreadsheetUrl,
            sheetTitle = target.sheetTitle,
            accessToken = token
        ) {
            val importData = loadAndValidatePerformance(target, token)
            googleSheetsClient.clearManualRowStrikethrough(
                spreadsheetUrl = target.spreadsheetUrl,
                sheetTitle = target.sheetTitle,
                accessToken = token,
                rowNumbers = importData.rows
                    .filter {
                        it.applicationMutationMetadataState != ApplicationMutationMetadataState.VALID
                    }
                    .mapNotNull(ReservationSpreadsheetRow::sourceRowNumber)
            )
            importData.rows
                .filter {
                    it.applicationMutationMetadataState == ApplicationMutationMetadataState.INVALID
                }
                .forEach { row ->
                    googleSheetsClient.clearApplicationMetadata(
                        spreadsheetUrl = target.spreadsheetUrl,
                        sheetTitle = target.sheetTitle,
                        accessToken = token,
                        sheetRowId = row.sheetRowId,
                        sourceIdentity = row.sourceIdentity
                    )
                }
            val localRowsBeforeImport = reservationDao.getByPerformanceWithTicketSales(target.performanceId)
                .map(ReservationWithTicketSales::toReservation)
            val allChanges = pendingSheetChangeDao.getAllByPerformanceId(target.performanceId)
            val pendingChanges = allChanges.filter { it.status == PendingSheetChangeStatus.PENDING }
            val pendingReservationIds = allChanges.map(PendingSheetChangeEntity::reservationId).toSet()

            val directlyEditedRows = localRowsBeforeImport
                .filter { it.id !in pendingReservationIds && it.syncState == ReservationSyncState.SYNCED }
                .mapNotNull { localReservation ->
                    val remoteRow = importData.rows.find { row -> row.matches(localReservation) }
                        ?: return@mapNotNull null
                    val localRow = ReservationSpreadsheetRow.fromReservation(localReservation)
                    remoteRow.takeUnless { it.hasSameSheetContentAs(localRow) }
                }
            directlyEditedRows.forEach { row ->
                googleSheetsClient.clearApplicationMetadata(
                    spreadsheetUrl = target.spreadsheetUrl,
                    sheetTitle = target.sheetTitle,
                    accessToken = token,
                    sheetRowId = row.sheetRowId,
                    sourceIdentity = row.sourceIdentity
                )
            }

            database.withTransaction {
                importSpreadsheetRows(
                    rows = importData.rows,
                    performanceId = target.performanceId,
                    preserveReservationIds = pendingReservationIds
                )
                removeMissingSheetReservations(target.performanceId, importData.rows, pendingReservationIds)
            }

            pendingChanges.forEach { change ->
                replayPendingChange(target, token, importData.rows, change)
            }
            importData.rows.size
        }
    }

    private suspend fun replayPendingChange(
        target: CloudSheetTarget,
        accessToken: String,
        remoteRows: List<ReservationSpreadsheetRow>,
        change: PendingSheetChangeEntity
    ) {
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
                            sheetRowId = remoteRow.sheetRowId,
                            sourceIdentity = remoteRow.sourceIdentity
                        )
                        markPendingDeletionSynced(change)
                    }
                    else -> markPendingChangeConflict(change, remoteRow)
                }
            }
        }
    }

    private suspend fun markPendingChangeSynced(
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
    }

    private suspend fun markPendingDeletionSynced(change: PendingSheetChangeEntity) {
        database.withTransaction {
            pendingSheetChangeDao.deleteByReservationId(change.reservationId)
            reservationDao.deleteById(change.reservationId)
        }
    }

    private suspend fun markPendingChangeConflict(
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
    }

    private suspend fun loadAndValidatePerformance(
        target: CloudSheetTarget,
        accessToken: String
    ) = googleSheetsClient.loadImportDataForTab(
        spreadsheetUrl = target.spreadsheetUrl,
        accessToken = accessToken,
        sheetTitle = target.sheetTitle
    ).also { importData ->
        val performance = requireNotNull(performanceDao.getById(target.performanceId))
        if (importData.candidate.performanceName != performance.actName || importData.candidate.date != performance.date) {
            throw GoogleSheetSourceChangedException()
        }
    }

    override suspend fun createPerformance(actName: String, date: String): Long = database.withTransaction {
        val normalizedActName = actName.trim()
        val normalizedDate = date.trim()
        require(normalizedActName.isNotBlank()) { "Performance name must not be blank." }
        require(normalizedDate.isNotBlank()) { "Performance date must not be blank." }
        val performanceId = performanceDao.findByNameAndDate(normalizedActName, normalizedDate)?.id
            ?: performanceDao.insert(
                PerformanceEntity(
                    actName = normalizedActName,
                    date = normalizedDate
                )
            )
        performanceDao.setActive(performanceId)
        performanceId
    }

    override suspend fun selectPerformance(performanceId: Long) {
        performanceDao.setActive(performanceId)
    }

    override suspend fun deletePerformance(performanceId: Long) {
        database.withTransaction {
            requireNotNull(performanceDao.getById(performanceId)) { "Performance does not exist." }
            reservationDao.deleteAllByPerformance(performanceId)
            performanceDao.deleteById(performanceId)
        }
    }

    override suspend fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>,
        accessToken: String?
    ): Long = runCloudMutation(
        accessToken = accessToken,
        mutation = {
            database.withTransaction {
                val activePerformance = requireNotNull(performanceDao.getActive()) {
                    "Select a performance before adding reservations."
                }
                validateReservedTicketAllocations(reservedTicketAllocations, seatCount)
                val isDoorSale = admissionType == AdmissionType.DOOR_SALE
                val reservationId = reservationDao.insert(
                    ReservationEntity(
                        performanceId = activePerformance.id,
                        lastName = lastName.trim(),
                        firstName = firstName.trim(),
                        contact = contact.trim(),
                        seatCount = seatCount,
                        admissionType = admissionType,
                        arrivalCount = if (isDoorSale) seatCount else 0,
                        isPresent = isDoorSale
                    )
                )
                replaceReservedTicketAllocations(reservationId, reservedTicketAllocations)
                reservationId
            }
        },
        affectedReservationIds = { reservationId -> listOf(reservationId) }
    )

    override suspend fun updateReservation(reservation: Reservation, accessToken: String?) {
        runCloudMutation(
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val existingReservation = requireNotNull(
                        reservationDao.getWithTicketSalesById(reservation.id)
                    )
                    val existingEntity = existingReservation.reservation
                    val existingPaidSeatCount = existingReservation.toReservation().paidSeatCount
                    require(
                        reservation.seatCount >= maxOf(
                            existingEntity.arrivalCount,
                            existingPaidSeatCount
                        )
                    ) {
                        "Seat count must not be lower than arrived or redeemed tickets."
                    }
                    validateReservedTicketAllocations(
                        reservation.reservedTicketAllocations,
                        reservation.seatCount
                    )
                    val isDoorSale = reservation.admissionType == AdmissionType.DOOR_SALE
                    reservationDao.update(
                        ReservationEntity(
                            id = reservation.id,
                            performanceId = existingEntity.performanceId,
                            lastName = reservation.lastName.trim(),
                            firstName = reservation.firstName.trim(),
                            contact = reservation.contact.trim(),
                            seatCount = reservation.seatCount,
                            notes = reservation.notes.trim(),
                            sourceIdentity = existingEntity.sourceIdentity,
                            sheetRowId = existingEntity.sheetRowId,
                            admissionType = reservation.admissionType,
                            arrivalCount = if (isDoorSale) {
                                reservation.seatCount
                            } else {
                                existingEntity.arrivalCount
                            },
                            isPresent = isDoorSale || reservation.isPresent,
                            paymentMethod = null
                        )
                    )
                    replaceReservedTicketAllocations(
                        reservation.id,
                        reservation.reservedTicketAllocations
                    )
                }
            },
            affectedReservationIds = { listOf(reservation.id) }
        )
    }

    override suspend fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String?
    ) {
        validateTicketSale(ticketType, quantity, payments)
        runCloudMutation(
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val currentReservation = requireNotNull(
                        reservationDao.getWithTicketSalesById(reservationId)
                    )
                    val reservation = currentReservation.reservation
                    require(quantity <= currentReservation.toReservation().unpaidSeatCount) {
                        "Ticket quantity exceeds the number of unredeemed seats."
                    }
                    val ticketSaleId = reservationDao.insertTicketSale(
                        TicketSaleEntity(
                            reservationId = reservationId,
                            ticketType = ticketType,
                            quantity = quantity,
                            unitPriceCents = ticketType.defaultPriceCents,
                            origin = TicketSaleOrigin.MANUAL,
                            countsAsArrival = true
                        )
                    )
                    replacePaymentAllocations(ticketSaleId, payments)
                    reservationDao.update(
                        reservation.copy(
                            arrivalCount = (reservation.arrivalCount + quantity)
                                .coerceAtMost(reservation.seatCount),
                            isPresent = true
                        )
                    )
                }
            },
            affectedReservationIds = { listOf(reservationId) }
        )
    }

    override suspend fun updateArrivalCount(reservationId: Long, arrivalCount: Int, accessToken: String?) {
        require(arrivalCount >= 0) { "Arrival count must not be negative." }
        runCloudMutation(
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val reservation = requireNotNull(reservationDao.getById(reservationId))
                    val boundedArrivalCount = if (
                        reservation.admissionType == AdmissionType.DOOR_SALE
                    ) {
                        reservation.seatCount
                    } else {
                        arrivalCount.coerceAtMost(reservation.seatCount)
                    }
                    reservationDao.update(
                        reservation.copy(
                            arrivalCount = boundedArrivalCount,
                            isPresent = boundedArrivalCount > 0
                        )
                    )
                }
            },
            affectedReservationIds = { listOf(reservationId) }
        )
    }

    override suspend fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String?
    ) {
        validateTicketSale(ticketType, quantity, payments)
        runCloudMutation(
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val existingTicketSale = requireNotNull(
                        reservationDao.getTicketSaleById(ticketSaleId)
                    )
                    val reservationWithSales = requireNotNull(
                        reservationDao.getWithTicketSalesById(existingTicketSale.reservationId)
                    )
                    val reservation = reservationWithSales.reservation
                    val otherPaidSeatCount = reservationWithSales.toReservation().paidSeatCount -
                        existingTicketSale.quantity
                    require(quantity <= reservation.seatCount - otherPaidSeatCount) {
                        "Ticket quantity exceeds the number of unredeemed seats."
                    }
                    reservationDao.updateTicketSale(
                        existingTicketSale.copy(
                            ticketType = ticketType,
                            quantity = quantity,
                            unitPriceCents = ticketType.defaultPriceCents
                        )
                    )
                    replacePaymentAllocations(ticketSaleId, payments)
                    if (existingTicketSale.countsAsArrival) {
                        val arrivalCount = (reservation.arrivalCount + quantity - existingTicketSale.quantity)
                            .coerceIn(0, reservation.seatCount)
                        reservationDao.update(
                            reservation.copy(
                                arrivalCount = arrivalCount,
                                isPresent = arrivalCount > 0
                            )
                        )
                    }
                }
            },
            affectedReservationIds = {
                val ticketSale = reservationDao.getTicketSaleById(ticketSaleId)
                listOfNotNull(ticketSale?.reservationId)
            }
        )
    }

    override suspend fun deleteTicketSale(ticketSaleId: Long, accessToken: String?) {
        runCloudMutation(
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val ticketSale = reservationDao.getTicketSaleById(ticketSaleId) ?: return@withTransaction 0L
                    reservationDao.deleteTicketSaleById(ticketSaleId)
                    if (ticketSale.countsAsArrival) {
                        reservationDao.getById(ticketSale.reservationId)?.let { reservation ->
                            val updatedArrivalCount = (reservation.arrivalCount - ticketSale.quantity).coerceAtLeast(0)
                            reservationDao.update(
                                reservation.copy(
                                    arrivalCount = updatedArrivalCount,
                                    isPresent = updatedArrivalCount > 0
                                )
                            )
                        }
                    }
                    ticketSale.reservationId
                }
            },
            affectedReservationIds = { reservationId -> listOfNotNull(reservationId.takeIf { it > 0 }) }
        )
    }

    override suspend fun deleteReservation(reservationId: Long, accessToken: String?) {
        val target = activeCloudTarget()
        if (target == null) {
            reservationDao.deleteById(reservationId)
            return
        }
        val baseRows = spreadsheetRowsByReservationId(target.performanceId)
        database.withTransaction {
            reservationDao.getById(reservationId)?.let { reservation ->
                reservationDao.update(reservation.copy(syncState = ReservationSyncState.PENDING_DELETION))
                stagePendingChanges(target.performanceId, listOf(reservationId), baseRows)
            }
        }
        flushPendingChangesOrThrow(target, accessToken)
    }

    override suspend fun deleteAllReservations(accessToken: String?) {
        val target = activeCloudTarget()
        if (target == null) {
            performanceDao.getActive()?.let { reservationDao.deleteAllByPerformance(it.id) }
            return
        }
        val reservations = reservationDao.getByPerformanceWithTicketSales(target.performanceId)
        val baseRows = reservations.associate { reservation ->
            reservation.reservation.id to ReservationSpreadsheetRow.fromReservation(
                reservation.toReservation()
            )
        }
        val reservationIds = reservations.map { it.reservation.id }
        database.withTransaction {
            reservations.forEach { reservation ->
                reservationDao.update(
                    reservation.reservation.copy(syncState = ReservationSyncState.PENDING_DELETION)
                )
            }
            stagePendingChanges(target.performanceId, reservationIds, baseRows)
        }
        flushPendingChangesOrThrow(target, accessToken)
    }

    private suspend fun importSpreadsheetRows(
        rows: List<ReservationSpreadsheetRow>,
        performanceId: Long,
        preserveReservationIds: Set<Long> = emptySet()
    ) {
        rows.forEach { row ->
            val existingReservation = if (row.sheetRowId.isNotBlank()) {
                reservationDao.findBySheetRowId(row.sheetRowId)
            } else {
                null
            } ?: if (row.sourceIdentity.isNotBlank()) {
                reservationDao.findBySourceIdentity(row.sourceIdentity)
            } else {
                null
            }
            if (existingReservation?.id in preserveReservationIds) return@forEach
            val reservationId = existingReservation?.id ?: reservationDao.insert(
                ReservationEntity(
                    performanceId = performanceId,
                    lastName = row.lastName.trim(),
                    firstName = row.firstName.trim(),
                    contact = row.contact.trim(),
                    seatCount = row.reservedSeatCount,
                    notes = row.notes,
                    sourceIdentity = row.sourceIdentity,
                    sheetRowId = row.sheetRowId,
                    syncState = ReservationSyncState.SYNCED,
                    admissionType = AdmissionType.RESERVATION,
                    arrivalCount = row.arrivalCount,
                    isPresent = row.arrivalCount > 0
                )
            )
            if (existingReservation != null) {
                reservationDao.update(
                    existingReservation.copy(
                        lastName = row.lastName.trim(),
                        firstName = row.firstName.trim(),
                        contact = row.contact.trim(),
                        seatCount = row.reservedSeatCount,
                        notes = row.notes,
                        sourceIdentity = row.sourceIdentity.ifBlank { existingReservation.sourceIdentity },
                        sheetRowId = row.sheetRowId.ifBlank { existingReservation.sheetRowId },
                        syncState = ReservationSyncState.SYNCED,
                        arrivalCount = row.arrivalCount,
                        isPresent = row.arrivalCount > 0
                    )
                )
            }
            replaceReservedTicketAllocations(
                reservationId,
                row.reservedTicketCounts.map { (ticketType, quantity) ->
                    ReservedTicketAllocation(ticketType, quantity)
                }
            )
            reservationDao.deleteAllTicketSalesForReservation(reservationId)
            row.paymentTicketCounts.forEach { (paymentMethod, quantity) ->
                if (quantity > 0) {
                    val ticketSaleId = reservationDao.insertTicketSale(
                        TicketSaleEntity(
                            reservationId = reservationId,
                            ticketType = TicketType.UNSPECIFIED,
                            quantity = quantity,
                            unitPriceCents = 0,
                            origin = TicketSaleOrigin.IMPORTED,
                            countsAsArrival = false
                        )
                    )
                    reservationDao.insertPaymentAllocations(
                        listOf(
                            PaymentAllocationEntity(
                                ticketSaleId = ticketSaleId,
                                paymentMethod = paymentMethod,
                                amountCents = 0
                            )
                        )
                    )
                }
            }
        }
    }

    private suspend fun removeMissingSheetReservations(
        performanceId: Long,
        rows: List<ReservationSpreadsheetRow>,
        preserveReservationIds: Set<Long>
    ) {
        val remoteIdentities = rows.flatMap { row -> listOf(row.sheetRowId, row.sourceIdentity) }
            .filter(String::isNotBlank)
            .toSet()
        reservationDao.getByPerformanceWithTicketSales(performanceId)
            .map(ReservationWithTicketSales::reservation)
            .filter { reservation ->
                reservation.id !in preserveReservationIds &&
                    (reservation.sheetRowId.isNotBlank() || reservation.sourceIdentity.isNotBlank())
            }
            .filter { reservation ->
                reservation.sheetRowId !in remoteIdentities && reservation.sourceIdentity !in remoteIdentities
            }
            .forEach { reservationDao.deleteById(it.id) }
    }

    override suspend fun importGoogleSheet(
        spreadsheetUrl: String,
        accessToken: String
    ): GoogleSheetImportResult {
        val importedData = googleSheetsClient.loadImportData(spreadsheetUrl, accessToken)
        if (importedData.isEmpty()) throw NoGoogleSheetImportCandidatesException()
        val performanceIds = database.withTransaction {
            val importedPerformanceIds = importedData.map { data ->
                val performanceId = findOrCreateImportedPerformance(
                    actName = data.candidate.performanceName,
                    date = data.candidate.date,
                    sourceSheetTitle = data.candidate.sheetTitle
                )
                importSpreadsheetRows(data.rows, performanceId)
                performanceId
            }
            performanceDao.setActive(importedPerformanceIds.first())
            importedData
                .map { it.candidate.performanceName }
                .distinct()
                .forEach { actName ->
                    googleSheetSourceDao.upsert(GoogleSheetSource(actName, spreadsheetUrl).toEntity())
                }
            importedPerformanceIds
        }
        return GoogleSheetImportResult(
            performanceCount = performanceIds.distinct().size,
            reservationCount = importedData.sumOf { it.rows.size }
        )
    }

    override suspend fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String
    ): Int {
        val performance = requireNotNull(performanceDao.getById(performanceId)) {
            "Performance does not exist."
        }
        val sourceSheetTitle = performance.sourceSheetTitle ?: throw GoogleSheetSourceChangedException()
        val target = CloudSheetTarget(performanceId, spreadsheetUrl, sourceSheetTitle)
        return try {
            flushPendingChanges(target, accessToken)
        } catch (_: IllegalArgumentException) {
            throw GoogleSheetSourceChangedException()
        }
    }

    override suspend fun upsertGoogleSheetSource(source: GoogleSheetSource) {
        googleSheetSourceDao.upsert(source.toEntity())
    }

    override suspend fun deleteGoogleSheetSource(actName: String) {
        googleSheetSourceDao.deleteByActName(actName)
    }

    private suspend fun findOrCreateImportedPerformance(
        actName: String,
        date: String,
        sourceSheetTitle: String
    ): Long {
        val normalizedActName = actName.trim()
        val normalizedDate = date.trim()
        val existingPerformance = performanceDao.findByNameAndDate(normalizedActName, normalizedDate)
        return if (existingPerformance != null) {
            performanceDao.updateSourceSheetTitle(existingPerformance.id, sourceSheetTitle)
            existingPerformance.id
        } else {
            performanceDao.insert(
                PerformanceEntity(
                    actName = normalizedActName,
                    date = normalizedDate,
                    sourceSheetTitle = sourceSheetTitle
                )
            )
        }
    }

    private suspend fun replaceReservedTicketAllocations(
        reservationId: Long,
        allocations: List<ReservedTicketAllocation>
    ) {
        reservationDao.deleteReservedTicketAllocationsForReservation(reservationId)
        reservationDao.insertReservedTicketAllocations(
            allocations.map { allocation ->
                ReservedTicketAllocationEntity(
                    reservationId = reservationId,
                    ticketType = allocation.ticketType,
                    quantity = allocation.quantity
                )
            }
        )
    }

    private suspend fun replacePaymentAllocations(
        ticketSaleId: Long,
        payments: List<PendingPaymentAllocation>
    ) {
        reservationDao.deletePaymentAllocationsForTicketSale(ticketSaleId)
        reservationDao.insertPaymentAllocations(
            payments.map { payment ->
                PaymentAllocationEntity(
                    ticketSaleId = ticketSaleId,
                    paymentMethod = payment.method,
                    amountCents = payment.amountCents
                )
            }
        )
    }

    private fun validateReservedTicketAllocations(
        allocations: List<ReservedTicketAllocation>,
        seatCount: Int
    ) {
        require(allocations.sumOf(ReservedTicketAllocation::quantity) <= seatCount) {
            "Reserved ticket quantities must not exceed the seat count."
        }
        require(allocations.map(ReservedTicketAllocation::ticketType).distinct().size == allocations.size) {
            "Each reserved ticket type may only appear once."
        }
    }

    private fun validateTicketSale(
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    ) {
        require(quantity > 0) { "Ticket quantity must be positive." }
        require(ticketType != TicketType.UNSPECIFIED) { "Ticket sales need a ticket type." }
        require(payments.all { payment -> payment.amountCents >= 0 }) {
            "Payment amounts must not be negative."
        }
        require(
            payments.sumOf(PendingPaymentAllocation::amountCents) ==
                ticketType.defaultPriceCents * quantity
        ) {
            "Payment total must match the ticket price."
        }
    }
}
