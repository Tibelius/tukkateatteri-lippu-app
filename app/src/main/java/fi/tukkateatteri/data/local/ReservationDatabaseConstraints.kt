package fi.tukkateatteri.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/** Database invariants which Room cannot express in entity annotations. */
internal object ReservationDatabaseConstraints {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE `performances`
            SET is_active = 0
            WHERE is_active = 1
                AND id <> (SELECT MIN(id) FROM `performances` WHERE is_active = 1)
            """.trimIndent()
        )
        createNonBlankUniquenessTriggers(db, "source_identity")
        createNonBlankUniquenessTriggers(db, "sheet_row_id")
        createRangeTriggers(
            db = db,
            table = "reservations",
            condition = "NEW.seat_count < 1 OR NEW.arrival_count < 0 OR NEW.arrival_count > NEW.seat_count",
            message = "invalid reservation counts"
        )
        createRangeTriggers(
            db = db,
            table = "ticket_sales",
            condition = "NEW.quantity < 1 OR NEW.unit_price_cents < 0",
            message = "invalid ticket sale values"
        )
        createRangeTriggers(
            db = db,
            table = "payment_allocations",
            condition = "NEW.amount_cents < 0",
            message = "invalid payment amount"
        )
        createRangeTriggers(
            db = db,
            table = "reserved_ticket_allocations",
            condition = "NEW.quantity < 1",
            message = "invalid reserved ticket quantity"
        )
        createRangeTriggers(
            db = db,
            table = "pending_sheet_changes",
            condition = "NEW.created_at < 0",
            message = "invalid pending change timestamp"
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `performances_single_active_insert`
            AFTER INSERT ON `performances`
            WHEN NEW.is_active = 1
            BEGIN
                UPDATE `performances` SET is_active = 0 WHERE id <> NEW.id;
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `performances_single_active_update`
            AFTER UPDATE OF is_active ON `performances`
            WHEN NEW.is_active = 1
            BEGIN
                UPDATE `performances` SET is_active = 0 WHERE id <> NEW.id;
            END
            """.trimIndent()
        )
    }

    private fun createNonBlankUniquenessTriggers(db: SupportSQLiteDatabase, column: String) {
        listOf("insert" to "", "update" to "AND id <> NEW.id").forEach { (operation, ownRowClause) ->
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `reservations_unique_${column}_$operation`
                BEFORE ${operation.uppercase(Locale.ROOT)} ON `reservations`
                WHEN NEW.$column <> '' AND EXISTS (
                    SELECT 1 FROM `reservations`
                    WHERE $column = NEW.$column $ownRowClause
                )
                BEGIN
                    SELECT RAISE(ABORT, 'duplicate $column');
                END
                """.trimIndent()
            )
        }
    }

    private fun createRangeTriggers(
        db: SupportSQLiteDatabase,
        table: String,
        condition: String,
        message: String
    ) {
        listOf("insert", "update").forEach { operation ->
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `${table}_valid_values_$operation`
                BEFORE ${operation.uppercase(Locale.ROOT)} ON `$table`
                WHEN $condition
                BEGIN
                    SELECT RAISE(ABORT, '$message');
                END
                """.trimIndent()
            )
        }
    }
}
