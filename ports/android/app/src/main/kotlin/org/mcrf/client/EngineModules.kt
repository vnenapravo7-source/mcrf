package org.mcrf.client

/** Optional executables are separately installed, same-signer APKs, never main-app assets. */
object EngineModules {
    val optionalCores=setOf("xray","tls-core")
    val kinds=setOf("csqtt","csqtt25","qwdtt","wdtt","openflux","olcrtc","dnstt","warpscout","tgws",S3BypassProfile.KIND)+optionalCores
    val primaryCores get()=EngineUpdatePrimary.kinds
    val packetCores=setOf(WgAddonPolicy.KIND)
    val allKinds get()=kinds+primaryCores+packetCores
    const val LEGACY="org.mcrf.engines"
    fun packageName(kind:String):String {if(kind in primaryCores)return EngineUpdatePrimary.contract(kind).packageName;if(kind in packetCores)return ClientPacketBackendLoader.PACKAGE;require(kind in kinds);return "$LEGACY.${if(kind=="tls-core")"tlscore"else kind}"}
    fun kind(packageId:String)=allKinds.firstOrNull {packageName(it)==packageId}
    fun assetName(kind:String,abi:String):String {require(kind in allKinds&&abi in setOf("arm64-v8a","armeabi-v7a","x86_64","universal"));return "mcrf-engine-$kind-$abi-release.apk"}
    fun libraries(kind:String):Set<String> {require(kind in kinds+packetCores);return if(kind in packetCores)setOf("libmcrfwg.so")else if(kind=="csqtt")setOf("libqwdtt.so","libcsqtt_transport.so")else if(kind=="csqtt25")setOf("libcsqtt25.so","libcsqtt25_transport.so")else setOf(if(kind=="tls-core")"libtls_core.so"else"lib$kind.so")}
    fun carrierKind(profile:org.json.JSONObject):String=if(Csqtt25Profile.isProfile(profile))Csqtt25Profile.ENGINE else profile.getString("type").also {require(it!="csqtt"||!profile.has("csqtt_generation")||profile.opt("csqtt_generation")=="legacy") {"Неизвестная версия CSQTT; автоматическая подмена движка запрещена."}}
    fun sourceCommit(kind:String)=when(kind){"xray"->"e662c22ab1096883c4df200a0e8068a89da04df1";"tls-core"->"ebe0747b41823e02335ad3c7ae531bc577e7bc75";else->throw IllegalArgumentException("Not an optional core")}
    fun sourceRepository(kind:String)=when(kind){"xray"->"throneproj/xray-core";"tls-core"->"throneproj/sing-box";else->throw IllegalArgumentException("Not an optional core")}
    fun ingress(kind:String)=when(kind){"xray"->"authenticated-loopback-socks5-tcp-udp";"tls-core"->"authenticated-loopback-socks5-tcp";else->throw IllegalArgumentException("Not an optional core")}
    fun installation(context:android.content.Context,kind:String):android.content.pm.ApplicationInfo {
        if(kind in packetCores)return EngineUpdatePacket.installation(context)
        if(kind in primaryCores)return EngineUpdatePrimary.installation(context,kind)
        if(kind=="tgws")return EngineUpdateTelegram.installation(context)
        if(kind !in optionalCores)return NativeCarrier.installation(context,kind)
        val pm=context.packageManager
        val info=pm.getApplicationInfo(packageName(kind),android.content.pm.PackageManager.GET_META_DATA)
        require(info.enabled && pm.checkSignatures(context.packageName,info.packageName)==android.content.pm.PackageManager.SIGNATURE_MATCH &&
            info.metaData?.getInt("org.mcrf.engines.interface")==1 && info.metaData?.getString("org.mcrf.engines.kind")==kind &&
            info.metaData?.getInt("org.mcrf.engines.optional-core")==1 && info.metaData?.getString("org.mcrf.engines.source.commit")==sourceCommit(kind) && info.metaData?.getString("org.mcrf.engines.source.repository")==sourceRepository(kind) && info.metaData?.getString("org.mcrf.engines.optional-ingress")==ingress(kind)) {"Подпись, исходник или интерфейс дополнительного ядра не совпадает с MCRF."}
        return info
    }
    fun executable(context:android.content.Context,kind:String):java.io.File {
        require(kind !in primaryCores+packetCores){"Движок загружается через проверенный SPI, а не как транспортный процесс."}
        if(kind !in optionalCores&&kind!="tgws")return NativeCarrier.executable(context,kind)
        val root=java.io.File(installation(context,kind).nativeLibraryDir).canonicalFile
        return java.io.File(root,libraries(kind).single()).also {require(it.canonicalFile.parentFile==root&&it.isFile&&it.canExecute()) {"Нет дополнительного ядра для этой архитектуры."}}
    }
    fun validNativeEntries(kind:String,abi:String,entries:List<String>):Boolean {
        val expected=libraries(kind)
        if(kind=="csqtt25"&&abi!="arm64-v8a")return false
        return abi in setOf("arm64-v8a","armeabi-v7a","x86_64") && entries.isNotEmpty() && entries.distinct().size==entries.size && entries.all {name ->
            val parts=name.split('/');parts.size==3 && parts[0]=="lib" && parts[1] in setOf("arm64-v8a","armeabi-v7a","x86_64") && parts[2] in expected
        } && expected.all {"lib/$abi/$it" in entries}
    }
}
