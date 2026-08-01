package no.mwmai.mwmcloud.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The language the interface is drawn in.
 *
 * Android picks `values-nb` only when the whole phone is set to Norwegian, so a
 * Norwegian speaker with an English phone saw an English app and had no way to
 * change it. The strings were always there; nothing exposed them.
 */
enum class AppLanguage(val tag: String?) {
    /** Follow the phone. What everyone gets until they choose otherwise. */
    SYSTEM(null),
    NORSK("nb"),
    ENGLISH("en"),
    ;

    companion object {
        fun parse(raw: String?): AppLanguage = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * Draws [content] in [language], whatever the phone is set to.
 *
 * Done by overriding the composition's context rather than through
 * `AppCompatDelegate.setApplicationLocales`, which would mean adding AppCompat and
 * an activity recreate for a preference that only affects text. `stringResource`
 * reads `LocalContext.current.resources`, so replacing the context is enough and
 * it takes effect on the next frame, with no restart and no flicker.
 */
@Composable
fun WithAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current

    if (language.tag == null) {
        content()
        return
    }

    val localised = remember(language, configuration) {
        val locale = Locale.forLanguageTag(language.tag)
        // Deliberately not Locale.setDefault: that is process-wide state, and
        // reaching out of a composition to set it would change date and number
        // formatting for background work that never asked.
        val config = Configuration(configuration).apply { setLocale(locale) }
        base.createConfigurationContext(config) to config
    }

    CompositionLocalProvider(
        LocalContext provides localised.first,
        LocalConfiguration provides localised.second,
    ) {
        content()
    }
}

/** Reads a language back as a plain [Context], for code outside composition. */
fun Context.localisedFor(language: AppLanguage): Context {
    val tag = language.tag ?: return this
    val config = Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
    }
    return createConfigurationContext(config)
}
