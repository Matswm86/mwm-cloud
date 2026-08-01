package no.mwmai.mwmcloud.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import no.mwmai.mwmcloud.data.media.CategoryMode
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

    /** Folders the user picked by hand, as persisted tree URIs. */
    val pickedFolders: Flow<Set<String>> =
        context.settingsDataStore.data.map { it[KEY_FOLDERS] ?: emptySet() }

    /** Individual files the user picked by hand, as persisted document URIs. */
    val pickedFiles: Flow<Set<String>> =
        context.settingsDataStore.data.map { it[KEY_FILES] ?: emptySet() }

    suspend fun currentPickedFolders(): Set<String> = pickedFolders.first()

    suspend fun currentPickedFiles(): Set<String> = pickedFiles.first()

    suspend fun addPickedFolders(uris: Set<String>) {
        context.settingsDataStore.edit { it[KEY_FOLDERS] = (it[KEY_FOLDERS] ?: emptySet()) + uris }
    }

    suspend fun addPickedFiles(uris: Set<String>) {
        context.settingsDataStore.edit { it[KEY_FILES] = (it[KEY_FILES] ?: emptySet()) + uris }
    }

    /**
     * Individual files the user unticked.
     *
     * Exclusions are stored rather than inclusions, so a photo taken tomorrow is
     * backed up automatically instead of silently missing because it was not on
     * a list written today.
     */
    val excluded: Flow<Set<String>> =
        context.settingsDataStore.data.map { it[KEY_EXCLUDED] ?: emptySet() }

    suspend fun currentExcluded(): Set<String> = excluded.first()

    suspend fun setExcluded(uris: Set<String>) {
        context.settingsDataStore.edit { it[KEY_EXCLUDED] = uris }
    }

    /**
     * Whether a category means "everything" or "only what I picked".
     *
     * Defaults to [CategoryMode.ALL], which is what someone who turns a category
     * on without opening it meant.
     */
    fun categoryMode(category: MediaCategory): Flow<CategoryMode> =
        context.settingsDataStore.data.map { CategoryMode.parse(it[modeKey(category)]) }

    suspend fun currentCategoryMode(category: MediaCategory): CategoryMode =
        categoryMode(category).first()

    suspend fun currentCategoryModes(): Map<MediaCategory, CategoryMode> =
        context.settingsDataStore.data.first().let { prefs ->
            MediaCategory.entries.associateWith { CategoryMode.parse(prefs[modeKey(it)]) }
        }

    suspend fun setCategoryMode(category: MediaCategory, mode: CategoryMode) {
        context.settingsDataStore.edit { it[modeKey(category)] = mode.name }
    }

    /**
     * Files explicitly picked inside one category. Only consulted when that
     * category is in [CategoryMode.ONLY_PICKED].
     *
     * Kept per category rather than in one shared set, so a card can show
     * "12 chosen" without scanning the phone to work out which of the URIs in a
     * shared set happen to be photos.
     */
    fun included(category: MediaCategory): Flow<Set<String>> =
        context.settingsDataStore.data.map { it[includedKey(category)] ?: emptySet() }

    suspend fun currentIncluded(category: MediaCategory): Set<String> = included(category).first()

    suspend fun currentIncludedAll(): Map<MediaCategory, Set<String>> =
        context.settingsDataStore.data.first().let { prefs ->
            MediaCategory.entries.associateWith { prefs[includedKey(it)] ?: emptySet() }
        }

    suspend fun setIncluded(category: MediaCategory, uris: Set<String>) {
        context.settingsDataStore.edit { it[includedKey(category)] = uris }
    }

    // ---- automatic backup -------------------------------------------------

    /** How often the app backs up on its own. */
    val schedule: Flow<BackupSchedule> =
        context.settingsDataStore.data.map { BackupSchedule.parse(it[KEY_SCHEDULE]) }

    suspend fun currentSchedule(): BackupSchedule = schedule.first()

    suspend fun setSchedule(value: BackupSchedule) {
        context.settingsDataStore.edit { it[KEY_SCHEDULE] = value.name }
    }

    /**
     * What the automatic run covers.
     *
     * Deliberately separate from [selectedCategories]: "back these up when I press
     * the button" and "back these up every week without asking" are different
     * decisions, and a 40 GB music library is a reasonable answer to the first
     * and a poor one to the second. Defaults to the same set, so a user who never
     * opens this screen gets the obvious behaviour.
     */
    val autoCategories: Flow<Set<MediaCategory>> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUTO_CATEGORIES]
            ?.mapNotNull { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: prefs[KEY_CATEGORIES]
                ?.mapNotNull { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
                ?.toSet()
            ?: DEFAULT_CATEGORIES
    }

    suspend fun currentAutoCategories(): Set<MediaCategory> = autoCategories.first()

    suspend fun setAutoCategories(categories: Set<MediaCategory>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUTO_CATEGORIES] = categories.map { it.name }.toSet()
        }
    }

    /** Whether hand-picked files and folders go along on the automatic run. */
    val autoIncludePicked: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_PICKED] ?: true }

    suspend fun currentAutoIncludePicked(): Boolean = autoIncludePicked.first()

    suspend fun setAutoIncludePicked(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_PICKED] = value }
    }

    suspend fun clearPicked() {
        context.settingsDataStore.edit {
            it[KEY_FOLDERS] = emptySet()
            it[KEY_FILES] = emptySet()
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_CATEGORIES = stringSetPreferencesKey("categories")
        val KEY_SETUP = booleanPreferencesKey("setup_complete")
        val KEY_FOLDERS = stringSetPreferencesKey("picked_folders")
        val KEY_FILES = stringSetPreferencesKey("picked_files")
        val KEY_EXCLUDED = stringSetPreferencesKey("excluded_uris")
        val KEY_SCHEDULE = stringPreferencesKey("backup_schedule")
        val KEY_AUTO_CATEGORIES = stringSetPreferencesKey("auto_categories")
        val KEY_AUTO_PICKED = booleanPreferencesKey("auto_include_picked")

        fun modeKey(c: MediaCategory): Preferences.Key<String> =
            stringPreferencesKey("mode_${c.name}")

        fun includedKey(c: MediaCategory): Preferences.Key<Set<String>> =
            stringSetPreferencesKey("included_${c.name}")

        /** Everything on by default. Anything left out is something not backed up. */
        val DEFAULT_CATEGORIES = MediaCategory.entries.toSet()
    }
}

/**
 * How often the app backs up without being asked.
 *
 * WorkManager's floor for periodic work is 15 minutes, so every value here is
 * comfortably above it. There is no "continuous" option on purpose: this app
 * uploads whole files over a connection that cannot resume, and a tighter loop
 * would spend battery re-checking a library that has not changed.
 */
enum class BackupSchedule(val hours: Long) {
    /** Only when the user presses the button. */
    OFF(0),
    DAILY(24),
    WEEKLY(24 * 7),
    MONTHLY(24 * 30),
    ;

    companion object {
        fun parse(raw: String?): BackupSchedule =
            entries.firstOrNull { it.name == raw } ?: OFF
    }
}
