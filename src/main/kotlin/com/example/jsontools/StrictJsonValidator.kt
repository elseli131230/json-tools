package com.cn.`else`.jsontools

/**
 * 手写 JSON 严格扫描器，专门拦截 Gson 历史上"宽容"的几种写法：
 * - 数组 / 对象尾逗号：`[1,2,]` / `{"a":1,}`
 * - 空逗号位：`[1,,2]`、`{"a":1,,"b":2}`
 * - 注释 `//` 或 `/* */`
 * - 单引号字符串
 * - 对象里缺少 key（`{,"a":1}`、`{:1}`）
 *
 * 只做"扫描级别"的校验，完整语法 / 值合法性交给 Gson 的 [com.google.gson.Strictness.STRICT]。
 * 返回 null 表示没发现问题；否则返回带行列号的中文错误信息。
 */
internal object StrictJsonValidator {

    fun validate(text: String): String? {
        var i = 0
        val n = text.length

        // 是否刚刚看到一个 ','，等待下一个真实值
        var pendingComma = false
        var pendingCommaPos = -1

        // 是否是容器刚开始，这时 ',' 就是非法的（例如 `[,1]`、`{,"a":1}`）
        var containerJustOpened = false
        var containerOpenPos = -1

        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    val closeAt = skipString(text, i)
                    if (closeAt < 0) return errorAt(text, i, JsonToolsBundle.message("validator.unclosedString"))
                    pendingComma = false
                    containerJustOpened = false
                    i = closeAt + 1
                }
                c == '\'' -> return errorAt(text, i, JsonToolsBundle.message("validator.mustUseDoubleQuotes"))
                c == '/' && i + 1 < n && (text[i + 1] == '/' || text[i + 1] == '*') -> {
                    return errorAt(text, i, JsonToolsBundle.message("validator.commentsNotAllowed"))
                }
                c.isWhitespace() -> i++
                c == '[' || c == '{' -> {
                    if (pendingComma) {
                        // 这没问题：逗号后可以是新的对象/数组 value
                        pendingComma = false
                    }
                    containerJustOpened = true
                    containerOpenPos = i
                    i++
                }
                c == ']' || c == '}' -> {
                    if (pendingComma) {
                        return errorAt(
                            text,
                            pendingCommaPos,
                            JsonToolsBundle.message("validator.trailingComma", c.toString())
                        )
                    }
                    pendingComma = false
                    containerJustOpened = false
                    i++
                }
                c == ',' -> {
                    if (containerJustOpened) {
                        return errorAt(text, i, JsonToolsBundle.message("validator.commaAtContainerStart"))
                    }
                    if (pendingComma) {
                        return errorAt(text, i, JsonToolsBundle.message("validator.emptyCommaSlot"))
                    }
                    pendingComma = true
                    pendingCommaPos = i
                    i++
                }
                else -> {
                    // 数字 / true / false / null / 冒号 等普通 token 起点
                    pendingComma = false
                    containerJustOpened = false
                    i++
                }
            }
        }

        if (pendingComma) {
            return errorAt(text, pendingCommaPos, JsonToolsBundle.message("validator.extraCommaAtEnd"))
        }
        return null
    }

    /** 从 [start] 位置的双引号开始，跳过完整字符串，返回闭合双引号的下标；失败返回 -1。 */
    private fun skipString(text: String, start: Int): Int {
        var i = start + 1
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '\\') {
                if (i + 1 >= n) return -1
                i += 2
                continue
            }
            if (ch == '"') return i
            i++
        }
        return -1
    }

    private fun errorAt(text: String, pos: Int, msg: String): String {
        var line = 1
        var col = 1
        val end = pos.coerceAtMost(text.length)
        for (k in 0 until end) {
            if (text[k] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return JsonToolsBundle.message("validator.errorAt", line, col, msg)
    }
}
