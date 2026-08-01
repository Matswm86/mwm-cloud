package no.mwmai.mwmcloud.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.credentialDataStore by preferencesDataStore(name = "mwmcloud_creds")

/** What the app needs to reach a storage box. */
data class BoxCredentials(
    val host: String,
    val username: String,
    val password: String,
) {
    /** Normalised to an https base URL, whatever the user typed. */
    val baseUrl: String
        get() = host.trim().removeSuffix("/").let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
}

/**
 * Stores the storage-box password.
 *
 * Values are sealed with Tink AES-GCM under a key held in the Android Keystore,
 * then written to DataStore as base64. Deliberately NOT
 * EncryptedSharedPreferences, which is deprecated as of security-crypto
 * 1.1.0-alpha07 for keyset corruption and main-thread I/O.
 *
 * The keyset never leaves the device and the file is excluded from cloud backup
 * and device transfer (see res/xml/backup_rules.xml), so a restore onto a new
 * phone asks for the password again rather than silently carrying it across.
 */
class CredentialStore(private val context: Context) {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            // KeyTemplates.get, not PredefinedAeadParameters: the builder takes a
            // KeyTemplate, and the Parameters type does not satisfy it.
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    val credentials: Flow<BoxCredentials?> = context.credentialDataStore.data.map { prefs ->
        val host = prefs[KEY_HOST] ?: return@map null
        val user = prefs[KEY_USER] ?: return@map null
        val sealed = prefs[KEY_PASS] ?: return@map null
        BoxCredentials(host, user, decrypt(sealed) ?: return@map null)
    }

    suspend fun current(): BoxCredentials? = credentials.first()

    suspend fun save(creds: BoxCredentials) {
        val sealed = encrypt(creds.password)
        context.credentialDataStore.edit { prefs ->
            prefs[KEY_HOST] = creds.host
            prefs[KEY_USER] = creds.username
            prefs[KEY_PASS] = sealed
        }
    }

    suspend fun clear() {
        context.credentialDataStore.edit { it.clear() }
    }

    private fun encrypt(plain: String): String =
        Base64.encodeToString(aead.encrypt(plain.toByteArray(), ASSOCIATED_DATA), Base64.NO_WRAP)

    /**
     * Returns null rather than throwing when the ciphertext will not open. That
     * happens for real: a Keystore key can be invalidated by a lock-screen change
     * or an OS upgrade. The right response is to ask for the password again, not
     * to crash on launch.
     */
    private fun decrypt(sealed: String): String? = try {
        String(aead.decrypt(Base64.decode(sealed, Base64.NO_WRAP), ASSOCIATED_DATA))
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEYSET_NAME = "mwmcloud_keyset"
        const val KEYSET_PREF_FILE = "mwmcloud_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://mwmcloud_master_key"

        /** Binds ciphertext to this app's purpose, so a blob cannot be replayed elsewhere. */
        val ASSOCIATED_DATA = "no.mwmai.mwmcloud/box-credentials".toByteArray()

        val KEY_HOST = stringPreferencesKey("host")
        val KEY_USER = stringPreferencesKey("username")
        val KEY_PASS = stringPreferencesKey("password_sealed")
    }
}
