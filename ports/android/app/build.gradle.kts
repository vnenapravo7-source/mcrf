import java.security.MessageDigest

plugins { id("com.android.application") }
val isolatedFirstLaunchFixture=providers.gradleProperty("isolatedFirstLaunchFixture").orNull=="true"
val isolatedAnimationFixture=providers.gradleProperty("isolatedAnimationFixture").orNull=="true"
require(!isolatedAnimationFixture||providers.gradleProperty("isolatedAnimationFixtureGate").orNull=="true") {"Animation APK requires explicit fresh-UID fixture gate"}
require(!(isolatedFirstLaunchFixture&&isolatedAnimationFixture)) {"Fixture identities must stay separate"}
require(!isolatedFirstLaunchFixture||providers.gradleProperty("isolatedFirstLaunchFixtureGate").orNull=="true") {"Isolated first-launch APK requires explicit fixture gate"}
// Coordinator-only, hash-verified peer. It is never a main/release source directory.
val optionalDebugFixtureRoot=providers.gradleProperty("optionalDebugFixtureStage").orNull?.let {file(it)}
optionalDebugFixtureRoot?.let {root ->
    require(providers.gradleProperty("optionalDebugFixtureNativeGate").orNull=="true") {"Debug peer requires an explicit frozen native gate"}
    val receipt=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    require(receipt["stage"]=="debug-only-native-peer")
    val rows=(receipt["files"] as List<*>).map {it as Map<*,*>}
    require(rows.size==3)
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach {abi ->
        val row=rows.single {it["abi"]==abi && it["kind"]=="debug-feature-peer"}
        val binary=root.resolve("fixtures/$abi/liboptional_fixture.so")
        require(binary.isFile && binary.length()==row["bytes"].toString().toLong())
        val digest=MessageDigest.getInstance("SHA-256")
        binary.inputStream().use {input -> val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
        require(row["sha256"]==digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}) {"Debug peer SHA mismatch for $abi"}
    }
}
val dnsttDebugFixtureRoot=providers.gradleProperty("dnsttDebugFixtureStage").orNull?.let {file(it)}
dnsttDebugFixtureRoot?.let {root ->
    require(providers.gradleProperty("dnsttDebugFixtureNativeGate").orNull=="true") {"DNSTT peer requires explicit frozen gate"}
    val receipt=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    val lock=(groovy.json.JsonSlurper().parse(rootProject.file("transports/transport-lock.json")) as Map<*,*>)["dnstt"] as Map<*,*>
    require(receipt["stage"]=="debug-only-dnstt-peer" && receipt["module"]==lock["module"] && receipt["version"]==lock["version"] && receipt["moduleSum"]==lock["sum"])
    val rows=(receipt["files"] as List<*>).map {it as Map<*,*>};require(rows.size==3)
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach {abi ->
        val row=rows.single {it["abi"]==abi && it["kind"]=="dnstt-test-peer"}
        val binary=root.resolve("fixtures/$abi/libdnstt_testserver.so")
        require(binary.isFile&&binary.length()==row["bytes"].toString().toLong())
        val digest=MessageDigest.getInstance("SHA-256")
        binary.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        require(row["sha256"]==digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}) {"DNSTT peer SHA mismatch"}
    }
}
android {
    namespace = "org.mcrf.client"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.mcrf.client"
        minSdk = 29
        targetSdk = 34
        versionCode = 10021
        versionName = "0.1.0-preview.20261020.1"
        manifestPlaceholders["debugApplicationLabel"] = if (isolatedFirstLaunchFixture) "MCRF · UI-стенд" else "MCRF"
        if(isolatedFirstLaunchFixture)testApplicationId="org.mcrf.client.onboardingfixture.test"
        if(isolatedAnimationFixture)testApplicationId="org.mcrf.client.animationfixture10.test"
        testInstrumentationRunner = if(project.findProperty("releaseSmoke")=="true") "org.mcrf.client.ReleaseSmokeInstrumentation" else "org.mcrf.client.DeviceSmokeInstrumentation"
        if(isolatedAnimationFixture)testInstrumentationRunner="org.mcrf.client.ConnectionAnimationInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // Explicit development opt-in only; never silently sign a public release with a test key.
        if(project.findProperty("testRelease") == "true") signingConfig=signingConfigs.getByName("debug")
    } }
    if(isolatedFirstLaunchFixture)buildTypes.getByName("debug").applicationIdSuffix=".onboardingfixture"
    if(isolatedAnimationFixture)buildTypes.getByName("debug").applicationIdSuffix=".animationfixture10"
    splits { abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64")
        isUniversalApk = true
    } }
    // Compress .so in the APK. Android extracts only the device ABI at installation.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets.getByName("test").resources.srcDir("../../fixtures")
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    sourceSets.getByName("main").java.srcDir("../../../work/steer-android-port/android-vpn/integration-helper/src/main/java")
    // Native payloads remain packaging inputs for separate engines, never base APK content.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    sourceSets.getByName("main").assets.srcDir("../../catalogue")
    sourceSets.getByName("androidTest").assets.srcDir("../../fixtures")
    project.findProperty("liveFixtures")?.let { sourceSets.getByName("androidTest").assets.srcDir(it.toString()) }
    sourceSets.getByName("debug").assets.srcDir("../../fixtures")
    optionalDebugFixtureRoot?.let {sourceSets.getByName("androidTest").jniLibs.srcDir(it.resolve("fixtures"))}
    dnsttDebugFixtureRoot?.let {sourceSets.getByName("androidTest").jniLibs.srcDir(it.resolve("fixtures"))}
    packaging.jniLibs.keepDebugSymbols += "**/liboptional_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libdnstt_testserver.so"
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation(project(":primary-spi"))
    // Only transitional source/test signatures compile against libbox. Runtime and
    // JNI are installed separately, and loaded solely in a fresh primary worker.
    compileOnly(files("libs/libbox.aar"))
    androidTestCompileOnly(files("libs/libbox.aar"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.mwiede:jsch:0.2.24")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // SDK stubs only; the test APK requests the device's framework test library.
    // Never package these classes or add the library to the application APK.
    androidTestCompileOnly(files(androidComponents.sdkComponents.sdkDirectory.map {
        it.file("platforms/android-${android.compileSdk}/optional/android.test.mock.jar")
    }))
}
