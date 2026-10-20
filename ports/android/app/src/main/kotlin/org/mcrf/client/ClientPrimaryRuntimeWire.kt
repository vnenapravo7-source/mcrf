package org.mcrf.client

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import org.mcrf.engine.spi.v1.Platform

/**
 * Schema 1 transport for the actual parent-loaded SPI DTO. This does not validate Android
 * Builder applicability or resolve addresses. Every field is mandatory, even when disabled.
 *
 * Failures are IllegalArgumentException with a fixed TUN_WIRE_* message and no cause:
 * SIZE, ARRAY, STRING, JSON, KEYS, TYPE, NUMBER, VERSION or POLICY. No input is echoed.
 * Strings are limited to 2048 UTF-16 code units, arrays to 4096 entries and JSON to 256 KiB
 * in UTF-8. Strings must contain valid surrogate pairs and no ISO control characters.
 * Numbers must be exact signed 32-bit integers with at most 64 characters in their JSON token.
 */
object ClientPrimaryRuntimeWire {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_ARRAY = 4096
    private const val MAX_STRING = 2048
    private const val MAX_NUMBER = 64

    private val planKeys = setOf(
        "schema", "mtu", "autoRoute", "dnsEnabled", "metered", "routes", "dnsServers",
        "includePackage", "excludePackage", "httpProxy"
    )
    private val routeKeys = setOf(
        "inet4Address", "inet6Address", "inet4Routes", "inet6Routes",
        "inet4Exclude", "inet6Exclude", "inet4Range", "inet6Range"
    )
    private val proxyKeys = setOf("enabled", "server", "port", "bypass")

    fun encodeTunPlan(plan: Platform.TunPlan): String {
        if (plan.schema != 1) fail("VERSION")
        val out = Writer()
        out.append("{\"schema\":1,\"mtu\":${plan.mtu},\"autoRoute\":${plan.autoRoute},")
        out.append("\"dnsEnabled\":${plan.dnsEnabled},\"metered\":${plan.metered},\"routes\":{")
        with(plan.routes) {
            out.field("inet4Address", inet4Address)
            out.field("inet6Address", inet6Address)
            out.field("inet4Routes", inet4Routes)
            out.field("inet6Routes", inet6Routes)
            out.field("inet4Exclude", inet4Exclude)
            out.field("inet6Exclude", inet6Exclude)
            out.field("inet4Range", inet4Range)
            out.field("inet6Range", inet6Range, last = true)
        }
        out.append("},")
        out.field("dnsServers", plan.dnsServers)
        out.field("includePackage", plan.includePackage)
        out.field("excludePackage", plan.excludePackage)
        out.append("\"httpProxy\":{\"enabled\":${plan.httpProxy.enabled},\"server\":")
        out.string(plan.httpProxy.server)
        out.append(",\"port\":${plan.httpProxy.port},")
        out.field("bypass", plan.httpProxy.bypass, last = true)
        out.append("}}")
        return out.finish()
    }

    fun decodeTunPlan(json: String): Platform.TunPlan {
        checkPayload(json)
        return Reader(json).plan()
    }

    private fun fail(code: String): Nothing = throw IllegalArgumentException("TUN_WIRE_$code")

    private fun checkPayload(value: String) {
        // Avoid allocating an unbounded UTF-8 buffer for a hostile input String.
        if (value.length > MAX_BYTES || value.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            fail("SIZE")
        }
    }

