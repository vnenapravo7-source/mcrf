import java.security.MessageDigest
plugins { id("com.android.application") }
require(providers.gradleProperty("slipstreamOwnedFixture").orNull=="true")
val stage=file(requireNotNull(providers.gradleProperty("slipstreamFixtureStage").orNull))
val receipt=groovy.json.JsonSlurper().parse(stage.resolve("receipt.json")) as Map<*,*>
require(receipt["kind"]=="slipstream"&&receipt["sourceStable"]==true&&receipt["state"] in setOf("COMPILED_FIXTURE_PENDING","FIXTURE_FAILED_PRESERVED"))
val extra=(receipt["androidFixture"] as? Map<*,*>)?.get("file") as? String
val names=listOf("x86_64/libslipstream.so","x86_64/libslipstream_core.so","fixtures/libslipstream_fixture.so","fixtures/libslipstream_testserver.so","fixtures/libslipstream_cserver.so")+listOfNotNull(extra)
val artifacts=((receipt["artifacts"] as List<*>)+(receipt["fixtureArtifacts"] as List<*>)).map {it as Map<*,*>}
val hashes=names.map { path ->
    val file=stage.resolve(path);val proof=artifacts.single {it["file"]==path}
    val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    require(sha==proof["sha256"]&&file.length()==(proof["bytes"] as Number).toLong()&&proof["elfChecked"]==true)
    file.name+":"+sha
}.joinToString("|")
val native=tasks.register<Copy>("stageOwnedNative"){
    names.forEach {from(stage.resolve(it))};into(layout.buildDirectory.dir("generated/native/x86_64"))
}
val diagnostic=layout.buildDirectory.file("generated/native/x86_64/libslipstream_diagnostic_server.so")
val diagnosticServer=tasks.register<Exec>("compileDiagnosticServer"){
    dependsOn(native)
    val source=file("src/main/cpp/server_diagnostic.c")
    inputs.file(source);outputs.file(diagnostic)
    doFirst {diagnostic.get().asFile.parentFile.mkdirs()}
    commandLine("C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe",
        "--target=x86_64-linux-android29","-fPIE","-pie","-Wl,-z,max-page-size=16384",source.absolutePath,"-o",diagnostic.get().asFile.absolutePath)
}
val diagnosticCServer=tasks.register<Copy>("stageDiagnosticCServer"){
    dependsOn(native,diagnosticServer);from(diagnostic);into(layout.buildDirectory.dir("generated/native/x86_64"));rename {"libslipstream_diagnostic_cserver.so"}
}
android {
    namespace="org.mcrf.slipstreamfixtures";compileSdk=34
    defaultConfig {
        applicationId="org.mcrf.slipstreamfixtures";minSdk=31;targetSdk=34;versionCode=1;versionName="owned.1"
        ndk {abiFilters.add("x86_64")};manifestPlaceholders["fixtureHashes"]=hashes
    }
    packaging {jniLibs.useLegacyPackaging=true;jniLibs.keepDebugSymbols.add("**/*.so")}
    sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("generated/native").get().asFile.absolutePath)
    buildTypes {getByName("debug"){isDebuggable=true}}
}
tasks.named("preBuild"){dependsOn(native,diagnosticServer,diagnosticCServer)}
