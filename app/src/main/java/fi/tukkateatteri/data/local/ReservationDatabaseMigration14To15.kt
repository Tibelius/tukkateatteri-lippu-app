package fi.tukkateatteri.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fi.tukkateatteri.data.toPerformanceDateOrNull

internal val ReservationDatabaseMigration14To15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addPerformanceDateSortKeys(db)
        rebuildReservationTables(db)
        ReservationDatabaseConstraints.create(db)
    }
}

private fun addPerformanceDateSortKeys(db: SupportSQLiteDatabase) {
    db.execSQL(
        "ALTER TABLE performances ADD COLUMN performance_date_sort_key INTEGER NOT NULL " +
            "DEFAULT ${Long.MIN_VALUE}"
    )
    db.query("SELECT id, performance_date FROM performances").use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow("id")
        val dateIndex = cursor.getColumnIndexOrThrow("performance_date")
        while (cursor.moveToNext()) {
            val sortKey = cursor.getString(dateIndex)
                .toPerformanceDateOrNull()
                ?.toEpochDay()
                ?: Long.MIN_VALUE
            db.execSQL(
                "UPDATE performances SET performance_date_sort_key = ? WHERE id = ?",
                arrayOf(sortKey, cursor.getLong(idIndex))
            )
        }
    }
}

private fun rebuildReservationTables(db: SupportSQLiteDatabase) {
    createTemporaryVersion15Tables(db)
    copyVersion14Data(db)

    db.execSQL("DROP TABLE payment_allocations")
    db.execSQL("DROP TABLE ticket_sales")
    db.execSQL("DROP TABLE reserved_ticket_allocations")
    db.execSQL("DROP TABLE pending_sheet_changes")
    db.execSQL("DROP TABLE reservations")

    db.execSQL("ALTER TABLE reservations_v15 RENAME TO reservations")
    createFinalVersion15ChildTables(db)
    db.execSQL("INSERT INTO ticket_sales SELECT * FROM ticket_sales_v15")
    db.execSQL("INSERT INTO payment_allocations SELECT * FROM payment_allocations_v15")
    db.execSQL("INSERT INTO reserved_ticket_allocations SELECT * FROM reserved_ticket_allocations_v15")
    db.execSQL("INSERT INTO pending_sheet_changes SELECT * FROM pending_sheet_changes_v15")
    db.execSQL("DROP TABLE payment_allocations_v15")
    db.execSQL("DROP TABLE ticket_sales_v15")
    db.execSQL("DROP TABLE reserved_ticket_allocations_v15")
    db.execSQL("DROP TABLE pending_sheet_changes_v15")

    createVersion15Indices(db)
}

