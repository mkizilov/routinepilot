package com.routinepilot.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.routinepilot.domain.sync.SyncCredentials
import com.routinepilot.domain.sync.SyncSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account state on this device. Tokens are kept encrypted in [TokenVault]. The owner (the account that this
 * device's data
 * belongs to), the email, and the cursor are kept in DataStore. The owner survives sign-out, so queued
 * changes stay with the
 * account that made them.
 */
@Singleton
class SyncAccountStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val vault: TokenVault,
    private val clock: Clock,
) : SyncCredentials, SyncSessionStore {
    override suspend fun accountSignedIn(): Boolean {
        val expiresAt = read(ACCOUNT_EXPIRES_AT) ?: return false
        return expiresAt > clock.millis() && vault.get(ACCOUNT_TOKEN) != null
    }

    override suspend fun cursor(): Long = read(CURSOR) ?: 0L

    override suspend fun saveCursor(seq: Long) {
        dataStore.edit { it[CURSOR] = seq }
    }

    override suspend fun markSynced(atMillis: Long) {
        dataStore.edit { it[LAST_SYNCED_AT] = atMillis }
    }

    override suspend fun lastSyncedAt(): Long? = read(LAST_SYNCED_AT)

    val email: Flow<String?> = dataStore.data.map { it[EMAIL] }

    suspend fun owner(): String? = read(OWNER)

    /** Sets the owning account. A different owner starts a new cursor, because each account has its own log. */
    suspend fun setOwner(accountId: String) {
        dataStore.edit {
            if (it[OWNER] != accountId) {
                it[OWNER] = accountId
                it[CURSOR] = 0L
            }
        }
    }

    suspend fun accountToken(): String? = vault.get(ACCOUNT_TOKEN)

    suspend fun accountTokenExpired(): Boolean = (read(ACCOUNT_EXPIRES_AT) ?: 0L) <= clock.millis()

    suspend fun saveAccount(email: String, token: String, expiresAtMillis: Long) {
        vault.put(ACCOUNT_TOKEN, token)
        dataStore.edit {
            it[EMAIL] = email
            it[ACCOUNT_EXPIRES_AT] = expiresAtMillis
        }
    }

    suspend fun deviceId(): String? = read(DEVICE_ID)

    suspend fun deviceToken(): String? = vault.get(DEVICE_TOKEN)

    suspend fun deviceTokenExpired(): Boolean = (read(DEVICE_EXPIRES_AT) ?: 0L) <= clock.millis()

    suspend fun saveDevice(deviceId: String, token: String, expiresAtMillis: Long) {
        vault.put(DEVICE_TOKEN, token)
        dataStore.edit {
            it[DEVICE_ID] = deviceId
            it[DEVICE_EXPIRES_AT] = expiresAtMillis
        }
    }

    /** Forgets the tokens and the device. The owner, cursor, and queued changes are kept. */
    suspend fun clearSignIn() {
        vault.remove(ACCOUNT_TOKEN)
        vault.remove(DEVICE_TOKEN)
        dataStore.edit {
            it.remove(EMAIL)
            it.remove(ACCOUNT_EXPIRES_AT)
            it.remove(DEVICE_ID)
            it.remove(DEVICE_EXPIRES_AT)
        }
    }

    /** Forgets only the device session. The next sync registers a new device with the account session. */
    suspend fun clearDevice() {
        vault.remove(DEVICE_TOKEN)
        dataStore.edit {
            it.remove(DEVICE_ID)
            it.remove(DEVICE_EXPIRES_AT)
        }
    }

    private suspend fun <T> read(key: Preferences.Key<T>): T? = dataStore.data.first()[key]

    private companion object {
        val OWNER = stringPreferencesKey("sync.owner_account_id")
        val EMAIL = stringPreferencesKey("sync.account_email")
        val ACCOUNT_EXPIRES_AT = longPreferencesKey("sync.account_expires_at")
        val DEVICE_ID = stringPreferencesKey("sync.device_id")
        val DEVICE_EXPIRES_AT = longPreferencesKey("sync.device_expires_at")
        val CURSOR = longPreferencesKey("sync.cursor")
        val LAST_SYNCED_AT = longPreferencesKey("sync.last_synced_at")
        const val ACCOUNT_TOKEN = "sync.account_token"
        const val DEVICE_TOKEN = "sync.device_token"
    }
}
