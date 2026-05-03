package com.cn.`else`.jsontools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.IdentityHashMap

/**
 * 语义 JSON diff。
 *
 * 设计原则：
 * - **不重排任何内容**：左侧输出严格按左侧 JSON 的原 key/元素顺序，右侧同理。
 * - 对象：两侧共有 key 递归比较；只在一侧有的 key 就地标 REMOVED / ADDED。
 * - 数组：顺序无关。精确匹配（canonical 串相同）+ 相似度贪心匹配。大数组跳过相似度阶段。
 * - 基本类型值不同 → CHANGED；类型不同 → 整块 CHANGED。
 *
 * 性能：
 * - 用 [IdentityHashMap] 对每个 [JsonElement] 的 canonical 串做一次性缓存，
 *   同一 diff 内不会重复序列化同一子树。
 * - 数组元素数超过 [SIMILARITY_ARRAY_THRESHOLD] 时，跳过相似度配对阶段（只做精确匹配），
 *   避免 O(n*m) 的相似度计算拖慢大型数组对比。
 */
object JsonDiffer {

    enum class Tag { SAME, ADDED, REMOVED, CHANGED }

    data class DiffLine(val text: String, val tag: Tag)

    data class DiffResult(
        val leftLines: List<DiffLine>,
        val rightLines: List<DiffLine>,
        val addedCount: Int,
        val removedCount: Int,
        val changedCount: Int
    ) {
        val hasDiff: Boolean get() = addedCount + removedCount + changedCount > 0
    }

    private const val INDENT = "  "
    private const val SIMILARITY_THRESHOLD = 1_000_000
    /** 数组元素数超过此值时，跳过相似度匹配阶段（只做精确匹配）。 */
    private const val SIMILARITY_ARRAY_THRESHOLD = 200
    private val IDENTITY_KEYS = listOf("id", "_id", "uuid")

    private enum class Side { LEFT, RIGHT }
    private class Counter(var added: Int = 0, var removed: Int = 0, var changed: Int = 0)

    fun diff(left: JsonElement, right: JsonElement): DiffResult {
        val s = Session()
        s.renderSide(left, right, Side.LEFT, s.leftOut, 0, "", "")
        s.renderSide(right, left, Side.RIGHT, s.rightOut, 0, "", "")
        return DiffResult(
            leftLines = s.leftOut,
            rightLines = s.rightOut,
            addedCount = s.counter.added,
            removedCount = s.counter.removed,
            changedCount = s.counter.changed
        )
    }

    // ---------------- Session：持有 canonical 缓存 + 输出 / 计数 ----------------

    private class Session {
        val leftOut = mutableListOf<DiffLine>()
        val rightOut = mutableListOf<DiffLine>()
        val counter = Counter()
        private val canonCache = IdentityHashMap<JsonElement, String>()

        fun canonical(e: JsonElement): String {
            canonCache[e]?.let { return it }
            val s = buildString { writeCanonical(e, this) }
            canonCache[e] = s
            return s
        }

        private fun writeCanonical(e: JsonElement, out: StringBuilder) {
            when {
                e.isJsonNull -> out.append("null")
                e.isJsonPrimitive -> out.append(renderInline(e))
                e.isJsonObject -> {
                    val obj = e.asJsonObject
                    out.append('{')
                    val keys = obj.keySet().toSortedSet()
                    var first = true
                    for (k in keys) {
                        if (!first) out.append(',')
                        first = false
                        out.append('"').append(escape(k)).append('"').append(':')
                        out.append(canonical(obj[k])) // 递归复用缓存
                    }
                    out.append('}')
                }
                e.isJsonArray -> {
                    val arr = e.asJsonArray
                    // 排序是为了"顺序无关"等价：用子 canonical 串排序
                    val parts = arr.map { canonical(it) }.sorted()
                    out.append('[')
                    parts.joinTo(out, ",")
                    out.append(']')
                }
            }
        }

        fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = canonical(a) == canonical(b)

