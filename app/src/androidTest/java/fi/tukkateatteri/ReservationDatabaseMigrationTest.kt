package fi.tukkateatteri

import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationDatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReservationDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ReservationDatabase::class.java
    )

    @Test
    fun migrate1To15_preservesTheOldestSupportedReservation() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO reservations (
                    id, last_name, first_name, contact, seat_count, is_present, payment_method
                ) VALUES (10, 'Vanhin', 'Varaus', '', 1, 1, 'CARD')
                """.trimIndent()
            )
            close()
        }

        val db = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            15,
            true,
            *ReservationDatabaseMigrations.ALL
        )

        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM performances"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM reservations"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM ticket_sales"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM payment_allocations"))
        db.close()
    }

    @Test
    fun migrate8To15_preservesTheLegacyReservationGraph() {
        migrationHelper.createDatabase(DATABASE_NAME, 8).apply {
            execSQL(
                """
                INSERT INTO reservations (
                    id, last_name, first_name, contact, seat_count, notes, source_identity,
                    admission_type, arrival_count, is_present, payment_method
                ) VALUES (10, 'Vanha', 'Varaus', '', 1, '', 'legacy', 'RESERVATION', 1, 1, 'CARD')
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO reserved_ticket_allocations " +
                    "(reservation_id, ticket_type, quantity) VALUES (10, 'BASIC', 1)"
            )
            execSQL(
                "INSERT INTO ticket_sales " +
                    "(id, reservation_id, ticket_type, quantity, unit_price_cents, origin, counts_as_arrival) " +
                    "VALUES (20, 10, 'BASIC', 1, 2200, 'MANUAL', 1)"
            )
            execSQL(
                "INSERT INTO payment_allocations " +
                    "(id, ticket_sale_id, payment_method, amount_cents) VALUES (30, 20, 'CARD', 2200)"
            )
            close()
        }

        val db = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            15,
            true,
            *ReservationDatabaseMigrations.ALL
        )

        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM reservations"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM ticket_sales"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM payment_allocations"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM reserved_ticket_allocations"))
        db.close()
    }

    @Test
    fun migrate14To15_preservesDataAndAppliesNewSchema() {
        migrationHelper.createDatabase(DATABASE_NAME, 14).apply {
            execSQL(
                "INSERT INTO performances " +
                    "(id, act_name, performance_date, is_active, source_sheet_title) " +
                    "VALUES (1, 'Yön Vuodenaika', '24.10.2026', 1, '24.10')"
            )
            execSQL(
                """
                INSERT INTO reservations (
                    id, performance_id, last_name, first_name, contact, seat_count, notes,
                    source_identity, sheet_row_id, sync_state, admission_type, arrival_count,
                    is_present, payment_method
                ) VALUES (
                    10, 1, 'Kippari', 'Kalle', 'kippari@example.com', 2, '',
                    'source-10', 'row-10', 'PENDING', 'RESERVATION', 1, 1, 'CARD'
                )
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO reserved_ticket_allocations " +
                    "(reservation_id, ticket_type, quantity) VALUES (10, 'BASIC', 2)"
            )
            execSQL(
                "INSERT INTO ticket_sales " +
                    "(id, reservation_id, ticket_type, quantity, unit_price_cents, origin, counts_as_arrival) " +
                    "VALUES (20, 10, 'BASIC', 1, 2200, 'MANUAL', 1)"
            )
            execSQL(
                "INSERT INTO payment_allocations " +
                    "(id, ticket_sale_id, payment_method, amount_cents, zettle_successful) " +
                    "VALUES (30, 20, 'CARD', 2200, 1)"
            )
            execSQL(
                """
                INSERT INTO pending_sheet_changes (
                    id, reservation_id, performance_id, operation, base_row_json,
                    desired_row_json, status, last_error, created_at
                ) VALUES ('change-10', 10, 1, 'UPSERT', NULL, '{}', 'PENDING', '', 100)
                """.trimIndent()
            )
            close()
        }

        val db = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            15,
            true,
            ReservationDatabaseMigrations.MIGRATION_14_15
        )

        db.query("PRAGMA table_info(reservations)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
            assertFalse("is_present" in columns)
            assertFalse("payment_method" in columns)
        }
        db.query("SELECT performance_date_sort_key FROM performances WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(LocalDate.of(2026, 10, 24).toEpochDay(), cursor.getLong(0))
        }
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM reservations"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM ticket_sales"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM payment_allocations"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM reserved_ticket_allocations"))
        assertEquals(1, db.singleInt("SELECT COUNT(*) FROM pending_sheet_changes"))
        assertEquals(0, db.singleInt("PRAGMA foreign_key_check"))
        db.close()
    }

    @Test
    fun migrate14To15_enforcesIdentifiersCountsAndPendingPerformanceReference() {
        migrationHelper.createDatabase(DATABASE_NAME, 14).apply {
            execSQL(
                "INSERT INTO performances " +
                    "(id, act_name, performance_date, is_active, source_sheet_title) " +
                    "VALUES (1, 'Yön Vuodenaika', '24.10.2026', 1, NULL)"
            )
            close()
        }
        val db = migrationHelper.runMigrationsAndValidate(
            DATABASE_NAME,
            15,
            true,
            ReservationDatabaseMigrations.MIGRATION_14_15
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(validReservationInsert(id = 1, sourceIdentity = "source", sheetRowId = "row"))

        assertConstraintViolation {
            db.execSQL(validReservationInsert(id = 2, sourceIdentity = "source", sheetRowId = "other-row"))
        }
        assertConstraintViolation {
            db.execSQL(validReservationInsert(id = 3, sourceIdentity = "other", sheetRowId = "row"))
        }
        assertConstraintViolation {
            db.execSQL(validReservationInsert(id = 4, sourceIdentity = "", sheetRowId = "", seatCount = 0))
        }
        assertConstraintViolation {
            db.execSQL(
                """
                INSERT INTO pending_sheet_changes (
                    id, reservation_id, performance_id, operation, base_row_json,
                    desired_row_json, status, last_error, created_at
                ) VALUES ('orphan', 1, 999, 'UPSERT', NULL, NULL, 'PENDING', '', 1)
                """.trimIndent()
            )
        }
        db.close()
    }

    private fun validReservationInsert(
        id: Long,
        sourceIdentity: String,
        sheetRowId: String,
        seatCount: Int = 1
    ): String =
        """
        INSERT INTO reservations (
            id, performance_id, last_name, first_name, contact, seat_count, notes,
            source_identity, sheet_row_id, sync_state, admission_type, arrival_count
        ) VALUES (
            $id, 1, 'Testi', 'Henkilö', '', $seatCount, '',
            '$sourceIdentity', '$sheetRowId', 'SYNCED', 'RESERVATION', 0
        )
        """.trimIndent()

    private fun assertConstraintViolation(block: () -> Unit) {
        try {
            block()
            fail("Expected database constraint violation")
        } catch (_: SQLiteException) {
            // Expected.
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.singleInt(sql: String): Int =
        query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private companion object {
        const val DATABASE_NAME = "reservation-migration-test"
    }
}
