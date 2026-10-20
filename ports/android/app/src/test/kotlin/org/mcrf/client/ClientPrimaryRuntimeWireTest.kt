package org.mcrf.client

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mcrf.engine.spi.v1.Platform

class ClientPrimaryRuntimeWireTest {
    private val routeNames = listOf(
        "inet4Address", "inet6Address", "inet4Routes", "inet6Routes",
        "inet4Exclude", "inet6Exclude", "inet4Range", "inet6Range"
    )
    private val arrayNames = routeNames.map { "routes.$it" } +
        listOf("dnsServers", "includePackage", "excludePackage", "httpProxy.bypass")
    private val codes = setOf(
        "SIZE", "ARRAY", "STRING", "JSON", "KEYS", "TYPE", "NUMBER", "VERSION", "POLICY"
    ).map { "TUN_WIRE_$it" }.toSet()

    private fun fixture(
        mtu: Int = 1500,
        autoRoute: Boolean = true,
        dnsEnabled: Boolean = true,
        metered: Boolean = false,
        routes: Platform.Routes = Platform.Routes(
            listOf("192.0.2.7/24", "192.0.2.7/24"), listOf("FD00:0::7/64"),
            listOf("198.51.100.7/24"), listOf("2001:DB8::/32"),
            listOf("203.0.113.0/24"), listOf("FD01::/48"),
            listOf("192.0.2.128/25"), listOf("2001:db8:1::/48")
        ),
        dns: List<String> = listOf("192.0.2.53", "2001:DB8::53"),
        include: List<String> = listOf("org.example.Z", "org.example.a"),
        exclude: List<String> = listOf("org.example.excluded"),
        proxy: Platform.HttpProxy = Platform.HttpProxy(
            true, "PrOxY\"\\Ж🚀.invalid", 8080,
            listOf("*.Example.invalid", "<local>", "", "*.Example.invalid")
        )
    ) = Platform.TunPlan(mtu, autoRoute, dnsEnabled, metered, routes, dns, include, exclude, proxy)

    private fun emptyRoutes() = Platform.Routes(
        emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList()
    )

    private fun allLists(plan: Platform.TunPlan): List<List<String>> = with(plan.routes) {
        listOf(inet4Address, inet6Address, inet4Routes, inet6Routes,
            inet4Exclude, inet6Exclude, inet4Range, inet6Range,
            plan.dnsServers, plan.includePackage, plan.excludePackage, plan.httpProxy.bypass)
    }

    private fun withLists(lists: List<List<String>>) = fixture(
        routes = Platform.Routes(lists[0], lists[1], lists[2], lists[3],
            lists[4], lists[5], lists[6], lists[7]),
        dns = lists[8], include = lists[9], exclude = lists[10],
        proxy = Platform.HttpProxy(false, "", 0, lists[11])
    )

    private fun encode(plan: Platform.TunPlan = fixture()) = ClientPrimaryRuntimeWire.encodeTunPlan(plan)
    private fun decode(json: String) = ClientPrimaryRuntimeWire.decodeTunPlan(json)
    private fun tree() = JSONObject(encode())

    private fun setArray(root: JSONObject, path: String, value: Any) {
        val parts = path.split('.')
        if (parts.size == 1) root.put(path, value)
        else root.getJSONObject(parts[0]).put(parts[1], value)
    }

    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()

    private fun assertPlan(expected: Platform.TunPlan, actual: Platform.TunPlan) {
        assertEquals(expected.schema, actual.schema)
        assertEquals(expected.mtu, actual.mtu)
        assertEquals(expected.autoRoute, actual.autoRoute)
        assertEquals(expected.dnsEnabled, actual.dnsEnabled)
        assertEquals(expected.metered, actual.metered)
        assertEquals(allLists(expected), allLists(actual))
        assertEquals(expected.httpProxy.enabled, actual.httpProxy.enabled)
        assertEquals(expected.httpProxy.server, actual.httpProxy.server)
        assertEquals(expected.httpProxy.port, actual.httpProxy.port)
    }