        fun similarity(a: JsonElement, b: JsonElement): Int {
            if (jsonEquals(a, b)) return 0
            if (a.isJsonPrimitive && b.isJsonPrimitive) return 10
            if (a.isJsonObject && b.isJsonObject) {
                val ao = a.asJsonObject
                val bo = b.asJsonObject
                val keys = ao.keySet() + bo.keySet()
                var diff = 0
                for (k in keys) {
                    val av = if (ao.has(k)) ao[k] else null
                    val bv = if (bo.has(k)) bo[k] else null
                    if (av == null || bv == null) diff += 2
                    else if (!jsonEquals(av, bv)) diff += 1
                }
                return diff + 1
            }
            if (a.isJsonArray && b.isJsonArray) {
                return kotlin.math.abs(a.asJsonArray.size() - b.asJsonArray.size()) + 5
            }
            return SIMILARITY_THRESHOLD
        }

        // ---------------- 递归渲染 ----------------

        fun renderSide(
            self: JsonElement,
            other: JsonElement?,
            side: Side,
            out: MutableList<DiffLine>,
            indent: Int,
            keyPrefix: String,
            trailingSuffix: String
        ) {
            // 对方缺失 → self 整块独有
            if (other == null) {
                val tag = if (side == Side.LEFT) Tag.REMOVED else Tag.ADDED
                writeAllWithTag(self, out, indent, keyPrefix, trailingSuffix, tag)
                if (side == Side.LEFT) counter.removed++ else counter.added++
                return
            }
            if (!sameKind(self, other)) {
                writeAllWithTag(self, out, indent, keyPrefix, trailingSuffix, Tag.CHANGED)
                if (side == Side.LEFT) counter.changed++
                return
            }

            when {
                self.isJsonObject -> renderObject(
                    self.asJsonObject, other.asJsonObject, side, out, indent, keyPrefix, trailingSuffix
                )
                self.isJsonArray -> renderArray(
                    self.asJsonArray, other.asJsonArray, side, out, indent, keyPrefix, trailingSuffix
                )
                else -> {
                    val pad = INDENT.repeat(indent)
                    val tag = if (jsonEquals(self, other)) Tag.SAME else Tag.CHANGED
                    out += DiffLine("$pad$keyPrefix${renderInline(self)}$trailingSuffix", tag)
                    if (tag == Tag.CHANGED && side == Side.LEFT) counter.changed++
                }
            }
        }

        private fun renderObject(
            self: JsonObject, other: JsonObject, side: Side, out: MutableList<DiffLine>,
            indent: Int, keyPrefix: String, trailingSuffix: String
        ) {
            val pad = INDENT.repeat(indent)
            out += DiffLine("$pad${keyPrefix}{", Tag.SAME)
            val keys = self.keySet().toList()
            keys.forEachIndexed { idx, key ->
                val isLast = idx == keys.size - 1
                val childSuffix = if (isLast) "" else ","
                val keyLabel = "\"${escape(key)}\": "
                if (other.has(key)) {
                    renderSide(self[key], other[key], side, out, indent + 1, keyLabel, childSuffix)
                } else {
                    val tag = if (side == Side.LEFT) Tag.REMOVED else Tag.ADDED
                    writeAllWithTag(self[key], out, indent + 1, keyLabel, childSuffix, tag)
                    if (side == Side.LEFT) counter.removed++ else counter.added++
                }
            }
            out += DiffLine("$pad}$trailingSuffix", Tag.SAME)
        }

        private fun renderArray(
            self: JsonArray, other: JsonArray, side: Side, out: MutableList<DiffLine>,
            indent: Int, keyPrefix: String, trailingSuffix: String
        ) {
            val pad = INDENT.repeat(indent)
            out += DiffLine("$pad${keyPrefix}[", Tag.SAME)
            val selfList = self.toList()
            val otherList = other.toList()
            val mapping = matchArray(selfList, otherList)
            selfList.forEachIndexed { idx, item ->
                val isLast = idx == selfList.size - 1
                val childSuffix = if (isLast) "" else ","
                val match = mapping[idx]
                renderSide(item, match, side, out, indent + 1, "", childSuffix)
            }
            out += DiffLine("$pad]$trailingSuffix", Tag.SAME)
        }

