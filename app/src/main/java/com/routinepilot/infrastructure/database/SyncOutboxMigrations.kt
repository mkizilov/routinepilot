package com.routinepilot.infrastructure.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 6 to 7 adds the offline outbox and the server state it is merged against. The tables start empty. Existing
 * records are queued the first time they are edited after this version.
 */
val MIGRATION_6_7: Migration =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `mutation_outbox` (" +
                    "`localSequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mutationId` TEXT NOT NULL, " +
                    "`objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, `seenVersion` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `expectation` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_mutation_outbox_mutationId` " +
                    "ON `mutation_outbox` (`mutationId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_mutation_outbox_objectType_objectId` " +
                    "ON `mutation_outbox` (`objectType`, `objectId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `mutation_outbox_field` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `localSequence` INTEGER NOT NULL, " +
                    "`fieldName` TEXT NOT NULL, `beforeValue` TEXT, `afterValue` TEXT, `changed` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`localSequence`) REFERENCES `mutation_outbox`(`localSequence`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_mutation_outbox_field_localSequence` " +
                    "ON `mutation_outbox_field` (`localSequence`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_base` (" +
                    "`objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                    "`deleted` INTEGER NOT NULL, PRIMARY KEY(`objectType`, `objectId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_base_field` (" +
                    "`objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, `fieldName` TEXT NOT NULL, " +
                    "`fieldValue` TEXT, PRIMARY KEY(`objectType`, `objectId`, `fieldName`))",
            )
        }
    }