    private fun rejects(code: String? = null, action: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java, action)
        assertTrue("Only fixed wire codes may escape", error.message in codes)
        if (code != null) assertEquals("TUN_WIRE_$code", error.message)
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
    }

    @Test fun actualSpiRoundTripPreservesEveryFieldAndListOrder() {
        val original = fixture()
        val encoded = encode(original)
        val decoded = decode(encoded)
        assertPlan(original, decoded)
        assertNotSame(original, decoded)
        assertNotSame(original.routes, decoded.routes)
        assertNotSame(original.httpProxy, decoded.httpProxy)
        assertEquals(encoded, encode(decoded))
    }

    @Test fun schemaContainsExactlyAllActualDtoFields() {
        val root = tree()
        assertEquals(setOf("schema", "mtu", "autoRoute", "dnsEnabled", "metered", "routes",
            "dnsServers", "includePackage", "excludePackage", "httpProxy"), keys(root))
        assertEquals(routeNames.toSet(), keys(root.getJSONObject("routes")))
        assertEquals(setOf("enabled", "server", "port", "bypass"), keys(root.getJSONObject("httpProxy")))
    }

    @Test fun everyBooleanCombinationAndDisabledProxyFieldsSurvive() {
        for (autoRoute in listOf(false, true)) for (dnsEnabled in listOf(false, true)) {
            for (metered in listOf(false, true)) for (enabled in listOf(false, true)) {
                val original = fixture(autoRoute = autoRoute, dnsEnabled = dnsEnabled, metered = metered,
                    proxy = Platform.HttpProxy(enabled, "CaseSensitive.invalid", 1234, listOf("", "A", "A")))
                assertPlan(original, decode(encode(original)))
            }
        }
    }

    @Test fun emptyPlanDoesNotInventRoutesDnsPackagesOrProxyDefaults() {
        val original = fixture(576, false, false, true, emptyRoutes(), emptyList(), emptyList(), emptyList(),
            Platform.HttpProxy(false, "", Int.MIN_VALUE, emptyList()))
        assertPlan(original, decode(encode(original)))
        assertTrue(allLists(decode(encode(original))).all { it.isEmpty() })
    }

    @Test fun wirePreservesOpaquePolicyStringsWithoutResolutionOrNormalization() {
        val values = List(12) { listOf(" never-resolve.invalid/37 ", "CaseSensitive", "", "CaseSensitive") }
        val original = withLists(values)
        assertPlan(original, decode(encode(original)))
    }

    @Test fun objectFieldOrderAndJsonWhitespaceAreUnimportant() {
        val original = tree()
        for (container in listOf(original.getJSONObject("routes"), original.getJSONObject("httpProxy"), original)) {
            val entries = keys(container).map { it to container.get(it) }.reversed()
            entries.forEach { container.remove(it.first) }
            entries.forEach { container.put(it.first, it.second) }
        }
        assertPlan(fixture(), decode(" \n\t" + original.toString(2) + "\r "))
    }

    @Test fun unknownKeysAtEveryObjectLevelFailClosed() {
        for (level in listOf("root", "routes", "httpProxy")) {
            val root = tree()
            val target = if (level == "root") root else root.getJSONObject(level)
            target.put("futureSensitiveField", JSONObject().put("secret", "not echoed"))
            rejects("KEYS") { decode(root.toString()) }
        }
    }

    @Test fun everyMissingKeyAtEveryObjectLevelFailsClosed() {
        for (level in listOf("root", "routes", "httpProxy")) {
            val original = tree()
            val target = if (level == "root") original else original.getJSONObject(level)
            for (key in keys(target)) {
                val root = tree()
                (if (level == "root") root else root.getJSONObject(level)).remove(key)
                rejects("KEYS") { decode(root.toString()) }
            }
        }
    }

    @Test fun duplicateKeysIncludingEscapedAliasesAreRejectedAtEveryLevel() {
        val original = encode()
        for (json in listOf(
            original.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            original.replace("\"schema\":1", "\"schema\":1,\"\\u0073chema\":1"),
            original.replace("\"routes\":{", "\"routes\":{\"inet4Address\":[],"),
            original.replace("\"httpProxy\":{", "\"httpProxy\":{\"enabled\":true,")
        )) rejects("KEYS") { decode(json) }
    }

    @Test fun unsupportedSchemaVersionsDoNotGetDefaulted() {
        for (version in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            rejects("VERSION") { decode(tree().put("schema", version).toString()) }
        }
    }

    @Test fun nullIsRejectedForEveryFieldAtEveryLevel() {
        for (level in listOf("root", "routes", "httpProxy")) {
            val original = tree()
            for (key in keys(if (level == "root") original else original.getJSONObject(level))) {
                val root = tree()
                (if (level == "root") root else root.getJSONObject(level)).put(key, JSONObject.NULL)
                rejects("TYPE") { decode(root.toString()) }
            }
        }
    }

    @Test fun numbersAndBooleansNeverCoerceFromOtherTypes() {
        for (key in listOf("schema", "mtu", "autoRoute", "dnsEnabled", "metered")) {
            val replacements = if (key == "schema" || key == "mtu")
                listOf<Any>("1500", true, JSONArray(), JSONObject())
            else listOf<Any>("true", "false", "TRUE", 0, 1, JSONArray(), JSONObject())
            for (value in replacements) {
                rejects("TYPE") { decode(tree().put(key, value).toString()) }
            }
        }
        for ((key, values) in listOf(
            "enabled" to listOf<Any>("true", 1, "FALSE"),
            "port" to listOf<Any>("8080", false),
            "server" to listOf<Any>(123, false, JSONArray(), JSONObject())
        )) for (value in values) {
            val root = tree()
            root.getJSONObject("httpProxy").put(key, value)
            rejects("TYPE") { decode(root.toString()) }
        }
    }

    @Test fun objectsArraysAndEveryArrayElementHaveExactTypes() {
        for (key in listOf("routes", "httpProxy")) {
            for (value in listOf<Any>("{}", true, 1, JSONArray())) {
                rejects("TYPE") { decode(tree().put(key, value).toString()) }
            }
        }
        for (path in arrayNames) {
            for (value in listOf<Any>("[]", true, 1, JSONObject())) {
                val root = tree()
                setArray(root, path, value)
                rejects("TYPE") { decode(root.toString()) }
            }
            for (value in listOf(JSONObject.NULL, 1, false, JSONArray(), JSONObject())) {
                val root = tree()
                setArray(root, path, JSONArray().put(value))
                rejects("TYPE") { decode(root.toString()) }
            }
        }
    }

    @Test fun nonIntegralHugeAndNonFiniteNumbersAreRejectedWithoutRounding() {
        for (number in listOf("1500.5", "1500.0000000000000000000001", "1e-1000000000",
            "2147483648", "-2147483649", "1e1000000000", "1e9999999999999999999999", "9".repeat(65))) {
            rejects("NUMBER") { decode(encode().replace("\"mtu\":1500", "\"mtu\":$number")) }
        }
        for (number in listOf("NaN", "Infinity", "-Infinity", "\"NaN\"", "\"Infinity\"")) {
            rejects { decode(encode().replace("\"mtu\":1500", "\"mtu\":$number")) }
        }
        rejects("NUMBER") { decode(encode().replace("\"port\":8080", "\"port\":8080.1")) }
    }

    @Test fun exactIntegralDecimalAndExponentFormsRemainIntegers() {
        val json = encode().replace("\"schema\":1", "\"schema\":1e0")
            .replace("\"mtu\":1500", "\"mtu\":15.00e2")
            .replace("\"port\":8080", "\"port\":80800e-1")
        assertPlan(fixture(), decode(json))
        val original = fixture(proxy = Platform.HttpProxy(false, "", Int.MAX_VALUE, emptyList()))
        assertPlan(original, decode(encode(original).replace("2147483647", "2147483647.0")))
    }

    @Test fun permissiveJsonExtensionsTrailingContentAndMalformedNumbersFailClosed() {
        val original = encode()
        for (json in listOf("", "[]", "null", "{}", "'text'", original + "{}", original + " secret",
            "/*comment*/$original", original.replace("\"schema\"", "schema"),
            original.replace("\"schema\"", "'schema'"), original.replace("\"schema\":1", "\"schema\"=1"),
            original.replace("\"schema\":1,", "\"schema\":1;"), original.dropLast(1) + ",}",
            original.replace("\"inet4Address\":[", "\"inet4Address\":[,"),
            original.replace("\"autoRoute\":true", "\"autoRoute\":TRUE"),
            original.replace("\"autoRoute\":true", "\"autoRoute\":truefalse"))) {
            rejects { decode(json) }
        }
        for (number in listOf("01500", "+1500", ".1500", "1500.", "1500e", "0x5dc", "--1500")) {
            rejects { decode(original.replace("\"mtu\":1500", "\"mtu\":$number")) }
        }
    }

    @Test fun unexpectedDeepStructuresAreRejectedWithoutRecursiveTraversal() {
        val deep = "[".repeat(10000) + "0" + "]".repeat(10000)
        rejects("TYPE") { decode(encode().replace("\"mtu\":1500", "\"mtu\":$deep")) }
        rejects("KEYS") { decode("{\"unknown\":$deep}") }
    }

    @Test fun allTwelveArraysAllow4096EntriesWithoutDroppingValues() {
        val original = withLists(List(12) { List(4096) { "" } })
        assertPlan(original, decode(encode(original)))
    }

    @Test fun everyArrayRejects4097EntriesOnEncodeAndDecode() {
        for ((index, path) in arrayNames.withIndex()) {
            val tooMany = List(4097) { "" }
            val lists = allLists(fixture()).toMutableList()
            lists[index] = tooMany
            rejects("ARRAY") { encode(withLists(lists)) }
            val root = tree()
            setArray(root, path, JSONArray(tooMany))
            rejects("ARRAY") { decode(root.toString()) }
        }
    }

    @Test fun stringsAllow2048CodeUnitsAndReject2049Everywhere() {
        val limit = "Ж".repeat(2048)
        val original = withLists(List(12) { listOf(limit) })
        assertPlan(original, decode(encode(original)))
        assertPlan(fixture(proxy = Platform.HttpProxy(true, limit, 80, emptyList())),
            decode(encode(fixture(proxy = Platform.HttpProxy(true, limit, 80, emptyList())))))
        for ((index, path) in arrayNames.withIndex()) {
            val lists = allLists(fixture()).toMutableList()
            lists[index] = listOf(limit + "x")
            rejects("STRING") { encode(withLists(lists)) }
            val root = tree()
            setArray(root, path, JSONArray().put(limit + "x"))
            rejects("STRING") { decode(root.toString()) }
        }
        val root = tree()
        root.getJSONObject("httpProxy").put("server", limit + "x")
        rejects("STRING") { decode(root.toString()) }
        rejects("STRING") { encode(fixture(proxy = Platform.HttpProxy(true, limit + "x", 80, emptyList()))) }
        rejects("STRING") { decode("{\"" + "x".repeat(2049) + "\":1}") }
    }

    @Test fun utf8PayloadAllowsExactly256KiBAndRejectsOneExtraByte() {
        val base = encode(fixture(proxy = Platform.HttpProxy(true, "多".repeat(1024), 80, emptyList())))
        val size = base.toByteArray(StandardCharsets.UTF_8).size
        val exact = " ".repeat(256 * 1024 - size) + base
        assertEquals(256 * 1024, exact.toByteArray(StandardCharsets.UTF_8).size)
        assertPlan(decode(base), decode(exact))
        rejects("SIZE") { decode(" $exact") }
        rejects("SIZE") { decode(" ".repeat(256 * 1024 + 1)) }
    }

    @Test fun aggregateUtf8AndEscapingSizeAreBoundedDuringEncoding() {
        val multibyte = fixture(dns = List(128) { "多".repeat(700) })
        rejects("SIZE") { encode(multibyte) }
        val root = tree().put("dnsServers", JSONArray(multibyte.dnsServers))
        assertTrue(root.toString().length < 256 * 1024)
        rejects("SIZE") { decode(root.toString()) }
        rejects("SIZE") { encode(fixture(dns = List(70) { "\\\"".repeat(1024) })) }
    }

    @Test fun encoderAllowsExactly256KiBAndRejectsOneExtraByte() {
        val fullStrings = List(127) { "a".repeat(2048) }
        fun plan(tail: String) = fixture(routes = emptyRoutes(), dns = fullStrings + tail,
            proxy = Platform.HttpProxy(false, "", 0, emptyList()))
        val padding = 256 * 1024 - encode(plan("")).toByteArray(StandardCharsets.UTF_8).size
        assertTrue(padding in 0 until 2048)
        val original = plan("x".repeat(padding))
        val encoded = encode(original)
        assertEquals(256 * 1024, encoded.toByteArray(StandardCharsets.UTF_8).size)
        assertPlan(original, decode(encoded))
        rejects("SIZE") { encode(plan("x".repeat(padding + 1))) }
    }

    @Test fun allIsoControlsAreRejectedInEscapedAndLiteralStringsAndOnEncode() {
        for (code in (0..31) + (127..159)) {
            val value = "secret" + code.toChar()
            rejects("STRING") { encode(fixture(dns = listOf(value))) }
            val root = tree().put("dnsServers", JSONArray().put(value))
            rejects("STRING") { decode(root.toString()) }
            rejects("STRING") { decode(encode().replace("192.0.2.53", value)) }
            val escaped = "\\u" + code.toString(16).padStart(4, '0')
            rejects("STRING") { decode(encode().replace("192.0.2.53", "secret$escaped")) }
        }
        for (escape in listOf("\\n", "\\r", "\\t", "\\b", "\\f")) {
            rejects("STRING") { decode(encode().replace("192.0.2.53", "secret$escape")) }
        }
    }

    @Test fun unicodeAndJsonEscapesPreserveValuesButUnpairedSurrogatesFail() {
        val value = " /\\\"Ж🚀多 "
        val original = fixture(dns = listOf(value))
        assertPlan(original, decode(encode(original)))
        assertEquals(listOf("Ж🚀/"), decode(encode().replace("192.0.2.53", "\\u0416\\uD83D\\uDE80\\/")).dnsServers.take(1))
        for (valueWithSurrogate in listOf("\uD800", "\uDC00", "\uD800x", "\uDC00\uD800")) {
            rejects("STRING") { encode(fixture(dns = listOf(valueWithSurrogate))) }
            rejects("STRING") { decode(encode().replace("192.0.2.53", valueWithSurrogate)) }
        }
        for (escape in listOf("\\uD800", "\\uDC00", "\\uD800x")) {
            rejects("STRING") { decode(encode().replace("192.0.2.53", escape)) }
        }
        for (escape in listOf("\\x", "\\uGGGG", "\\u12")) {
            rejects("JSON") { decode(encode().replace("192.0.2.53", escape)) }
        }
    }

    @Test fun realDtoListsAndDecodedListsAreImmutableAndIndependent() {
        val inputs = List(12) { mutableListOf("value-$it") }
        val original = withLists(inputs)
        val encoded = encode(original)
        inputs.forEach { it.clear() }
        assertEquals(encoded, encode(original))
        val decoded = decode(encoded)
        assertPlan(original, decoded)
        for ((index, values) in allLists(decoded).withIndex()) {
            assertNotSame(allLists(original)[index], values)
            assertNotSame(allLists(decode(encoded))[index], values)
            assertThrows(UnsupportedOperationException::class.java) { (values as MutableList<String>).clear() }
        }
        assertEquals(encoded, encode(decoded))
    }

    @Test fun actualSpiMtuAndIpv6AddressConstraintsAreEnforcedWithSanitizedPolicyCode() {
        for (mtu in listOf(575, 65536)) {
            rejects("POLICY") { decode(tree().put("mtu", mtu).toString()) }
        }
        rejects("POLICY") { decode(tree().put("mtu", 1279).toString()) }
        assertEquals(1280, decode(tree().put("mtu", 1280).toString()).mtu)
        for (mtu in listOf(576, 1279, 65535)) {
            // Only IPv6 interface addresses trigger the SPI's IPv6 MTU rule, not route strings.
            val root = tree().put("mtu", mtu)
            root.getJSONObject("routes").put("inet6Address", JSONArray())
            assertEquals(mtu, decode(root.toString()).mtu)
        }
        assertThrows(IllegalArgumentException::class.java) { fixture(mtu = 1279) }
    }

    @Test fun actualSpiProxyPolicyIsPreservedIncludingDisabledPortValues() {
        for (port in listOf(-1, 0, 65536)) {
            val root = tree()
            root.getJSONObject("httpProxy").put("port", port)
            rejects("POLICY") { decode(root.toString()) }
        }
        val root = tree()
        root.getJSONObject("httpProxy").put("server", "")
        rejects("POLICY") { decode(root.toString()) }
        for (port in listOf(1, 65535)) {
            val original = fixture(proxy = Platform.HttpProxy(true, "proxy.invalid", port, emptyList()))
            assertPlan(original, decode(encode(original)))
        }
        for (port in listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)) {
            val original = fixture(proxy = Platform.HttpProxy(false, "", port, listOf("retained")))
            assertPlan(original, decode(encode(original)))
        }
    }
}
