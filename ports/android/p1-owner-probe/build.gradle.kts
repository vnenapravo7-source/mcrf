import java.security.MessageDigest
import java.nio.file.Files
import java.io.File

plugins { id("com.android.application") }

// No ad-hoc helper APK may evade the human's WHOLE-P1 source-ready gate.
if (providers.gradleProperty("strictOwnerFixtureReceipt").isPresent || providers.gradleProperty("strictOwnerFixtureSha256").isPresent) {
    apply(from = rootProject.file("strict-owner-fixture-gate.gradle.kts"))
} else {
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
}

val quicStageProp = providers.gradleProperty("ownerQuicFixtureStage").orNull
val quicShaProp = providers.gradleProperty("ownerQuicFixtureSha256").orNull
require((quicStageProp == null) == (quicShaProp == null)) { "QUIC fixture stage and receipt digest must be paired" }
val quicLibrary = "libmcrf_owned_quic_fixture.so"
val quicJni = quicStageProp?.let { location ->
    require(providers.gradleProperty("strictOwnerFixtureReceipt").isPresent &&
        providers.gradleProperty("strictOwnerFixtureSha256").isPresent &&
        requireNotNull(quicShaProp).matches(Regex("[0-9a-f]{64}"))) { "Frozen strict owner QUIC evidence required" }
    val stage = File(location)
    val buildRoot = file("D:/MCRF/Builds/Android").canonicalFile
    require(stage.isAbsolute && stage.absoluteFile.normalize() == stage.canonicalFile &&
        stage.canonicalFile.parentFile == buildRoot && stage.name.matches(Regex("owner-quic-[A-Za-z0-9.-]+"))) {
        "Use a single owned QUIC fixture stage"
    }
    fun digest(input: File): String {
        require(input.isFile && input.absoluteFile.normalize() == input.canonicalFile && !Files.isSymbolicLink(input.toPath()))
        return MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val receipt = stage.resolve("receipt.json")
    require(digest(receipt) == quicShaProp) { "QUIC receipt digest differs" }
    val proof = groovy.json.JsonSlurper().parse(receipt) as Map<*, *>
    require(proof["schema"] == 1 && proof["state"] == "OWNER_QUIC_HOST_PASS_ANDROID_PIE_READY" && proof["testOnly"] == true &&
        proof["dependency"] == "github.com/sagernet/quic-go v0.61.0-sing-box-mod.9")
    val names = setOf("bind_android.go", "bind_other.go", "go.mod", "go.sum", "main.go", "main_test.go", "build.py")
    val sources = (proof["source"] as List<*>).map { it as Map<*, *> }
    require(sources.size == names.size && sources.map { it["file"] }.toSet() == names)
    sources.forEach { entry ->
        val name = entry["file"] as String
        require(digest(rootProject.file("quic-fixture/$name")) == entry["sha256"] &&
            digest(stage.resolve("source/$name")) == entry["sha256"]) { "QUIC source differs: $name" }
    }
    require(digest(stage.resolve("host.jsonl")) == proof["hostTestSha256"])
    val jni = stage.resolve("jniLibs")
    val abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val artifacts = (proof["artifacts"] as List<*>).map { it as Map<*, *> }
    require(artifacts.size == abis.size && artifacts.map { it["abi"] }.toSet() == abis)
    artifacts.forEach { row ->
        val relative = "${row["abi"]}/$quicLibrary"
        val binary = jni.resolve(relative)
        require(row["file"] == "jniLibs/$relative" && binary.length() == (row["bytes"] as Number).toLong() &&
            digest(binary) == row["sha256"]) { "QUIC fixture binary differs: $relative" }
    }
    require(jni.walkTopDown().filter(File::isFile).map { it.relativeTo(jni).invariantSeparatorsPath }.toSet() ==
        abis.map { "$it/$quicLibrary" }.toSet())
    jni
}

android {
    namespace = "org.mcrf.p1owner"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.mcrf.p1owner"
        minSdk = 29
        targetSdk = 34
        versionCode = if (quicJni == null) 1 else 2
        versionName = if (quicJni == null) "p1-owner-fixture-1" else "p1-owner-fixture-quic-2"
    }
    quicJni?.let { staged ->
        sourceSets.getByName("debug").jniLibs.srcDir(staged)
        packaging.jniLibs.useLegacyPackaging = true
        packaging.jniLibs.keepDebugSymbols += "**/$quicLibrary"
    }
    buildFeatures { buildConfig = false }
}
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
