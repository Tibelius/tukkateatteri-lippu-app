package fi.tukkateatteri.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object ReservationDatabaseMigrations {
private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reservations ADD COLUMN admission_type TEXT NOT NULL DEFAULT 'RESERVATION'"
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ticket_sales` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `reservation_id` INTEGER NOT NULL,
                    `ticket_type` TEXT NOT NULL,
                    `quantity` INTEGER NOT NULL,
                    `unit_price_cents` INTEGER NOT NULL,
                    FOREIGN KEY(`reservation_id`) REFERENCES `reservations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ticket_sales_reservation_id` ON `ticket_sales` (`reservation_id`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `payment_allocations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `ticket_sale_id` INTEGER NOT NULL,
                    `payment_method` TEXT NOT NULL,
                    `amount_cents` INTEGER NOT NULL,
                    FOREIGN KEY(`ticket_sale_id`) REFERENCES `ticket_sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_payment_allocations_ticket_sale_id` ON `payment_allocations` (`ticket_sale_id`)"
            )

            db.execSQL(
                """
                INSERT INTO ticket_sales (reservation_id, ticket_type, quantity, unit_price_cents)
                SELECT id, 'UNSPECIFIED', seat_count, 0
                FROM reservations
                WHERE is_present = 1
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO payment_allocations (ticket_sale_id, payment_method, amount_cents)
                SELECT ticket_sales.id,
                    CASE reservations.payment_method
                        WHEN 'PREPAID' THEN 'LIPPUAGENTTI'
                        WHEN 'CARD' THEN 'CARD'
                        WHEN 'CASH' THEN 'CASH'
                        ELSE 'LIPPUAGENTTI'
                    END,
                    0
                FROM ticket_sales
                INNER JOIN reservations ON reservations.id = ticket_sales.reservation_id
                WHERE reservations.is_present = 1 AND reservations.payment_method IS NOT NULL
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sheet_field_aliases` (
                    `normalizedAlias` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `allowsSplitPayment` INTEGER NOT NULL,
                    PRIMARY KEY(`normalizedAlias`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reservations ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reservations ADD COLUMN source_identity TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reservations_source_identity " +
                    "ON reservations (source_identity)"
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `google_sheet_sources` (" +
                    "`actName` TEXT NOT NULL, " +
                    "`spreadsheetUrl` TEXT NOT NULL, " +
                    "PRIMARY KEY(`actName`)" +
                    ")"
            )
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reservations ADD COLUMN arrival_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE ticket_sales ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE ticket_sales ADD COLUMN counts_as_arrival INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reserved_ticket_allocations` (
                    `reservation_id` INTEGER NOT NULL,
                    `ticket_type` TEXT NOT NULL,
                    `quantity` INTEGER NOT NULL,
                    PRIMARY KEY(`reservation_id`, `ticket_type`),
                    FOREIGN KEY(`reservation_id`) REFERENCES `reservations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reserved_ticket_allocations_reservation_id` " +
                    "ON `reserved_ticket_allocations` (`reservation_id`)"
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO reserved_ticket_allocations (reservation_id, ticket_type, quantity)
                SELECT ticket_sales.reservation_id, ticket_sales.ticket_type, SUM(ticket_sales.quantity)
                FROM ticket_sales
                INNER JOIN reservations ON reservations.id = ticket_sales.reservation_id
                WHERE reservations.source_identity != ''
                    AND NOT EXISTS (
                        SELECT 1 FROM payment_allocations
                        WHERE payment_allocations.ticket_sale_id = ticket_sales.id
                    )
                GROUP BY ticket_sales.reservation_id, ticket_sales.ticket_type
                """.trimIndent()
            )
            db.execSQL(
                """
                DELETE FROM ticket_sales
                WHERE id IN (
                    SELECT ticket_sales.id
                    FROM ticket_sales
                    INNER JOIN reservations ON reservations.id = ticket_sales.reservation_id
                    WHERE reservations.source_identity != ''
                        AND NOT EXISTS (
                            SELECT 1 FROM payment_allocations
                            WHERE payment_allocations.ticket_sale_id = ticket_sales.id
                        )
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE ticket_sales
                SET origin = 'IMPORTED', counts_as_arrival = 0
                WHERE NOT EXISTS (
                    SELECT 1 FROM payment_allocations
                    WHERE payment_allocations.ticket_sale_id = ticket_sales.id
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE reservations
                SET arrival_count = MIN(
                    seat_count,
                    COALESCE(
                        (
                            SELECT SUM(quantity)
                            FROM ticket_sales
                            WHERE ticket_sales.reservation_id = reservations.id
                                AND ticket_sales.counts_as_arrival = 1
                        ),
                        0
                    )
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE reservations SET arrival_count = seat_count, is_present = 1 WHERE admission_type = 'DOOR_SALE'")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `performances` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`act_name` TEXT NOT NULL, " +
                    "`performance_date` TEXT NOT NULL, " +
                    "`is_active` INTEGER NOT NULL" +
                    ")"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_performances_act_name_performance_date` " +
                    "ON `performances` (`act_name`, `performance_date`)"
            )
            db.execSQL(
                "INSERT INTO performances (`id`, `act_name`, `performance_date`, `is_active`) " +
                    "VALUES (1, 'Aiemmat varaukset', '', 1)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reservations_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`performance_id` INTEGER NOT NULL, " +
                    "`last_name` TEXT NOT NULL, " +
                    "`first_name` TEXT NOT NULL, " +
                    "`contact` TEXT NOT NULL, " +
                    "`seat_count` INTEGER NOT NULL, " +
                    "`notes` TEXT NOT NULL, " +
                    "`source_identity` TEXT NOT NULL, " +
                    "`admission_type` TEXT NOT NULL, " +
                    "`arrival_count` INTEGER NOT NULL, " +
                    "`is_present` INTEGER NOT NULL, " +
                    "`payment_method` TEXT, " +
                    "FOREIGN KEY(`performance_id`) REFERENCES `performances`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT" +
                    ")"
            )
            db.execSQL(
                "INSERT INTO reservations_new (" +
                    "id, performance_id, last_name, first_name, contact, seat_count, notes, " +
                    "source_identity, admission_type, arrival_count, is_present, payment_method" +
                    ") SELECT id, 1, last_name, first_name, contact, seat_count, notes, " +
                    "source_identity, admission_type, arrival_count, is_present, payment_method " +
                    "FROM reservations"
            )
            db.execSQL("DROP TABLE reservations")
            db.execSQL("ALTER TABLE reservations_new RENAME TO reservations")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reservations_source_identity` " +
                    "ON `reservations` (`source_identity`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reservations_performance_id` " +
                    "ON `reservations` (`performance_id`)"
            )
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE performances ADD COLUMN source_sheet_title TEXT")
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reservations ADD COLUMN sheet_row_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reservations_sheet_row_id " +
                    "ON reservations (sheet_row_id)"
            )
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reservations ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'SYNCED'"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_sheet_changes` (
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
                    FOREIGN KEY(`reservation_id`) REFERENCES `reservations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_sheet_changes_performance_id` " +
                    "ON `pending_sheet_changes` (`performance_id`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_sheet_changes_reservation_id` " +
                    "ON `pending_sheet_changes` (`reservation_id`)"
            )
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sheet_field_definitions` (
                    `spreadsheet_url` TEXT NOT NULL,
                    `normalized_header` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `identifier` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `price_cents` INTEGER,
                    `allows_split_payment` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `active` INTEGER NOT NULL,
                    PRIMARY KEY(`spreadsheet_url`, `normalized_header`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sheet_field_aliases` (
                    `normalizedAlias` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `allowsSplitPayment` INTEGER NOT NULL,
                    PRIMARY KEY(`normalizedAlias`)
                )
                """.trimIndent()
            )
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13
    )
}
