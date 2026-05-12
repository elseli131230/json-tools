package com.cn.`else`.jsontools

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.ResourceBundle.Control

private const val BUNDLE = "messages.JsonToolsBundle"

object JsonToolsBundle : DynamicBundle(BUNDLE) {

    /** 与 IDE/操作系统一致：使用 JVM 默认区域设置，不再提供插件内语言切换。 */
    @JvmStatic
    fun currentLocale(): Locale = Locale.getDefault()

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        val rb = resourceBundleForDisplay()
        val pattern = rb.getString(key)
        val loc = currentLocale()
        return if (params.isEmpty()) pattern else MessageFormat(pattern, loc).format(params)
    }

    /** 优先加载与 [currentLocale] 匹配的 bundle；无对应文件时依次尝试仅语言码、英文、ROOT。 */
    private fun resourceBundleForDisplay(): ResourceBundle {
        val loader = JsonToolsBundle::class.java.classLoader
        val ctl = Control.getNoFallbackControl(Control.FORMAT_DEFAULT)
        val want = currentLocale()
        val candidates = buildList {
            add(want)
            if (want.language.isNotBlank()) {
                val langOnly = Locale.forLanguageTag(want.language)
                if (langOnly != want) add(langOnly)
            }
            add(Locale.ENGLISH)
            add(Locale.ROOT)
        }
        var last: MissingResourceException? = null
        for (loc in candidates) {
            try {
                return ResourceBundle.getBundle(BUNDLE, loc, loader, ctl)
            } catch (e: MissingResourceException) {
                last = e
            }
        }
        throw last ?: error("Missing resource bundle: $BUNDLE")
    }
}
