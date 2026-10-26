package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class OpenFluxFreshCandidatePolicyReviewTest {
    private fun descriptor() = OpenFluxFreshCandidatePolicy.Descriptor(
        packageName = OpenFluxFreshCandidatePolicy.PACKAGE, versionCode = 13, versionName = OpenFluxFreshCandidatePolicy.VERSION_NAME,
        enabled = true, sameSigners = true, singleApk = true, kind = "openflux", repository = OpenFluxFreshCandidatePolicy.REPOSITORY,
        version = OpenFluxProfile.FRESH_VERSION, commit = OpenFluxProfile.FRESH_COMMIT, overlay = "", interfaceVersion = 1,
        carrier = 2, verification = 1, observation = 1, scriptUpdater = 1, diagnostic = 0)
    private fun expected() = OpenFluxFreshCandidatePolicy.Expected("a".repeat(64), "b".repeat(64), 12345, "arm64-v8a")

    @Test fun exactFreshDescriptorIsAdmittedOnlyAsCandidate() {
        assertTrue(OpenFluxFreshCandidatePolicy.admits(descriptor()))
        assertFalse(OpenFluxFreshCandidatePolicy.admits(descriptor().copy(packageName = "org.mcrf.engines.openflux")))
        assertFalse(OpenFluxFreshCandidatePolicy.admits(descriptor().copy(versionCode = 6, versionName = "0.7.3-mcrf.6")))
    }
    @Test fun productionCallersAndOldReviewApksAreNotEligible() {
        assertTrue(OpenFluxFreshCandidatePolicy.callerAllowed("org.mcrf.p3test", 10056))
        for (name in listOf("org.mcrf.client", "org.mcrf.client.test", "org.mcrf.engines.openflux.candidate", ""))
            assertFalse(OpenFluxFreshCandidatePolicy.callerAllowed(name, 10056))
        assertFalse(OpenFluxFreshCandidatePolicy.callerAllowed("org.mcrf.p3test", 10055))
    }
    @Test fun DisabledSplitWrongSignerAndWrongKindAreRejected() {
        for (value in listOf(descriptor().copy(enabled = false), descriptor().copy(singleApk = false),
            descriptor().copy(sameSigners = false), descriptor().copy(kind = "wdtt"),
            descriptor().copy(versionName = "0.13.0"), descriptor().copy(interfaceVersion = 2)))
            assertFalse(OpenFluxFreshCandidatePolicy.admits(value))
    }
    @Test fun sourceVersionCommitAndRepositoryCannotBeMixedOrGuessed() {
        for (value in listOf(descriptor().copy(version = "v0.7.3"), descriptor().copy(commit = "0".repeat(40)),
            descriptor().copy(repository = "unapproved/source"), descriptor().copy(commit = null), descriptor().copy(version = null)))
            assertFalse(OpenFluxFreshCandidatePolicy.admits(value))
    }
    @Test fun oldDiagnosticOverlayAndMissingCapabilitiesCannotBeAdmitted() {
        for (value in listOf(descriptor().copy(overlay = "mcrf-provider-stage.3"), descriptor().copy(diagnostic = 3),
            descriptor().copy(carrier = 1), descriptor().copy(verification = 0), descriptor().copy(observation = 0),
            descriptor().copy(scriptUpdater = 0)))
            assertFalse(OpenFluxFreshCandidatePolicy.admits(value))
    }
    @Test fun expectedReceiptMustHaveFiniteDigestsBytesAndMatchingProcessAbi() {
        val abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        assertTrue(OpenFluxFreshCandidatePolicy.validExpected(expected(), abis, true))
        for (value in listOf(expected().copy(apkSha256 = "A".repeat(64)), expected().copy(nativeSha256 = ""),
            expected().copy(nativeBytes = 0), expected().copy(nativeBytes = (128L shl 20) + 1),
            expected().copy(abi = "unsupported"), expected().copy(abi = "armeabi-v7a")))
            assertFalse(OpenFluxFreshCandidatePolicy.validExpected(value, abis, true))
        assertFalse(OpenFluxFreshCandidatePolicy.validExpected(expected(), setOf("x86_64"), true))
        assertFalse(OpenFluxFreshCandidatePolicy.validExpected(expected(), abis, false))
        assertTrue(OpenFluxFreshCandidatePolicy.validExpected(expected().copy(abi = "armeabi-v7a"), abis, false))
    }
    @Test fun actualApkNativeAndByteCountMustAllMatchExplicitReceipt() {
        val value = expected()
        assertTrue(OpenFluxFreshCandidatePolicy.matches(value, value.apkSha256, value.nativeSha256, value.nativeBytes))
        assertFalse(OpenFluxFreshCandidatePolicy.matches(value, "c".repeat(64), value.nativeSha256, value.nativeBytes))
        assertFalse(OpenFluxFreshCandidatePolicy.matches(value, value.apkSha256, "c".repeat(64), value.nativeBytes))
        assertFalse(OpenFluxFreshCandidatePolicy.matches(value, value.apkSha256, value.nativeSha256, value.nativeBytes + 1))
    }
    @Test fun elfClassMachineEndianAndPieTypeMatchTheRequestedAbi() {
        fun header(abi: String) = ByteArray(20).apply {
            this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = if (abi == "armeabi-v7a") 1 else 2; this[5] = 1; this[6] = 1; this[16] = 3
            this[18] = when (abi) { "arm64-v8a" -> 183; "armeabi-v7a" -> 40; else -> 62 }.toByte()
        }
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) assertTrue(OpenFluxFreshCandidatePolicy.elfMatches(header(abi), abi))
        for (invalid in listOf(header("x86_64"), header("arm64-v8a").apply { this[4] = 1 },
            header("arm64-v8a").apply { this[5] = 2 }, header("arm64-v8a").apply { this[6] = 0 },
            header("arm64-v8a").apply { this[16] = 2 }, ByteArray(19)))
            assertFalse(OpenFluxFreshCandidatePolicy.elfMatches(invalid, "arm64-v8a"))
    }
}
