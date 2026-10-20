import java.security.MessageDigest
import java.nio.file.Files

plugins { id("com.android.application") }
val stage = file(requireNotNull(providers.gradleProperty("wgBackendStage").orNull))
require(providers.gradleProperty("wgBackendCandidateGate").orNull == "true")
val receipt = groovy.json.JsonSlurper().parse(stage.resolve("receipt.json")) as Map<*, *>
val runtime = stage.resolve("wg-backend.aar")
val p1Candidate = providers.gradleProperty("p1WgCandidate").orNull == "true"
if (p1Candidate) {
    require(receipt["managedDnsContract"] == "owned_kernel_dns_nat_v1") { "No managed DNS declaration on legacy native artifacts" }
    val gateFile = file(requireNotNull(providers.gradleProperty("p1SourceReadyReceipt").orNull))
    val gateSha = requireNotNull(providers.gradleProperty("p1SourceReadySha256").orNull)
    require(gateSha.matches(Regex("[0-9a-f]{64}")) && gateFile.isAbsolute &&
        gateFile.absoluteFile.normalize() == gateFile.canonicalFile &&
        gateFile.canonicalFile.parentFile == rootProject.file("coordination").canonicalFile &&
        gateFile.name.matches(Regex("p1-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")) &&
        !Files.isSymbolicLink(gateFile.toPath()) && gateFile.isFile && gateFile.length() in 1L..262144L)
    val bytes = gateFile.readBytes()
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == gateSha)
    val gate = groovy.json.JsonSlurper().parse(bytes) as Map<*, *>
    require(gate["priority"].toString() == "1" && listOf("sourceReady", "readyForBuild", "allowCommonVerification", "nativeSourceReady", "allowNativeCandidateBuild", "allowApkBuild").all { gate[it] == true }) {
        "Whole-P1 source/native/common packaging gates must all be open"
    }
    // This generated candidate is NOT public acceptance. The normal manifest
    // remains marker-free, and ordinary invocations cannot enable these paths.
}
require(receipt["state"] == "built_awaiting_native_and_device_gates")
require(receipt["upstreamCommit"] == "64065e6826ad82aa28429dd829c1c65f29b7f6c9")
require(MessageDigest.getInstance("SHA-256").digest(runtime.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) } == receipt["aarSha256"])
android {
    namespace = "org.mcrf.engines.wg"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.mcrf.engines.wg"
        minSdk = 29
        targetSdk = 34
        versionCode = if (p1Candidate) 3 else 2
        versionName = if (p1Candidate) "0.1.0-p1-candidate.3" else "0.1.0-candidate.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    sourceSets.getByName("main").java.srcDir("../transports/wg-backend/android/src")
    if (p1Candidate) listOf("debug", "release").forEach { sourceSets.getByName(it).manifest.srcFile("src/p1Candidate/AndroidManifest.xml") }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = false } }
    buildTypes { release { if (project.findProperty("testRelease") == "true") signingConfig = signingConfigs.getByName("debug") } }
}
dependencies { compileOnly(project(":primary-spi")); implementation(files(runtime)) }
