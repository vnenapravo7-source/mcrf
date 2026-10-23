import java.security.MessageDigest

plugins { id("com.android.application") }
providers.gradleProperty("p2UiCorrectionReceipt").orNull?.let {path->
    require(providers.gradleProperty("p2ExpansionSourceReadyReceipt").isPresent)
    val input=file(path)
    val sha=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    require(sha==providers.gradleProperty("p2UiCorrectionSha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(input) as Map<*,*>
    require(proof["state"]=="UI_CORRECTION_SOURCE_READY"&&proof["priority"].toString()=="2"&&proof["versionCode"].toString()=="10027"&&proof["nativeUnchanged"]==true)
    require(gradle.startParameter.taskNames.none {it.contains("Release")||it.substringAfterLast(':').startsWith("test",ignoreCase=true)}) {"Retain accepted model/native checks; run only the new Android UI/runtime cases"}
}
// Expanded P2 has its OWN immutable source/selection binding. This is not an
// exception reopening the completed P2 candidate, retained suites or device grant.
providers.gradleProperty("p2ExpansionSourceReadyReceipt").orNull?.let {receiptPath->
    require(!providers.gradleProperty("p2SourceReadyReceipt").isPresent)
    val receiptFile=file(receiptPath)
    fun digest(input:java.io.File)=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    require(digest(receiptFile)==providers.gradleProperty("p2ExpansionSourceReadySha256").orNull)
    val receipt=groovy.json.JsonSlurper().parse(receiptFile) as Map<*,*>
    require(receipt["priority"].toString()=="2"&&receipt["verificationAuthority"]=="human-20261021-expanded-p2-whole-source-before-common-verification")
    require(listOf("sourceReady","readyForBuild","allowCommonVerification","allowApkBuild").all {receipt[it]==true})
    val items=(receipt["tasks"] as List<*>).map {it as Map<*,*>}
    require(items.map {it["id"].toString()}.sorted()==listOf("51","52","53","54","55","56","57")&&items.all {it["source"]=="ready"})
    val selection=rootProject.file("coordination/p2-expansion-jvm-affected-2026-10-21.txt")
    val sha=digest(selection)
    require(sha==providers.gradleProperty("p2ExpansionJvmSelectionSha256").orNull)
    val cases=selection.readLines().filter {it.isNotBlank()}
    require(cases.isNotEmpty()&&cases.distinct().size==cases.size&&cases.all {it.matches(Regex("org\\.mcrf\\.client\\.[A-Za-z0-9_]+Test(\\.[A-Za-z0-9_]+)?"))})
    val retainedCases=providers.gradleProperty("p2ExpansionJvmRetainedProof").orNull?.let {path->
        val proofFile=file(path)
        require(digest(proofFile)==providers.gradleProperty("p2ExpansionJvmRetainedSha256").orNull)
        val proof=groovy.json.JsonSlurper().parse(proofFile) as Map<*,*>
        require(proof["sourceReadySha256"]==providers.gradleProperty("p2ExpansionSourceReadySha256").orNull&&proof["newExecution"]==false&&proof["allRetained"]==false)
        val retained=(proof["cases"] as List<*>).map {val row=it as Map<*,*>;row["class"].toString()+"."+row["name"].toString()}
        require(retained.distinct().size==retained.size&&retained.all {identity->cases.any {identity==it||identity.startsWith(it+".")}})
        if(proof["mode"] in listOf("csqtt-generations-affected-only","csqtt-generations-fixture-resume","csqtt-generations-autodeploy-ux","csqtt-generations-swg25-accordion")){
            require(receipt["phase"].toString().contains("CSQTT_GENERATIONS"))
            if(proof["mode"]=="csqtt-generations-swg25-accordion"){
                require(retained.size==63)
                require(receipt["phase"]=="CSQTT_GENERATIONS_SWG25_ACCORDION_WHOLE_SOURCE_READY")
                require(cases.contains("org.mcrf.client.AndroidDeploySwg25Test"))
                val fresh=(proof["freshDependencies"] as List<*>).map {it as Map<*,*>}
                require(fresh.isNotEmpty())
                fresh.forEach {row->
                    val relative=row["path"].toString()
                    require(relative.startsWith("ports/android/app/src/")&&!relative.contains(".."))
                    require(digest(rootProject.file("../../$relative"))==row["sha256"])
                }
            }else if(proof["mode"]=="csqtt-generations-autodeploy-ux"){
                require(retained.size==61)
                require(cases.containsAll(listOf("org.mcrf.client.AndroidDeployQuickConnectionTest","org.mcrf.client.AndroidDeployAddTransportTest.openfluxExistingPanelUsesPinnedProvisioningWithoutInstallation")))
                val fresh=(proof["freshDependencies"] as List<*>).map {it as Map<*,*>}
                require(fresh.isNotEmpty())
                fresh.forEach {row->
                    val relative=row["path"].toString()
                    require(relative.startsWith("ports/android/app/src/")&&!relative.contains(".."))
                    require(digest(rootProject.file("../../$relative"))==row["sha256"])
                }
            }else if(proof["mode"]=="csqtt-generations-fixture-resume"){
                require(retained.size==60&&proof["baseRetainedCaseCount"].toString()=="14"&&proof["resumedCaseCount"].toString()=="46")
                val partial=proof["partial"] as Map<*,*>
                require(partial["parentExitCode"].toString()=="1"&&partial["fixtureOnlyCorrection"]==true)
            }else require(retained.size==14)
            require(cases.containsAll(listOf("org.mcrf.client.CsqttGenerationsTest","org.mcrf.client.Csqtt25ProfileTest","org.mcrf.client.ServerManagementFailureTest")))
            val dependencies=(proof["dependencies"] as List<*>).map {it as Map<*,*>}
            require(dependencies.isNotEmpty())
            dependencies.forEach {row->
                val relative=row["path"].toString()
                require(relative.startsWith("ports/android/app/src/")&&!relative.contains(".."))
                require(digest(rootProject.file("../../$relative"))==row["sha256"])
            }
        }else if(retained.size==19)require(cases.last()=="org.mcrf.client.UnifiedAutoSelectionTest")
        else if(retained.size==22)require(cases.takeLast(3)==listOf("org.mcrf.client.NotificationTrafficSettingsTest","org.mcrf.client.AutoSelectionOffTest","org.mcrf.client.OutboundObservationTest"))
        else if(retained.size==29)require(cases.last()=="org.mcrf.client.AutoSelectionDeviationTest")
        else {require(retained.size==31);require(cases.takeLast(3)==listOf("org.mcrf.client.AndroidDeploySwgTest.legacyLongCallDraftOpensAndPreservesAllWdttCalls","org.mcrf.client.AndroidDeploySwgTest.expandedCallListsHaveNoProtocolCountCapAndKeepPayloadGuard","org.mcrf.client.VkRelayTest.deploymentCallPoolsRespectActualAndroidImportLimits"))}
        retained
    }?:emptyList()
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        if(name=="testDebugUnitTest") {
            filter {isFailOnNoMatchingTests=true;cases.forEach {includeTestsMatching(it)};retainedCases.forEach {excludeTestsMatching(it)}}
            inputs.file(selection);inputs.property("p2ExpansionJvmSelectionSha256",sha)
            inputs.property("p2ExpansionRetainedCases",retainedCases)
        }else enabled=false
    }
}
// The whole-P2 coordinator compiles one common snapshot but executes only new
// and affected JVM cases. Retained unchanged P1 suites are not silently rerun.
providers.gradleProperty("p2SourceReadyReceipt").orNull?.let { receiptPath ->
    val receiptFile=file(receiptPath)
    val sha=MessageDigest.getInstance("SHA-256").digest(receiptFile.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    require(sha==providers.gradleProperty("p2SourceReadySha256").orNull){"Exact whole-P2 readiness snapshot required"}
    val receipt=groovy.json.JsonSlurper().parse(receiptFile) as Map<*,*>
    require(receipt["priority"].toString()=="2" && listOf("sourceReady","readyForBuild","allowCommonVerification","allowApkBuild").all {receipt[it]==true})
    val items=(receipt["items"] as List<*>).map {it as Map<*,*>}
    require(items.map {it["id"].toString()}.sorted()==listOf("11","2","20","6","7") && items.all {it["sourceReady"]==true})
    val selection=rootProject.file("coordination/p2-jvm-affected-2026-10-20.txt")
    val selectionSha=MessageDigest.getInstance("SHA-256").digest(selection.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    require(selectionSha==providers.gradleProperty("p2JvmSelectionSha256").orNull){"Exact affected JVM selection required"}
    var cases=selection.readLines().filter {it.isNotBlank()}
    require(cases.isNotEmpty() && cases.distinct().size==cases.size && cases.all {it.matches(Regex("org\\.mcrf\\.client\\.[A-Za-z0-9_]+Test(\\.[A-Za-z0-9_]+)?"))})
    providers.gradleProperty("p2JvmRetainedProof").orNull?.let {path->
        val proofFile=file(path)
        require(MessageDigest.getInstance("SHA-256").digest(proofFile.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}==providers.gradleProperty("p2JvmRetainedSha256").orNull)
        val proof=groovy.json.JsonSlurper().parse(proofFile) as Map<*,*>
        require(proof["priority"].toString()=="2"&&proof["sourceReadySha256"]==sha&&proof["newExecution"]==false)
        val rerun=(proof["rerunClasses"] as List<*>).map {it.toString()}
        val reviewedDelta=proof["reviewedAppDelta"]=="warp-progress-carrier-session"&&rerun.size==17&&
            rerun.containsAll(listOf("org.mcrf.client.CarrierChallengeFlightsTest","org.mcrf.client.WarpProgressPhaseTest"))&&
            (proof["changedSources"] as? List<*>)?.isNotEmpty()==true
        require((rerun.size in 1..5||reviewedDelta||rerun.isEmpty()&&proof["allRetained"]==true&&proof["javaBytecodeUnchanged"]==true)&&rerun.distinct().size==rerun.size&&rerun.all {pattern->cases.any {pattern==it||pattern.startsWith(it+".")}})
        cases=rerun
    }
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        if(name=="testDebugUnitTest"){
            if(cases.isEmpty())enabled=false
            filter {isFailOnNoMatchingTests=true;cases.forEach {includeTestsMatching(it)}}
            inputs.file(selection);inputs.property("p2JvmSelectionSha256",selectionSha)
        }else enabled=false // No accidental duplicate release-suite execution.
    }
}
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
        versionCode = when {
            providers.gradleProperty("p2UiCorrectionReceipt").isPresent -> 10027
        providers.gradleProperty("p2ExpansionSourceReadyReceipt").isPresent -> 10043
            providers.gradleProperty("p2SourceReadyReceipt").isPresent -> 10025
            else -> 10046
        }
        versionName = when {
            providers.gradleProperty("p2UiCorrectionReceipt").isPresent -> "0.6-p2-candidate.3"
        providers.gradleProperty("p2ExpansionSourceReadyReceipt").isPresent -> "0.6"
            providers.gradleProperty("p2SourceReadyReceipt").isPresent -> "0.6-p2-candidate.1"
            else -> "0.6.1"
        }
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
