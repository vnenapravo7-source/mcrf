import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins { id("com.android.application") }
// A release promotion requires the separately verified completed phone/runtime
// receipt. Merely supplying a historical native stage never enables publishing.
val historicalReleaseAllowed=providers.gradleProperty("historicalCsqttReleaseReceipt").orNull?.let {path->
    val receipt=file(path)
    val digest=MessageDigest.getInstance("SHA-256").digest(receipt.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
    require(digest==providers.gradleProperty("historicalCsqttReleaseSha256").orNull)
    val proof=groovy.json.JsonSlurper().parse(receipt) as Map<*,*>
    require(proof["state"]=="P2_RELEASE_PROMOTION_READY"&&proof["versionCode"].toString()=="10043"&&proof["versionName"]=="0.6")
    require((proof["protocolTrafficPassed"] as List<*>).map {it.toString()}.sorted()==listOf("2.0","2.1.5","2.1.9","2.5"))
    require(proof["managementChecks"].toString()=="5"&&proof["accordionChecks"].toString()=="7")
    true
}?:false
abstract class StageEngineJni:DefaultTask(){
    @get:InputDirectory abstract val inputDirectory:org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory:org.gradle.api.file.DirectoryProperty
    @get:Input abstract val libraries:org.gradle.api.provider.ListProperty<String>
    @TaskAction fun stage(){project.sync {from(inputDirectory);into(outputDirectory);libraries.get().forEach {include("*/$it")}}}
}
abstract class StageEngineTelegramNotices:DefaultTask(){
    @get:InputDirectory abstract val inputDirectory:org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory:org.gradle.api.file.DirectoryProperty
    @TaskAction fun stage(){project.sync{from(inputDirectory){include("tgws-LICENSE","tgws-go-LICENSE","tgws-x-net-LICENSE");into("licenses")};into(outputDirectory)}}
}
abstract class StageEngineProviderNotices:DefaultTask(){
    @get:InputDirectory abstract val inputDirectory:org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory:org.gradle.api.file.DirectoryProperty
    @TaskAction fun stage(){project.sync{from(inputDirectory){include("OpenFlux-LICENSE");into("licenses")};into(outputDirectory)}}
}
abstract class StageEngineCsqtt25Notices:DefaultTask(){
    @get:InputDirectory abstract val inputDirectory:org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory:org.gradle.api.file.DirectoryProperty
    @TaskAction fun stage(){project.sync{from(inputDirectory){include("CSQTT-LICENSE","qwdtt-LICENSE","swg-panel-reference.json");into("licenses")};into(outputDirectory)}}
}
abstract class StageEngineSwgNotices:DefaultTask(){
    @get:InputDirectory abstract val inputDirectory:org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory:org.gradle.api.file.DirectoryProperty
    @TaskAction fun stage(){project.sync{from(inputDirectory){include("wdtt-LICENSE","qwdtt-LICENSE");into("licenses")};into(outputDirectory)}}
}
abstract class StageEngineS3Notices:DefaultTask(){
    @get:InputDirectory abstract val inputDirectory:org.gradle.api.file.DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory:org.gradle.api.file.DirectoryProperty
    @TaskAction fun stage(){project.sync{from(inputDirectory){include("**/*");into("licenses")};into(outputDirectory)}}
}
val optionalKinds=setOf("xray","tlscore")
val primaryKinds=setOf("singbox","steer")
val telegramGate=providers.gradleProperty("tgwsNativeGate").orNull=="true"
val telegramNative=file("../app/src/main/jniLibs")
if(telegramGate){
    val receipt=groovy.json.JsonSlurper().parse(file("EngineUpdateTelegramStage.json")) as Map<*,*>
    require(receipt["version"].toString()=="1"&&receipt["kind"]=="tgws"&&receipt["api"].toString()=="1"&&receipt["cli"]=="port-v1"&&receipt["repository"]=="y0sy4/telegram-proxy"&&receipt["commit"]=="cc6b7928e7306509bdeefbb6e0e29f2a982bdb48")
    val files=(receipt["files"] as List<*>).map{it as Map<*,*>}
    require(files.size==3&&files.map{it["abi"]}.toSet()==setOf("arm64-v8a","armeabi-v7a","x86_64"))
    files.forEach{row->val binary=telegramNative.resolve("${row["abi"]}/libtgws.so")
        require(binary.isFile&&binary.length()==row["bytes"].toString().toLong())
        val hash=MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
        require(hash==row["sha256"]){"TG WS existing native differs from reviewed stage receipt"}
    }
    val notices=(receipt["licenses"] as List<*>).map{it as Map<*,*>}
    require(notices.size==3&&notices.map{it["file"]}.toSet()==setOf("tgws-LICENSE","tgws-go-LICENSE","tgws-x-net-LICENSE"))
    notices.forEach{row->val name=row["file"].toString()
        require(name in setOf("tgws-LICENSE","tgws-go-LICENSE","tgws-x-net-LICENSE"))
        val input=file("../app/src/main/assets/licenses/$name")
        require(input.isFile&&input.length()==row["bytes"].toString().toLong())
        require(MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==row["sha256"])
    }
}
val primaryStage=providers.gradleProperty("primaryCoreStage").orNull?.let {file(it)}
val primaryGate=providers.gradleProperty("primaryCoreNativeGate").orNull=="true"
val primaryDescriptors=if(primaryStage!=null){
    val primaryDirectory=requireNotNull(primaryStage)
    require(primaryGate){"Primary modules require explicit coordinator stage gate"}
    val receipt=groovy.json.JsonSlurper().parse(primaryDirectory.resolve("receipt.json")) as Map<*,*>
    require(receipt["version"].toString()=="1")
    (receipt["modules"] as Map<*,*>).also{modules->
    require(modules.isNotEmpty()&&modules.keys.all{it in setOf("sing-box","steer")})
    primaryKinds.filter{(if (it == "singbox") "sing-box" else it) in modules}.forEach {flavor->
        val id = if (flavor == "singbox") "sing-box" else "steer"
        val d=modules[id] as Map<*,*>
        val api = if (flavor == "singbox") "1" else "2"
        val entry = if (flavor == "singbox") "org.mcrf.engines.singbox.SingBoxEngine" else "org.mcrf.engines.steer.SteerEngine"
        require(d["spi"].toString()=="1"&&d["api"].toString()==api&&d["entry"]==entry)
        require(d["versionCode"].toString().toInt()>0&&d["moduleVersion"].toString().isNotBlank())
        require(d["sourceSha256"].toString().matches(Regex("[0-9a-f]{64}"))&&d["sourceRepository"].toString().isNotBlank()&&d["sourceVersion"].toString().isNotBlank())
        val abis=(d["abis"] as List<*>).map{it.toString()}
        val allowed = if (flavor == "singbox") setOf("arm64-v8a","armeabi-v7a","x86_64") else setOf("arm64-v8a","x86_64")
        require(abis.isNotEmpty()&&abis.distinct().size==abis.size&&allowed.containsAll(abis))
        val artifacts=d["artifacts"] as List<*>
        require(artifacts.size==2)
        listOf("runtime.aar","adapter.aar").forEach{name->
            val a=artifacts.map{it as Map<*,*>}.single{it["file"]==name}
            val input=primaryDirectory.resolve("$id/$name")
            require(input.isFile&&input.length()==a["bytes"].toString().toLong())
            val sha=MessageDigest.getInstance("SHA-256");input.inputStream().use{stream->val b=ByteArray(65536);while(true){val n=stream.read(b);if(n<0)break;sha.update(b,0,n)}}
            require(a["sha256"]==sha.digest().joinToString(""){"%02x".format(it.toInt()and 255)}){"Primary AAR digest mismatch"}
            ZipFile(input).use {zip->
                val native=zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("jni/")}.map{it.name}.toSet()
                val library = if (flavor == "singbox") "libbox.so" else "libsteer_vpn.so"
                // P049_TLS_SHARED_BEGIN
                val tls49=d["tls49"] as? Map<*,*>
                val helperHashes=tls49?.get("abiSha256") as? Map<*,*>
                if(tls49!=null) {
                    require(flavor=="steer"&&tls49.keys==setOf("library","abiSha256")&&tls49["library"]=="libmcrf_utls49.so")
                    require(helperHashes!=null&&helperHashes.keys==abis.toSet()&&helperHashes.values.all{it.toString().matches(Regex("[0-9a-f]{64}"))})
                }
                val libraries=setOf(library)+(if(tls49!=null)setOf("libmcrf_utls49.so") else emptySet<String>())
                require(native == if (name == "runtime.aar") abis.flatMap{abi->libraries.map{"jni/$abi/$it"}}.toSet() else emptySet<String>()){"Unexpected primary native payload"}
                if(name=="runtime.aar"&&tls49!=null)abis.forEach{abi->
                    val helper=requireNotNull(zip.getEntry("jni/$abi/libmcrf_utls49.so"))
                    val hash=zip.getInputStream(helper).use{MessageDigest.getInstance("SHA-256").digest(it.readBytes())}
                        .joinToString(""){"%02x".format(it.toInt()and 255)}
                    require(helperHashes!![abi]==hash){"Steer TLS helper digest mismatch"}
                }
                // P049_TLS_SHARED_END
                val classes=requireNotNull(zip.getEntry("classes.jar"))
                val names=zip.getInputStream(classes).use{stream->ZipInputStream(stream).use{jar->buildSet<String>{while(true){val item=jar.nextEntry?:break;add(item.name)}}}}
                if(name=="adapter.aar")require(entry.replace('.','/')+".class" in names)
                require(names.none{it.startsWith("org/mcrf/engine/spi/v1/")&&it.endsWith(".class")}){"SPI must come from host, not duplicated by runtime or adapter"}
            }
        }
        val notices=d["licenses"] as List<*>
        require(notices.isNotEmpty()){"Primary core source/license notices required"}
        notices.forEach{item->val notice=item as Map<*,*>;val name=notice["file"].toString()
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            val input=primaryDirectory.resolve("$id/licenses/$name")
            require(input.isFile&&input.length()==notice["bytes"].toString().toLong())
            val sha=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            require(sha==notice["sha256"])
        }
    }}
}else emptyMap<Any,Any>()
val csqtt25Stage=providers.gradleProperty("csqtt25Stage").orNull?.let{file(it)}
val csqtt25Gate=providers.gradleProperty("csqtt25CandidateGate").orNull=="true"
csqtt25Stage?.let {root->
    require(csqtt25Gate){"CSQTT2.5 requires an explicit frozen native stage"}
    val receipt=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    require(receipt["kind"]=="csqtt25"&&receipt["state"]=="COMPILED_NOT_EXECUTED"&&receipt["sourceStable"]==true)
    val artifacts=(receipt["artifacts"] as List<*>).map{it as Map<*,*>}
    require(artifacts.size==2&&artifacts.all{it["abi"]=="arm64-v8a"})
    listOf("libcsqtt25.so","libcsqtt25_transport.so").forEach{name->
        val row=artifacts.single{it["file"].toString().replace('\\','/').substringAfterLast('/')==name}
        val input=root.resolve("arm64-v8a/$name")
        require(input.isFile&&input.length()==row["bytes"].toString().toLong())
        val hash=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
        require(hash==row["sha256"]){"CSQTT2.5 stage digest mismatch"}
        if(name=="libcsqtt25_transport.so")require(hash=="9dad51554bafb2e6cf386a48cc278fb08c434a78ece40baff00fb51f203b237c"){"Wrong swg-panel 2.5.1 generation/ABI"}
    }
    listOf("CSQTT-LICENSE","qwdtt-LICENSE").forEach{require(root.resolve("licenses/$it").isFile)}
}
val csqttLegacyStage=providers.gradleProperty("csqttLegacyStage").orNull?.let{file(it)}
csqttLegacyStage?.let {root->
    require(providers.gradleProperty("csqttLegacyCandidateGate").orNull=="true"){"Legacy native requires explicit frozen pairing gate"}
    val receipt=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    require(receipt["kind"]=="csqtt"&&receipt["state"]=="COMPILED_NOT_EXECUTED"&&receipt["sourceStable"]==true&&receipt["captchaWaiterCapability"]=="csqtt-id48-v1")
    val artifacts=(receipt["artifacts"] as List<*>).map{it as Map<*,*>}
    require(artifacts.size==6)
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach{abi->
        listOf("libqwdtt.so","libcsqtt_transport.so").forEach{name->
            val row=artifacts.single{it["abi"]==abi&&it["file"].toString().replace('\\','/').substringAfterLast('/')==name}
            val input=root.resolve("$abi/$name")
            require(input.isFile&&input.length()==row["bytes"].toString().toLong())
            require(MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==row["sha256"]){"Paired legacy native digest mismatch"}
        }
    }
    listOf("CSQTT-LICENSE","qwdtt-LICENSE").forEach{require(root.resolve("licenses/$it").isFile)}
}
val swgStages=listOf("qwdtt","wdtt").mapNotNull {kind->providers.gradleProperty("swg${kind.replaceFirstChar{it.uppercase()}}Stage").orNull?.let {kind to file(it)}}.toMap()
swgStages.forEach{(kind,root)->
    require(providers.gradleProperty("swgNativeCandidateGate").orNull=="true"){"Standalone native requires explicit frozen stage gate"}
    val receipt=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    require(receipt["kind"]==kind&&receipt["state"]=="COMPILED_NOT_EXECUTED"&&receipt["sourceStable"]==true)
    val artifacts=(receipt["artifacts"] as List<*>).map{it as Map<*,*>};require(artifacts.size==3)
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach{abi->
        val row=artifacts.single{it["abi"]==abi&&it["file"].toString().replace('\\','/').substringAfterLast('/')=="lib$kind.so"}
        val input=root.resolve("$abi/lib$kind.so")
        require(input.isFile&&input.length()==row["bytes"].toString().toLong())
        require(MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==row["sha256"]){"Standalone native digest mismatch"}
    }
    require(root.resolve("licenses/$kind-LICENSE").isFile)
}
val historicalKinds=setOf("csqtt20","csqtt215")
val historicalStages=historicalKinds.mapNotNull {kind->providers.gradleProperty("${kind}Stage").orNull?.let {kind to file(it)}}.toMap()
val historicalDescriptors=historicalStages.mapValues {(kind,root)->
    require(providers.gradleProperty("historicalCsqttCandidateGate").orNull=="true"){"Historical CSQTT requires an explicit frozen stage"}
    val d=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    val generation = if (kind == "csqtt20") "2.0" else "2.1.5"
    val revision = if (kind == "csqtt20") "2ff8a1a4fbe031fb76b799e172dce25cfe1ca942" else "de7afc23cec7481f98845bf24fc8943af19b4cf8"
    require(d["state"]=="HISTORICAL_ENGINE_INPUTS_READY"&&d["sourceStable"]==true&&d["kind"]==kind&&d["generation"]==generation&&d["revision"]==revision)
    fun digest(file:File):String {require(file.isFile&&!Files.isSymbolicLink(file.toPath()));return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}}
    require(digest(root.resolve("wrapper-receipt.json"))==d["wrapperReceiptSha256"]&&digest(root.resolve("native-receipt.json"))==d["nativeReceiptSha256"])
    val wrapper=groovy.json.JsonSlurper().parse(root.resolve("wrapper-receipt.json")) as Map<*,*>
    require(wrapper["kind"]=="csqtt"&&wrapper["sourceStable"]==true&&wrapper["state"]=="COMPILED_NOT_EXECUTED"&&wrapper["processGroupContract"]==1&&wrapper["turnHashPoolContract"]==1&&wrapper["captchaWaiterCapability"]=="csqtt-id48-v1")
    val native=groovy.json.JsonSlurper().parse(root.resolve("native-receipt.json")) as Map<*,*>
    require(native["engine"]==kind&&native["generation"]==generation&&native["revision"]==revision&&native["state"]=="COMPILED_NOT_EXECUTED"&&native["sourceStable"]==true)
    val abis=setOf("arm64-v8a","armeabi-v7a","x86_64")
    val rows=(d["artifacts"] as List<*>).map {it as Map<*,*>}
    val paths=abis.flatMap {abi->listOf("$abi/lib$kind.so","$abi/libcsqtt_transport.so")}.toSet()
    require(rows.size==6&&rows.map {it["path"]}.toSet()==paths)
    rows.forEach {row->
        val file=root.resolve(row["path"].toString());require(file.length()==row["bytes"].toString().toLong()&&digest(file)==row["sha256"])
        val rust=row["path"].toString().endsWith("/libcsqtt_transport.so")
        val original=((if(rust)native else wrapper)["artifacts"] as List<*>).map {it as Map<*,*>}.single {it["abi"]==row["abi"]&&(rust||it["file"].toString().replace('\\','/').endsWith("/libqwdtt.so"))}
        require(original["sha256"]==row["sha256"]&&original["bytes"].toString()==row["bytes"].toString())
    }
    require(root.resolve("licenses/CSQTT-LICENSE").isFile&&root.resolve("licenses/qwdtt-LICENSE").isFile)
    d
}
val engineKinds=listOf("csqtt","csqtt25","qwdtt","wdtt","openflux","olcrtc","dnstt","warpscout","tgws","s3bypass")+historicalKinds+optionalKinds+primaryKinds
val s3BypassStage=providers.gradleProperty("s3BypassStage").orNull?.let {file(it)}
s3BypassStage?.let {root->
    require(providers.gradleProperty("s3BypassCandidateGate").orNull=="true"){"S3 requires a frozen external stage"}
    val descriptor=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    require(descriptor["kind"]=="s3bypass"&&descriptor["state"]=="COMPILED_NOT_EXECUTED"&&descriptor["sourceStable"]==true&&descriptor["producerComplete"]==true&&descriptor["commit"]=="b54ed13e3d7473ad8c25b5c39dfe127b004db540"){"S3 native source/state mismatch"}
    val rows=(descriptor["artifacts"] as List<*>).map {it as Map<*,*>}
    require(rows.size==3)
    val notices=groovy.json.JsonSlurper().parse(root.resolve("notice-manifest.json")) as Map<*,*>
    require((notices["missingModules"] as List<*>).isEmpty()){"S3 dependency notices require review; native compilation is not package acceptance"}
    require((notices["linkedModules"] as Number).toInt()>0)
    require((notices["files"] as List<*>).map {(it as Map<*,*>)["file"]}.containsAll(listOf("s3bypass-LICENSE","S3-Bypass-app-LICENSE","go/LICENSE")))
    (notices["files"] as List<*>).forEach {item->
        val row=item as Map<*,*>;val notice=root.resolve("licenses/"+row["file"].toString()).canonicalFile
        require(notice.path.startsWith(root.resolve("licenses").canonicalPath+File.separator)&&notice.isFile)
        require(MessageDigest.getInstance("SHA-256").digest(notice.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}==row["sha256"]){"S3 notice bytes changed"}
    }
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach {abi->
        val row=rows.single {it["abi"]==abi}
        val binary=root.resolve("$abi/libs3bypass.so")
        require(row["file"]=="$abi/libs3bypass.so"&&row["elfChecked"]==true&&binary.isFile&&binary.length()==row["bytes"].toString().toLong())
        require(MessageDigest.getInstance("SHA-256").digest(binary.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}==row["sha256"]){"S3 native bytes changed"}
    }
    listOf("s3bypass-LICENSE","S3-Bypass-app-LICENSE").forEach {require(root.resolve("licenses/$it").isFile)}
}
fun moduleKind(flavor:String) = when(flavor){"tlscore"->"tls-core";"singbox"->"sing-box";else->flavor}
fun moduleLibrary(flavor:String) = if (flavor == "tlscore") "libtls_core.so" else "lib$flavor.so"
val optionalStage=providers.gradleProperty("optionalCoreStage").orNull?.let {file(it)}
val warpScannerStage=providers.gradleProperty("warpScannerStage").orNull?.let {file(it)}
warpScannerStage?.let {root ->
    require(providers.gradleProperty("warpScannerCandidateGate").orNull=="true") {"Warp candidate requires an explicit frozen gate"}
    val receipt=groovy.json.JsonSlurper().parse(root.resolve("receipt.json")) as Map<*,*>
    require(receipt["stage"]=="android-warp-fast-candidate" && receipt["upstream"]=="d35fa797e702cc1e930701568c184aa99459f692" && receipt["hostTestsPassed"]==true)
    require(receipt["durabilityEchoes"].toString()=="10" && receipt["candidateBudgetQuick"].toString()=="2" && receipt["candidateBudgetExtended"].toString()=="8")
    val rows=(receipt["files"] as List<*>).map {it as Map<*,*>};require(rows.size==3)
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach {abi ->
        val row=rows.single {it["abi"]==abi && it["kind"]=="warpscout"}
        val binary=root.resolve("candidates/$abi/libwarpscout.so")
        require(binary.isFile && binary.length()==row["bytes"].toString().toLong())
        val digest=MessageDigest.getInstance("SHA-256")
        binary.inputStream().use {input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        require(row["sha256"]==digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}) {"Warp candidate digest mismatch"}
    }
}
val optionalGate=providers.gradleProperty("optionalCoreNativeGate").orNull=="true"
val optionalCommits=mapOf("xray" to "e662c22ab1096883c4df200a0e8068a89da04df1","tlscore" to "ebe0747b41823e02335ad3c7ae531bc577e7bc75")
if(optionalStage!=null){
    val candidateRoot=requireNotNull(optionalStage)
    require(optionalGate){"Optional modules require the coordinator native gate; no implicit promotion"}
    val receipt=groovy.json.JsonSlurper().parse(candidateRoot.resolve("receipt.json")) as Map<*,*>
    require(receipt["api"].toString()=="1")
    val descriptors=receipt["descriptors"] as Map<*,*>
    val files=receipt["files"] as List<*>
    optionalKinds.forEach {flavor->
        val kind=moduleKind(flavor);val descriptor=descriptors[kind] as Map<*,*>
        val expectedIngress = if (flavor == "xray") "authenticated-loopback-socks5-tcp-udp" else "authenticated-loopback-socks5-tcp"
        require(descriptor["api"].toString()=="1"&&descriptor["kind"]==kind&&descriptor["upstream"]==optionalCommits[flavor]&&descriptor["ingress"]==expectedIngress)
        listOf("arm64-v8a","armeabi-v7a","x86_64").forEach {abi->
            val entry=files.map{it as Map<*,*>}.single{it["kind"]==kind&&it["abi"]==abi}
            val binary=candidateRoot.resolve("candidates/$kind/$abi/${moduleLibrary(flavor)}")
            require(binary.isFile&&entry["bytes"].toString().toLong()==binary.length())
            val hash=MessageDigest.getInstance("SHA-256");binary.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
            require(entry["sha256"]==hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}){"Optional stage SHA mismatch for $kind/$abi"}
        }
    }
}
val openfluxLock=groovy.json.JsonSlurper().parse(file("../transports/transport-lock.json")) as Map<*,*>
val openfluxSource=openfluxLock["openflux"] as Map<*,*>
val openfluxCandidate=providers.gradleProperty("openfluxCandidate").orNull?.let {file(it)}
val openfluxPromotion=providers.gradleProperty("openfluxPromotionStage").orNull?.let {file(it)}
val openfluxAck=providers.gradleProperty("openfluxAckStage").orNull?.let {path->
    require(File(path).isAbsolute){"ACK stage must be an absolute frozen stage path"};file(path)
}
val openfluxProviderV1=providers.gradleProperty("openfluxProviderStage").orNull?.let{path->
    require(File(path).isAbsolute){"Provider diagnostic stage must be an absolute frozen path"};file(path)
}
val openfluxProviderV2=providers.gradleProperty("openfluxProviderV2Stage").orNull?.let{path->
    require(File(path).isAbsolute){"Provider V2 stage must be an absolute frozen path"};file(path)
}
val openfluxProviderV3=providers.gradleProperty("openfluxProviderV3Stage").orNull?.let{path->
    require(File(path).isAbsolute){"Provider V3 stage must be an absolute frozen path"};file(path)
}
require(listOf(openfluxProviderV1,openfluxProviderV2,openfluxProviderV3).count{it!=null}<=1){"Provider V1/V2/V3 stages are mutually exclusive"}
val openfluxProvider=openfluxProviderV3?:openfluxProviderV2?:openfluxProviderV1
val openfluxProviderNumber=if(openfluxProviderV3!=null)3 else if(openfluxProviderV2!=null)2 else if(openfluxProviderV1!=null)1 else 0
if(openfluxProvider!=null){
    val v2=openfluxProviderV2!=null
    val v3=openfluxProviderV3!=null
    require(providers.gradleProperty(if(v3)"openfluxProviderV3Gate" else if(v2)"openfluxProviderV2Gate" else "openfluxProviderGate").orNull=="true"&&openfluxAck==null&&openfluxPromotion==null&&openfluxCandidate==null){"Provider diagnostics require explicit gate and exclusive stage"}
    val stage=requireNotNull(openfluxProvider)
    val receipt=groovy.json.JsonSlurper().parse(stage.resolve(if(v3)"provider-diagnostic-v3-candidate.json" else if(v2)"provider-diagnostic-v2-candidate.json" else "provider-diagnostic-candidate.json")) as Map<*,*>
    require(receipt["version"].toString()==openfluxProviderNumber.toString())
    require(receipt["abi"]=="arm64-v8a"&&receipt["upstream"]=="p1neappleXpress/OpenFlux")
    require(receipt["sourceVersion"]=="v0.7.3"&&receipt["sourceCommit"]=="f12e981629a18c8c01007d59ac002254c02972bb")
    require(receipt["sourceOverlay"]==(if(v3)"mcrf-provider-stage.3" else if(v2)"mcrf-provider-stage.2" else "mcrf-mailru-ack.1+mcrf-provider-stage.1"))
    if(v2||v3){
        require(receipt["basePatchSha256"]=="4764e6add52a853c0f5f9534eb2461d3bb8070b9a7a227f15848c5c840885fc6")
        require(receipt["patchSha256"]==(if(v3)"eb444448beb44382d7bb59677a107be1b19f5be3d87ae299a9c4817970a679e7" else "25ba2ab139cb0be27d5349e7be8429da14c94fa83b76c613e4e9d56786f677e5"))
        if(v3)require(receipt["v2PatchSha256"]=="25ba2ab139cb0be27d5349e7be8429da14c94fa83b76c613e4e9d56786f677e5")
    }else require(receipt["patchSha256"]=="4764e6add52a853c0f5f9534eb2461d3bb8070b9a7a227f15848c5c840885fc6")
    val cli=receipt["cli"] as Map<*,*>
    require(cli["private"]=="--mcrf-diagnostic-config-v$openfluxProviderNumber"&&cli["prefix"]=="MCRF_PROVIDER_STAGE_V$openfluxProviderNumber "&&cli["defaultDiagnostics"]==false)
    val metadata=receipt["proposedMetadata"] as Map<*,*>
    require(metadata["scriptUpdater"].toString()=="0"&&metadata["org.mcrf.engines.provider-diagnostic"].toString()==openfluxProviderNumber.toString()&&metadata["org.mcrf.engines.provider-diagnostic-cli"]=="diagnostic-config-v$openfluxProviderNumber")
    if(v2||v3){val code=if(v3)10 else 9;require(metadata["moduleVersionCode"].toString()==code.toString()&&metadata["moduleVersion"]=="0.7.3-mcrf-diagnostic.$code"&&metadata["package"]=="org.mcrf.engines.openflux.diagnostic")}
    fun checkProviderFile(relative:String,marked:Map<*,*>,expected:String,bytes:Long){
        val input=stage.resolve(relative)
        require(input.isFile&&input.length()==bytes&&marked["bytes"].toString().toLong()==bytes&&marked["sha256"]==expected)
        val digest=MessageDigest.getInstance("SHA-256")
        input.inputStream().use{stream->val b=ByteArray(65536);while(true){val n=stream.read(b);if(n<0)break;digest.update(b,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==expected){"Provider diagnostic artifact differs from reviewed stage"}
    }
    checkProviderFile("arm64-v8a/libopenflux.so",receipt["native"] as Map<*,*>,if(v3)"26af028a7283d9f73a17c8ae04a94ee357bbd58641b0b0f1434aceddce5f3c53" else if(v2)"759245905e7d0ffa1cc3ed237a88aac5250c8819f8650035b32767e250747fea" else "e687063d433e4e93f128a59de4af56356632cdd8da52198e6c7626aa71812518",if(v3)27439984L else if(v2)27433616L else 27428656L)
    val license=receipt["license"] as Map<*,*>;require(license["file"]=="OpenFlux-LICENSE")
    checkProviderFile("OpenFlux-LICENSE",license,"230184f60bae2feaf244f10a8bac053c8ff33a183bcc365b4d8b876d2b7f4809",35823L)
    require(stage.walkTopDown().filter{it.isFile&&it.name.endsWith(".so")}.map{it.relativeTo(stage).invariantSeparatorsPath}.toSet()==setOf("arm64-v8a/libopenflux.so")){"Provider diagnostic stage must contain ARM64 only"}
}
if(openfluxAck!=null){
    require(providers.gradleProperty("openfluxAckGate").orNull=="true"&&openfluxCandidate==null&&openfluxPromotion==null){"ACK stage requires explicit coordinator gate and cannot mix other OpenFlux stages"}
    val stage=requireNotNull(openfluxAck)
    val marker=groovy.json.JsonSlurper().parse(stage.resolve("mailru-ack-candidate.json")) as Map<*,*>
    require(marker["version"].toString()=="1"&&marker["tag"]=="v0.7.3"&&marker["commit"]=="f12e981629a18c8c01007d59ac002254c02972bb"&&marker["upstream"]=="p1neappleXpress/OpenFlux")
    require(marker["overlay"]=="mcrf-mailru-ack.1"&&marker["moduleVersionCode"].toString()=="7"&&marker["scriptUpdater"].toString()=="0"&&marker["cli"]=="--mcrf-config")
    val overlays=mapOf("mailru_ack.go" to "84df42c087241e82cb5ad8839feb4d32e8c889f73c49445f64e58ed69d1c9799","mailru_ack_test.go" to "09506a8725c6b51574bf0d73e87b12b87e151f03b1c7d9d15f8d4809c16dce5c","mailru_ack.patch" to "eb3884cc313cef53f1b4837bcd46293d4e7e3be21f185d5dd825081278e05ef2")
    require(marker["overlays"]==overlays){"ACK overlay receipt differs from reviewed source"}
    val pins=mapOf("arm64-v8a" to ("6ace759104f6b9d50986b9efe642aa885f11a071551bd1bb243751b51a10582d" to 27420048L),"armeabi-v7a" to ("9a6f1a09bfbc65c7a0d19e3db394f20aaa5543ff8dab1a132542cb42bf0bb0ec" to 26422836L),"x86_64" to ("e6bcba87b631a85ce5294070c23270069d9512dc4563430790908b97593a02ee" to 29111784L))
    val marked=marker["files"] as Map<*,*>
    require(marked.keys==pins.keys)
    pins.forEach{(abi,pin)->
        val row=marked[abi] as Map<*,*>;val binary=stage.resolve("$abi/libopenflux.so")
        require(binary.isFile&&binary.length()==pin.second&&row["bytes"].toString().toLong()==pin.second&&row["sha256"]==pin.first)
        val digest=MessageDigest.getInstance("SHA-256")
        binary.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==pin.first){"ACK native differs from reviewed stage"}
    }
}
if(openfluxPromotion!=null){
    require(providers.gradleProperty("openfluxPromotionGate").orNull=="true"&&openfluxCandidate==null){"Fresh OpenFlux requires explicit coordinator gate and cannot mix candidate/promotion"}
    val stage=requireNotNull(openfluxPromotion)
    val marker=groovy.json.JsonSlurper().parse(stage.resolve("script-updater-capability.json")) as Map<*,*>
    require(marker["version"]=="v0.7.3"&&marker["commit"]=="f12e981629a18c8c01007d59ac002254c02972bb"&&marker["interface"].toString()=="1"&&marker["scriptUpdater"].toString()=="1")
    val pins=mapOf("arm64-v8a" to "3bc4d237b183b44bc50906416720fae3f46173c6394d213a029961fff790686d","armeabi-v7a" to "7ffd9ee6fd913491ca3a47961672058890c0b11f93cf4cee8fe210a448800957","x86_64" to "82e853e6b6020723d5fbd152a6700da9303a95ca3cf48921616f9631cc11f495")
    val marked=marker["sha256"] as Map<*,*>
    pins.forEach{(abi,expected)->
        val binary=stage.resolve("$abi/libopenflux.so");require(binary.isFile)
        val digest=MessageDigest.getInstance("SHA-256")
        binary.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        require(marked[abi]==expected&&digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==expected){"Fresh OpenFlux differs from reviewed immutable native stage"}
    }
}
val openfluxVersion=if(openfluxProvider!=null||openfluxPromotion!=null||openfluxAck!=null) "v0.7.3" else if(openfluxCandidate==null)openfluxSource["version"].toString() else providers.gradleProperty("openfluxVersion").get()
val openfluxCommit=if(openfluxProvider!=null||openfluxPromotion!=null||openfluxAck!=null) "f12e981629a18c8c01007d59ac002254c02972bb" else if(openfluxCandidate==null)openfluxSource["commit"].toString() else providers.gradleProperty("openfluxCommit").get()
require(openfluxVersion.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))&&openfluxCommit.matches(Regex("[0-9a-f]{40}")))
val openfluxScriptUpdater=openfluxCandidate?.resolve("script-updater-capability.json")?.takeIf{it.isFile}?.let {marker->
    val candidateDirectory=requireNotNull(openfluxCandidate)
    val data=groovy.json.JsonSlurper().parse(marker) as Map<*,*>
    require(data["interface"].toString()=="1"&&data["scriptUpdater"].toString()=="1"&&data["version"]==openfluxVersion&&data["commit"]==openfluxCommit)
    val digests=data["sha256"] as Map<*,*>
    listOf("arm64-v8a","armeabi-v7a","x86_64").forEach {abi->
        val hash=MessageDigest.getInstance("SHA-256");candidateDirectory.resolve("$abi/libopenflux.so").inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
        require(digests[abi]==hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}){"Script capability binary digest mismatch for $abi"}
    };1
}?:0
val engineStages=engineKinds.filter{it !in primaryKinds}.associateWith {kind->tasks.register<StageEngineJni>("prepare${kind.replaceFirstChar{it.uppercase()}}Jni"){
    inputDirectory.set(swgStages[kind]?:if(kind=="s3bypass"&&s3BypassStage!=null)s3BypassStage else if(kind=="csqtt"&&csqttLegacyStage!=null)csqttLegacyStage else if(kind=="csqtt25"&&csqtt25Stage!=null)csqtt25Stage else if(kind=="tgws")telegramNative else if(kind=="warpscout"&&warpScannerStage!=null)warpScannerStage.resolve("candidates") else if(kind in optionalKinds&&optionalStage!=null)optionalStage.resolve("candidates/${moduleKind(kind)}")else if(kind=="openflux"&&(openfluxProvider!=null||openfluxAck!=null||openfluxPromotion!=null||openfluxCandidate!=null))requireNotNull(openfluxProvider?:openfluxAck?:openfluxPromotion?:openfluxCandidate) else file("src/main/jniLibs"));outputDirectory.set(layout.buildDirectory.dir("generated/engine-jni/$kind"))
    if(kind in historicalStages)inputDirectory.set(historicalStages.getValue(kind))
    libraries.set(if(kind=="csqtt")listOf("libqwdtt.so","libcsqtt_transport.so")else if(kind in historicalKinds)listOf("lib$kind.so","libcsqtt_transport.so")else if(kind=="csqtt25")listOf("libcsqtt25.so","libcsqtt25_transport.so")else listOf(moduleLibrary(kind)))
}}
val csqtt25Notices=csqtt25Stage?.let{stage->tasks.register<StageEngineCsqtt25Notices>("prepareCsqtt25Notices"){
    inputDirectory.set(stage.resolve("licenses"));outputDirectory.set(layout.buildDirectory.dir("generated/csqtt25-assets"))
}}
val csqttLegacyNotices=csqttLegacyStage?.let{stage->tasks.register<StageEngineCsqtt25Notices>("prepareCsqttLegacyNotices"){
    inputDirectory.set(stage.resolve("licenses"));outputDirectory.set(layout.buildDirectory.dir("generated/csqtt-legacy-assets"))
}}
val historicalNotices=historicalStages.mapValues {(kind,stage)->tasks.register<StageEngineCsqtt25Notices>("prepare${kind.replaceFirstChar {it.uppercase()}}Notices"){
    inputDirectory.set(stage.resolve("licenses"));outputDirectory.set(layout.buildDirectory.dir("generated/$kind-assets"))
}}
val swgNotices=swgStages.mapValues{(kind,stage)->tasks.register<StageEngineSwgNotices>("prepare${kind.replaceFirstChar{it.uppercase()}}Notices"){
    inputDirectory.set(stage.resolve("licenses"));outputDirectory.set(layout.buildDirectory.dir("generated/$kind-assets"))
}}
val captchaMergedAssets=mutableMapOf<String,org.gradle.api.provider.Provider<org.gradle.api.file.Directory>>()
val s3Notices=s3BypassStage?.let {stage->tasks.register<StageEngineS3Notices>("prepareS3BypassNotices"){
    inputDirectory.set(stage.resolve("licenses"));outputDirectory.set(layout.buildDirectory.dir("generated/s3bypass-assets"))
}}
val telegramNotices=tasks.register<StageEngineTelegramNotices>("prepareTgwsNotices"){
    inputDirectory.set(file("../app/src/main/assets/licenses"));outputDirectory.set(layout.buildDirectory.dir("generated/tgws-assets"))
}
val providerNotices=openfluxProvider?.let{stage->tasks.register<StageEngineProviderNotices>("prepareOpenfluxProviderNotices"){
    inputDirectory.set(stage);outputDirectory.set(layout.buildDirectory.dir("generated/provider-diagnostic-assets"))
}}
android {
    namespace = "org.mcrf.engines"
    compileSdk = 34
    buildFeatures { resValues = true }
    defaultConfig { applicationId = "org.mcrf.engines"; minSdk = 29; targetSdk = 34; versionCode = 4; versionName = "0.1.0-dev.4";manifestPlaceholders["csqttProcessGroupContract"]=0;manifestPlaceholders["wdttProviderOptionsContract"]=0;manifestPlaceholders["csqtt25PanelContract"]=0;manifestPlaceholders["csqttAccountOptionsContract"]=0 }
    flavorDimensions += "engine"
    defaultConfig {manifestPlaceholders["qwdttFingerprintContract"]=0;manifestPlaceholders["wdttMasqueContract"]=0;manifestPlaceholders["turnHashPoolContract"]=0;manifestPlaceholders["wdttDualFamilyContract"]=0}
    productFlavors {
        engineKinds.forEach {kind->create(kind){dimension="engine";applicationId="org.mcrf.engines.$kind";manifestPlaceholders["engineKind"]=moduleKind(kind);manifestPlaceholders["engineLabel"]="MCRF · ${moduleKind(kind).uppercase()}"
            if(kind=="s3bypass"){versionCode=1;versionName="0.1.0-s3bypass.1"}
            if(kind=="warpscout"&&warpScannerStage!=null){versionCode=5;versionName="1.3.1-mcrf-fast.5"}
            if(kind=="csqtt25"){versionCode=1;versionName="2.5.0-mcrf-candidate.1";manifestPlaceholders["engineLabel"]="MCRF · CSQTT 2.5"}
            if(kind in historicalKinds){
                val generation = if (kind == "csqtt20") "2.0" else "2.1.5"
                versionCode=if(historicalReleaseAllowed)2 else 1;versionName="$generation-mcrf.${if(historicalReleaseAllowed)2 else 1}";manifestPlaceholders["engineLabel"]="MCRF · CSQTT $generation"
                resValue("string","mcrf_csqtt_generation",generation)
                resValue("string","mcrf_csqtt_revision",if (kind == "csqtt20") "2ff8a1a4fbe031fb76b799e172dce25cfe1ca942" else "de7afc23cec7481f98845bf24fc8943af19b4cf8")
                if(kind in historicalDescriptors){manifestPlaceholders["csqttProcessGroupContract"]=1;manifestPlaceholders["turnHashPoolContract"]=1}
            }
            if(kind=="csqtt"&&csqttLegacyStage!=null){versionCode=5;versionName="2.1.9-mcrf-captcha.5"}
            if(kind in swgStages){versionCode=5;versionName="0.1.0-captcha.5"}
            val packageStage=when(kind){"csqtt"->csqttLegacyStage;"csqtt25"->csqtt25Stage;in optionalKinds->optionalStage;else->swgStages[kind]}
            if(packageStage!=null){
                val descriptor=groovy.json.JsonSlurper().parse(packageStage.resolve("receipt.json")) as Map<*,*>
                if(descriptor["turnHashPoolContract"]==1){
                    val expected=when(kind){
                        "qwdtt"->setOf("bridge_config.go")
                        "csqtt"->setOf("csqtt_bridge.go","internal/csqtt/config.go")
                        "csqtt25"->setOf("csqtt_bridge.go","internal/csqtt/config.go","internal/csqtt25/profile.go")
                        "wdtt"->setOf("mcrf_wdtt.go","socks5_server.go","http_connect_server.go")
                        else->error("Unexpected TURN feature producer")
                    }
                    val rows=(descriptor["turnFeatureInputs"] as List<*>).map {it as Map<*,*>}
                    require(rows.size==expected.size&&rows.map {it["file"]}.toSet()==expected)
                    val manifest=(groovy.json.JsonSlurper().parse(packageStage.resolve("source-manifest.json")) as List<*>).map {it as Map<*,*>}
                    rows.forEach {row->
                        val name=row["file"] as String
                        val input=packageStage.resolve("turn-feature-inputs/$name")
                        require(input.isFile&&!Files.isSymbolicLink(input.toPath()))
                        val digest=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(digest==row["sha256"]&&manifest.single {it["path"].toString().replace('\\','/')==name}["sha256"]==digest)
                    }
                    manifestPlaceholders["turnHashPoolContract"]=1
                    if(kind=="wdtt"&&descriptor["wdttDualFamilyContract"]==1)manifestPlaceholders["wdttDualFamilyContract"]=1
                }
                providers.gradleProperty("p2ExpansionSourceReadySha256").orNull?.let {readySha->
                    val origin=groovy.json.JsonSlurper().parse(packageStage.resolve("prepared-expansion-source.json")) as Map<*,*>
                    require(descriptor["expansionAuthority"]=="human-20261021-expanded-p2-whole-source-before-common-verification"&&origin["sourceReadySha256"]==readySha&&origin["kind"]==kind)
                    val compiled=groovy.json.JsonSlurper().parse(packageStage.resolve("source-manifest.json")) as List<*>
                    fun sourceRows(rows:List<*>)=rows.map {it as Map<*,*>}.map {it["path"].toString().replace('\\','/')+"="+it["sha256"]}.sorted()
                    require(sourceRows(compiled)==sourceRows(origin["inputs"] as List<*>)){"Retained native input set differs from current frozen source"}
                }
                if(kind=="qwdtt"&&descriptor["fingerprintContract"]==1){
                    val expected=setOf("mcrf_qwdtt_fingerprint.go","bridge_config.go","creds.go","creds_vkcalls.go","profiles.go","captcha_v2.go","main.go")
                    val rows=(descriptor["fingerprintInputs"] as List<*>).map {it as Map<*,*>}
                    require(rows.size==expected.size&&rows.map {it["file"]}.toSet()==expected)
                    val manifest=(groovy.json.JsonSlurper().parse(packageStage.resolve("source-manifest.json")) as List<*>).map {it as Map<*,*>}
                    rows.forEach {row->
                        val name=row["file"] as String
                        val input=packageStage.resolve("fingerprint-inputs/$name")
                        require(input.isFile&&!Files.isSymbolicLink(input.toPath()))
                        val sha=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(sha==row["sha256"]&&manifest.single {it["path"].toString().replace('\\','/')==name}["sha256"]==sha)
                    }
                    manifestPlaceholders["qwdttFingerprintContract"]=1
                }
                if(kind=="csqtt"&&descriptor["accountOptionsContract"]==1){
                    val archive=packageStage.resolve("account-options-inputs")
                    val adapter=archive.resolve("prepare_csqtt.py")
                    require(adapter.isFile&&!Files.isSymbolicLink(adapter.toPath()))
                    val sha=MessageDigest.getInstance("SHA-256").digest(adapter.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                    require(sha==descriptor["accountAdapterSha256"]&&sha=="27ca4d970f34cedd8f44e6cf0a8dbbcc51010ee24a33f238a2850688371dbd00")
                    val rows=(descriptor["accountBridgeInputs"] as List<*>).map {it as Map<*,*>}
                    require(rows.map {it["file"]}.toSet()==setOf("csqtt_bridge.go","bridge_config.go")&&rows.size==2)
                    val manifest=(groovy.json.JsonSlurper().parse(packageStage.resolve("source-manifest.json")) as List<*>).map {it as Map<*,*>}
                    rows.forEach {row->
                        val name=row["file"] as String
                        val input=archive.resolve(name)
                        require(input.isFile&&!Files.isSymbolicLink(input.toPath()))
                        val digest=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(digest==row["sha256"]&&manifest.single {it["path"].toString().replace('\\','/')==name}["sha256"]==digest)
                    }
                    manifestPlaceholders["csqttAccountOptionsContract"]=1
                }
                if(kind=="csqtt25"){
                    require(csqtt25Stage!=null&&descriptor["packageVersionCode"].toString().toInt()>=4)
                    manifestPlaceholders["csqtt25PanelContract"]=1
                }
                if(kind=="wdtt"&&descriptor["providerOptionsContract"]==1){
                    val input=packageStage.resolve("provider-options-input.go")
                    require(input.isFile&&!Files.isSymbolicLink(input.toPath()))
                    val sha=MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                    require(sha==descriptor["providerOptionsInputSha256"])
                    val inputs=(groovy.json.JsonSlurper().parse(packageStage.resolve("source-manifest.json")) as List<*>).map {it as Map<*,*>}
                    require(inputs.single {it["path"].toString().replace('\\','/')=="mcrf_wdtt.go"}["sha256"]==sha)
                    manifestPlaceholders["wdttProviderOptionsContract"]=1
                    if(descriptor["masqueContract"]==1){
                        val main=packageStage.resolve("masque-main-input.go")
                        require(main.isFile&&!Files.isSymbolicLink(main.toPath()))
                        val digest=MessageDigest.getInstance("SHA-256").digest(main.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(digest==descriptor["masqueMainInputSha256"]&&inputs.single {it["path"].toString().replace('\\','/')=="main.go"}["sha256"]==digest)
                        manifestPlaceholders["wdttMasqueContract"]=1
                    }
                }
                if(kind in setOf("csqtt","csqtt25")&&descriptor["processGroupContract"]==1) {
                    val inputs=(descriptor["processGroupInputs"] as? List<*>)?.map {it as Map<*,*>}?:error("CSQTT group input proof missing")
                    val expected=setOf("mcrf_process_group.go","mcrf_process_group_linux.go","mcrf_process_group_other.go","mcrf_child_input.go","csqtt_bridge.go")
                    require(inputs.map {it["file"]}.toSet()==expected&&inputs.size==expected.size)
                    val manifest=(groovy.json.JsonSlurper().parse(packageStage.resolve("source-manifest.json")) as List<*>).map {it as Map<*,*>}
                    inputs.forEach {input->
                        val name=input["file"] as String
                        val file=packageStage.resolve("process-group-inputs/$name")
                        require(file.isFile&&!Files.isSymbolicLink(file.toPath()))
                        val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(sha==input["sha256"]&&manifest.single {it["path"].toString().replace('\\','/')==name}["sha256"]==sha)
                    }
                    val bridge=packageStage.resolve("process-group-inputs/csqtt_bridge.go").readText()
                    require(bridge.contains("mcrfPrepareProcessGroup()")&&bridge.contains("processOwner.await(ctx)"))
                    if(descriptor["expansionAuthority"]!=null){
                        fun sha(path:java.io.File):String {
                            require(path.isFile&&!Files.isSymbolicLink(path.toPath()))
                            return MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        }
                        val closureFile=packageStage.resolve("retained-owned-closure.json")
                        require(sha(closureFile)==descriptor["retainedOwnerClosureSha256"])
                        val closure=groovy.json.JsonSlurper().parse(closureFile) as Map<*,*>
                        require(closure["newExecution"]==false&&sha(packageStage.resolve("retained-owned-bridge.go"))==closure["oldBridgeSha256"])
                        (closure["acceptedExecution"] as? Map<*,*>)?.let {accepted->
                            val prior=file(accepted["stage"].toString())
                            val allowed=if(kind=="csqtt25")"csqtt25-captcha-3826488249184e138badc91ec899e8b8" else "csqtt-captcha-d31a14c4338e4bbb96ef5def4e8dc80e"
                            require(prior.canonicalFile.parentFile==file("D:/MCRF/Builds/Android").canonicalFile&&prior.name==allowed)
                            require(sha(prior.resolve("receipt.json"))==accepted["receiptSha256"]&&
                                sha(prior.resolve("retained-owned-closure.json"))==accepted["closureSha256"]&&
                                sha(prior.resolve("expansion-affected-tests.jsonl"))==accepted["logSha256"])
                            require(sha(prior.resolve("process-group-inputs/csqtt_bridge.go"))==closure["oldBridgeSha256"])
                        }
                        val old=packageStage.resolve("retained-owned-bridge.go").readText()
                        fun execution(body:String):String {
                            val parser=body.indexOf("func readCSQTTConfig(");val run=body.indexOf("func runCSQTTBridge(")
                            require(parser>=0&&run>parser)
                            return body.substring(0,parser)+body.substring(run)
                        }
                        var currentExecution=execution(bridge).replace("\r\n","\n")
                        if(closure["requiresAffectedBridgeRecheck"]==true){
                            val start="return fmt.Errorf(\"cannot start bundled CSQTT transport: %s\", mcrfPublicChildFailure(c, err.Error()))"
                            val wait="""
	go func() {
		waitErr := command.Wait()
		if waitErr != nil && ctx.Err() == nil && command.ProcessState != nil { fmt.Printf("[CSQTT] child failure stage=transport exit=%d\n", command.ProcessState.ExitCode()) }
		writer.Close()
		select { case <-scanDone: case <-time.After(250 * time.Millisecond): }
		close(done)
	}()
""".trimIndent().prependIndent("\t")
                            val scan="""
		// Keep only bounded, sanitized failures, never a config/challenge dump.
		if message := mcrfPublicChildFailure(c, line); message != "" {
			fmt.Printf("[CSQTT] child error: %s\n", message)
		}
""".trimIndent().prependIndent("\t\t")
                            val oldScan="\t\t// Raw transport logs may contain hashes and captcha session tokens.\n\t\t// Only explicit public lifecycle messages are emitted by this adapter."
                            val tunnel="\tif c.NormalizeTunnel != nil {\n\t\tconfig, err = c.NormalizeTunnel(config)\n\t\tif err != nil { return err }\n\t}\n"
                            require(listOf(start,wait,scan,tunnel).all {currentExecution.contains(it)}){"Only the exact reviewed diagnostics/tunnel delta may retain helper evidence"}
                            currentExecution=currentExecution.replace(tunnel,"")
                            currentExecution=currentExecution.replace(start,"return errors.New(\"cannot start bundled CSQTT transport\")")
                                .replace(wait,"\tgo func() { command.Wait(); writer.Close(); close(done) }()").replace(scan,oldScan)
                            val freshStage=requireNotNull(csqtt25Stage)
                            val fresh=groovy.json.JsonSlurper().parse(freshStage.resolve("receipt.json")) as Map<*,*>
                            val freshCases=(fresh["affectedCases"] as List<*>).map {it.toString()}
                            require(fresh["sourceStable"]==true&&sha(freshStage.resolve("expansion-affected-tests.jsonl"))==fresh["affectedLogSha256"]&&
                                freshCases.containsAll(listOf("Test25SharedBridgeWaitsForOwnedTransportAndGrandchildCleanupAfterStop","Test25SharedBridgeSendsPrivateBootstrapAndHonorsOwnedStopCleanup","Test25TunnelOptionalPolicyDoesNotBecomeRevisionOrRouting","Test25TunnelIPv6DnsAndMalformedFramesAreHandledWithoutSecrets"))){"Changed diagnostic/tunnel execution requires NEW shared-bridge and frame evidence"}
                        }
                        require(execution(old).replace("\r\n","\n")==currentExecution){"Changed owner execution cannot retain old acceptance"}
                        val helpers=(closure["helpers"] as List<*>).map {it as Map<*,*>}
                        require(helpers.size==4&&helpers.map {it["file"]}.toSet()==expected-"csqtt_bridge.go")
                        helpers.forEach {helper->require(sha(packageStage.resolve("process-group-inputs/${helper["file"]}"))==helper["sha256"])}
                        val oldProof=packageStage.resolve("retained-owned-evidence.json")
                        require(sha(oldProof)==closure["retainedReceiptSha256"])
                        val proof=groovy.json.JsonSlurper().parse(oldProof) as Map<*,*>
                        require(proof["passed"]==true&&proof["sourceStable"]==true&&(proof["cases"] as List<*>).size==8)
                    }
                    if(descriptor["priority"]==2){
                        val readySha=providers.gradleProperty("p2SourceReadySha256").orNull
                        require(readySha!=null&&descriptor["sourceReadySha256"]==readySha){"CSQTT belongs to the SAME whole-P2 source batch"}
                        val proofFile=packageStage.resolve("process-group-evidence.json")
                        val hash=MessageDigest.getInstance("SHA-256").digest(proofFile.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(hash==descriptor["processGroupEvidenceSha256"])
                        val proof=groovy.json.JsonSlurper().parse(proofFile) as Map<*,*>
                        require(proof["priority"]==2&&proof["passed"]==true&&proof["sourceStable"]==true&&proof["sourceReadySha256"]==readySha&&
                            proof["state"] in setOf("OWNED_LINUX_GROUP_AND_INPUT_PASS_NOT_ANDROID","OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED"))
                        if(proof["state"]=="OWNED_ANDROID_KERNEL_PASS_APP_UNVERIFIED")require(proof["newExecution"]==false&&proof["kernelEvidenceSha256"].toString().matches(Regex("[0-9a-f]{64}")))
                        val cases=(proof["cases"] as List<*>).map {it as String}
                        require(cases.size==8&&cases.distinct().size==8&&"TestMcrfProcessGroupLinuxRealAckAndWholeTreeRetirement" in cases)
                        val logSha=MessageDigest.getInstance("SHA-256").digest(packageStage.resolve("process-group-tests.log").readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
                        require(logSha==proof["logSha256"])
                    }
                    manifestPlaceholders["csqttProcessGroupContract"]=1
                }
                require((descriptor["packageVersionCode"]==null)==(descriptor["packageVersionName"]==null)){"Incomplete frozen package version"}
                descriptor["packageVersionCode"]?.let {value->
                    val code=value.toString().toInt()
                    val name=descriptor["packageVersionName"] as? String ?: error("Frozen stage package version name missing")
                    require(code >= if(kind=="csqtt25")1 else 5)
                    require(name.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}")))
                    versionCode=code;versionName=name
                }
            }
            if(kind in optionalKinds){manifestPlaceholders["optionalSourceCommit"]=optionalCommits.getValue(kind);manifestPlaceholders["optionalSourceRepository"] = if (kind == "xray") "throneproj/xray-core" else "throneproj/sing-box"}
            if(kind in optionalKinds){manifestPlaceholders["optionalIngress"] = if (kind == "xray") "authenticated-loopback-socks5-tcp-udp" else "authenticated-loopback-socks5-tcp"}
            if(kind in primaryKinds){
                val d=primaryDescriptors[moduleKind(kind)] as? Map<*,*>
                if(d!=null){versionCode=d["versionCode"].toString().toInt();versionName=d["moduleVersion"].toString()}
                manifestPlaceholders["primaryCoreApi"] = if (kind == "singbox") 1 else 2
                manifestPlaceholders["primaryCoreEntry"] = if (kind == "singbox") "org.mcrf.engines.singbox.SingBoxEngine" else "org.mcrf.engines.steer.SteerEngine"
                manifestPlaceholders["primaryCoreAbis"]=(d?.get("abis") as? List<*>)?.joinToString(",")?:""
                manifestPlaceholders["primarySourceRepository"]=d?.get("sourceRepository")?:""
                manifestPlaceholders["primarySourceVersion"]=d?.get("sourceVersion")?:""
                manifestPlaceholders["primarySourceSha256"]=d?.get("sourceSha256")?:""
            }
            manifestPlaceholders["openfluxSourceVersion"]=openfluxVersion;manifestPlaceholders["openfluxSourceCommit"]=openfluxCommit
            manifestPlaceholders["openfluxSourceOverlay"]=if(openfluxProvider!=null) "mcrf-provider-stage.$openfluxProviderNumber" else if(openfluxAck!=null) "mcrf-mailru-ack.1" else ""
            manifestPlaceholders["openfluxProviderDiagnostic"]=openfluxProviderNumber
            manifestPlaceholders["openfluxProviderDiagnosticCli"]=if(openfluxProvider!=null) "diagnostic-config-v$openfluxProviderNumber" else ""
            manifestPlaceholders["openfluxScriptUpdater"]=if(kind=="openflux")openfluxScriptUpdater else 0
            if(kind=="openflux"&&openfluxCandidate!=null){applicationId="org.mcrf.engines.openflux.candidate";versionCode=5;versionName="0.1.0-dev.5-candidate"}
            if(kind=="openflux"&&openfluxPromotion!=null){versionCode=6;versionName="0.7.3-mcrf.6"}
            if(kind=="openflux"&&openfluxAck!=null){versionCode=7;versionName="0.7.3-mcrf.7"}
            if(kind=="openflux"&&openfluxProvider!=null){val code=7+openfluxProviderNumber;applicationId="org.mcrf.engines.openflux.diagnostic";versionCode=code;versionName="0.7.3-mcrf-diagnostic.$code"}
        }}
    }
    sourceSets.getByName("csqtt25").manifest.srcFile("EngineUpdateCsqtt25Manifest.xml")
    historicalKinds.forEach {kind->
        val historicalManifest=file("EngineUpdateCsqttHistoricalManifest.xml")
        require(historicalManifest.isFile){"Historical CSQTT generation/source manifest must be in the frozen integration snapshot"}
        sourceSets.getByName(kind).manifest.srcFile(historicalManifest)
    }
    sourceSets.getByName("s3bypass").manifest.srcFile("EngineUpdateS3BypassManifest.xml")
    if(csqttLegacyStage!=null)sourceSets.getByName("csqtt").manifest.srcFile("EngineUpdateCsqttLegacyManifest.xml")
    swgStages.keys.forEach{kind->sourceSets.getByName(kind).manifest.srcFile("EngineUpdateSwgManifest.xml")}
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    if(openfluxProvider!=null)sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())
    optionalKinds.forEach{kind->sourceSets.getByName(kind).manifest.srcFile(file("EngineUpdateOptionalManifest.xml"));sourceSets.getByName(kind).assets.srcDir(file("../optional-cores/licenses"))}
    sourceSets.getByName("tgws").manifest.srcFile(file("EngineUpdateTelegramManifest.xml"))
    primaryKinds.forEach{kind->sourceSets.getByName(kind).manifest.srcFile(file("EngineUpdatePrimaryManifest.xml"));primaryStage?.let{stage->sourceSets.getByName(kind).assets.srcDir(stage.resolve("${moduleKind(kind)}/licenses"))}}
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = true } }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
    buildTypes { release { if(project.findProperty("testRelease") == "true") signingConfig = signingConfigs.getByName("debug") } }
}
androidComponents {
    // A candidate can never leak into a release build or the other engine packages.
    beforeVariants(selector().all()){variant->
        if(variant.productFlavors.any {it.second in historicalKinds&&(it.second !in historicalStages||(variant.buildType!="debug"&&!historicalReleaseAllowed))})variant.enable=false
        if(variant.productFlavors.any{it.second=="csqtt25"}&&(csqtt25Stage==null||!csqtt25Gate))variant.enable=false
        if(variant.productFlavors.any{it.second=="s3bypass"}&&s3BypassStage==null)variant.enable=false
        if(variant.productFlavors.any{it.second in optionalKinds}&&(optionalStage==null||!optionalGate))variant.enable=false
        if(variant.productFlavors.any{it.second in primaryKinds&&(!primaryGate||moduleKind(it.second) !in primaryDescriptors)})variant.enable=false
        if(providers.gradleProperty("p2SteerCandidate").orNull=="true"&&variant.productFlavors.any {it.second=="steer"}&&variant.buildType!="debug"&&!historicalReleaseAllowed)variant.enable=false
        if(variant.productFlavors.any{it.second=="tgws"}&&!telegramGate)variant.enable=false
        if(openfluxCandidate!=null&&(variant.buildType!="debug"||variant.productFlavors.none{it.second=="openflux"}))variant.enable=false
        if(openfluxProvider!=null&&variant.productFlavors.none{it.second=="openflux"})variant.enable=false
    }
    onVariants(selector().all()){variant->
    val kind=variant.productFlavors.first{it.first=="engine"}.second
    if(kind=="s3bypass")variant.sources.assets?.addGeneratedSourceDirectory(requireNotNull(s3Notices),StageEngineS3Notices::outputDirectory)
    if(variant.buildType=="debug"&&(kind in swgStages||kind=="csqtt"&&csqttLegacyStage!=null||kind=="csqtt25"&&csqtt25Stage!=null))
        captchaMergedAssets[kind]=variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.ASSETS)
    if(kind=="csqtt"&&csqttLegacyNotices!=null)variant.sources.assets?.addGeneratedSourceDirectory(csqttLegacyNotices,StageEngineCsqtt25Notices::outputDirectory)
    historicalNotices[kind]?.let {notices->variant.sources.assets?.addGeneratedSourceDirectory(notices,StageEngineCsqtt25Notices::outputDirectory)}
    swgNotices[kind]?.let{notices->variant.sources.assets?.addGeneratedSourceDirectory(notices,StageEngineSwgNotices::outputDirectory)}
    if(kind=="csqtt25"){
        variant.outputs.forEach{output->output.enabled.set(output.filters.any{it.filterType==com.android.build.api.variant.FilterConfiguration.FilterType.ABI&&it.identifier=="arm64-v8a"})}
        variant.sources.assets?.addGeneratedSourceDirectory(requireNotNull(csqtt25Notices),StageEngineCsqtt25Notices::outputDirectory)
    }
    if(kind=="openflux"&&openfluxProvider!=null){
        variant.outputs.forEach{output->
            output.enabled.set(output.filters.any{it.filterType==com.android.build.api.variant.FilterConfiguration.FilterType.ABI&&it.identifier=="arm64-v8a"})
        }
        variant.sources.assets?.addGeneratedSourceDirectory(requireNotNull(providerNotices),StageEngineProviderNotices::outputDirectory)
    }
    if(kind in primaryKinds){
        val descriptor=primaryDescriptors[moduleKind(kind)] as Map<*,*>
        val abis=(descriptor["abis"] as List<*>).map{it.toString()}.toSet()
        variant.outputs.forEach{output->
            if(output.filters.any{it.filterType==com.android.build.api.variant.FilterConfiguration.FilterType.ABI&&it.identifier !in abis})output.enabled.set(false)
        }
    }
    if(kind !in primaryKinds)variant.sources.jniLibs?.addGeneratedSourceDirectory(engineStages.getValue(kind),StageEngineJni::outputDirectory)
    if(kind=="tgws")variant.sources.assets?.addGeneratedSourceDirectory(telegramNotices,StageEngineTelegramNotices::outputDirectory)
    if(openfluxCandidate!=null&&variant.buildType=="debug"&&kind=="openflux"){
        val fixture=tasks.register<StageEngineJni>("prepareOpenfluxCandidateFixture"){
            inputDirectory.set(openfluxCandidate.resolve("fixtures"));outputDirectory.set(layout.buildDirectory.dir("generated/engine-fixtures/openflux"));libraries.set(listOf("libopenflux_testserver.so"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fixture,StageEngineJni::outputDirectory)
    }
}}
if(primaryStage!=null&&primaryGate){
    val primaryDirectory=requireNotNull(primaryStage)
    primaryKinds.filter{moduleKind(it) in primaryDescriptors}.forEach{kind->
        // Parent-defined contract resolves D8 references without a second SPI inside the module APK.
        dependencies.add("${kind}CompileOnly",project(":primary-spi"))
        dependencies.add("${kind}Implementation",files(primaryDirectory.resolve("${moduleKind(kind)}/runtime.aar"),primaryDirectory.resolve("${moduleKind(kind)}/adapter.aar")))
    }
}
// This reviewed Steer runtime contains Kotlin 2.2.10 and the exact annotations-13 classes.
// Exclude only redundant external copies in Steer configurations; app/singbox stay untouched.
val steerDescriptor=primaryDescriptors["steer"] as? Map<*,*>
val steerRuntimePin=(steerDescriptor?.get("artifacts") as? List<*>)?.map{it as Map<*,*>}?.singleOrNull{it["file"]=="runtime.aar"}?.get("sha256")
val reviewedFatSteerRuntimePins=setOf(
    // P049 parser-only native correction; non-JNI contents equal the accepted P1 runtime.
    "3e8141dc96ec6a924c10262c56a99eccf24ab68448653d4ab351984b9f23fd43",
    "38958fba063b2fcc8633e55aa69489585429f60c2ed5118478b3242d35bfab11",
    // dev.5 changes only the runtime manifest namespace; all 13 other entries match dev.4.
    "fdc3e44e638a8df4cd9ac5b85654148af8c5ca83e18a7e14b8ef11c2bcd2423c"
)
val p1SteerCandidate = providers.gradleProperty("p1SteerCandidate").orNull == "true"
val p2SteerCandidate = providers.gradleProperty("p2SteerCandidate").orNull == "true"
require(!(p1SteerCandidate&&p2SteerCandidate)){"Candidate priorities cannot be combined"}
require(steerDescriptor?.get("p2Candidate")!=true||p2SteerCandidate){"P2 primary stage requires its own common candidate gate"}
val candidateFatRuntimeVerified = if(p1SteerCandidate||p2SteerCandidate){
    val priority=if(p2SteerCandidate)2 else 1
    val prefix="p$priority"
    val directory=requireNotNull(primaryStage)
    val descriptor=requireNotNull(steerDescriptor)
    val gateFile=file(requireNotNull(providers.gradleProperty("${prefix}SourceReadyReceipt").orNull))
    val expectedSha=requireNotNull(providers.gradleProperty("${prefix}SourceReadySha256").orNull)
    require(expectedSha.matches(Regex("[0-9a-f]{64}"))&&gateFile.isAbsolute&&gateFile.absoluteFile.normalize()==gateFile.canonicalFile&&
        gateFile.canonicalFile.parentFile==rootProject.file("coordination").canonicalFile&&
        (if(p2SteerCandidate)gateFile.name=="p2-progress-2026-10-20.json" else gateFile.name.matches(Regex("p1-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")))&&gateFile.isFile&&gateFile.length() in 1L..262144L&&
        !Files.isSymbolicLink(gateFile.toPath()))
    val gateBytes=gateFile.readBytes()
    require(MessageDigest.getInstance("SHA-256").digest(gateBytes).joinToString(""){"%02x".format(it.toInt()and 255)}==expectedSha)
    val gate=groovy.json.JsonSlurper().parse(gateBytes) as Map<*,*>
    val nativeGateSha=descriptor["${prefix}ReadinessSha256"]
    if(nativeGateSha!=expectedSha){
        require(!p2SteerCandidate){"P2 candidate requires the exact frozen common-source gate"}
        val original=file(requireNotNull(providers.gradleProperty("p1NativeSourceReadyReceipt").orNull))
        val originalSha=requireNotNull(providers.gradleProperty("p1NativeSourceReadySha256").orNull)
        require(originalSha==nativeGateSha&&originalSha.matches(Regex("[0-9a-f]{64}"))&&
            original.canonicalFile==original.absoluteFile.normalize()&&original.parentFile.canonicalFile==directory.canonicalFile&&
            original.name=="original-p1-readiness.json"&&original.isFile&&original.length() in 1L..262144L&&!Files.isSymbolicLink(original.toPath()))
        val bytes=original.readBytes()
        require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}==originalSha)
        val before=groovy.json.JsonSlurper().parse(bytes) as Map<*,*>
        val deviceMetadata=setOf("allowCommonDeviceVerification","commonVerificationReceipt","deviceVerificationAuthority","humanDeviceReply","phase")
        require(before.filterKeys {it !in deviceMetadata}==gate.filterKeys {it !in deviceMetadata})
    }
    require(primaryGate&&descriptor["${prefix}Candidate"]==true&&descriptor["p${3-priority}Candidate"]!=true&&gate["priority"].toString()==priority.toString()&&
        gate["verificationAuthority"]==(if(p2SteerCandidate)"human-20261020-p2-whole-source-before-common-verification" else "human-20261019-p1-whole-source-before-common-verification")&&
        listOf("sourceReady","readyForBuild","allowCommonVerification","nativeSourceReady","allowNativeCandidateBuild","allowApkBuild").all {gate[it]==true})
    if(p2SteerCandidate){
        val candidateCode=gate["steerCandidateVersionCode"].toString().toInt()
        require(candidateCode>=13&&descriptor["versionCode"].toString()==candidateCode.toString()&&descriptor["moduleVersion"]=="0.3.3-p2-candidate.$candidateCode")
        val items=(gate["items"] as List<*>).map {it as Map<*,*>}
        require(items.map {it["id"].toString()}.sorted()==listOf("11","2","20","6","7")&&items.all {it["sourceReady"]==true})
    }
    val pins=mapOf(
        "com.squareup.okhttp3:okhttp:4.12.0" to "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0",
        "com.squareup.okio:okio-jvm:3.6.0" to "67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412",
        "org.jetbrains.kotlin:kotlin-stdlib:2.2.10" to "9c67cc79efd6b9215b49d2a4308f5f3433537376c7c88e89bdd6729bd096e61a",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10" to "ac6361bf9ad1ed382c2103d9712c47cdec166232b4903ed596e8876b0681c9b7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10" to "a4c74d94d64ce1abe53760fe0389dd941f6fc558d0dab35e47c085a11ec80f28",
        "org.jetbrains:annotations:13.0" to "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478")
    val dependencies=(descriptor["bundledDependencies"] as List<*>).map {it as Map<*,*>}
    require(dependencies.size==pins.size&&dependencies.map{it["coordinate"]}.toSet()==pins.keys)
    val owned=hashSetOf<String>()
    ZipFile(directory.resolve("steer/runtime.aar")).use {aar->
        if(p2SteerCandidate){
            val caps=aar.getInputStream(requireNotNull(aar.getEntry("assets/steer-capabilities.json"))).use {stream->
                groovy.json.JsonSlurper().parse(stream) as Map<*,*>
            }
            require(caps["mcrf_snapshot_model"].toString()=="2"&&caps["candidate_only"]==true&&caps["runtime_accepted"]==false&&
                caps["independent_probe"]=="native_https_204_fresh_worker_v1"){"Exact unaccepted P2 independent-probe candidate required"}
        }
        val runtime=aar.getInputStream(requireNotNull(aar.getEntry("classes.jar"))).use {stream->ZipInputStream(stream).use {jar->
            buildMap<String,ByteArray>{while(true){val entry=jar.nextEntry?:break;if(entry.name.endsWith(".class")&&(entry.name.startsWith("kotlin/")||entry.name.startsWith("org/jetbrains/annotations/")))put(entry.name,jar.readBytes())}}
        }}
        dependencies.forEach {row->
            val coordinate=row["coordinate"].toString();val relative=row["file"].toString()
            require(row["sha256"]==pins[coordinate]&&relative=="bundled/"+coordinate.replace(':','_')+".jar")
            val source=directory.resolve("steer/$relative")
            require(source.isFile&&source.absoluteFile.normalize()==source.canonicalFile&&!Files.isSymbolicLink(source.toPath())&&source.length() in 1L..16777216L)
            require(MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==pins[coordinate])
            ZipFile(source).use {jar->jar.entries().asSequence().filter {it.name.endsWith(".class")&&(it.name.startsWith("kotlin/")||it.name.startsWith("org/jetbrains/annotations/"))&&!it.name.startsWith("META-INF/")}.forEach {entry->
                val value=jar.getInputStream(entry).use {it.readBytes()};require(runtime[entry.name]?.contentEquals(value)==true){"P1 fat runtime dependency class differs"};owned+=entry.name
            }}
        }
        require(runtime.isNotEmpty()&&runtime.keys==owned){"P1 runtime contains unpinned Kotlin/annotation classes"}
    }
    true
}else false
if(primaryGate&&(steerRuntimePin in reviewedFatSteerRuntimePins||candidateFatRuntimeVerified)){
    configurations.configureEach {
        if(name.startsWith("steer")){
            listOf("kotlin-stdlib","kotlin-stdlib-jdk7","kotlin-stdlib-jdk8","kotlin-stdlib-common").forEach{module->exclude(group="org.jetbrains.kotlin",module=module)}
            exclude(group="org.jetbrains",module="annotations")
        }
    }
}
if(p2SteerCandidate&&historicalReleaseAllowed){
    android.productFlavors.getByName("steer").apply {versionCode=14;versionName="0.3.3-mcrf.14"}
}
if(optionalStage!=null&&historicalReleaseAllowed){
    android.productFlavors.getByName("xray").apply {versionCode=6;versionName="0.1.0-mcrf.6"}
    android.productFlavors.getByName("tlscore").apply {versionCode=7;versionName="0.1.0-mcrf.7"}
}
if(swgStages.isNotEmpty()){
    tasks.register("verifySwgNativeCandidates"){
        swgStages.keys.forEach{kind->val cap=kind.replaceFirstChar{it.uppercase()};dependsOn("merge${cap}DebugNativeLibs","process${cap}DebugResources","merge${cap}DebugAssets","prepare${cap}Notices")}
        doLast{swgStages.forEach{(kind,stage)->
            val descriptor=groovy.json.JsonSlurper().parse(stage.resolve("receipt.json")) as Map<*,*>
            descriptor["packageVersionCode"]?.let {code->
                val flavor=android.productFlavors.getByName(kind)
                require(flavor.versionCode==code.toString().toInt()&&flavor.versionName==descriptor["packageVersionName"]){"Frozen module version was not applied to $kind"}
                println("SWG_CANDIDATE_VERSION: $kind; ${flavor.versionCode}; ${flavor.versionName}")
            }
            val cap=kind.replaceFirstChar{it.uppercase()}
            val root=layout.buildDirectory.dir("intermediates/merged_native_libs/${kind}Debug/merge${cap}DebugNativeLibs/out/lib").get().asFile
            val expected=listOf("arm64-v8a","armeabi-v7a","x86_64").map{"$it/lib$kind.so"}.toSet()
            require(root.walkTopDown().filter{it.isFile&&it.extension=="so"}.map{it.relativeTo(root).invariantSeparatorsPath}.toSet()==expected){"Standalone package contains an unrelated engine or missing ABI"}
            expected.forEach{name->require(MessageDigest.getInstance("SHA-256").digest(root.resolve(name).readBytes()).contentEquals(MessageDigest.getInstance("SHA-256").digest(stage.resolve(name).readBytes())))}
            val merged=requireNotNull(captchaMergedAssets[kind]).get().asFile.resolve("licenses/$kind-LICENSE")
            require(merged.isFile&&merged.readBytes().contentEquals(stage.resolve("licenses/$kind-LICENSE").readBytes())){"Merged original license missing or changed for $kind at ${merged.invariantSeparatorsPath}"}
            println("SWG_CANDIDATE_VERIFIED: $kind; exact3native/3ABI; mergednotices; noAPK/noexecution")
        }}
    }
}
if(csqttLegacyStage!=null){
    tasks.register("verifyCsqttLegacyCandidate"){
        dependsOn("mergeCsqttDebugNativeLibs","processCsqttDebugResources","mergeCsqttDebugAssets","prepareCsqttLegacyNotices")
        doLast{
            val root=layout.buildDirectory.dir("intermediates/merged_native_libs/csqttDebug/mergeCsqttDebugNativeLibs/out/lib").get().asFile
            val expected=listOf("arm64-v8a","armeabi-v7a","x86_64").flatMap{abi->listOf("$abi/libqwdtt.so","$abi/libcsqtt_transport.so")}.toSet()
            val actual=root.walkTopDown().filter{it.isFile&&it.extension=="so"}.map{it.relativeTo(root).invariantSeparatorsPath}.toSet()
            require(actual==expected){"Legacy CSQTT must contain exactly one paired wrapper/transport per ABI, never2.5"}
            expected.forEach{name->require(MessageDigest.getInstance("SHA-256").digest(root.resolve(name).readBytes()).contentEquals(MessageDigest.getInstance("SHA-256").digest(requireNotNull(csqttLegacyStage).resolve(name).readBytes())))}
            val notices=layout.buildDirectory.dir("generated/csqtt-legacy-assets/licenses").get().asFile
            listOf("CSQTT-LICENSE","qwdtt-LICENSE").forEach{name->require(notices.resolve(name).readBytes().contentEquals(requireNotNull(csqttLegacyStage).resolve("licenses/$name").readBytes()))}
            val manifests=layout.buildDirectory.dir("intermediates").get().asFile.walkTopDown().filter{it.isFile&&it.name=="AndroidManifest.xml"&&it.invariantSeparatorsPath.contains("csqttDebug")}.toList()
            require(manifests.any{it.readText().let{text->text.contains("org.mcrf.engines.csqtt")&&text.contains("org.mcrf.engines.csqtt_captcha_ids")&&text.contains("android:value=\"legacy\"")}}){"Legacy exact-waiter metadata missing"}
            println("CSQTT_LEGACY_CANDIDATE_VERIFIED: paired6native; threeABI; legacygeneration; originalnotices; noAPK/noexecution")
        }
    }
}
if(csqtt25Stage!=null&&csqtt25Gate){
    tasks.register("verifyCsqtt25Candidate"){
        dependsOn("mergeCsqtt25DebugNativeLibs","processCsqtt25DebugResources","mergeCsqtt25DebugAssets","prepareCsqtt25Notices")
        doLast{
            val root=layout.buildDirectory.dir("intermediates/merged_native_libs/csqtt25Debug/mergeCsqtt25DebugNativeLibs/out/lib").get().asFile
            val actual=root.walkTopDown().filter{it.isFile&&it.extension=="so"}.map{it.relativeTo(root).invariantSeparatorsPath}.toSet()
            val expected=setOf("arm64-v8a/libcsqtt25.so","arm64-v8a/libcsqtt25_transport.so")
            require(actual==expected){"New CSQTT package mixed legacy/unrelated native libraries or an unavailable ABI"}
            expected.forEach{name->
                val staged=requireNotNull(csqtt25Stage).resolve(name)
                require(MessageDigest.getInstance("SHA-256").digest(root.resolve(name).readBytes()).contentEquals(MessageDigest.getInstance("SHA-256").digest(staged.readBytes()))){"Merged native changed after frozen gate"}
            }
            val notices=layout.buildDirectory.dir("generated/csqtt25-assets/licenses").get().asFile
            listOf("CSQTT-LICENSE","qwdtt-LICENSE").forEach{name->require(notices.resolve(name).readBytes().contentEquals(requireNotNull(csqtt25Stage).resolve("licenses/$name").readBytes()))}
            val manifests=layout.buildDirectory.dir("intermediates").get().asFile.walkTopDown().filter{it.isFile&&it.name=="AndroidManifest.xml"&&it.invariantSeparatorsPath.contains("csqtt25Debug")}.toList()
            require(manifests.any{it.readText().let{text->text.contains("org.mcrf.engines.csqtt25")&&text.contains("org.mcrf.engines.csqtt_generation")&&text.contains("@string/mcrf_csqtt_generation")}}){"Dedicated generation manifest missing"}
            println("CSQTT25_CANDIDATE_VERIFIED: arm64-only; exact2native; separatepackage; generation-string-resource; originalnotices; noAPK/noexecution")
        }
    }
}
