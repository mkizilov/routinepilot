package com.routinepilot.infrastructure.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** SQLite leaves foreign-key enforcement off unless each connection enables it. */
object ForeignKeysCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}
