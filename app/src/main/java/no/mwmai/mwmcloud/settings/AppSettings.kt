package no.mwmai.mwmcloud.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import no.mwmai.mwmcloud.data.media.MediaCategory

private val Context.settingsDataStore by preferencesDataStore(name = "mwmcloud_settings")

/** Non-secret preferences. Nothing here is sensitive; credentials live in [CredentialStore]. */
class AppSettings(private val context: Context) {

    val selectedCategories: Flow<Set<MediaCategory>> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CATEGORIES]
            ?.mapNotNull { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: DEFAULT_CATEGORIES
    }

    val setupComplete: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_SETUP] ?: false }

    suspend fun currentCategories(): Set<MediaCategory> = selectedCategories.first()

    suspend fun setCategories(categories: Set<MediaCategory>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CATEGORIES] = categories.map { it.name }.toSet()
        }
    }

    suspend fun markSetupComplete() {
        context.settingsDataStore.edit { it[KEY_SETUP] = true }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_CATEGORIES = stringSetPreferencesKey("categories")
        val KEY_SETUP = booleanPreferencesKey("setup_complete")

        /** Everything on by default. Anything left out is something not backed up. */
        val DEFAULT_CATEGORIES = MediaCategory.entries.toSet()
    }
}
