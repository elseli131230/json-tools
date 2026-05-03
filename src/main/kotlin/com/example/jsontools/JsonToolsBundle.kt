package com.cn.`else`.jsontools

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import java.util.ResourceBundle.Control
import java.util.concurrent.atomic.AtomicReference

private const val BUNDLE = "messages.JsonToolsBundle"

object JsonToolsBundle : DynamicBundle(BUNDLE) {
    private val localeOverride = AtomicReference<Locale?>(null)

    @JvmStatic
    fun setLocale(locale: Locale?) {
        localeOverride.set(locale)
    }

    @JvmStatic
    fun hasLocaleOverride(): Boolean = localeOverride.get() != null

    @JvmStatic
    fun currentLocale(): Locale = localeOverride.get() ?: Locale.getDefault()

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        val rb = ResourceBundle.getBundle(
            BUNDLE,
            currentLocale(),
            javaClass.classLoader,
            Control.getNoFallbackControl(Control.FORMAT_DEFAULT)
        )
        val pattern = rb.getString(key)
        return if (params.isEmpty()) pattern else MessageFormat(pattern, currentLocale()).format(params)
    }
}
