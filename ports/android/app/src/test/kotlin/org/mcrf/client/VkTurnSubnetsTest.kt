package org.mcrf.client

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM contracts; no Android, storage, sockets, provider calls or timing probes. */
class VkTurnSubnetsTest {
    private fun profile() = JSONObject()
        .put("type", "csqtt").put("csqtt_generation", "2.5")
        .put("server", "example.test").put("server_port", 443)
        .put("password", "fixture-password").put("vk_auth_mode", "vkcalls")
        .put("vk_hash_mode", "manual").put("hashes", JSONArray().put("fixture-hash"))
        .put("client_ids", JSONArray().put("fixture-client"))
        .put("unrelated", JSONObject().put("keep", true))

    private fun selection(limit: String = "ALL", index: Int = 0) =
        VkTurnSubnets.Selection(VkTurnSubnets.pools[index].id, limit)

    private fun settings(limit: String = "ALL", index: Int = 0) =
        VkTurnSubnets.write(JSONObject(), selection(limit, index))

    private fun strings(value: JSONArray) = (0 until value.length()).map(value::getString)

    private fun rejects(messagePart: String, action: () -> Unit) {
        try {
            action()
            fail("Expected explicit validation failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(messagePart))
        }
    }

    @Test fun exactPinnedRegistryLabelsHostCountsAndOrder() {
        assertEquals("0188606a243c92a54440be09dc59a93848c463aa", VkTurnSubnets.UPSTREAM_REVISION)
        assertEquals(listOf(
            "95_163_34_0_24", "91_231_135_0_24", "90_156_232_0_21",
            "193_203_43_0_24", "185_180_200_0_22",
        ), VkTurnSubnets.pools.map { it.id })
        assertEquals(listOf(
            "95.163.34.0/24", "91.231.135.0/24", "90.156.232.0/21",
            "193.203.43.0/24", "185.180.200.0/22",
        ), VkTurnSubnets.pools.map { it.cidr })
        // The fourth upstream count says 48, but its actual literal list has 47.
        assertEquals(listOf(64, 64, 51, 47, 2), VkTurnSubnets.pools.map { it.ips.size })
        // SHA-256 of comma-joined literal lists from the pinned git blob, not generated CIDRs.
        val expected = listOf(
            "3fe790013cd53e1ca24a316c18345c0bedd59723c42c48df2da3d8f6efc3b9cb",
            "105af8daed02017dfe76f26d7325c15ff4e9c20a16c121072906cfb6ffb2c953",
            "4ae43e01556706b926bd6ceb872384557fa68fe59ff83ca7b9e410e41bc136ea",
            "f6fd452c598d0ac8bc9adeee9f04b5670ac03b30f5fed47dc497d85a0bacd25d",
            "bdd1ae1d233bb4ce6146254d8e44860e0ba2c706fe16dee92bcaba2b17178c7f",
        )
        assertEquals(expected, VkTurnSubnets.pools.map { pool ->
            MessageDigest.getInstance("SHA-256").digest(pool.ips.joinToString(",").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        })
    }

    @Test fun upstreamGapsAndUnusualSuffixesAreRetained() {
        val pool = VkTurnSubnets.pools[3].ips
        assertEquals("193.203.43.0", pool.first())
        assertFalse("193.203.43.5" in pool)
        assertFalse("193.203.43.20" in pool)
        assertTrue("90.156.234.255" in VkTurnSubnets.pools[2].ips)
        assertFalse("90.156.236.116" in VkTurnSubnets.pools[2].ips)
        assertEquals(listOf("185.180.202.14", "185.180.202.15"), VkTurnSubnets.pools[4].ips)
    }

    @Test fun allFivePoolsAndIndependentCountChoicesUseExactPrefixes() {
        assertEquals(listOf("ALL", "1", "2", "4", "9"), VkTurnSubnets.ipLimits)
        VkTurnSubnets.pools.forEachIndexed { index, pool ->
            assertEquals(pool.ips, VkTurnSubnets.ips(selection(index = index)))
            assertEquals(pool.ips, strings(
                VkTurnSubnets.apply(profile(), settings(index = index)).getJSONArray(VkTurnSubnets.CUSTOM_IPS),
            ))
            VkTurnSubnets.ipLimits.drop(1).filter { it.toInt() <= pool.ips.size }.forEach { limit ->
                val chosen = selection(limit, index)
                assertEquals(pool.ips.take(limit.toInt()), VkTurnSubnets.ips(chosen))
                assertEquals(chosen, VkTurnSubnets.read(VkTurnSubnets.write(JSONObject(), chosen)))
                assertEquals(pool.ips.take(limit.toInt()), strings(
                    VkTurnSubnets.apply(profile(), settings(limit, index)).getJSONArray(VkTurnSubnets.CUSTOM_IPS),
                ))
            }
        }
    }

    @Test fun automaticIsDefaultAndGeneratesNoAddressesForAnyCount() {
        assertEquals(VkTurnSubnets.Selection(), VkTurnSubnets.read(JSONObject()))
        VkTurnSubnets.ipLimits.forEach { limit ->
            val automatic = VkTurnSubnets.Selection(ipLimit = limit)
            val metadata = VkTurnSubnets.write(JSONObject(), automatic)
            assertEquals(automatic, VkTurnSubnets.read(metadata))
            assertEquals(0, metadata.getJSONArray(VkTurnSubnets.CUSTOM_IPS).length())
            assertTrue(VkTurnSubnets.ips(automatic).isEmpty())
            val original = profile()
            val copy = VkTurnSubnets.apply(original, metadata)
            assertNotSame(original, copy)
            assertEquals(original.toString(), copy.toString())
            assertFalse(copy.has(VkTurnSubnets.CUSTOM_IPS))
        }
    }

    @Test fun metadataClonePreservesUnrelatedSettingsAndDoesNotTouchInputs() {
        val original = JSONObject().put("source", "MANUAL").put("callCount", 6)
            .put("manual", JSONArray().put("fixture-manual-call"))
            .put("manualSupplementAll", true).put("future", JSONObject().put("keep", true))
        val before = original.toString()
        val written = VkTurnSubnets.write(original, selection("2"))
        assertEquals(before, original.toString())
        assertEquals("MANUAL", written.getString("source"))
        assertEquals(6, written.getInt("callCount"))
        assertEquals(original.getJSONArray("manual").toString(), written.getJSONArray("manual").toString())
        assertTrue(written.getBoolean("manualSupplementAll"))
        written.getJSONObject("future").put("keep", false)
        assertTrue(original.getJSONObject("future").getBoolean("keep"))
        assertEquals(VkTurnSubnets.pools[0].ips.take(2), strings(written.getJSONArray(VkTurnSubnets.CUSTOM_IPS)))
    }

    @Test fun aliasesReadAndWriteCanonicalIdsWithoutChangingAddressLists() {
        VkTurnSubnets.pools.forEach { pool ->
            pool.aliases.forEach { alias ->
                val metadata = JSONObject().put(VkTurnSubnets.SELECTED, alias).put(VkTurnSubnets.IP_LIMIT, "2")
                val canonical = VkTurnSubnets.Selection(pool.id, "2")
                assertEquals(canonical, VkTurnSubnets.read(metadata))
                assertEquals(pool.ips.take(2), VkTurnSubnets.ips(VkTurnSubnets.Selection(alias, "2")))
                assertEquals(pool.id, VkTurnSubnets.write(metadata, canonical).getString(VkTurnSubnets.SELECTED))
                assertEquals(alias, metadata.getString(VkTurnSubnets.SELECTED))
            }
        }
    }

    @Test fun invalidIdsAndCountsFailExplicitlyWithoutMutation() {
        listOf("unknown", "95.163.34.0/24", " ").forEach { id ->
            val metadata = JSONObject().put(VkTurnSubnets.SELECTED, id)
            val original = profile()
            val before = original.toString()
            rejects("Неизвестная подсеть TURN") { VkTurnSubnets.read(metadata) }
            rejects("Неизвестная подсеть TURN") { VkTurnSubnets.apply(original, metadata) }
            assertEquals(before, original.toString())
        }
        listOf("", "all", "0", "3", "-1", "10", "01").forEach { limit ->
            val metadata = JSONObject().put(VkTurnSubnets.IP_LIMIT, limit)
            rejects("Неизвестное количество IP TURN") { VkTurnSubnets.read(metadata) }
            val before = metadata.toString()
            rejects("Неизвестное количество IP TURN") {
                VkTurnSubnets.write(metadata, VkTurnSubnets.Selection(ipLimit = limit))
            }
            assertEquals(before, metadata.toString())
        }
    }

    @Test fun malformedMetadataTypesAreNotCoercedToDefaults() {
        listOf(VkTurnSubnets.SELECTED, VkTurnSubnets.IP_LIMIT).forEach { key ->
            listOf(1, false, JSONObject.NULL, JSONArray(), JSONObject()).forEach { value ->
                rejects("Некорректная настройка TURN") { VkTurnSubnets.read(JSONObject().put(key, value)) }
            }
        }
    }

    @Test fun insufficientPoolDoesNotSwitchSubnetOrTruncateSilently() {
        listOf("4", "9").forEach { limit ->
            val chosen = selection(limit, 4)
            rejects("только 2 IP") { VkTurnSubnets.ips(chosen) }
            rejects("только 2 IP") { VkTurnSubnets.write(JSONObject(), chosen) }
            val metadata = JSONObject().put(VkTurnSubnets.SELECTED, chosen.subnetId).put(VkTurnSubnets.IP_LIMIT, limit)
            rejects("только 2 IP") { VkTurnSubnets.apply(profile(), metadata) }
        }
    }

    @Test fun profileApplyClonesAndPreservesAllOtherKeysAndNestedValues() {
        val original = profile()
        val before = original.toString()
        val applied = VkTurnSubnets.apply(original, settings("4"))
        assertNotSame(original, applied)
        assertEquals(before, original.toString())
        val withoutGenerated = JSONObject(applied.toString()).apply { remove(VkTurnSubnets.CUSTOM_IPS) }
        assertEquals(before, withoutGenerated.toString())
        applied.getJSONArray("hashes").put("copy-only")
        applied.getJSONObject("unrelated").put("keep", false)
        assertEquals(1, original.getJSONArray("hashes").length())
        assertTrue(original.getJSONObject("unrelated").getBoolean("keep"))
    }

    @Test fun explicitIpsWinEvenWhenGlobalSelectionIsInvalid() {
        val original = profile().put(VkTurnSubnets.CUSTOM_IPS, JSONArray().put("192.0.2.42"))
        val metadata = JSONObject().put(VkTurnSubnets.SELECTED, "unknown").put(VkTurnSubnets.IP_LIMIT, "3")
        val applied = VkTurnSubnets.apply(original, metadata)
        assertEquals(original.toString(), applied.toString())
        applied.getJSONArray(VkTurnSubnets.CUSTOM_IPS).put("192.0.2.43")
        assertEquals(1, original.getJSONArray(VkTurnSubnets.CUSTOM_IPS).length())
    }

    @Test fun absentNullEmptyArrayAndBlankIpsAllowGeneratedDefaults() {
        val variants = listOf(
            profile(), profile().put(VkTurnSubnets.CUSTOM_IPS, JSONObject.NULL),
            profile().put(VkTurnSubnets.CUSTOM_IPS, JSONArray()),
            profile().put(VkTurnSubnets.CUSTOM_IPS, ""), profile().put(VkTurnSubnets.CUSTOM_IPS, "  "),
        )
        variants.forEach { original ->
            assertEquals(VkTurnSubnets.pools[0].ips.take(1), strings(
                VkTurnSubnets.apply(original, settings("1")).getJSONArray(VkTurnSubnets.CUSTOM_IPS),
            ))
            assertEquals(original.toString(), VkTurnSubnets.apply(original, JSONObject()).toString())
        }
    }

    @Test fun malformedProfileIpFieldFailsClosedWithoutMutation() {
        listOf<Any>(false, 2, JSONObject(), "192.0.2.42").forEach { value ->
            val original = profile().put(VkTurnSubnets.CUSTOM_IPS, value)
            val before = original.toString()
            rejects("custom_turn_ips") { VkTurnSubnets.apply(original, settings("1")) }
            assertEquals(before, original.toString())
        }
    }

    @Test fun unconfirmedTransportFamiliesNeverConsumeGlobalMetadata() {
        val invalidMetadata = JSONObject().put(VkTurnSubnets.SELECTED, "unknown")
        val others = listOf(
            profile().put("csqtt_generation", "legacy"), profile().put("csqtt_generation", "2.1.9"),
            profile().put("csqtt_generation", "2.1.5"), profile().put("csqtt_generation", "2.0"),
            profile().put("type", "qwdtt"), profile().put("type", "wdtt"),
            profile().apply { remove("csqtt_generation") }, profile().put("csqtt_generation", 2.5),
        )
        others.forEach { original ->
            val applied = VkTurnSubnets.apply(original, invalidMetadata)
            assertNotSame(original, applied)
            assertEquals(original.toString(), applied.toString())
        }
    }

    @Test fun staleGeneratedCacheIsNeverUsedAsExplicitProfileAddresses() {
        val metadata = settings("2", 4).put(VkTurnSubnets.CUSTOM_IPS, JSONArray().put("192.0.2.99"))
        val applied = VkTurnSubnets.apply(profile(), metadata)
        assertEquals(VkTurnSubnets.pools[4].ips, strings(applied.getJSONArray(VkTurnSubnets.CUSTOM_IPS)))
        val automatic = JSONObject().put(VkTurnSubnets.CUSTOM_IPS, JSONArray().put("192.0.2.99"))
        assertEquals(profile().toString(), VkTurnSubnets.apply(profile(), automatic).toString())
    }

    @Test fun summariesExposeBothSubnetAndIndependentCount() {
        assertEquals("Автоматически · Все IP", VkTurnSubnets.summary(VkTurnSubnets.Selection()))
        VkTurnSubnets.pools.forEachIndexed { index, pool ->
            assertEquals("${pool.cidr} · Все IP", VkTurnSubnets.summary(selection(index = index)))
            assertEquals("${pool.cidr} · 2 IP", VkTurnSubnets.summary(selection("2", index)))
        }
    }
}