        /**
         * 数组配对，顺序无关：
         * 1. 先用 canonical 串做 HashMap 精确匹配 —— O(n + m)。
         * 2. 按完整 id/_id/uuid（primitive）配对同一实体。
         * 3. 按「去掉 volatile id 后的对象 canonical」配对（解决仅 id 变更却被整块标差异）。
         * 4. 若两边规模都不太大，再跑相似度贪心。
         * 5. 剩下的元素返回 null（由上层标 REMOVED / ADDED）。
         */
        fun matchArray(selfList: List<JsonElement>, otherList: List<JsonElement>): List<JsonElement?> {
            val n = selfList.size
            val m = otherList.size
            val result = arrayOfNulls<JsonElement>(n)
            if (n == 0 || m == 0) return result.toList()

            // 1. 精确匹配（hash 表加速）
            val otherUsed = BooleanArray(m)
            val bucketByCanon = HashMap<String, ArrayDeque<Int>>(m)
            for (j in 0 until m) {
                bucketByCanon.getOrPut(canonical(otherList[j])) { ArrayDeque() }.addLast(j)
            }
            for (i in 0 until n) {
                val k = canonical(selfList[i])
                val bucket = bucketByCanon[k]
                if (bucket != null) {
                    while (bucket.isNotEmpty()) {
                        val j = bucket.removeFirst()
                        if (!otherUsed[j]) {
                            result[i] = otherList[j]
                            otherUsed[j] = true
                            break
                        }
                    }
                }
            }

            // 2. 标识字段匹配：按完整 id（id/_id/uuid）配对（revision 场景：同 id 不同内容）。
            val otherTokenBuckets = HashMap<String, ArrayDeque<Int>>()
            for (j in 0 until m) {
                if (otherUsed[j]) continue
                val token = identityToken(otherList[j]) ?: continue
                otherTokenBuckets.getOrPut(token) { ArrayDeque() }.addLast(j)
            }
            for (i in 0 until n) {
                if (result[i] != null) continue
                val token = identityToken(selfList[i]) ?: continue
                val bucket = otherTokenBuckets[token] ?: continue
                while (bucket.isNotEmpty()) {
                    val j = bucket.removeFirst()
                    if (!otherUsed[j]) {
                        result[i] = otherList[j]
                        otherUsed[j] = true
                        break
                    }
                }
            }

            // 3. 结构匹配：去掉 volatile id 后的 canonical 相同则配对（仅 id 变更等场景）。
            val otherStructBuckets = HashMap<String, ArrayDeque<Int>>()
            for (j in 0 until m) {
                if (otherUsed[j]) continue
                val tok = structuralPairToken(otherList[j]) ?: continue
                otherStructBuckets.getOrPut(tok) { ArrayDeque() }.addLast(j)
            }
            for (i in 0 until n) {
                if (result[i] != null) continue
                val tok = structuralPairToken(selfList[i]) ?: continue
                val bucket = otherStructBuckets[tok] ?: continue
                while (bucket.isNotEmpty()) {
                    val j = bucket.removeFirst()
                    if (!otherUsed[j]) {
                        result[i] = otherList[j]
                        otherUsed[j] = true
                        break
                    }
                }
            }

            // 4. 相似度匹配：只在两边规模都不太大时做
            if (n <= SIMILARITY_ARRAY_THRESHOLD && m <= SIMILARITY_ARRAY_THRESHOLD) {
                while (true) {
                    var bestI = -1
                    var bestJ = -1
                    var bestD = Int.MAX_VALUE
                    for (i in 0 until n) {
                        if (result[i] != null) continue
                        for (j in 0 until m) {
                            if (otherUsed[j]) continue
                            val d = similarity(selfList[i], otherList[j])
                            if (d < bestD) {
                                bestD = d
                                bestI = i
                                bestJ = j
                            }
                        }
                    }
                    if (bestI < 0 || bestD >= SIMILARITY_THRESHOLD) break
                    result[bestI] = otherList[bestJ]
                    otherUsed[bestJ] = true
                }
            }
            return result.toList()
        }

