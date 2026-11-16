import java.security.MessageDigest
import java.io.File
import java.nio.file.Files

plugins { id("com.android.application") }
// Explicit local previews have a distinguishable upgrade code. They cannot
// inherit a historical/release receipt or produce a release artifact.
val intermediateBuildCode=providers.gradleProperty("intermediateBuildCode").orNull?.let {value->
    require(value.matches(Regex("[0-9]{5,9}"))){"Invalid intermediate build code"}
    value.toInt().also {require(it in 10066..999999999){"Intermediate build must upgrade 2.0"}}
}
val mobileBetaBuild=providers.gradleProperty("mobileBetaBuild").orNull=="true"
require(!mobileBetaBuild||intermediateBuildCode==null)
if(intermediateBuildCode!=null) {
    require(listOf("release08Receipt","release073Receipt","release072Receipt","openflux016PublicReceipt",
        "openflux016Receipt","release07Receipt","p3p4CandidateReceipt","nextIndependentReceipt",
        "p2UiCorrectionReceipt","p2ExpansionSourceReadyReceipt","p2SourceReadyReceipt").none {providers.gradleProperty(it).isPresent})
    gradle.taskGraph.whenReady {
        require(allTasks.none {it.project==project && it.name.contains("release",ignoreCase=true)}){"Intermediate build is debug-only"}
    }
}
val supportedClientAbis=listOf("arm64-v8a","armeabi-v7a","x86_64")
// An ABI-specific offline edition carries only its own two signed module APKs.
// Never emit a universal APK when its asset index covers fewer than all ABIs.
val offlineClientAbis=providers.gradleProperty("offlineAssets").orNull?.let {location->
    val index=File(file(location),"bundled-engines/index.json")
    require(index.isFile&&index.length() in 1..16384){"Bounded offline engine index required"}
    val doc=groovy.json.JsonSlurper().parse(index) as Map<*,*>
    require(doc["schema"].toString()=="1")
    val rows=(doc["packages"] as List<*>).map {it as Map<*,*>}
    require(rows.size in 2..6&&rows.map {it["kind"] to it["abi"]}.distinct().size==rows.size)
    val groups=rows.groupBy {it["abi"].toString()}
    require(groups.keys.all {it in supportedClientAbis})
    require(groups.size==1||groups.size==supportedClientAbis.size){"Offline assets must cover one ABI or all supported ABIs"}
    groups.forEach {(_,pair)->require(pair.map {it["kind"]}.toSet()==setOf("bs","sing-box"))}
    supportedClientAbis.filter {it in groups}
}?:supportedClientAbis
val nativeRouterInput=providers.gradleProperty("nativeRouterInputs").orNull?.let {location->
    fun digest(binary:File)=MessageDigest.getInstance("SHA-256").digest(binary.readBytes())
        .joinToString(""){"%02x".format(it.toInt() and 255)}
    val receipt=file(location).canonicalFile
    require(receipt.isFile&&receipt.length() in 1..4_000_000&&digest(receipt)==providers.gradleProperty("nativeRouterInputsSha256").orNull)
    val doc=groovy.json.JsonSlurper().parse(receipt) as Map<*,*>
    require(doc["state"]=="COMPILED_MULTICALL_PROOF_NOT_PACKAGED"&&doc["sourceStable"]==true)
    val root=File(receipt.parentFile,"router-artifacts").canonicalFile
    val rows=(doc["routerArtifacts"] as List<*>).map {it as Map<*,*>}
    require(rows.map {it["abi"].toString()}.sorted()==supportedClientAbis.sorted())
    val pins=rows.associate {row->
        val abi=row["abi"].toString()
        val binary=File(root,"$abi/libmcrf_router.so")
        require(binary.isFile&&binary.canonicalFile==binary.absoluteFile&&binary.length()==row["bytes"].toString().toLong())
        require(binary.canonicalFile==file(row["path"].toString()).canonicalFile&&digest(binary)==row["sha256"])
        abi to row["sha256"].toString()
    }
    require(root.walkTopDown().filter {it.isFile}.map {it.canonicalFile}.toSet()==
        supportedClientAbis.map {File(root,"$it/libmcrf_router.so").canonicalFile}.toSet())
    root to pins
}
// An executable owned Go peer belongs only to the instrumentation APK.
val originalPacketFixtureRoot=providers.gradleProperty("originalPacketFixtureStage").orNull?.let {location->
    val root=file(location).canonicalFile
    val receipt=groovy.json.JsonSlurper().parse(File(root,"receipt.json")) as Map<*,*>
    val abi=receipt["abi"].toString()
    require(receipt["stage"]=="debug-only-original-packet-peer"&&abi in setOf("x86_64","arm64-v8a"))
    val binary=File(root,"fixtures/$abi/libmcrf_original_packet_fixture.so")
    require(binary.isFile&&binary.length()==receipt["bytes"].toString().toLong())
    require(MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==receipt["sha256"])
    root
}
providers.gradleProperty("release08Receipt").orNull?.let {path->
    require(listOf("release073Receipt","release072Receipt","openflux016PublicReceipt","openflux016Receipt","release07Receipt","p3p4CandidateReceipt","nextIndependentReceipt","p2UiCorrectionReceipt","p2ExpansionSourceReadyReceipt","p2SourceReadyReceipt").none {providers.gradleProperty(it).isPresent}) {"2.0 cannot inherit historical candidate authority"}
    fun digest(input:java.io.File):String {
        require(input.isFile&&!Files.isSymbolicLink(input.toPath()))
        return MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    val input=file(path)
    require(digest(input)==providers.gradleProperty("release08Sha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(input) as Map<*,*>
    require(proof["authority"]=="human-20261029-local-test-then-release08"&&proof["state"]=="SOURCE_READY")
    require(proof["versionCode"].toString()=="10065"&&proof["versionName"]=="2.0"&&proof["releaseType"]=="release"&&proof["publish"]==false)
    require(proof["groupedAffectedBuild"]==true)
    val manifest=file(proof["sourceManifest"].toString())
    require(digest(manifest)==proof["sourceManifestSha256"])
    val rows=(groovy.json.JsonSlurper().parse(manifest) as List<*>).map {it as Map<*,*>}
    require(rows.isNotEmpty()&&rows.map {it["path"]}.distinct().size==rows.size)
    rows.forEach {require(digest(file(it["path"].toString()))==it["sha256"])}
    val native=file(proof["nativeReceipt"].toString())
    require(digest(native)==proof["nativeReceiptSha256"])
    val rebuilt=groovy.json.JsonSlurper().parse(native) as Map<*,*>
    require(rebuilt["state"]=="AFFECTED_NATIVE_REBUILT"&&rebuilt["sourceManifestSha256"]==proof["sourceManifestSha256"])
    val engines=(rebuilt["engines"] as List<*>).map {it as Map<*,*>}
    require(engines.map {it["kind"].toString()}.sorted()==listOf("csqtt","csqtt25","qwdtt","steer","wdtt"))
    engines.forEach {engine->
        require(engine["rebuilt"]==true&&engine["sourceStable"]==true)
        val source=engine["sourceManifest"] as Map<*,*>
        require(digest(file(source["path"].toString()))==source["sha256"])
        val sourceRows=(groovy.json.JsonSlurper().parse(file(source["path"].toString())) as List<*>).map {it as Map<*,*>}
        require(sourceRows.isNotEmpty()&&sourceRows.map {it["path"]}.distinct().size==sourceRows.size)
        sourceRows.forEach {require(digest(file(it["path"].toString()))==it["sha256"])}
        val outputs=(engine["outputs"] as List<*>).map {it as Map<*,*>}
        require(outputs.isNotEmpty());outputs.forEach {require(digest(file(it["path"].toString()))==it["sha256"])}
    }
    val historical=(rebuilt["historicalPackages"] as List<*>).map {it as Map<*,*>}
    require(historical.map {it["kind"].toString()}.sorted()==listOf("csqtt20","csqtt215"))
    historical.forEach {engine->
        require(engine["rebuilt"]==false&&engine["sourceStable"]==true)
        val bound=engine["packageReceipt"] as Map<*,*>
        require(digest(file(bound["path"].toString()))==bound["sha256"])
        val outputs=(engine["outputs"] as List<*>).map {it as Map<*,*>}
        require(outputs.size==6);outputs.forEach {require(digest(file(it["path"].toString()))==it["sha256"])}
    }
}
providers.gradleProperty("release073Receipt").orNull?.let {path->
    val proof=groovy.json.JsonSlurper().parse(file(path)) as Map<*,*>
    require(proof["authority"]=="human-20261029-publish-0.7.3"&&proof["state"]=="SOURCE_READY")
    require(proof["versionCode"].toString()=="10063"&&proof["versionName"]=="0.7.3")
    require(proof["scope"]=="installer-hardening-20261029"&&proof["releaseType"]=="release"&&proof["nativeUnchanged"]==true)
}
providers.gradleProperty("release072Receipt").orNull?.let {path->
    val proof=groovy.json.JsonSlurper().parse(file(path)) as Map<*,*>
    require(proof["authority"]=="human-20261028-publish-0.7.2"&&proof["state"]=="SOURCE_READY")
    require(proof["versionCode"].toString()=="10062"&&proof["versionName"]=="0.7.2")
}
providers.gradleProperty("openflux016PublicReceipt").orNull?.let {path->
    require(providers.gradleProperty("openflux016Receipt").isPresent)
    val proof=groovy.json.JsonSlurper().parse(file(path)) as Map<*,*>
    require(proof["authority"]=="human-20261026-publish-0.7.1"&&proof["state"]=="SOURCE_READY")
    require(proof["versionCode"].toString()=="10061"&&proof["versionName"]=="0.7.1")
}
providers.gradleProperty("openflux016Receipt").orNull?.let {path->
    require(!providers.gradleProperty("release07Receipt").isPresent&&!providers.gradleProperty("p3p4CandidateReceipt").isPresent)
    val proof=groovy.json.JsonSlurper().parse(file(path)) as Map<*,*>
    require(proof["authority"]=="human-20261026-support-openflux016"&&proof["state"]=="SOURCE_READY"&&proof["publish"]==false)
    require(proof["versionCode"].toString()=="10060"&&proof["versionName"]=="0.7.1-candidate.1")
}
providers.gradleProperty("release07Receipt").orNull?.let {path->
    require(!providers.gradleProperty("p3p4CandidateReceipt").isPresent)
    val proof=groovy.json.JsonSlurper().parse(file(path)) as Map<*,*>
    require(proof["authority"]=="human-20261026-finish-openflux-phone-publish07"&&proof["state"]=="SOURCE_READY")
    require(proof["versionCode"].toString()=="10059"&&proof["versionName"]=="0.7")
}
providers.gradleProperty("p3p4CandidateReceipt").orNull?.let {path->
    require(!providers.gradleProperty("nextIndependentReceipt").isPresent)
    val input=file(path)
    val sha=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    require(sha==providers.gradleProperty("p3p4CandidateSha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(input) as Map<*,*>
    require(proof["authority"]=="human-20261023-finish-current-fixes-then-p3-p4"&&proof["publish"]==false)
    require(listOf("sourceReady","allowCommonVerification","allowApkBuild").all {proof[it]==true})
}
providers.gradleProperty("nextIndependentReceipt").orNull?.let {path->
    val input=file(path)
    val sha=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    require(sha==providers.gradleProperty("nextIndependentSha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(input) as Map<*,*>
    require(proof["state"]=="INDEPENDENT_SOURCE_READY"&&proof["verificationScope"]=="independent-six-only"&&proof["publish"]==false)
    require(proof["candidateVersionCode"].toString()=="10048"&&proof["candidateVersionName"]=="0.6.2-candidate.1")
    require(listOf("sourceReady","readyForBuild","allowCommonVerification","allowApkBuild").all {proof[it]==true})
}
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
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        // Enable only after the first-party TLS service has been deployed and checked.
        buildConfigField("boolean", "DOWNLOAD_SOURCE_REPORTING", (providers.gradleProperty("downloadSourceReporting").orNull == "true").toString())
        buildConfigField("boolean", "OFFLINE_EDITION", providers.gradleProperty("offlineAssets").isPresent.toString())
        val routerPins=nativeRouterInput?.second.orEmpty().entries.joinToString(",") {"${it.key}=${it.value}"}
        buildConfigField("String","NATIVE_ROUTER_PINS","\"$routerPins\"")
        val nativeRouterProxy=nativeRouterInput?.first?.let {root->supportedClientAbis.all {abi->
            File(root,"$abi/libmcrf_router.so").readBytes().toString(Charsets.ISO_8859_1).contains("MCRF_BS_PROXY_READY ")
        }}==true
        buildConfigField("boolean","NATIVE_ROUTER_PROXY",nativeRouterProxy.toString())
        applicationId = "org.mcrf.client"
        minSdk = 29
        targetSdk = 34
        versionCode = when {
            mobileBetaBuild -> 10178
            intermediateBuildCode!=null -> intermediateBuildCode
            providers.gradleProperty("release08Receipt").isPresent -> 10065
            providers.gradleProperty("release073Receipt").isPresent -> 10063
            providers.gradleProperty("release072Receipt").isPresent -> 10062
            providers.gradleProperty("openflux016PublicReceipt").isPresent -> 10061
            providers.gradleProperty("openflux016Receipt").isPresent -> 10060
            providers.gradleProperty("release07Receipt").isPresent -> 10059
            providers.gradleProperty("p3p4CandidateReceipt").isPresent -> 10058
            providers.gradleProperty("nextIndependentReceipt").isPresent -> 10048
            providers.gradleProperty("p2UiCorrectionReceipt").isPresent -> 10027
        providers.gradleProperty("p2ExpansionSourceReadyReceipt").isPresent -> 10043
            providers.gradleProperty("p2SourceReadyReceipt").isPresent -> 10025
            else -> 10065
        }
        versionName = when {
            mobileBetaBuild -> "2.0.0-beta.10178"
            intermediateBuildCode!=null -> "2.0-preview.$intermediateBuildCode"
            providers.gradleProperty("release08Receipt").isPresent -> "2.0"
            providers.gradleProperty("release073Receipt").isPresent -> "0.7.3"
            providers.gradleProperty("release072Receipt").isPresent -> "0.7.2"
            providers.gradleProperty("openflux016PublicReceipt").isPresent -> "0.7.1"
            providers.gradleProperty("openflux016Receipt").isPresent -> "0.7.1-candidate.1"
            providers.gradleProperty("release07Receipt").isPresent -> "0.7"
            providers.gradleProperty("p3p4CandidateReceipt").isPresent -> "0.7-candidate.10"
            providers.gradleProperty("nextIndependentReceipt").isPresent -> "0.6.2-candidate.1"
            providers.gradleProperty("p2UiCorrectionReceipt").isPresent -> "0.6-p2-candidate.3"
        providers.gradleProperty("p2ExpansionSourceReadyReceipt").isPresent -> "0.6"
            providers.gradleProperty("p2SourceReadyReceipt").isPresent -> "0.6-p2-candidate.1"
            else -> "2.0"
        }
        manifestPlaceholders["debugApplicationLabel"] = if (isolatedFirstLaunchFixture) "MCRF · UI-стенд" else "MCRF"
        if(isolatedFirstLaunchFixture)testApplicationId="org.mcrf.client.onboardingfixture.test"
        if(isolatedAnimationFixture)testApplicationId="org.mcrf.client.animationfixture10.test"
        testInstrumentationRunner = if(project.findProperty("releaseSmoke")=="true") "org.mcrf.client.ReleaseSmokeInstrumentation" else "org.mcrf.client.DeviceSmokeInstrumentation"
        if(isolatedAnimationFixture)testInstrumentationRunner="org.mcrf.client.ConnectionAnimationInstrumentation"
        ndk { abiFilters += offlineClientAbis }
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
        isEnable = offlineClientAbis==supportedClientAbis
        reset()
        if(offlineClientAbis==supportedClientAbis)include(*supportedClientAbis.toTypedArray())
        isUniversalApk = offlineClientAbis==supportedClientAbis
    } }
    // Compress .so in the APK. Android extracts only the device ABI at installation.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets.getByName("test").resources.srcDir("../../fixtures")
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    sourceSets.getByName("main").java.srcDir("../../../work/steer-android-port/android-vpn/integration-helper/src/main/java")
    // The common dispatcher can be bundled. Protocol engines remain separate.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    nativeRouterInput?.first?.let {sourceSets.getByName("main").jniLibs.srcDir(it)}
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_router.so"
    sourceSets.getByName("main").assets.srcDir("../../catalogue")
    providers.gradleProperty("offlineAssets").orNull?.let {sourceSets.getByName("main").assets.srcDir(it)}
    sourceSets.getByName("androidTest").assets.srcDir("../../fixtures")
    project.findProperty("liveFixtures")?.let { sourceSets.getByName("androidTest").assets.srcDir(it.toString()) }
    sourceSets.getByName("debug").assets.srcDir("../../fixtures")
    optionalDebugFixtureRoot?.let {sourceSets.getByName("androidTest").jniLibs.srcDir(it.resolve("fixtures"))}
    dnsttDebugFixtureRoot?.let {sourceSets.getByName("androidTest").jniLibs.srcDir(it.resolve("fixtures"))}
    originalPacketFixtureRoot?.let {
        sourceSets.getByName("androidTest").jniLibs.srcDir(it.resolve("fixtures"))
        sourceSets.getByName("androidTest").assets.srcDir(it.resolve("test-assets"))
    }
    packaging.jniLibs.keepDebugSymbols += "**/liboptional_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libdnstt_testserver.so"
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_original_packet_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_bs_net_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_bs_dns_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_owned_native_dns_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_owned_native_tcp_fixture.so"
    packaging.jniLibs.keepDebugSymbols += "**/libmcrf_owned_native_udp_fixture.so"
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation(project(":primary-spi"))
    // Only transitional source/test signatures compile against libbox. Runtime and
    // JNI are installed separately, and loaded solely in a fresh primary worker.
    compileOnly(files("libs/libbox.aar"))
    androidTestCompileOnly(files("libs/libbox.aar"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.github.luben:zstd-jni:1.5.7-25@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-25")
    implementation("com.github.mwiede:jsch:0.2.24")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // SDK stubs only; the test APK requests the device's framework test library.
    // Never package these classes or add the library to the application APK.
    androidTestCompileOnly(files(androidComponents.sdkComponents.sdkDirectory.map {
        it.file("platforms/android-37.0/optional/android.test.mock.jar")
    }))
}

// Offline edition inputs are complete signed module APKs, not extracted JNI or downloaded code.
providers.gradleProperty("offlineAssets").orNull?.let {location ->
    val verifyOffline=tasks.register("verifyOfflineEngines") {
        inputs.dir(location)
        doLast {
            val root=file(location).canonicalFile
            val folder=File(root,"bundled-engines").canonicalFile
            require(folder.parentFile==root)
            val index=File(folder,"index.json")
            require(index.isFile&&index.length() in 1..16384)
            val doc=groovy.json.JsonSlurper().parse(index) as Map<*,*>
            require(doc["schema"].toString()=="1")
            val rows=(doc["packages"] as List<*>).map {it as Map<*,*>}
            require(rows.size in 2..6&&rows.map {it["kind"] to it["abi"]}.distinct().size==rows.size)
            rows.groupBy {it["abi"]}.forEach {(_,pair)->require(pair.map {it["kind"]}.toSet()==setOf("bs","sing-box"))}
            rows.forEach {row ->
                val kind=row["kind"].toString();val abi=row["abi"].toString()
                require(kind in setOf("bs","sing-box")&&abi in setOf("arm64-v8a","armeabi-v7a","x86_64"))
                val apk=File(folder,"$kind-$abi.apk").canonicalFile
                require(apk.parentFile==folder&&apk.isFile&&apk.length()==row["bytes"].toString().toLong())
                require(MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}==row["sha256"])
            }
        }
    }
    tasks.named("preBuild").configure {dependsOn(verifyOffline)}
}
