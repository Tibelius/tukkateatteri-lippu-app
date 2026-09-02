package fi.tukkateatteri.data.local

import androidx.room.RoomDatabase

internal fun RoomDatabase.Builder<ReservationDatabase>.withBuildSpecificDatabaseConfiguration(
    @Suppress("UNUSED_PARAMETER") databaseProvider: () -> ReservationDatabase
): RoomDatabase.Builder<ReservationDatabase> = this
