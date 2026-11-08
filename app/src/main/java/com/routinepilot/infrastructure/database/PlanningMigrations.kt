package com.routinepilot.infrastructure.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 7 to 8 adds the smart planning tables. They start empty, so nothing existing changes. */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planning_task` (" +
                "`taskId` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, `priority` INTEGER NOT NULL, " +
                "`deadlineMinute` INTEGER, PRIMARY KEY(`taskId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planning_dependency` (" +
                "`taskId` TEXT NOT NULL, `dependsOnTaskId` TEXT NOT NULL, PRIMARY KEY(`taskId`, `dependsOnTaskId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planned_block` (" +
                "`planDate` TEXT NOT NULL, `taskId` TEXT NOT NULL, `startMinute` INTEGER NOT NULL, " +
                "`endMinute` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, PRIMARY KEY(`planDate`, `taskId`))",
        )
    }
}
