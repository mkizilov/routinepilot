package com.routinepilot.infrastructure.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 5 to 6 adds the calendar links, one row per exported schedule. The table starts empty, so existing data is
 * untouched and calendar export begins only once the user turns it on.
 */
val MIGRATION_5_6: Migration =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calendar_links` (" +
                    "`scheduleId` TEXT NOT NULL, `calendarId` INTEGER NOT NULL, `eventId` INTEGER, " +
                    "`uid` TEXT NOT NULL, " +
                    "`allDay` INTEGER NOT NULL, `rrule` TEXT NOT NULL, `startMillis` INTEGER NOT NULL, " +
                    "`ruleKey` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`scheduleId`))",
            )
        }
    }
