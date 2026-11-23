package org.mcrf.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

internal object BsEnginePackage {
    fun metadata(data:android.os.Bundle?,schema:Int?=null) {
        val m=EngineMetadata{data?.get(it)}
        require(m.requiredString("org.mcrf.engines.kind")==BsEngineContract.KIND&&m.requiredInt("org.mcrf.engines.interface")==1&&
            m.requiredInt("org.mcrf.engines.bs")==1&&m.requiredString("org.mcrf.engines.bs.logical") in
                setOf(BsEngineContract.engines.joinToString(","),BsEngineContract.legacyEngines.joinToString(",")))
        if(schema!=null){
            require(m.requiredString("org.mcrf.engines.bs.logical")==
                (if(schema>=3)BsEngineContract.engines else BsEngineContract.legacyEngines).joinToString(","))
            if(schema>=3)for(key in listOf("org.mcrf.engines.wdtt_provider_options","org.mcrf.engines.wdtt_masque","org.mcrf.engines.wdtt_dual_family"))require(m.requiredInt(key)==0)
            if(schema==4)require(m.requiredInt("org.mcrf.engines.bs.rust_multicall")==1)
        }
        for(key in listOf("org.mcrf.engines.swg","org.mcrf.engines.csqtt_process_group","org.mcrf.engines.csqtt_account_options",
            "org.mcrf.engines.turn_hash_pool","org.mcrf.engines.csqtt_historical_options","org.mcrf.engines.csqtt_captcha_ids",
            "org.mcrf.engines.csqtt25_swg_panel",CsqttSwgVersions.CAPABILITY,
            "org.mcrf.engines.qwdtt_fingerprint"))require(m.requiredInt(key)==1)
    }
    private data class Verified(val identity:List<Any>,val app:ApplicationInfo,val catalog:BsEngineContract.Catalog)
    @Volatile private var cached:Verified?=null
    private fun identity(context:Context,app:ApplicationInfo):List<Any> {
        val p=context.packageManager.getPackageInfo(app.packageName,0)
        return listOf(app.sourceDir,app.nativeLibraryDir,p.longVersionCode,p.lastUpdateTime,File(app.sourceDir).length(),app.enabled)
    }
    private fun verified(context:Context):Verified {
        val pm=context.packageManager
        val app=pm.getApplicationInfo(BsEngineContract.PACKAGE,PackageManager.GET_META_DATA)
        require(app.enabled&&app.splitSourceDirs.isNullOrEmpty())
        val before=identity(context,app)
        cached?.takeIf{it.identity==before}?.let{return it}
        metadata(app.metaData)
        EngineSignerPolicy.requireSame(EngineSignatures.installed(pm,app.packageName).signers,EngineSignatures.installed(pm,context.packageName).signers)
        val apk=File(app.sourceDir)
        val archive=requireNotNull(EngineSignatures.archive(pm,apk.path))
        require(archive.info.packageName==BsEngineContract.PACKAGE)
        EngineSignerPolicy.requireSame(archive.signers,EngineSignatures.installed(pm,context.packageName).signers)
        metadata(archive.info.applicationInfo?.metaData)
        val abi=EnginePackage.abi(Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
        val catalog=ZipFile(apk).use{BsEngineContract.read(it,abi)}
        metadata(app.metaData,catalog.schema)
        metadata(archive.info.applicationInfo?.metaData,catalog.schema)
        val root=File(app.nativeLibraryDir).canonicalFile
        catalog.libraries.forEach{(name,row)->val file=File(root,name)
            require(file.canonicalFile.parentFile==root&&!Files.isSymbolicLink(file.toPath())&&file.isFile&&file.canExecute()&&file.length()==row.first&&BsEngineContract.sha(file.inputStream())==row.second)
        }
        require(identity(context,pm.getApplicationInfo(app.packageName,0))==before){"BS Engine изменился во время проверки."}
        return Verified(before,app,catalog).also{cached=it}
    }
    fun installation(context:Context)=verified(context).app
    /** Identity is emitted only AFTER signature, catalogue and executable digests
     * have been verified. Never uses a version label as a trust decision. */
    internal fun migrationIdentity(context:Context):String {
        val value=verified(context)
        return CsqttEngineMigration.digest(value.identity.joinToString("\u0000")+"\u0000"+
            value.catalog.libraries.toSortedMap().entries.joinToString("\u0000") {it.key+":"+it.value.first+":"+it.value.second})
    }
    fun usesMulticall(context:Context)=verified(context).catalog.schema>=4
    fun executable(context:Context,kind:String):File {
        require(kind in BsEngineContract.engines)
        val verified=verified(context)
        return File(verified.app.nativeLibraryDir,verified.catalog.executables.getValue(kind))
    }
    fun preferred(context:Context,kind:String):ApplicationInfo? {
        if(kind !in BsEngineContract.engines)return null
        // A broken/rejected BS install must leave the old package usable.
        return try{installation(context)}catch(_:Exception){null}
    }
}
