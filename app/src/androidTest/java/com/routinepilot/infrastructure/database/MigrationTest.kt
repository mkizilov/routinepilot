package com.routinepilot.infrastructure.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migration1To2_convertsRoutinesToUuidIdsInOriginalOrder() = runBlocking {
        createVersion1Database { legacy ->
            legacy.execSQL("INSERT INTO `routines` (`title`) VALUES ('Morning walk'), ('Evening review')")
        }

        val db = openMigratedDatabase()
        try {
            val routines = db.routineDao().observeActive().first()

            assertEquals(listOf("Morning walk", "Evening review"), routines.map { it.title })
            assertEquals(listOf(1, 2), routines.map { it.position })
            assertTrue(routines.all { it.id.version() == 4 })
            assertEquals(routines.size, routines.map { it.id }.toSet().size)
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM routine_steps"))
        } finally {
            db.close()
        }
    }

    @Test
    fun migration1To2_keepsForeignKeysEnforced() = runBlocking {
        createVersion1Database { }

        val db = openMigratedDatabase()
        try {
            val orphanStep = RoutineStepEntity(
                id = UUID.randomUUID(),
                routineId = UUID.randomUUID(),
                title = "Orphan",
                position = 0,
                createdAt = TEST_TIME,
                modifiedAt = TEST_TIME,
            )

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { db.routineStepDao().upsert(listOf(orphanStep)) }
            }
        } finally {
            db.close()
        }
    }

    private fun createVersion1Database(seed: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TEST_DB), null).use { legacy ->
            legacy.execSQL(
                "CREATE TABLE IF NOT EXISTS `routines` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL)",
            )
            seed(legacy)
            legacy.version = 1
        }
    }

    private fun openMigratedDatabase(): RoutinePilotDatabase =
        Room.databaseBuilder(context, RoutinePilotDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2)
            .addCallback(ForeignKeysCallback)
            .build()

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
