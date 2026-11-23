package org.mcrf.client

import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Success proofs for the owner's ONE pure structural validator, not runtime admission.
 * Keys describe the complete detached input, including primitive number classes and floating-point bits.
 * No JSON or result object is retained. Capture/validation (including recursion) run outside the cache lock;
 * duplicate work is allowed. Unsupported types/oversize captures use the original validator without caching.
 */
internal class RoutingValidationMemo(
    private val validator: (JSONObject) -> Unit,
    private val budgetBytes: Int = MAX_BYTES,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private val lock = Any()
    private val successes = LinkedHashMap<String, Int>(16, .75f, true)
    private var retainedBytes = 0

    init { require(budgetBytes in 0..MAX_BYTES && maxEntries in 0..MAX_ENTRIES) }

    fun validate(source: JSONObject) {
        if (maxEntries == 0 || budgetBytes <= ENTRY_BYTES) { validator(source); return }
        val captured = try { Capture((budgetBytes - ENTRY_BYTES) / 2).capture(source) }
            catch (_: Uncacheable) { validator(source); return }
        if (hit(captured.first)) return
        validator(captured.second)
        publish(captured.first)
    }

    /** Exact serialized-input contract retained for callers that already own immutable JSON text. */
    fun validate(captured: String) {
        if (cost(captured) + 2L > budgetBytes || maxEntries == 0) { validator(JSONObject(captured)); return }
        val key = "j$captured" // Disjoint from the typed object's 'o' prefix.
        if (hit(key)) return
        validator(JSONObject(captured))
        publish(key)
    }

    private fun hit(key: String): Boolean = synchronized(lock) { successes[key] != null }
    private fun cost(key: String): Long = key.length.toLong() * 2L + ENTRY_BYTES
    private fun publish(key: String) {
        val cost = cost(key)
        if (cost > budgetBytes || maxEntries == 0 || Thread.currentThread().isInterrupted) return
        synchronized(lock) {
            if (successes[key] != null) return
            while (successes.isNotEmpty() && (retainedBytes.toLong() + cost > budgetBytes || successes.size >= maxEntries)) {
                val iterator = successes.entries.iterator()
                retainedBytes -= iterator.next().value
                iterator.remove()
            }
            successes[key] = cost.toInt()
            retainedBytes += cost.toInt()
        }
    }

    internal data class Stats(val entries: Int, val retainedBytes: Int)
    internal fun stats(): Stats = synchronized(lock) { Stats(successes.size, retainedBytes) }

    private class Uncacheable : RuntimeException()

    /** Capture key and private tree from the SAME reads, without a lossy toString/parse round trip.
     * Only exact known immutable primitive classes and plain JSON containers are supported.
     * Keys/string payloads are length-prefixed; container boundaries, order and primitive types are explicit.
     * Depth, nodes and key bytes are bounded even for input that will not be retained.
     */
    private class Capture(private val maxChars: Int) {
        private val key = StringBuilder()
        private val ancestors = IdentityHashMap<Any, Boolean>()
        private var nodes = 0
        fun capture(source: JSONObject): Pair<String, JSONObject> {
            val copy = copy(source, 0) as JSONObject
            return key.toString() to copy
        }
        private fun append(text: String) {
            if (text.length > maxChars - key.length) throw Uncacheable()
            key.append(text)
        }
        private fun text(tag: String, value: String) { append(tag); append(value.length.toString()); append(":"); append(value) }
        private fun copy(value: Any, depth: Int): Any {
            if (++nodes > 100_000 || depth > 64) throw Uncacheable()
            if (value === JSONObject.NULL) { append("n;"); return value }
            when (value.javaClass) {
                JSONObject::class.java -> {
                    if (ancestors.put(value, true) != null) throw Uncacheable()
                    try {
                        val source = value as JSONObject
                        val result = JSONObject()
                        append("o{")
                        val keys = source.keys()
                        while (keys.hasNext()) {
                            val name = keys.next()
                            text("k", name)
                            result.put(name, copy(source.get(name), depth + 1))
                        }
                        append("}")
                        return result
                    } finally { ancestors.remove(value) }
                }
                JSONArray::class.java -> {
                    if (ancestors.put(value, true) != null) throw Uncacheable()
                    try {
                        val source = value as JSONArray
                        val result = JSONArray()
                        append("a[")
                        repeat(source.length()) { result.put(copy(source.get(it), depth + 1)) }
                        append("]")
                        return result
                    } finally { ancestors.remove(value) }
                }
                String::class.java -> text("s", value as String)
                java.lang.Boolean::class.java -> append(if (value as Boolean) "T;" else "F;")
                java.lang.Byte::class.java -> text("b", value.toString())
                java.lang.Short::class.java -> text("h", value.toString())
                java.lang.Integer::class.java -> text("i", value.toString())
                java.lang.Long::class.java -> text("l", value.toString())
                java.lang.Float::class.java -> {
                    if (!(value as Float).isFinite()) throw Uncacheable()
                    text("f", java.lang.Float.floatToRawIntBits(value).toString(16))
                }
                java.lang.Double::class.java -> {
                    if (!(value as Double).isFinite()) throw Uncacheable()
                    text("d", java.lang.Double.doubleToRawLongBits(value).toString(16))
                }
                BigInteger::class.java -> text("g", value.toString())
                BigDecimal::class.java -> text("m", value.toString())
                else -> throw Uncacheable()
            }
            return value // Supported leaf types above are immutable; their exact runtime classes survive.
        }
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_ENTRIES = 16
        private const val ENTRY_BYTES = 512 // Conservative key/map entry bookkeeping, beyond UTF-16 payload.
    }
}
