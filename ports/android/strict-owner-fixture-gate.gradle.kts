import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

// Separate November acceptance authority. It does not certify the old P1 batch.
require(providers.gradleProperty("p1SourceReadyReceipt").orNull == null &&
    providers.gradleProperty("p1SourceReadySha256").orNull == null)
val receipt = file(requireNotNull(providers.gradleProperty("strictOwnerFixtureReceipt").orNull))
val expected = requireNotNull(providers.gradleProperty("strictOwnerFixtureSha256").orNull)
require(expected.matches(Regex("[0-9a-f]{64}")) && receipt.isFile && !Files.isSymbolicLink(receipt.toPath()) &&
    receipt.absoluteFile.normalize() == receipt.canonicalFile && receipt.length() in 1L..65536L &&
    receipt.canonicalFile.parentFile == File("D:/MCRF/Work/ui-batch-20261120").canonicalFile &&
    receipt.name == "strict-owner-fixture-build-inputs.json")
val tasks = gradle.startParameter.taskNames.toSet()
require(tasks.containsAll(setOf(":app:assembleDebug", ":app:assembleDebugAndroidTest",
    ":p1-owner-probe:assembleDebug", ":p1-owner-peer:assembleDebug"))) {
    "The strict-owner helpers belong to one debug Main + AndroidTest + helper-pair build."
}
fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 255) }
require(digest(receipt) == expected)
val gate = groovy.json.JsonSlurper().parse(receipt) as Map<*, *>
require(gate["schema"] == 1 && gate["scope"] == "nov20-strict-owner-debug-fixture" &&
    gate["physicalDevice"] == "R5CX73QYN3D" && gate["androidUser"] == 0 &&
    gate["providerTraffic"] == false && gate["productionActivated"] == false)

val paths = setOf(
    "strict-owner-fixture-gate.gradle.kts", "settings.gradle.kts",
    "p1-owner-probe/build.gradle.kts", "p1-owner-peer/build.gradle.kts",
    "p1-owner-probe/src/main/AndroidManifest.xml",
    "p1-owner-probe/src/main/java/org/mcrf/p1owner/OwnerProbeService.java",
    "p1-owner-probe/src/main/java/org/mcrf/p1owner/ProxyProbeService.java",
    "p1-owner-peer/src/main/AndroidManifest.xml",
    "app/build.gradle.kts", "app/src/debug/AndroidManifest.xml", "app/src/androidTest/AndroidManifest.xml",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/kotlin/org/mcrf/client/ClientP1OwnerFixtureScope.kt",
    "app/src/main/kotlin/org/mcrf/client/ClientP1StrictOwnerFixture.kt",
    "app/src/main/kotlin/org/mcrf/client/ClientSteerFixture.kt",
    "app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt",
    "app/src/main/kotlin/org/mcrf/client/ClientPacketFixture.kt",
    "app/src/main/kotlin/org/mcrf/client/ManagedAppFreezeAdb.kt",
    "app/src/main/kotlin/org/mcrf/client/ManagedAppFreezeAdbDaemon.kt",
    "app/src/main/kotlin/org/mcrf/client/VpnOwnerIsolation.kt",
    "app/src/main/kotlin/org/mcrf/client/BsNativeOwnedRuntimeFiles.kt",
    "app/src/main/kotlin/org/mcrf/client/BsNativePhysicalServiceFixture.kt",
    "app/src/main/kotlin/org/mcrf/client/BsNativeWorkerBackend.kt",
    "app/src/androidTest/java/org/mcrf/client/DeviceSmokeInstrumentation.java",
    "app/src/androidTest/java/org/mcrf/client/ManagedAppFreezeAdbPhysicalInstrumentation.java",
    "app/src/androidTest/kotlin/org/mcrf/client/ClientPrimaryVpnDeviceChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/PacketVpnDeviceChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/ClientP1StrictOwnerChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/ClientQuicOwnerChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/BsNativePhysicalDirectOwnerInstrumentation.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/BsNativeBootstrapInstrumentation.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/BsNativePhysicalServiceInstrumentation.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/StrictLanDeviceChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/ClientSteerVpnDeviceChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/ClientSteerP1ProxyChecks.kt",
    "app/src/androidTest/kotlin/org/mcrf/client/ClientSteerP1DnsChecks.kt",
    "app/src/test/StrictLanHostCheck.py",
    "app/src/androidTest/kotlin/org/mcrf/client/BsNamedEgressInstrumentation.kt"
)
val sources = (gate["sources"] as List<*>).map { it as Map<*, *> }
require(sources.size == paths.size && sources.map { it["path"] }.toSet() == paths)
for (row in sources) {
    val file = rootProject.file(row["path"].toString())
    require(file.isFile && file.absoluteFile.normalize() == file.canonicalFile && !Files.isSymbolicLink(file.toPath()) &&
        row["bytes"].toString().toLong() == file.length() && row["sha256"] == digest(file)) {
        "Strict-owner fixture source changed: ${row["path"]}"
    }
}
