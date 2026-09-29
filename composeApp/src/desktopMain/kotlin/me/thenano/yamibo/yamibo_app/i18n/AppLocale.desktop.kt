package me.thenano.yamibo.yamibo_app.i18n

import java.util.Locale
import me.thenano.yamibo.yamibo_app.repository.settings.AppLanguage

actual fun applyAppLocale(language: AppLanguage) { Locale.setDefault(Locale.forLanguageTag(language.languageTag)) }