        /**
         * 对象数组元素的稳定身份标识。
         * 只接受 primitive 值，避免把对象/数组字段作为 id 造成误配。
         */
        private fun identityToken(e: JsonElement): String? {
            if (!e.isJsonObject) return null
            val obj = e.asJsonObject
            for (k in IDENTITY_KEYS) {
                if (!obj.has(k)) continue
                val v = obj[k]
                if (!v.isJsonPrimitive) continue
                return "$k:${renderInline(v)}"
            }
            return null
        }

        /**
         * 用于数组元素配对的「结构指纹」：canonical(对象去掉 id/_id/uuid)。
         * 若去掉后为空对象则不返回 token，避免仅剩 id 字段的对象互相错误合并。
         */
        private fun structuralPairToken(e: JsonElement): String? {
            if (!e.isJsonObject) return null
            val obj = e.asJsonObject
            val stripped = JsonObject()
            for ((k, v) in obj.entrySet()) {
                if (k in IDENTITY_KEYS) continue
                stripped.add(k, v)
            }
            if (stripped.size() == 0) return null
            return canonical(stripped)
        }

        // ---------------- 工具：整块渲染 ----------------

        private fun writeAllWithTag(
            e: JsonElement,
            out: MutableList<DiffLine>,
            indent: Int,
            keyPrefix: String,
            trailingSuffix: String,
            tag: Tag
        ) {
            val lines = mutableListOf<String>()
            serializeLinesInto(e, indent, keyPrefix, trailingSuffix, lines)
            for (l in lines) out += DiffLine(l, tag)
        }

        private fun serializeLinesInto(
            e: JsonElement,
            indent: Int,
            keyPrefix: String,
            trailingSuffix: String,
            out: MutableList<String>
        ) {
            val pad = INDENT.repeat(indent)
            when {
                e.isJsonObject -> {
                    val obj = e.asJsonObject
                    val keys = obj.keySet().toList()
                    if (keys.isEmpty()) {
                        out += "$pad$keyPrefix{}$trailingSuffix"
                        return
                    }
                    out += "$pad$keyPrefix{"
                    keys.forEachIndexed { idx, k ->
                        val comma = if (idx == keys.size - 1) "" else ","
                        serializeLinesInto(obj[k], indent + 1, "\"${escape(k)}\": ", comma, out)
                    }
                    out += "$pad}$trailingSuffix"
                }
                e.isJsonArray -> {
                    val arr = e.asJsonArray
                    if (arr.size() == 0) {
                        out += "$pad$keyPrefix[]$trailingSuffix"
                        return
                    }
                    out += "$pad$keyPrefix["
                    val items = arr.toList()
                    items.forEachIndexed { idx, child ->
                        val comma = if (idx == items.size - 1) "" else ","
                        serializeLinesInto(child, indent + 1, "", comma, out)
                    }
                    out += "$pad]$trailingSuffix"
                }
                else -> out += "$pad$keyPrefix${renderInline(e)}$trailingSuffix"
            }
        }
    }

    // ---------------- 无状态工具 ----------------

    private fun sameKind(a: JsonElement, b: JsonElement): Boolean {
        if (a.isJsonObject) return b.isJsonObject
        if (a.isJsonArray) return b.isJsonArray
        if (a.isJsonNull) return b.isJsonNull
        if (a.isJsonPrimitive) return b.isJsonPrimitive
        return false
    }

    private fun renderInline(e: JsonElement): String = when {
        e.isJsonNull -> "null"
        e.isJsonPrimitive -> {
            val p = e.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean.toString()
                p.isNumber -> p.asNumber.toString()
                p.isString -> "\"" + escape(p.asString) + "\""
                else -> p.toString()
            }
        }
        e.isJsonObject -> if (e.asJsonObject.size() == 0) "{}" else "{…}"
        e.isJsonArray -> if (e.asJsonArray.size() == 0) "[]" else "[…]"
        else -> e.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
