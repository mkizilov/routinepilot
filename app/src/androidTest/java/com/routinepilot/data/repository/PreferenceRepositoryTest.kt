package com.routinepilot.data.repository

import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.scalar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PreferenceRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var repository: PreferenceRepositoryImpl

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        repository = PreferenceRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun setPreference_isObservableAndOverwritesValue() = runBlocking {
        assertNull(repository.observePreference("theme").first())

        repository.setPreference("theme", "dark")
        repository.setPreference("theme", "light")

        assertEquals("light", repository.observePreference("theme").first())
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM user_preferences WHERE name = ?", "theme"))
    }

    @Test
    fun removePreference_hidesValueAndAllowsResetWithoutDuplicateName() = runBlocking {
        repository.setPreference("reminders", "on")

        repository.removePreference("reminders")
        assertNull(repository.observePreference("reminders").first())

        repository.setPreference("reminders", "off")
        assertEquals("off", repository.observePreference("reminders").first())
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM user_preferences WHERE name = ?", "reminders"))
    }
}
