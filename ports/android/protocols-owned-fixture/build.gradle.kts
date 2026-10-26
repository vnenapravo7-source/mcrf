import java.security.MessageDigest

plugins { id("com.android.application") }
val stage=file(requireNotNull(providers.gradleProperty("protocolsFixtureStage").orNull))
require(providers.gradleProperty("protocolsOwnedFixture").orNull=="true")
val gate=groovy.json.JsonSlurper().parse(file("../coordination/p3-p4-2026-10-23.json")) as Map<*,*>
require(gate["sourceReady"]==true&&gate["allowCommonVerification"]==true&&gate["allowApkBuild"]==true)
val receipt=groovy.json.JsonSlurper().parse(stage.resolve("receipt.json")) as Map<*,*>
require(receipt["kind"]=="protocols"&&receipt["sourceStable"]==true&&receipt["state"] in setOf("COMPILED_LEGAL_AND_FIXTURE_PENDING","COMPILED_FIXTURE_PENDING","OWNED_FIXTURE_FAILED_RETAINED"))
val binary=stage.resolve("protocols-owned.test")
val digest=MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
require(receipt["fixtureSha256"]==digest)
val stageNative=tasks.register<Copy>("stageOwnedNative"){
    from(binary);into(layout.buildDirectory.dir("generated/native/x86_64"));rename {"libprotocols_fixture.so"}
}
val networkDiagnostic=layout.buildDirectory.file("generated/native/x86_64/libnetwork_diagnostic.so")
val compileNetworkDiagnostic=tasks.register<Exec>("compileNetworkDiagnostic"){
    val source=file("src/main/cpp/network_diagnostic.c")
    inputs.file(source);outputs.file(networkDiagnostic)
    doFirst { networkDiagnostic.get().asFile.parentFile.mkdirs() }
    commandLine("C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe",
        "--target=x86_64-linux-android29","-fPIE","-pie",source.absolutePath,"-landroid","-o",networkDiagnostic.get().asFile.absolutePath)
}
android {
    namespace="org.mcrf.protocolfixtures";compileSdk=34
    defaultConfig {
        applicationId="org.mcrf.protocolfixtures";minSdk=31;targetSdk=34;versionCode=1;versionName="owned.1"
        ndk {abiFilters.add("x86_64")}
        manifestPlaceholders["fixtureSha256"]=digest
    }
    packaging {
        jniLibs.useLegacyPackaging=true
        jniLibs.keepDebugSymbols.add("**/libprotocols_fixture.so")
        jniLibs.keepDebugSymbols.add("**/libnetwork_diagnostic.so")
    }
    sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("generated/native").get().asFile.absolutePath)
    buildTypes {getByName("debug"){isDebuggable=true}}
}
tasks.named("preBuild"){dependsOn(stageNative,compileNetworkDiagnostic)}
