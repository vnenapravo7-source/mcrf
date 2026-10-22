import java.security.MessageDigest
import java.nio.file.Files

plugins { id("com.android.application") }
val stage = file(requireNotNull(providers.gradleProperty("wgBackendStage").orNull))
require(providers.gradleProperty("wgBackendCandidateGate").orNull == "true")
val receipt = groovy.json.JsonSlurper().parse(stage.resolve("receipt.json")) as Map<*, *>
val runtime = stage.resolve("wg-backend.aar")
val p1Candidate = providers.gradleProperty("p1WgCandidate").orNull == "true"
val p2Candidate = providers.gradleProperty("p2WgCandidate").orNull == "true"
val releaseAllowed=providers.gradleProperty("historicalCsqttReleaseReceipt").orNull?.let {path->
    val proofFile=file(path)
    val digest=MessageDigest.getInstance("SHA-256").digest(proofFile.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    require(digest==providers.gradleProperty("historicalCsqttReleaseSha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(proofFile) as Map<*,*>
    require(proof["state"]=="P2_RELEASE_PROMOTION_READY"&&proof["versionName"]=="0.6"&&proof["versionCode"].toString()=="10043")
    require(proof["retainedPrimaryRuntimeChecks"].toString()=="81"&&proof["acceptedPrimaryManifestSha256"]=="538e2e2ce30591f27172017c4e7b40e4d0a03eaad35108e4cc897abb0135f5c0")
    true
}?:false
require(!(p1Candidate && p2Candidate)) { "P1/P2 candidate authorities cannot be mixed" }
if (p2Candidate) {
    val gateFile=file(requireNotNull(providers.gradleProperty("p2SourceReadyReceipt").orNull))
    val gateSha=requireNotNull(providers.gradleProperty("p2SourceReadySha256").orNull)
    require(gateSha.matches(Regex("[0-9a-f]{64}")) && gateFile.isAbsolute && gateFile.isFile &&
        gateFile.absoluteFile.normalize()==gateFile.canonicalFile && !Files.isSymbolicLink(gateFile.toPath()) &&
        gateFile.canonicalFile.parentFile==rootProject.file("coordination").canonicalFile &&
        gateFile.name=="p2-progress-2026-10-20.json" && gateFile.length() in 1L..262144L)
    val bytes=gateFile.readBytes()
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}==gateSha)
    val gate=groovy.json.JsonSlurper().parse(bytes) as Map<*, *>
    require(gate["priority"].toString()=="2" && gate["verificationAuthority"]=="human-20261020-p2-whole-source-before-common-verification" &&
        listOf("sourceReady","readyForBuild","allowCommonVerification","nativeSourceReady","allowNativeCandidateBuild","allowApkBuild").all {gate[it]==true})
    val items=(gate["items"] as List<*>).map {it as Map<*, *>}
    require(items.map {it["id"].toString()}.sorted()==listOf("11","2","20","6","7") && items.all {it["sourceReady"]==true})
    require(receipt["priority"].toString()=="2" && receipt["sourceReadySha256"]==gateSha &&
        receipt["packetPipeContract"]=="owned_unix_dgram_v1" && receipt["executedPipeCases"].toString()=="6" &&
        (receipt["encryptedPipeProtocols"] as? List<*>)?.map { it.toString() }?.sorted()==listOf("AWG3","AWG3_1","WG")) { "Exact source-bound new WG pipe native candidate required" }
}
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
        versionCode = if (p2Candidate && releaseAllowed) 5 else if (p2Candidate) 4 else if (p1Candidate) 3 else 2
        versionName = if (p2Candidate && releaseAllowed) "0.1.0-mcrf.5" else if (p2Candidate) "0.1.0-p2-candidate.4" else if (p1Candidate) "0.1.0-p1-candidate.3" else "0.1.0-candidate.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    sourceSets.getByName("main").java.srcDir("../transports/wg-backend/android/src")
    if (p1Candidate) listOf("debug", "release").forEach { sourceSets.getByName(it).manifest.srcFile("src/p1Candidate/AndroidManifest.xml") }
    if (p2Candidate) (if(releaseAllowed)listOf("debug","release") else listOf("debug")).forEach {sourceSets.getByName(it).manifest.srcFile("src/p2Candidate/AndroidManifest.xml")}
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = false } }
    buildTypes { release { if (project.findProperty("testRelease") == "true") signingConfig = signingConfigs.getByName("debug") } }
}
dependencies { compileOnly(project(":primary-spi")); implementation(files(runtime)) }
androidComponents.beforeVariants { variant -> if(p2Candidate && variant.buildType!="debug"&&!releaseAllowed)variant.enable=false }
