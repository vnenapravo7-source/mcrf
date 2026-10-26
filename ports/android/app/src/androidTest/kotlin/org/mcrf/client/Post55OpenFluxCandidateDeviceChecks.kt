package org.mcrf.client

import android.content.Context
import android.os.Bundle
import java.io.File
import java.util.function.Consumer

/** Real PackageManager/signature/file/ELF/hash admission, NO child execution.
 * Coordinator must supply exact final artifact receipt; never derive expected hashes
 * from the installed package under test or silently fall back to production/old core. */
object Post55OpenFluxCandidateDeviceChecks {
    @JvmStatic fun run(base: Context, temporary: File, arguments: Bundle, passed: Consumer<String>) {
        ClientOpenFluxFreshGate.requireCaller(base)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val byteCount = arguments.getString("candidate_native_bytes").orEmpty()
        require(byteCount.matches(Regex("[0-9]{1,9}"))) { "Candidate native byte count is missing or invalid." }
        val expected = OpenFluxFreshCandidatePolicy.Expected(
            requireNotNull(arguments.getString("candidate_apk_sha256")),
            requireNotNull(arguments.getString("candidate_native_sha256")),
            byteCount.toLong(),
            requireNotNull(arguments.getString("candidate_abi")))
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val admitted = ClientOpenFluxFreshGate.verify(base, expected)
        verify(admitted.application.packageName == OpenFluxFreshCandidatePolicy.PACKAGE && admitted.executable.canExecute(),
            "Actual signed fresh candidate/source/capabilities/ELF/hashes match the supplied common artifact receipt")
        fun changed(hash: String) = (if (hash.first() == 'a') "b" else "a") + hash.drop(1)
        fun refused(value: OpenFluxFreshCandidatePolicy.Expected) =
            try { ClientOpenFluxFreshGate.verify(base, value); false } catch (_: Exception) { true }
        verify(refused(expected.copy(apkSha256 = changed(expected.apkSha256))),
            "Actual installed candidate is refused for a different expected APK digest")
        verify(refused(expected.copy(nativeSha256 = changed(expected.nativeSha256))),
            "Actual installed candidate is refused for a different expected native digest")
        verify(refused(expected.copy(nativeBytes = expected.nativeBytes + 1)),
            "Actual installed candidate is refused for a different expected native byte count")
        verify(refused(expected.copy(abi = "unsupported")),
            "Unsupported receipt ABI cannot select a candidate or fall back to another engine")
    }
}