    private fun checkString(value: String) {
        if (value.length > MAX_STRING) fail("STRING")
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isISOControl(char)) fail("STRING")
            if (Character.isHighSurrogate(char)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) fail("STRING")
            } else if (Character.isLowSurrogate(char)) {
                fail("STRING")
            }
        }
    }

    private class Writer {
        private val text = StringBuilder()

        fun append(value: String) {
            // Valid UTF-8 uses at least as many bytes as UTF-16 code units. Bound construction
            // before each append; finish() checks the exact byte count as well.
            if (value.length > MAX_BYTES - text.length) fail("SIZE")
            text.append(value)
        }

        fun string(value: String) {
            checkString(value)
            append("\"")
            var start = 0
            value.forEachIndexed { index, char ->
                if (char == '\\' || char == '"') {
                    append(value.substring(start, index))
                    append(if (char == '\\') "\\\\" else "\\\"")
                    start = index + 1
                }
            }
            append(value.substring(start))
            append("\"")
        }

        fun field(key: String, values: List<String>, last: Boolean = false) {
            if (values.size > MAX_ARRAY) fail("ARRAY")
            append("\"$key\":[")
            values.forEachIndexed { index, value ->
                if (index != 0) append(",")
                string(value)
            }
            append(if (last) "]" else "],")
        }

        fun finish(): String = text.toString().also(::checkPayload)
    }

    /** Schema-directed parsing also bounds nesting: arbitrary objects/arrays are never read. */
    private class Reader(private val text: String) {
        private var index = 0

        fun plan(): Platform.TunPlan {
            val fields = fields(planKeys) { key ->
                when (key) {
                    "schema", "mtu" -> integer()
                    "autoRoute", "dnsEnabled", "metered" -> boolean()
                    "routes" -> routes()
                    "httpProxy" -> proxy()
                    else -> strings()
                }
            }
            whitespace()
            if (index != text.length) fail("JSON")
            if (fields["schema"] != 1) fail("VERSION")
            return try {
                Platform.TunPlan(
                    fields["mtu"] as Int, fields["autoRoute"] as Boolean,
                    fields["dnsEnabled"] as Boolean, fields["metered"] as Boolean,
                    fields["routes"] as Platform.Routes, strings(fields, "dnsServers"),
                    strings(fields, "includePackage"), strings(fields, "excludePackage"),
                    fields["httpProxy"] as Platform.HttpProxy
                )
            } catch (_: IllegalArgumentException) {
                fail("POLICY")
            }
        }

        private fun routes(): Platform.Routes {
            val fields = fields(routeKeys) { strings() }
            return Platform.Routes(
                strings(fields, "inet4Address"), strings(fields, "inet6Address"),
                strings(fields, "inet4Routes"), strings(fields, "inet6Routes"),
                strings(fields, "inet4Exclude"), strings(fields, "inet6Exclude"),
                strings(fields, "inet4Range"), strings(fields, "inet6Range")
            )
        }

        private fun proxy(): Platform.HttpProxy {
            val fields = fields(proxyKeys) { key ->
                when (key) {
                    "enabled" -> boolean()
                    "server" -> string()
                    "port" -> integer()
                    else -> strings()
                }
            }
            return try {
                Platform.HttpProxy(
                    fields["enabled"] as Boolean, fields["server"] as String,
                    fields["port"] as Int, strings(fields, "bypass")
                )
            } catch (_: IllegalArgumentException) {
                fail("POLICY")
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun strings(fields: Map<String, Any>, key: String): List<String> =
            fields.getValue(key) as List<String>

        private fun fields(keys: Set<String>, readValue: (String) -> Any): Map<String, Any> {
            expect('{', "TYPE")
            val values = LinkedHashMap<String, Any>()
            if (!take('}')) {
                while (true) {
                    val key = string()
                    // Check before reading a value, including duplicate escaped spellings.
                    if (key !in keys || key in values) fail("KEYS")
                    expect(':')
                    values[key] = readValue(key)
                    if (take('}')) break
                    expect(',')
                }
            }
            if (values.keys != keys) fail("KEYS")
            return values
        }

        private fun strings(): List<String> {
            expect('[', "TYPE")
            val values = ArrayList<String>()
            if (!take(']')) {
                while (true) {
                    if (values.size == MAX_ARRAY) fail("ARRAY")
                    values.add(string())
                    if (take(']')) break
                    expect(',')
                }
            }
            return values
        }

        private fun string(): String {
            expect('"', "TYPE")
            val value = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return value.toString().also(::checkString)
                val decoded = if (char != '\\') char else {
                    if (index == text.length) fail("JSON")
                    when (val escape = text[index++]) {
                        '"', '\\', '/' -> escape
                        'u' -> unicodeEscape()
                        'b', 'f', 'n', 'r', 't' -> fail("STRING")
                        else -> fail("JSON")
                    }
                }
                if (Character.isISOControl(decoded)) fail("STRING")
                if (value.length == MAX_STRING) fail("STRING")
                value.append(decoded)
            }
            fail("JSON")
        }

        private fun unicodeEscape(): Char {
            if (text.length - index < 4) fail("JSON")
            var value = 0
            repeat(4) {
                val digit = when (val char = text[index++]) {
                    in '0'..'9' -> char - '0'
                    in 'a'..'f' -> char - 'a' + 10
                    in 'A'..'F' -> char - 'A' + 10
                    else -> fail("JSON")
                }
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun boolean(): Boolean {
            whitespace()
            return when {
                text.startsWith("true", index) -> { index += 4; true }
                text.startsWith("false", index) -> { index += 5; false }
                else -> fail("TYPE")
            }
        }

        private fun integer(): Int {
            whitespace()
            val start = index
            if (index < text.length && text[index] == '-') index++
            if (index == text.length) fail("TYPE")
            when (text[index]) {
                '0' -> index++
                in '1'..'9' -> digits()
                else -> fail("TYPE")
            }
            if (index < text.length && text[index] == '.') {
                index++
                digits()
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                digits()
            }
            if (index - start > MAX_NUMBER) fail("NUMBER")
            return try {
                // No floating-point rounding, coercion or Int truncation, including exponent forms.
                BigDecimal(text.substring(start, index)).intValueExact()
            } catch (_: NumberFormatException) {
                fail("NUMBER")
            } catch (_: ArithmeticException) {
                fail("NUMBER")
            }
        }

        private fun digits() {
            val start = index
            while (index < text.length && text[index] in '0'..'9') {
                index++
                if (index - start > MAX_NUMBER) fail("NUMBER")
            }
            if (index == start) fail("JSON")
        }

        private fun whitespace() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        private fun take(char: Char): Boolean {
            whitespace()
            if (index == text.length || text[index] != char) return false
            index++
            return true
        }

        private fun expect(char: Char, code: String = "JSON") {
            if (!take(char)) fail(code)
        }
    }
}
