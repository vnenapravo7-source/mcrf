import java.security.MessageDigest
import java.nio.file.Files

plugins { id("com.android.application") }

// No ad-hoc helper APK may evade the human's WHOLE-P1 source-ready gate.
val gatePath = requireNotNull(providers.gradleProperty("p1SourceReadyReceipt").orNull)
val gateSha = requireNotNull(providers.gradleProperty("p1SourceReadySha256").orNull)
require(gateSha.matches(Regex("[0-9a-f]{64}"))) {
    "Frozen common-P1 readiness receipt/hash required"
}
val gateFile = file(gatePath)
require(gateFile.isAbsolute && gateFile.absoluteFile.normalize() == gateFile.canonicalFile &&
    gateFile.canonicalFile.parentFile == rootProject.file("coordination").canonicalFile &&
    gateFile.name.matches(Regex("p1-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")) &&
    !Files.isSymbolicLink(gateFile.toPath()) && gateFile.isFile && gateFile.length() in 1L..262144L) {
    "Readiness must be a bounded ordinary file inside this frozen project"
}
val gateBytes = gateFile.readBytes()
require(MessageDigest.getInstance("SHA-256").digest(gateBytes).joinToString("") { "%02x".format(it.toInt() and 255) } == gateSha)
val gate = groovy.json.JsonSlurper().parse(gateBytes) as Map<*, *>
require(gate["priority"].toString() == "1" && listOf("sourceReady","readyForBuild","allowCommonVerification","allowApkBuild").all {gate[it]==true}) { "Finish ALL P1 sources before ANY helper build" }

android {
    namespace = "org.mcrf.p1owner"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.mcrf.p1owner"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "p1-owner-fixture-1"
    }
    buildFeatures { buildConfig = false }
}
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
