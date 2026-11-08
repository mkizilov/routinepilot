package com.routinepilot.infrastructure.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `description` TEXT")
        db.execSQL("ALTER TABLE `routine_steps` ADD COLUMN `description` TEXT")
        db.execSQL("ALTER TABLE `routine_steps` ADD COLUMN `durationSeconds` INTEGER")
    }
}

private const val UUID_V4_SQL =
    "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || " +
        "substr('89ab', 1 + (random() & 3), 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))"

private const val NOW_MILLIS_SQL = "(CAST(strftime('%s', 'now') AS INTEGER) * 1000)"

/**
 * Version 1 kept routines with integer ids. Version 2 switches routines to UUID text ids,
 * keeps the old id order as `position`, and adds the rest of the local schema.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routines_new` (
                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `routines_new` (`id`, `title`, `position`, `createdAt`, `modifiedAt`, `deletedAt`)
            SELECT $UUID_V4_SQL, `title`, `id`, $NOW_MILLIS_SQL, $NOW_MILLIS_SQL, NULL
            FROM `routines` ORDER BY `id`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `routines`")
        db.execSQL("ALTER TABLE `routines_new` RENAME TO `routines`")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routine_steps` (
                `id` TEXT NOT NULL, `routineId` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_steps_routineId` ON `routine_steps` (`routineId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tasks` (
                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, `routineId` TEXT,
                `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_routineId` ON `tasks` (`routineId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scheduled_occurrences` (
                `id` TEXT NOT NULL, `routineId` TEXT NOT NULL, `scheduledAt` INTEGER NOT NULL, `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scheduled_occurrences_routineId_scheduledAt` " +
                "ON `scheduled_occurrences` (`routineId`, `scheduledAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `execution_sessions` (
                `id` TEXT NOT NULL, `routineId` TEXT NOT NULL, `occurrenceId` TEXT, `startedAt` INTEGER NOT NULL,
                `endedAt` INTEGER, `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`occurrenceId`) REFERENCES `scheduled_occurrences`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL)
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_execution_sessions_routineId` " +
                "ON `execution_sessions` (`routineId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_execution_sessions_occurrenceId` " +
                "ON `execution_sessions` (`occurrenceId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `step_completion_events` (
                `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `stepId` TEXT NOT NULL, `completedAt` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `execution_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`stepId`) REFERENCES `routine_steps`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_step_completion_events_sessionId` " +
                "ON `step_completion_events` (`sessionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_step_completion_events_stepId` " +
                "ON `step_completion_events` (`stepId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_preferences` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `value` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `deletedAt` INTEGER,
                PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_preferences_name` " +
                "ON `user_preferences` (`name`)",
        )
    }
}
