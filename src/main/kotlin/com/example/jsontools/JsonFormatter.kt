package com.cn.`else`.jsontools

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * JSON 格式化与解析工具。
 *
 * 严格模式（[Strictness.STRICT]）遵循 RFC 8259：
 * - 禁止数组 / 对象的尾逗号：`[1,2,]`、`{"a":1,}` 都报错（不再被补成 null）。
 * - 禁止空逗号位 `[1,,2]`。
 * - 禁止注释、单引号字符串、未加引号的 key。
 * - 禁止 NaN / Infinity。
 * - 只允许一个顶层值；解析完仍有多余内容报错。
 */
object JsonFormatter {

    private val PRETTY = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .setStrictness(Strictness.STRICT)
        .create()

    data class ParseResult(
        val element: JsonElement?,
        val error: String?
    ) {
        val ok: Boolean get() = element != null
    }

    /** 解析 JSON，失败时返回带详细错误信息的 [ParseResult]。 */
    fun parse(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ParseResult(null, JsonToolsBundle.message("parse.emptyContent"))
        }

        // 第一道防线：手写扫描器，拦截 Gson 历史上会被"宽容"接受的写法（尾逗号等）
        StrictJsonValidator.validate(trimmed)?.let { return ParseResult(null, it) }

        // 第二道防线：Gson STRICT 解析
        return try {
            val reader = JsonReader(StringReader(trimmed)).apply {
                strictness = Strictness.STRICT
            }
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return ParseResult(null, JsonToolsBundle.message("parse.extraContent"))
            }
            ParseResult(element, null)
        } catch (e: JsonSyntaxException) {
            ParseResult(null, extractMessage(e))
        } catch (e: Exception) {
            ParseResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 将任意文本格式化为漂亮的 JSON，失败时返回错误消息。 */
    fun format(text: String): ParseResult {
        val parsed = parse(text)
        if (!parsed.ok) return parsed
        return ParseResult(parsed.element, null)
    }

    /** 把已经解析好的元素按 2 空格缩进序列化。 */
    fun pretty(element: JsonElement): String = PRETTY.toJson(element)

    private fun extractMessage(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            val msg = cause.message
            if (!msg.isNullOrBlank()) return msg
            cause = cause.cause
        }
        return JsonToolsBundle.message("parse.invalidJsonGeneric")
    }
}

