package fi.tukkateatteri.data

import androidx.room.withTransaction
import fi.tukkateatteri.data.local.GoogleSheetSourceDao
import fi.tukkateatteri.data.local.PendingSheetChangeDao
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.PerformanceDao
import fi.tukkateatteri.data.local.PerformanceEntity
import fi.tukkateatteri.data.local.ReservationDao
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.ReservationWithTicketSales
import fi.tukkateatteri.data.local.SheetFieldDefinitionDao
import fi.tukkateatteri.data.local.SheetFieldAliasDao
import fi.tukkateatteri.data.local.toAliasEntities
import fi.tukkateatteri.data.local.toAliasEntity
import fi.tukkateatteri.data.local.toEntities
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.toEntity
import fi.tukkateatteri.data.local.toGoogleSheetSource
import fi.tukkateatteri.data.local.toPerformance
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.spreadsheet.GoogleSheetsClient
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.SheetFieldMapping
import fi.tukkateatteri.data.spreadsheet.SheetColumnSchema
import fi.tukkateatteri.data.spreadsheet.saveFieldMappings
import fi.tukkateatteri.data.spreadsheet.saveSheetSchemas
import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toLogSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

internal data class CloudSheetTarget(
    val performanceId: Long,
    val spreadsheetUrl: String,
    val sheetTitle: String
)

