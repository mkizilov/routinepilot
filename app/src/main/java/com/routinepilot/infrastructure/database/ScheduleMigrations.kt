package com.routinepilot.infrastructure.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 3 to 4 adds recurring schedules.
 *
 * The occurrence table has to change shape: routineId becomes nullable so tasks can own occurrences, and the table
 * gains schedule, original-time, and local-date columns. SQLite cannot make a column nullable in place, so the
 * table is rebuilt. Execution sessions point at occurrences, so their links are saved first and restored after the
 * rebuild. That keeps them correct whether or not foreign-key enforcement was on during the migration.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedules` (" +
                "`id` TEXT NOT NULL, `routineId` TEXT, `taskId` TEXT, `kind` TEXT NOT NULL, " +
                "`weekdaysMask` INTEGER, `intervalDays` INTEGER, `timeOfDayMinutes` INTEGER NOT NULL, " +
                "`startDate` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedules_routineId` ON `schedules` (`routineId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedules_taskId` ON `schedules` (`taskId`)")

        db.execSQL(
            "CREATE TEMP TABLE `migration_session_occurrence` AS " +
                "SELECT `id`, `occurrenceId` FROM `execution_sessions` WHERE `occurrenceId` IS NOT NULL",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scheduled_occurrences_v4` (" +
                "`id` TEXT NOT NULL, `scheduleId` TEXT, `routineId` TEXT, `taskId` TEXT, " +
                "`originalScheduledAt` INTEGER NOT NULL, `scheduledAt` INTEGER NOT NULL, " +
                "`localDate` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`scheduleId`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "INSERT INTO `scheduled_occurrences_v4` " +
                "(`id`, `scheduleId`, `routineId`, `taskId`, `originalScheduledAt`, `scheduledAt`, " +
                "`localDate`, `status`, `createdAt`, `modifiedAt`, `deletedAt`) " +
                "SELECT `id`, NULL, `routineId`, NULL, `scheduledAt`, `scheduledAt`, " +
                "strftime('%Y-%m-%d', `scheduledAt` / 1000, 'unixepoch'), `status`, `createdAt`, `modifiedAt`, " +
                "`deletedAt` FROM `scheduled_occurrences`",
        )
        db.execSQL("DROP TABLE `scheduled_occurrences`")
        db.execSQL("ALTER TABLE `scheduled_occurrences_v4` RENAME TO `scheduled_occurrences`")
        db.execSQL(
            "UPDATE `execution_sessions` SET `occurrenceId` = " +
                "(SELECT `m`.`occurrenceId` FROM `migration_session_occurrence` AS `m` " +
                "WHERE `m`.`id` = `execution_sessions`.`id`) " +
                "WHERE `id` IN (SELECT `id` FROM `migration_session_occurrence`)",
        )
        db.execSQL("DROP TABLE `migration_session_occurrence`")

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_occurrences_scheduleId_originalScheduledAt` " +
                "ON `scheduled_occurrences` (`scheduleId`, `originalScheduledAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_routineId` " +
                "ON `scheduled_occurrences` (`routineId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_taskId` " +
                "ON `scheduled_occurrences` (`taskId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_scheduledAt` " +
                "ON `scheduled_occurrences` (`scheduledAt`)",
        )
    }
}