private fun createTemporaryVersion15Tables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE `reservations_v15` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `performance_id` INTEGER NOT NULL,
            `last_name` TEXT NOT NULL,
            `first_name` TEXT NOT NULL,
            `contact` TEXT NOT NULL,
            `seat_count` INTEGER NOT NULL,
            `notes` TEXT NOT NULL,
            `source_identity` TEXT NOT NULL,
            `sheet_row_id` TEXT NOT NULL,
            `sync_state` TEXT NOT NULL,
            `admission_type` TEXT NOT NULL,
            `arrival_count` INTEGER NOT NULL,
            FOREIGN KEY(`performance_id`) REFERENCES `performances`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `ticket_sales_v15` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `reservation_id` INTEGER NOT NULL,
            `ticket_type` TEXT NOT NULL,
            `quantity` INTEGER NOT NULL,
            `unit_price_cents` INTEGER NOT NULL,
            `origin` TEXT NOT NULL,
            `counts_as_arrival` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `payment_allocations_v15` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `ticket_sale_id` INTEGER NOT NULL,
            `payment_method` TEXT NOT NULL,
            `amount_cents` INTEGER NOT NULL,
            `zettle_successful` INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `reserved_ticket_allocations_v15` (
            `reservation_id` INTEGER NOT NULL,
            `ticket_type` TEXT NOT NULL,
            `quantity` INTEGER NOT NULL,
            PRIMARY KEY(`reservation_id`, `ticket_type`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `pending_sheet_changes_v15` (
            `id` TEXT NOT NULL,
            `reservation_id` INTEGER NOT NULL,
            `performance_id` INTEGER NOT NULL,
            `operation` TEXT NOT NULL,
            `base_row_json` TEXT,
            `desired_row_json` TEXT,
            `status` TEXT NOT NULL,
            `last_error` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )
}

private fun createFinalVersion15ChildTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE `ticket_sales` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `reservation_id` INTEGER NOT NULL,
            `ticket_type` TEXT NOT NULL,
            `quantity` INTEGER NOT NULL,
            `unit_price_cents` INTEGER NOT NULL,
            `origin` TEXT NOT NULL,
            `counts_as_arrival` INTEGER NOT NULL,
            FOREIGN KEY(`reservation_id`) REFERENCES `reservations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `payment_allocations` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `ticket_sale_id` INTEGER NOT NULL,
            `payment_method` TEXT NOT NULL,
            `amount_cents` INTEGER NOT NULL,
            `zettle_successful` INTEGER NOT NULL,
            FOREIGN KEY(`ticket_sale_id`) REFERENCES `ticket_sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `reserved_ticket_allocations` (
            `reservation_id` INTEGER NOT NULL,
            `ticket_type` TEXT NOT NULL,
            `quantity` INTEGER NOT NULL,
            PRIMARY KEY(`reservation_id`, `ticket_type`),
            FOREIGN KEY(`reservation_id`) REFERENCES `reservations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE `pending_sheet_changes` (
            `id` TEXT NOT NULL,
            `reservation_id` INTEGER NOT NULL,
            `performance_id` INTEGER NOT NULL,
            `operation` TEXT NOT NULL,
            `base_row_json` TEXT,
            `desired_row_json` TEXT,
            `status` TEXT NOT NULL,
            `last_error` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`reservation_id`) REFERENCES `reservations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`performance_id`) REFERENCES `performances`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
}

private fun createVersion15Indices(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE INDEX index_reservations_source_identity ON reservations (source_identity)")
    db.execSQL("CREATE INDEX index_reservations_sheet_row_id ON reservations (sheet_row_id)")
    db.execSQL("CREATE INDEX index_reservations_performance_id ON reservations (performance_id)")
    db.execSQL("CREATE INDEX index_ticket_sales_reservation_id ON ticket_sales (reservation_id)")
    db.execSQL("CREATE INDEX index_payment_allocations_ticket_sale_id ON payment_allocations (ticket_sale_id)")
    db.execSQL("CREATE INDEX index_pending_sheet_changes_performance_id ON pending_sheet_changes (performance_id)")
    db.execSQL(
        "CREATE UNIQUE INDEX index_pending_sheet_changes_reservation_id " +
            "ON pending_sheet_changes (reservation_id)"
    )
}

private fun copyVersion14Data(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT INTO reservations_v15 (
            id, performance_id, last_name, first_name, contact, seat_count, notes,
            source_identity, sheet_row_id, sync_state, admission_type, arrival_count
        )
        SELECT
            id, performance_id, last_name, first_name, contact,
            MAX(1, seat_count), notes,
            CASE
                WHEN source_identity = '' OR id = (
                    SELECT MIN(other.id) FROM reservations AS other
                    WHERE other.source_identity = reservations.source_identity
                ) THEN source_identity
                ELSE ''
            END,
            CASE
                WHEN sheet_row_id = '' OR id = (
                    SELECT MIN(other.id) FROM reservations AS other
                    WHERE other.sheet_row_id = reservations.sheet_row_id
                ) THEN sheet_row_id
                ELSE ''
            END,
            sync_state, admission_type,
            MIN(MAX(0, arrival_count), MAX(1, seat_count))
        FROM reservations
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO ticket_sales_v15
        SELECT id, reservation_id, ticket_type, MAX(1, quantity),
            MAX(0, unit_price_cents), origin, counts_as_arrival
        FROM ticket_sales
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO payment_allocations_v15
        SELECT id, ticket_sale_id, payment_method, MAX(0, amount_cents), zettle_successful
        FROM payment_allocations
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO reserved_ticket_allocations_v15
        SELECT reservation_id, ticket_type, MAX(1, quantity)
        FROM reserved_ticket_allocations
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO pending_sheet_changes_v15
        SELECT id, reservation_id, performance_id, operation, base_row_json,
            desired_row_json, status, last_error, MAX(0, created_at)
        FROM pending_sheet_changes
        """.trimIndent()
    )
}
