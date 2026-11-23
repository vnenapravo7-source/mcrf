package org.mcrf.client

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Offline delivery reuses the same signed module packages and Android installer as online delivery. */
internal object BundledEngines {
    private fun packages(context:Context):List<EnginePackage> {
        if(!BuildConfig.OFFLINE_EDITION)return emptyList()
        val text=context.assets.open("bundled-engines/index.json").use {input->
            val bytes=input.readBytes();require(bytes.size<=16384);bytes.toString(Charsets.UTF_8)
        }
        val doc=JSONObject(text);require(doc.getInt("schema")==1)
        val rows=doc.getJSONArray("packages");require(rows.length() in 2..6)
        val all=(0 until rows.length()).map {i->val r=rows.getJSONObject(i)
            val kind=r.getString("kind");val abi=r.getString("abi")
            require(kind in setOf("bs","sing-box")&&abi in setOf("arm64-v8a","armeabi-v7a","x86_64"))
            require(r.getLong("bytes") in 1..EnginePackage.MAX_BYTES&&r.getLong("versionCode")>0&&r.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            EnginePackage(abi,r.getString("version"),URI("mcrf-bundled://engine/$abi/$kind"),r.getLong("bytes"),r.getString("sha256"),kind,r.getLong("versionCode"))
        }
        require(all.map {it.abi to it.engine}.distinct().size==all.size)
        return all
    }
    fun find(context:Context,kind:String):EnginePackage? {
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
        val bundledKind=if(kind in BsEngineContract.engines)"bs"else kind
        return packages(context).singleOrNull {it.abi==abi&&it.engine==bundledKind}
    }
    fun available(context:Context,kinds:Set<String>):List<EnginePackage> {
        val local=kinds.mapNotNull {kind->find(context,kind)?.let {kind to it}}.toMap()
        return (local.values+if(local.size==kinds.size)emptyList()else EnginePackages.published(kinds-local.keys)).distinctBy {it.url}
    }
    fun extract(context:Context,expected:EnginePackage,cancelled:()->Boolean,progress:(Long)->Unit):File {
        require(expected.url.scheme=="mcrf-bundled")
        require(packages(context).any {it==expected}){"Встроенный пакет не совпадает с индексом сборки."}
        val file=File(context.cacheDir,"mcrf-engine-${java.util.UUID.randomUUID()}.apk")
        try {
            check(file.createNewFile());android.system.Os.chmod(file.absolutePath,0x180)
            context.assets.open("bundled-engines/${expected.engine}-${expected.abi}.apk").use {input->file.outputStream().use {output->EnginePackage.copyVerified(input,output,expected,cancelled,progress)}}
            EnginePackages.verifyArchive(context,file,expected.engine,expected.versionCode)
            return file
        }catch(e:Exception){file.delete();throw e}
    }
}
