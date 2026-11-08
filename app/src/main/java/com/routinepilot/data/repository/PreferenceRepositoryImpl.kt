package com.routinepilot.data.repository

import androidx.room.withTransaction
import com.routinepilot.domain.repository.PreferenceRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.UserPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class PreferenceRepositoryImpl
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
    ) : PreferenceRepository {
        private val preferenceDao = database.userPreferenceDao()

        override fun observePreference(name: String): Flow<String?> =
            preferenceDao.observeActiveByName(name).map { it?.value }

        override suspend fun setPreference(
            name: String,
            value: String,
        ) {
            val now = Instant.now()
            database.withTransaction {
                val existing = preferenceDao.findByName(name)
                preferenceDao.upsert(
                    UserPreferenceEntity(
                        id = existing?.id ?: UUID.randomUUID(),
                        name = name,
                        value = value,
                        createdAt = existing?.createdAt ?: now,
                        modifiedAt = now,
                    ),
                )
            }
        }

        override suspend fun removePreference(name: String) {
            preferenceDao.markDeletedByName(name, Instant.now())
        }
    }
