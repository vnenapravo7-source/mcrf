import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.android.application") }
fun sha(file:java.io.File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
val releaseAllowed=providers.gradleProperty("historicalCsqttReleaseReceipt").orNull?.let {path->
    val proofFile=file(path)
    require(sha(proofFile)==providers.gradleProperty("historicalCsqttReleaseSha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(proofFile) as Map<*,*>
    require(proof["state"]=="P2_RELEASE_PROMOTION_READY"&&proof["versionName"]=="0.6"&&proof["versionCode"].toString()=="10043")
    require(proof["retainedPrimaryRuntimeChecks"].toString()=="81"&&proof["acceptedPrimaryManifestSha256"]=="538e2e2ce30591f27172017c4e7b40e4d0a03eaad35108e4cc897abb0135f5c0")
    true
}?:false
require(providers.gradleProperty("p2DispatcherCandidate").orNull=="true"){"Private whole-P2 dispatcher recipe only"}
val gateFile=file(requireNotNull(providers.gradleProperty("p2SourceReadyReceipt").orNull))
require(sha(gateFile)==providers.gradleProperty("p2SourceReadySha256").orNull)
val gate=groovy.json.JsonSlurper().parse(gateFile) as Map<*,*>
require(gate["priority"].toString()=="2"&&listOf("sourceReady","readyForBuild","allowCommonVerification","allowApkBuild","nativeSourceReady","allowNativeCandidateBuild").all {gate[it]==true})
val items=(gate["items"] as List<*>).map {it as Map<*,*>}
require(items.map {it["id"].toString()}.sorted()==listOf("11","2","20","6","7")&&items.all {it["sourceReady"]==true})
val stage=file(requireNotNull(providers.gradleProperty("p2DispatcherStage").orNull))
require(stage.isDirectory&&stage.absoluteFile.normalize()==stage.canonicalFile)
val built=groovy.json.JsonSlurper().parse(stage.resolve("build-receipt.json")) as Map<*,*>
val source=groovy.json.JsonSlurper().parse(stage.resolve("receipt.json")) as Map<*,*>
require(built["priority"].toString()=="2"&&built["state"]=="COMPILED_API_ABI_PASS_RUNTIME_OPEN"&&built["sourceStable"]==true&&built["producerComplete"]==true&&built["runtimeVerified"]==false)
require(built["sourceReadySha256"]==sha(gateFile)&&source["readinessSha256"]==sha(gateFile)&&built["sourceReceiptSha256"]==sha(stage.resolve("receipt.json")))
require(built["moduleVersionCode"].toString()=="8"&&built["version"]=="1.14.2-lx.11-mcrf-route-pipe.1")
val runtime=stage.resolve("runtime.aar")
require(runtime.isFile&&runtime.length()==built["aarBytes"].toString().toLong()&&sha(runtime)==built["aarSha256"])
require(sha(stage.resolve("api-signatures.txt"))==built["apiSha256"]&&sha(stage.resolve("source-manifest.json"))==source["sourceManifestSha256"])
require(built["retainedApiPreserved"]==true&&sha(stage.resolve("api-compatibility.json"))==built["apiCompatibilitySha256"])
val test=built["tests"] as Map<*,*>
require(test["group"] in setOf("P2_PIPE_LINUX","P2_PIPE_ANDROID_KERNEL")&&test["passed"].toString()=="14"&&sha(stage.resolve("pipe-tests.log"))==test["logSha256"])
if(test["group"]=="P2_PIPE_ANDROID_KERNEL")require(test["newExecution"]==false&&built["testTarget"]=="linux/arm64"&&built["kernelEvidenceSha256"].toString().matches(Regex("[0-9a-f]{64}")))
ZipFile(runtime).use {zip->
    val native=zip.entries().asSequence().filter {!it.isDirectory&&it.name.startsWith("jni/")}.map {it.name}.toSet()
    require(native==setOf("jni/arm64-v8a/libbox.so","jni/armeabi-v7a/libbox.so","jni/x86_64/libbox.so"))
}
// Compile the adapter IN THIS SAME common build, rather than reuse an old AAR
// or require another per-edit app build just to obtain adapter classes.
val adapterRoot=rootProject.file("primary-cores/singbox/src/main/kotlin")
val adapterRows=adapterRoot.walkTopDown().filter {it.isFile&&it.extension=="kt"}.sortedBy {it.relativeTo(adapterRoot).invariantSeparatorsPath}
    .map {it.relativeTo(adapterRoot).invariantSeparatorsPath+":"+sha(it)}.toList()
require(adapterRows.isNotEmpty())
val provenance=source["sourceManifestSha256"].toString()+"\n"+adapterRows.joinToString("\n")
val sourceSha=MessageDigest.getInstance("SHA-256").digest(provenance.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
val notices=layout.buildDirectory.dir("generated/p2-route-notices")
val prepareNotices=tasks.register("prepareP2RouteNotices"){
    inputs.file(stage.resolve("source-manifest.json"));inputs.files(adapterRoot);inputs.property("sourceSha",sourceSha);outputs.dir(notices)
    doLast {
        val destination=notices.get().asFile.resolve("licenses");destination.mkdirs()
        listOf("sing-box-GPL-3.0.txt","sing-box-NOTICE.txt").forEach {name->
            rootProject.file("app/src/main/assets/licenses/$name").copyTo(destination.resolve(name),overwrite=true)
        }
        stage.resolve("source-manifest.json").copyTo(destination.resolve("native-source-manifest.json"),overwrite=true)
        destination.resolve("adapter-source-manifest.txt").writeText(provenance,Charsets.UTF_8)
    }
}
android {
    namespace="org.mcrf.engines.p2dispatcher"
    compileSdk=34
    defaultConfig {
        applicationId="org.mcrf.engines.singbox";minSdk=29;targetSdk=34
        versionCode=if(releaseAllowed)10 else 8;versionName=if(releaseAllowed)"1.14.2-lx.11-mcrf.10" else "1.14.2-lx.11-mcrf-p2-candidate.8"
        manifestPlaceholders["moduleSourceVersion"]=if(releaseAllowed)"3d3d3db3-mcrf.10" else "3d3d3db3-mcrf-p2-candidate.8"
        manifestPlaceholders["sourceSha"]=sourceSha
    }
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
    sourceSets.getByName("main").assets.srcDir(notices.get().asFile)
    packaging.jniLibs.useLegacyPackaging=true
    splits { abi { isEnable=releaseAllowed;reset();include("arm64-v8a","armeabi-v7a","x86_64");isUniversalApk=false } }
    buildTypes { release {if(project.findProperty("testRelease")=="true")signingConfig=signingConfigs.getByName("debug")} }
}
tasks.named("preBuild").configure {dependsOn(prepareNotices)}
androidComponents.beforeVariants {variant->if(variant.buildType!="debug"&&!releaseAllowed)variant.enable=false}
dependencies {
    compileOnly(project(":primary-spi"))
    implementation(project(":primary-adapter"))
    implementation(files(runtime))
}