internal class RoomReservationRepository(
    internal val database: ReservationDatabase,
    internal val reservationDao: ReservationDao,
    internal val performanceDao: PerformanceDao,
    internal val googleSheetSourceDao: GoogleSheetSourceDao,
    internal val pendingSheetChangeDao: PendingSheetChangeDao,
    internal val sheetFieldDefinitionDao: SheetFieldDefinitionDao,
    internal val sheetFieldAliasDao: SheetFieldAliasDao,
    internal val googleSheetsClient: GoogleSheetsClient = GoogleSheetsClient()
) : ReservationRepository {
    override val performances: Flow<List<Performance>> = performanceDao.observeAll()
        .map { performances -> performances.map { performance -> performance.toPerformance() } }
    override val activePerformance: Flow<Performance?> = performanceDao.observeActive()
        .map { performance -> performance?.toPerformance() }
    override val googleSheetSources: Flow<List<GoogleSheetSource>> = googleSheetSourceDao.observeAll()
        .map { sources -> sources.map { source -> source.toGoogleSheetSource() } }
    override val availableTicketTypes: Flow<List<TicketType>> = activeDefinitions()
        .map { definitions ->
            definitions.mapNotNull { it.toTicketType() }.ifEmpty {
                TicketType.entries.filterNot { it == TicketType.UNSPECIFIED }
            }
        }
    override val availablePaymentMethods: Flow<List<PaymentMethod>> = activeDefinitions()
        .map { definitions ->
            definitions.mapNotNull { it.toPaymentMethod() }.ifEmpty { PaymentMethod.entries }
        }

    private fun activeDefinitions() = combine(
        performanceDao.observeActive(),
        googleSheetSourceDao.observeAll(),
        sheetFieldDefinitionDao.observeAll()
    ) { performance, sources, definitions ->
        val sourceUrl = performance?.let { active ->
            sources.firstOrNull { it.actName == active.actName }?.spreadsheetUrl
        }
        definitions.filter { it.active && it.spreadsheetUrl == sourceUrl }
            .sortedBy { it.sortOrder }
    }

    override fun reservationsForPerformance(performanceId: Long): Flow<List<Reservation>> =
        reservationDao.observeByPerformanceWithTicketSales(performanceId)
            .map { reservations -> reservations.map { reservation -> reservation.toReservation() } }

    override fun reservationsForAct(actName: String): Flow<List<Reservation>> =
        reservationDao.observeByActWithTicketSales(actName)
            .map { reservations -> reservations.map { reservation -> reservation.toReservation() } }

    override suspend fun createPerformance(actName: String, date: String): Long = database.withTransaction {
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Creating or selecting local performance; date=${date.trim()}" }
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
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Performance is active; performanceId=$performanceId" }
        performanceId
    }

    override suspend fun selectPerformance(performanceId: Long) {
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Selecting local performanceId=$performanceId" }
        performanceDao.setActive(performanceId)
    }

    override suspend fun deletePerformance(performanceId: Long) {
        AppLog.info(REPOSITORY_LOG_COMPONENT) { "Deleting performance and its local reservations; performanceId=$performanceId" }
        database.withTransaction {
            requireNotNull(performanceDao.getById(performanceId)) { "Performance does not exist." }
            reservationDao.deleteAllByPerformance(performanceId)
            performanceDao.deleteById(performanceId)
        }
    }

    override suspend fun deleteAct(actName: String) {
        AppLog.info(REPOSITORY_LOG_COMPONENT) { "Deleting local act data; act=$actName" }
        database.withTransaction {
            performanceDao.getByActName(actName).forEach { performance ->
                reservationDao.deleteAllByPerformance(performance.id)
            }
            performanceDao.deleteByActName(actName)
            googleSheetSourceDao.deleteByActName(actName)
        }
    }

    override suspend fun clearLocalData() {
        AppLog.info(REPOSITORY_LOG_COMPONENT) { "Deleting all locally stored app data" }
        database.withTransaction {
            performanceDao.getAll().forEach { performance ->
                reservationDao.deleteAllByPerformance(performance.id)
            }
            performanceDao.deleteAll()
            googleSheetSourceDao.deleteAll()
            sheetFieldDefinitionDao.deleteAll()
            sheetFieldAliasDao.deleteAll()
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
        operation = "add admission",
        accessToken = accessToken,
        mutation = {
            database.withTransaction {
                val activePerformance = requireNotNull(performanceDao.getActive()) {
                    "Select a performance before adding reservations."
                }
                val isDoorSale = admissionType == AdmissionType.DOOR_SALE
                if (!isDoorSale) {
                    validateReservedTicketAllocations(reservedTicketAllocations, seatCount)
                }
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
                AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                    "Inserted admission; reservationId=$reservationId, performanceId=${activePerformance.id}, " +
                        "admission=$admissionType, seats=$seatCount"
                }
                reservationId
            }
        },
        affectedReservationIds = { reservationId -> listOf(reservationId) }
    )

    override suspend fun updateReservation(reservation: Reservation, accessToken: String?) {
        runCloudMutation(
            operation = "update reservation",
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val existingReservation = requireNotNull(
                        reservationDao.getWithTicketSalesById(reservation.id)
                    )
                    val existingEntity = existingReservation.reservation
                    val existingRecordedSaleSeatCount = existingReservation.toReservation().recordedSaleSeatCount
                    require(
                        reservation.seatCount >= maxOf(
                            existingEntity.arrivalCount,
                            existingRecordedSaleSeatCount
                        )
                    ) {
                        "Seat count must not be lower than arrived or redeemed tickets."
                    }
                    val isDoorSale = reservation.admissionType == AdmissionType.DOOR_SALE
                    if (!isDoorSale) {
                        validateReservedTicketAllocations(
                            reservation.reservedTicketAllocations,
                            reservation.seatCount
                        )
                    }
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
                    AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Updated ${reservation.toLogSummary()}" }
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
            operation = "add ticket sale",
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val currentReservation = requireNotNull(
                        reservationDao.getWithTicketSalesById(reservationId)
                    )
                    val reservation = currentReservation.reservation
                    require(quantity <= currentReservation.toReservation().availableTicketSaleSeatCount) {
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
                    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                        "Inserted ticketSaleId=$ticketSaleId; reservationId=$reservationId, " +
                            "type=$ticketType, quantity=$quantity"
                    }
                }
            },
            affectedReservationIds = { listOf(reservationId) }
        )
    }

    override suspend fun updateArrivalCount(reservationId: Long, arrivalCount: Int, accessToken: String?) {
        require(arrivalCount >= 0) { "Arrival count must not be negative." }
        runCloudMutation(
            operation = "update arrival count",
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
                    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                        "Applied arrival count; reservationId=$reservationId, requested=$arrivalCount, " +
                            "stored=$boundedArrivalCount"
                    }
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
            operation = "update ticket sale",
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
                    val protectedPayments = reservationWithSales.ticketSales
                        .first { sale -> sale.ticketSale.id == ticketSaleId }
                        .payments
                        .filter(PaymentAllocationEntity::isLocked)
                    if (protectedPayments.isNotEmpty()) {
                        require(ticketType == existingTicketSale.ticketType && quantity == existingTicketSale.quantity) {
                            "A ticket sale with a completed terminal payment cannot change ticket type or quantity."
                        }
                        require(payments.retainProtectedPayments(protectedPayments)) {
                            "A confirmed external payment cannot be changed or removed."
                        }
                    }
                    val otherRecordedSeatCount = reservationWithSales.ticketSales
                        .filterNot { sale -> sale.ticketSale.id == ticketSaleId }
                        .sumOf { sale -> sale.ticketSale.quantity }
                    require(quantity <= reservation.seatCount - otherRecordedSeatCount) {
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
                    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                        "Updated ticketSaleId=$ticketSaleId; reservationId=${existingTicketSale.reservationId}, " +
                            "type=$ticketType, oldQuantity=${existingTicketSale.quantity}, newQuantity=$quantity"
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
            operation = "delete ticket sale",
            accessToken = accessToken,
            mutation = {
                database.withTransaction {
                    val ticketSale = reservationDao.getTicketSaleById(ticketSaleId) ?: run {
                        AppLog.warning(REPOSITORY_LOG_COMPONENT) { "Ticket sale deletion found no row; ticketSaleId=$ticketSaleId" }
                        return@withTransaction 0L
                    }
                    require(
                        reservationDao.getTicketSaleWithPaymentsById(ticketSaleId)
                            ?.payments
                            .orEmpty()
                            .none(PaymentAllocationEntity::isLocked)
                    ) { "A ticket sale with a confirmed external payment cannot be deleted." }
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
                    AppLog.debug(REPOSITORY_LOG_COMPONENT) {
                        "Deleted ticketSaleId=$ticketSaleId; reservationId=${ticketSale.reservationId}, " +
                            "quantity=${ticketSale.quantity}"
                    }
                    ticketSale.reservationId
                }
            },
            affectedReservationIds = { reservationId -> listOfNotNull(reservationId.takeIf { it > 0 }) }
        )
    }

    override suspend fun deleteReservation(reservationId: Long, accessToken: String?) {
        require(
            reservationDao.getWithTicketSalesById(reservationId)
                ?.hasLockedPayment() != true
        ) { "A reservation with a confirmed external payment cannot be deleted." }
        val target = activeCloudTarget()
        if (target == null) {
            AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Deleting reservation locally; reservationId=$reservationId" }
            reservationDao.deleteById(reservationId)
            return
        }
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Staging cloud-backed reservation deletion; reservationId=$reservationId" }
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
            performanceDao.getActive()?.let { performance ->
                val reservations = reservationDao.getByPerformanceWithTicketSales(performance.id)
                require(reservations.none { it.hasLockedPayment() }) {
                    "Reservations with confirmed external payments cannot be deleted."
                }
                AppLog.info(REPOSITORY_LOG_COMPONENT) {
                    "Deleting all local reservations; performanceId=${performance.id}"
                }
                reservationDao.deleteAllByPerformance(performance.id)
            }
            return
        }
        val reservations = reservationDao.getByPerformanceWithTicketSales(target.performanceId)
        require(reservations.none { it.hasLockedPayment() }) {
            "Reservations with confirmed external payments cannot be deleted."
        }
        val baseRows = reservations.associate { reservation ->
            reservation.reservation.id to ReservationSpreadsheetRow.fromReservation(
                reservation.toReservation()
            )
        }
        val reservationIds = reservations.map { it.reservation.id }
        AppLog.info(REPOSITORY_LOG_COMPONENT) {
            "Staging deletion of ${reservationIds.size} cloud-backed reservations; performanceId=${target.performanceId}"
        }
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

    override suspend fun importGoogleSheet(
        spreadsheetUrl: String,
        accessToken: String
    ): GoogleSheetImportResult {
        val startedAt = System.nanoTime()
        AppLog.info(REPOSITORY_LOG_COMPONENT) { "Starting full spreadsheet import" }
        val importedData = googleSheetsClient.loadImportData(
            spreadsheetUrl,
            accessToken,
            storedSheetAliases()
        )
        if (importedData.isEmpty()) throw NoGoogleSheetImportCandidatesException()
        googleSheetsClient.saveSheetSchemas(spreadsheetUrl, accessToken, importedData.map { it.schema })
        val performanceIds = database.withTransaction {
            storeSheetSchemas(spreadsheetUrl, importedData.map { it.schema })
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
        ).also { result ->
            AppLog.info(REPOSITORY_LOG_COMPONENT) {
                "Completed full spreadsheet import; performances=${result.performanceCount}, " +
                    "reservations=${result.reservationCount}, durationMs=${AppLog.elapsedMillis(startedAt)}"
            }
        }
    }

    internal suspend fun storeSheetSchemas(
        spreadsheetUrl: String,
        schemas: List<SheetColumnSchema>
    ) {
        val definitions = schemas.flatMap { it.toEntities(spreadsheetUrl) }
            .distinctBy { it.normalizedHeader }
        sheetFieldDefinitionDao.deactivateForSpreadsheet(spreadsheetUrl)
        sheetFieldDefinitionDao.upsertAll(definitions)
        sheetFieldAliasDao.insertAll(schemas.flatMap { it.toAliasEntities() })
        AppLog.info(REPOSITORY_LOG_COMPONENT) {
            "Stored Sheet-provided field definitions; tickets=${definitions.count { it.kind.name == "TICKET" }}, " +
                "payments=${definitions.count { it.kind.name == "PAYMENT" }}"
        }
    }

    override suspend fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String
    ): Int {
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Synchronizing performanceId=$performanceId" }
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
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Stored spreadsheet source mapping" }
    }

    override suspend fun deleteGoogleSheetSource(actName: String) {
        googleSheetSourceDao.deleteByActName(actName)
        AppLog.debug(REPOSITORY_LOG_COMPONENT) { "Deleted spreadsheet source mapping" }
    }

    override suspend fun saveSheetFieldMappings(
        spreadsheetUrl: String,
        accessToken: String,
        mappings: List<SheetFieldMapping>
    ) {
        AppLog.info(REPOSITORY_LOG_COMPONENT) { "Saving ${mappings.size} user-confirmed Sheet field mappings" }
        sheetFieldAliasDao.insertAll(mappings.map { it.toAliasEntity() })
        googleSheetsClient.saveFieldMappings(spreadsheetUrl, accessToken, mappings)
    }

    internal suspend fun storedSheetAliases() = sheetFieldAliasDao.getAll()
        .associate { it.normalizedAlias to it.toStoredAlias() }

}

private fun List<PendingPaymentAllocation>.retainProtectedPayments(
    protectedPayments: List<PaymentAllocationEntity>
): Boolean {
    val unmatchedPayments = toMutableList()
    return protectedPayments.all { protected ->
        val matchIndex = unmatchedPayments.indexOfFirst { pending ->
            pending.method == protected.paymentMethod &&
                pending.amountCents == protected.amountCents &&
                (!protected.zettleSuccessful || pending.zettleSuccessful)
        }
        if (matchIndex < 0) {
            false
        } else {
            unmatchedPayments.removeAt(matchIndex)
            true
        }
    }
}

private val PaymentAllocationEntity.isLocked: Boolean
    get() = paymentMethod.isExternallyConfirmed

private fun ReservationWithTicketSales.hasLockedPayment(): Boolean =
    ticketSales.any { sale -> sale.payments.any(PaymentAllocationEntity::isLocked) }
