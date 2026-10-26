package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

class OpenFluxFreshProfileReviewTest {
    private fun profile(type: String = "yandex", url: String = "https://docs.example/one") =
        JSONObject().put("type", "openflux").put("transports", JSONArray().put(
            JSONObject().put("type", type).put("url", url)))

    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected invalid profile") } catch (_: IllegalArgumentException) { }
    }

    @Test fun newShareableCarriersRoundTripButRequireFreshCore() {
        for (type in listOf("avito", "telemost")) {
            val imported = OpenFluxProfile.link(OpenFluxProfile.share(profile(type))).carrier!!
            assertEquals(type, imported.getJSONArray("transports").getJSONObject(0).getString("type"))
            assertTrue(OpenFluxProfile.requiresFreshCore(imported))
        }
    }

    @Test fun groupedUrlsKeepWireValueAndRequireFreshCore() {
        val raw = "https://docs.example/one,https://docs.example/two|https://docs.example/three\nhttps://docs.example/four"
        val imported = OpenFluxProfile.link(OpenFluxProfile.share(profile(url = raw))).carrier!!
        assertEquals(raw, imported.getJSONArray("transports").getJSONObject(0).getString("url"))
        assertTrue(OpenFluxProfile.requiresFreshCore(imported))
    }

    @Test fun everyGroupedEndpointIsValidated() {
        for (bad in listOf("file:///secret", "https://user:secret@docs.example/two", "invalid"))
            reject { OpenFluxProfile.share(profile(url = "https://docs.example/one|$bad")) }
    }

    @Test fun existingSingleAndCupsProfilesDoNotRequireFreshCore() {
        assertFalse(OpenFluxProfile.requiresFreshCore(profile()))
        assertFalse(OpenFluxProfile.requiresFreshCore(profile("cupsonline", "room-one,room-two")))
    }

    @Test fun onlyExactSourceIdentityAdmitsFreshProfile() {
        assertTrue(OpenFluxProfile.supportsFreshProfile(OpenFluxProfile.FRESH_VERSION, OpenFluxProfile.FRESH_COMMIT))
        assertFalse(OpenFluxProfile.supportsFreshProfile("v0.7.3", OpenFluxProfile.FRESH_COMMIT))
        assertFalse(OpenFluxProfile.supportsFreshProfile(OpenFluxProfile.FRESH_VERSION, "0".repeat(40)))
        assertFalse(OpenFluxProfile.supportsFreshProfile(null, null))
    }

    @Test fun streamAndPersonalTokenRestrictionsRemain() {
        reject { OpenFluxProfile.share(profile("oneme")) }
        reject { OpenFluxProfile.share(profile("telemost").put("mode", "stream")) }
        reject { OpenFluxProfile.share(profile("avito").put("mode", "stream")) }
    }

    @Test fun malformedUtf8InsideValidCompressedJsonIsRejected() {
        val prefix = "{\"transports\":[{\"type\":\"yandex\",\"url\":\"https://docs.example/".toByteArray()
        val raw = prefix + byteArrayOf(0xc3.toByte(), 0x28) + "\"}]}".toByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val packed = ByteArray(4096)
        try {
            deflater.setInput(raw); deflater.finish()
            val size = deflater.deflate(packed); assertTrue(deflater.finished())
            val link = "openflux://v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.copyOf(size))
            reject { OpenFluxProfile.link(link) }
        } finally { deflater.end(); packed.fill(0); raw.fill(0) }
    }

    @Test fun scriptSourceKeepsOldReviewedPairAndAddsExactFreshPair() {
        assertTrue(OpenFluxProfile.supportsScriptSource("v0.7.3", "f12e981629a18c8c01007d59ac002254c02972bb"))
        assertTrue(OpenFluxProfile.supportsScriptSource(OpenFluxProfile.FRESH_VERSION, OpenFluxProfile.FRESH_COMMIT))
        assertFalse(OpenFluxProfile.supportsScriptSource("v0.4.2", "74cac6d47bf4c27947348ee957538a2c0728a485"))
    }

    @Test fun scriptSourceRejectsMixedVersionsAndUnpinnedTags() {
        assertFalse(OpenFluxProfile.supportsScriptSource("v0.7.3", OpenFluxProfile.FRESH_COMMIT))
        assertFalse(OpenFluxProfile.supportsScriptSource(OpenFluxProfile.FRESH_VERSION, "f12e981629a18c8c01007d59ac002254c02972bb"))
        assertFalse(OpenFluxProfile.supportsScriptSource("v0.14.0", OpenFluxProfile.FRESH_COMMIT))
    }

    @Test fun finiteCaptchaFailureExplainsUnsupportedUiWithoutTimeout() {
        val detail = CarrierFailureDetail("openflux", JSONObject())
        detail.offer("MCRF_OPENFLUX_FAILURE provider_verification_required")
        assertTrue(detail.describe("выход", 1).contains("проверку в браузере"))
        assertFalse(detail.describe("выход", 1).contains("не сообщил причину"))
    }

    @Test fun ownPageHasSeparateExplanation() {
        val description = OpenFluxFailure.describe("openflux", "MCRF_OPENFLUX_FAILURE setup_page_unsupported")!!
        assertTrue(description.contains("страницу настройки"))
        assertFalse(description.contains("https://"))
    }

    @Test fun failureChannelRejectsOtherEnginesAndInjectedPayloads() {
        assertNull(OpenFluxFailure.describe("wdtt", "MCRF_OPENFLUX_FAILURE provider_verification_required"))
        assertNull(OpenFluxFailure.describe("openflux", "MCRF_OPENFLUX_FAILURE provider_verification_required https://private.invalid/secret"))
        assertNull(OpenFluxFailure.describe("openflux", "MCRF_OPENFLUX_FAILURE unknown"))
    }

    @Test fun importedJsonCannotEnableHostVerificationUi() {
        val input=profile().put("mcrf_verification_ui",true)
        assertFalse(OpenFluxProfile.fromJson("synthetic",input).carrier!!.has("mcrf_verification_ui"))
        assertTrue(input.getBoolean("mcrf_verification_ui")) // Caller data not mutated.
    }

    @Test fun sharedProfileDoesNotExportHostVerificationCapability() {
        val imported=OpenFluxProfile.link(OpenFluxProfile.share(profile().put("mcrf_verification_ui",true)))
        assertFalse(imported.carrier!!.has("mcrf_verification_ui"))
    }

    @Test fun exitVerificationAndReplyFailuresAreExplicitAndFinite() {
        for(code in listOf("exit_verification_proxy_unsupported","verification_page_rejected","verification_reply_failed")) {
            val description=OpenFluxFailure.describe("openflux","MCRF_OPENFLUX_FAILURE $code")!!
            assertFalse(description.contains("https://"))
            assertFalse(description.contains("cookie="))
        }
    }
}
