package com.shieldchild.app.i18n

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.shieldchild.app.ShieldApplication
import java.util.Locale

enum class AppLanguage(val tag: String?) { SYSTEM(null), ENGLISH("en"), INDONESIAN("id") }

/**
 * The parent's language choice, independent of the phone's language (a parent may run the
 * phone in English but want ShieldChild in Indonesian). Texts live in res/values and
 * res/values-in; [str] returns them in the chosen language anywhere, including services.
 */
object Language {
    private var cached: Context? = null

    fun current(): AppLanguage =
        runCatching { AppLanguage.valueOf(ShieldApplication.instance.preferencesRepository.getLanguage()) }
            .getOrDefault(AppLanguage.SYSTEM)

    fun set(activity: Activity, language: AppLanguage) {
        ShieldApplication.instance.preferencesRepository.setLanguage(language.name)
        cached = null
        activity.recreate()
    }

    fun locale(): Locale = current().tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()

    fun wrap(base: Context): Context {
        val tag = current().tag ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    fun context(): Context = cached ?: wrap(ShieldApplication.instance).also { cached = it }
}

/** A text in the app's chosen language. Works in Compose, services and notifications alike. */
fun str(@StringRes id: Int, vararg args: Any): String = Language.context().getString(id, *args)
