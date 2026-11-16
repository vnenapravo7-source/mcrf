package org.mcrf.client

import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Logical profile IDs never change when their physical APK changes. */
internal object BsEngineContract {
    const val KIND="bs"
    const val PACKAGE="org.mcrf.engines.bs"
    const val ASSET="assets/mcrf-bs.json"
    val engines=linkedSetOf("csqtt","csqtt20","csqtt215","csqtt25","qwdtt")
    // Read installed schema 1/2 containers during upgrade, but never use them for WDTT.
    val legacyEngines=engines+"wdtt"
    val abis=setOf("arm64-v8a","armeabi-v7a","x86_64")
    val transports=mapOf("csqtt" to "libcsqtt_transport.so","csqtt20" to "libcsqtt20_transport.so","csqtt215" to "libcsqtt215_transport.so")
    val files=setOf("libbs_csqtt.so","libcsqtt_transport.so","libcsqtt20_transport.so","libcsqtt215_transport.so",
        "libcsqtt25.so","libcsqtt25_transport.so",CsqttSwgVersions.LIBRARY,"libqwdtt.so","libwdtt.so")
    val compactFiles=files-setOf("libbs_csqtt.so","libcsqtt25.so","libqwdtt.so")+"libbs.so"
    val splitFiles=compactFiles-"libwdtt.so"
    val currentFiles=setOf("libbs.so","libcsqtt_transport.so")
    const val LAYOUT="rust-multicall-v1"
    fun rustMode(logical:String,implementation:String=""):String?=when(logical){
        "csqtt"->"2.1.9";"csqtt20"->"2.0";"csqtt215"->"2.1.5"
        "csqtt25"->when(implementation){"","swg-panel-2.5.1"->"2.5.1";"swg-panel-2.5.2"->"2.5.2";"swg-panel-2.5.3"->"2.5.3";else->throw IllegalArgumentException("Неизвестное поколение CSQTT.")}
        "qwdtt"->null;else->throw IllegalArgumentException("Неизвестный движок BS.")
    }
    /** Always clear inherited selectors, including for retained standalone engines. */
    fun configureHostMode(env:MutableMap<String,String>,executable:String,logical:String,multicall:Boolean=false,implementation:String="") {
        env.remove("MCRF_BS_MODE");env.remove("MCRF_BS_TRANSPORT")
        env.remove("MCRF_BS_RUST_MODE");env.remove("MCRF_BS_LAYOUT")
        when(executable) {
            "libbs.so"->{require(logical in engines);env["MCRF_BS_MODE"]=logical
                if(multicall){env["MCRF_BS_LAYOUT"]=LAYOUT;rustMode(logical,implementation)?.let{env["MCRF_BS_RUST_MODE"]=it}}}
            "libbs_csqtt.so"->env["MCRF_BS_TRANSPORT"]=transports.getValue(logical)
        }
    }
    fun covers(physical:String?,logical:String)=physical==logical||(physical==KIND&&logical in engines)
    fun sha(input:java.io.InputStream)=input.use {stream->
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true){val n=stream.read(buffer);if(n<0)break;hash.update(buffer,0,n)}
        hash.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    data class Catalog(val abi:String,val executables:Map<String,String>,val libraries:Map<String,Pair<Long,String>>,val schema:Int)
    fun read(zip:ZipFile,abi:String):Catalog {
        require(abi in abis)
        val names=zip.entries().asSequence().map{it.name}.toList()
        require(names.size==names.distinct().size){"Повторяющиеся пути APK BS."}
        val entry=requireNotNull(zip.getEntry(ASSET)){"Нет каталога BS Engine."}
        require(entry.size in 1..65536)
        val bytes=zip.getInputStream(entry).use{input->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=65536);output.write(buffer,0,n)}
            output.toByteArray()
        }
        val data=JSONObject(String(bytes,Charsets.UTF_8))
        val schema=data.getInt("schema")
        require(schema in 1..4&&data.getString("package")==PACKAGE)
        if(schema==4)require(data.getString("layout")==LAYOUT)
        val expectedFiles=when(schema){4->currentFiles;3->splitFiles;2->compactFiles;else->files}
        val packagedEngines=if(schema>=3)engines else legacyEngines
        val nodes=data.getJSONObject("engines")
        require(nodes.keys().asSequence().toSet()==packagedEngines)
        val executables=packagedEngines.associateWith {kind->nodes.getJSONObject(kind).getString("executable").also {name->
            require(name==if(schema>=2&&kind!="wdtt")"libbs.so" else when(kind){"csqtt","csqtt20","csqtt215"->"libbs_csqtt.so";else->"lib$kind.so"})
        }}
        val libraries=data.getJSONObject("native").getJSONObject(abi)
        require(libraries.keys().asSequence().toSet()==expectedFiles)
        val rows=expectedFiles.associateWith {name->val row=libraries.getJSONObject(name)
            val size=row.getLong("bytes");val hash=row.getString("sha256")
            require(size in 64..EnginePackage.MAX_BYTES&&hash.matches(Regex("[0-9a-f]{64}")))
            size to hash
        }
        val native=names.filter{it.startsWith("lib/")&&!it.endsWith('/')}
        require(native.toSet()==expectedFiles.mapTo(hashSetOf()){ "lib/$abi/$it" }){"BS APK должен содержать только ABI устройства."}
        rows.forEach{(name,row)->val nativeEntry=requireNotNull(zip.getEntry("lib/$abi/$name"))
            require(nativeEntry.size==row.first&&sha(zip.getInputStream(nativeEntry))==row.second){"Повреждена библиотека BS: $name"}
        }
        EngineArchivePolicy.requireElfLibraries(zip)
        return Catalog(abi,executables.filterKeys{it in engines},rows,schema)
    }
}
