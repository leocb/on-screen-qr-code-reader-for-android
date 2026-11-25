package com.leocb.screenqr.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.leocb.screenqr.Prefs
import java.util.Locale

/**
 * Manual language override for the app.
 *
 * On Android 13+ this writes the system per-app locale (the same value shown in
 * Settings → Apps → Screen QR → Language). On older versions there is no such API, so the
 * choice is stored in [Prefs] and applied by re-wrapping each activity/service context in
 * [wrap].
 */
object AppLocales {

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val PORTUGUESE = "pt-BR"

    private val SUPPORTED = listOf(SYSTEM, ENGLISH, PORTUGUESE)

    /** The language in effect right now, as one of the tags above. */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales
            }.getOrNull()
            if (locales == null || locales.isEmpty) return SYSTEM
            return normalize(locales.toLanguageTags())
        }
        return normalize(Prefs.language)
    }

    fun set(activity: Activity, tag: String) {
        val value = normalize(tag)
        Prefs.setLanguage(value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (value == SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(value)
            }
            // The framework recreates the activity once the locale changes.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
        } else {
            applyDefaultLocale(value)
            activity.recreate()
        }
    }

    /** Applies the stored language to process-wide defaults (dates, number formats). */
    fun applyDefaultLocale(tag: String = Prefs.language) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        if (tag == SYSTEM) return
        runCatching { Locale.setDefault(Locale.forLanguageTag(tag)) }
    }

    /** Wraps an activity/service base context. No-op on Android 13+ (the system does it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = normalize(Prefs.language)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    private fun normalize(tag: String): String = when {
        tag.startsWith("pt", ignoreCase = true) -> PORTUGUESE
        tag.startsWith("en", ignoreCase = true) -> ENGLISH
        tag in SUPPORTED -> tag
        else -> SYSTEM
    }
}
