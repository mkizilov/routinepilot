package com.routinepilot.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Keeps session tokens encrypted at rest. The AES-GCM key lives in the Android Keystore, which never exports it.
 * DataStore
 * holds only the IV and ciphertext. If the key is lost (for example after a restore to another
 * device), the tokens cannot
 * be read, and the caller treats the user as signed out. Local data is not affected.
 */
class TokenVault
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        suspend fun put(
            name: String,
            value: String,
        ) {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val body = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            dataStore.edit { it[stringPreferencesKey(name)] = iv + SEPARATOR + body }
        }

        suspend fun get(name: String): String? {
            val blob = dataStore.data.first()[stringPreferencesKey(name)] ?: return null
            val parts = blob.split(SEPARATOR, limit = 2)
            if (parts.size != 2) return null
            return try {
                val cipher =
                    Cipher.getInstance(TRANSFORMATION).apply {
                        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                        init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                    }
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            } catch (failure: GeneralSecurityException) {
                Log.w(TAG, "A stored sync token cannot be decrypted. The user will need to sign in again.", failure)
                null
            }
        }

        suspend fun remove(name: String) {
            dataStore.edit { it.remove(stringPreferencesKey(name)) }
        }

        private fun secretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
            return generator.generateKey()
        }

        private companion object {
            const val TAG = "TokenVault"
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "routinepilot.sync.tokens"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_BITS = 256
            const val GCM_TAG_BITS = 128
            const val SEPARATOR = ":"
        }
    }
