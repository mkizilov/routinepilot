package com.routinepilot.infrastructure.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 4 to 5 records how each session ended, how much of it was focused time, and the device-zone date of each
 * session and step completion, so statistics can group by day in SQL.
 *
 * Sessions that already ended get the UNRECORDED outcome, because the old schema did not say whether they finished.
 * Dates for existing rows are computed in UTC, since the device zone at the time is unknown.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `execution_sessions` ADD COLUMN `outcome` TEXT")
        db.execSQL("ALTER TABLE `execution_sessions` ADD COLUMN `focusedMillis` INTEGER")
        db.execSQL("ALTER TABLE `execution_sessions` ADD COLUMN `localDate` TEXT")
        db.execSQL("ALTER TABLE `step_completion_events` ADD COLUMN `localDate` TEXT")

        db.execSQL("UPDATE `execution_sessions` SET `outcome` = 'UNRECORDED' WHERE `endedAt` IS NOT NULL")
        db.execSQL(
            "UPDATE `execution_sessions` SET `localDate` = strftime('%Y-%m-%d', `startedAt` / 1000, 'unixepoch') " +
                "WHERE `localDate` IS NULL",
        )
        db.execSQL(
            "UPDATE `step_completion_events` " +
                "SET `localDate` = strftime('%Y-%m-%d', `completedAt` / 1000, 'unixepoch') " +
                "WHERE `localDate` IS NULL",
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_execution_sessions_localDate` " +
                "ON `execution_sessions` (`localDate`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_step_completion_events_localDate` " +
                "ON `step_completion_events` (`localDate`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_localDate` " +
                "ON `scheduled_occurrences` (`localDate`)",
        )
    }
}
