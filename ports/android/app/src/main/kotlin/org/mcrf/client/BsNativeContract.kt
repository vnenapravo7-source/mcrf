package org.mcrf.client
import android.content.Context
import org.json.JSONObject
/** Signed module capability, independent of the user's primary-core chooser. */
internal object BsNativeContract {
 const val CHOICE="bs-native"
 private data class VerifiedPin(val path:String,val stamp:List<Long>,val digest:String)
 @Volatile private var verifiedPin:VerifiedPin?=null
 private data class BundledRouter(val apkPath:String,val executablePath:String,val pin:String)
 @Volatile private var bundledRouter:BundledRouter?=null
 internal val markers=listOf("MCRF_BS_ROUTING_ADMITTED ","MCRF_BS_NATIVE_READY ","MCRF_BS_HEALTH_RESULT ","BS_NATIVE_ADMISSION_PARENT_GUARD","BS_SERVER_ROUTING_CONTRACT_1","BS_NATIVE_FLOW_OBSERVATION_CONTRACT_1")
 // Standalone routers have no CSQTT server handshake/ROUTING announcer.
 internal val routerMarkers=markers.filterNot {it=="BS_SERVER_ROUTING_CONTRACT_1"}+"MCRF_BS_ROUTER_LISTENING "
 const val CAPABILITY="org.mcrf.engines.bs_routing_session"
 const val ROUTER_CAPABILITY="org.mcrf.engines.bs_router"
 const val ROUTER_LISTENING="MCRF_BS_ROUTER_LISTENING "
 const val PROXY_READY="MCRF_BS_PROXY_READY "
 fun proxyHint()=android.os.Build.VERSION.SDK_INT>=29&&BuildConfig.NATIVE_ROUTER_PROXY
 internal fun standaloneHint(vpn:Boolean)=android.os.Build.VERSION.SDK_INT>=29&&BuildConfig.NATIVE_ROUTER_PINS.isNotBlank()&&(vpn||proxyHint())
 const val QWDTT_UNDERLAY="org.mcrf.engines.qwdtt_underlay"
 const val LEGACY_UNDERLAY="org.mcrf.engines.csqtt_legacy_underlay"
 const val MODERN_UNDERLAY="org.mcrf.engines.csqtt_modern_underlay"
 const val WDTT_UNDERLAY="org.mcrf.engines.wdtt_underlay"
 const val OPENFLUX_UNDERLAY="org.mcrf.engines.openflux_underlay"
 const val OLCRTC_UNDERLAY="org.mcrf.engines.olcrtc_underlay"
 const val VAYDNS_UNDERLAY="org.mcrf.engines.vaydns_underlay"
 const val S3BYPASS_UNDERLAY="org.mcrf.engines.s3bypass_underlay"
 const val DNSTT_UNDERLAY="org.mcrf.engines.dnstt_underlay"
 const val SLIPSTREAM_UNDERLAY="org.mcrf.engines.slipstream_underlay"
 const val SSTP_UNDERLAY="org.mcrf.engines.sstp_underlay"
 const val MASTERDNS_UNDERLAY="org.mcrf.engines.masterdnsvpn_underlay"
 const val QWDTT_UNDERLAY_ENV="MCRF_QWDTT_UNDERLAY"
 const val LEGACY_UNDERLAY_ENV="MCRF_CSQTT_LEGACY_UNDERLAY"
 const val MODERN_UNDERLAY_ENV="MCRF_CSQTT_MODERN_UNDERLAY"
 const val WDTT_UNDERLAY_ENV="MCRF_WDTT_UNDERLAY"
 const val OPENFLUX_UNDERLAY_ENV="MCRF_OPENFLUX_UNDERLAY"
 const val OLCRTC_UNDERLAY_ENV="MCRF_OLCRTC_UNDERLAY"
 const val VAYDNS_UNDERLAY_ENV="MCRF_VAYDNS_UNDERLAY"
 const val S3BYPASS_UNDERLAY_ENV="MCRF_S3BYPASS_UNDERLAY"
 const val DNSTT_UNDERLAY_ENV="MCRF_DNSTT_UNDERLAY"
 const val SLIPSTREAM_UNDERLAY_ENV="MCRF_SLIPSTREAM_UNDERLAY"
 const val SSTP_UNDERLAY_ENV="MCRF_SSTP_UNDERLAY"
 const val MASTERDNS_UNDERLAY_ENV="MCRF_MASTERDNS_UNDERLAY"
 fun carrierRouter(context:Context,profile:JSONObject,verified:Boolean):Boolean {
  if(android.os.Build.VERSION.SDK_INT<29)return false
  return try{
   val kind=EngineModules.carrierKind(profile)
   val capability=when(kind){
    "qwdtt"->QWDTT_UNDERLAY
    "csqtt","csqtt20","csqtt215"->LEGACY_UNDERLAY.takeUnless {CsqttAutoDetection.negotiates(profile)}?:return false
    "csqtt25"->MODERN_UNDERLAY.takeUnless {CsqttAutoDetection.negotiates(profile)}?:return false
    "wdtt"->WDTT_UNDERLAY
    "openflux"->OPENFLUX_UNDERLAY
    "olcrtc"->OLCRTC_UNDERLAY
    VayDnsProfile.KIND->VAYDNS_UNDERLAY
    S3BypassProfile.KIND->S3BYPASS_UNDERLAY
    "dnstt"->DNSTT_UNDERLAY
    SlipstreamProfile.KIND->{
     CarrierProfile.numericResolver(profile.getString("resolver"))
     SLIPSTREAM_UNDERLAY
    }
    SstpProfile.KIND->SSTP_UNDERLAY
    MasterDnsProfile.KIND->MASTERDNS_UNDERLAY
    else->return false
   }
   val embedded=if(verified)bundledRouter(context)!=null else BuildConfig.NATIVE_ROUTER_PINS.isNotEmpty()
   val bs=if(!embedded||kind in BsEngineContract.engines){
    if(verified)BsEnginePackage.installation(context)
    else context.packageManager.getApplicationInfo(BsEngineContract.PACKAGE,android.content.pm.PackageManager.GET_META_DATA)
   }else null
   if(!embedded&&(bs?.enabled!=true||bs.metaData?.get(ROUTER_CAPABILITY)!=1))return false
   if(kind in BsEngineContract.engines)return bs?.enabled==true&&bs.metaData?.get(capability)==1
   val packageName=EngineModules.packageName(kind)
   val carrier=if(verified)NativeCarrier.installation(context,kind)
       else context.packageManager.getApplicationInfo(packageName,android.content.pm.PackageManager.GET_META_DATA)
   if(!carrier.enabled||carrier.packageName!=packageName||carrier.metaData?.get(capability)!=1)return false
   if(!verified)return true
   val marker=when(kind){
    "wdtt"->WDTT_UNDERLAY_ENV
    "openflux"->OPENFLUX_UNDERLAY_ENV
    "olcrtc"->OLCRTC_UNDERLAY_ENV
    VayDnsProfile.KIND->VAYDNS_UNDERLAY_ENV
    S3BypassProfile.KIND->S3BYPASS_UNDERLAY_ENV
    "dnstt"->DNSTT_UNDERLAY_ENV
    SlipstreamProfile.KIND->SLIPSTREAM_UNDERLAY_ENV
    SstpProfile.KIND->SSTP_UNDERLAY_ENV
    MasterDnsProfile.KIND->MASTERDNS_UNDERLAY_ENV
    else->return false
   }
   val root=java.io.File(carrier.nativeLibraryDir).canonicalFile
   val executable=java.io.File(root,"lib$kind.so")
   val executables=if(kind==SlipstreamProfile.KIND) listOf(executable,java.io.File(root,"libslipstream_core.so")) else listOf(executable)
   executables.all {file->file.canonicalFile==file&&file.isFile&&file.canExecute()&&
      file.inputStream().use {hasMarkers(it,listOf(marker))}}
  }catch(_:Exception){false}
 }
 const val GENERATIONS="org.mcrf.engines.bs_routing_generations"
 internal fun profileCapability(fd:Int,declared:String?,implementation:String):Boolean {
  if(!BsDirectTunContract.admits(implementation,fd,true))return false
  val modes=declared?.split(',')?:if(fd>=2)listOf("2.5.1","2.5.2","2.5.3")else listOf("2.5.2","2.5.3")
  return modes.isNotEmpty()&&modes.size==modes.distinct().size&&modes.all {it in setOf("2.5.1","2.5.2","2.5.3")}&&implementation.removePrefix("swg-panel-") in modes
 }
 private fun capable(data:android.os.Bundle?,profile:JSONObject?):Boolean {
  if(data?.get(CAPABILITY)!=1)return false
  val declared=data.get(GENERATIONS);if(declared!=null&&declared !is String)return false
  return profile==null||supportedProfile(profile)&&profileCapability(data.get(BsDirectTunContract.CAPABILITY) as? Int?:0,declared as? String,profile.optString("csqtt_implementation"))
 }
 /** UI hint only. Signature/catalogue/ELF admission remains mandatory off UI. */
 fun advertisedHint(context:Context,profile:JSONObject?=null):Boolean=android.os.Build.VERSION.SDK_INT>=29&&try {
  context.packageManager.getApplicationInfo(BsEngineContract.PACKAGE,android.content.pm.PackageManager.GET_META_DATA).let {it.enabled&&capable(it.metaData,profile)}
 }catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
 fun supportedProfile(profile:JSONObject)=CsqttDirectTunSupport.profileReason(profile,true)==null
 /** Coarse admission only. A negotiated profile is never sent to the native worker. */
 internal fun allowsVersionDetection(profile:JSONObject)=
  profile.optString("type")=="csqtt"&&CsqttAutoDetection.negotiates(profile)&&
      profile.optString("vk_hash_mode","manual")=="manual"&&profile.optString("vk_token").isEmpty()&&
      profile.optString("vk_anon_path")!="auto_js"
 /** Detection must keep every possible attempt on the same physical network.
  * A raw TUN capability alone does not authorize a transport-only probe. */
 internal fun detectionRouter(profile:JSONObject,available:(JSONObject)->Boolean):Boolean {
  if(!allowsVersionDetection(profile))return false
  return CsqttAutoDetection.plan(profile,null).all {target->
   val concrete=try {CsqttAutoDetection.profile(profile,target)}catch(_:Exception){return false}
   available(concrete)
  }
 }
 fun routerTransport(context:Context,profile:JSONObject,verified:Boolean):Boolean =
  if(CsqttAutoDetection.negotiates(profile))detectionRouter(profile){carrierRouter(context,it,verified)}
  else carrierRouter(context,profile,verified)
 private fun connection(context:Context,profile:JSONObject,vpn:Boolean,verified:Boolean):Boolean {
  if(!vpn&&!proxyHint())return false
  if(routerTransport(context,profile,verified))return true
  return vpn&&supportedProfile(profile)&&
      if(verified)advertised(context,profile)else advertisedHint(context,profile)
 }
 fun connectionHint(context:Context,profile:JSONObject,vpn:Boolean=true)=connection(context,profile,vpn,false)
 fun connectionAvailable(context:Context,profile:JSONObject,vpn:Boolean=true)=connection(context,profile,vpn,true)
 fun advertised(context:Context,profile:JSONObject?=null):Boolean=android.os.Build.VERSION.SDK_INT>=29&&try {capable(BsEnginePackage.installation(context).metaData,profile)}catch(_:Exception){false}
 fun pin(context:Context):String {
  require(android.os.Build.VERSION.SDK_INT>=29){"BS_NATIVE_ANDROID_VERSION"}
  val app=BsEnginePackage.installation(context);require(app.metaData?.get(CAPABILITY)==1&&BsEnginePackage.usesMulticall(context)){"BS_NATIVE_CAPABILITY_REQUIRED"}
  val apk=java.io.File(app.sourceDir).canonicalFile
  fun stamp()=android.system.Os.stat(apk.path).let {
   listOf(it.st_dev,it.st_ino,it.st_size,it.st_mtim.tv_sec,it.st_mtim.tv_nsec,it.st_ctim.tv_sec,it.st_ctim.tv_nsec)
  }
  val before=stamp()
  verifiedPin?.takeIf {it.path==apk.path&&it.stamp==before}?.let {return it.digest}
  val digest=ClientPrimaryWorkerWire.hashFile(apk)
  val executable=java.io.File(app.nativeLibraryDir,"libcsqtt_transport.so")
  require(executable.isFile&&executable.inputStream().use(::hasMarkers)){"BS_NATIVE_RUNTIME_CAPABILITY_MISMATCH"}
  require(before==stamp()){"BS_NATIVE_MODULE_CHANGED"}
  verifiedPin=VerifiedPin(apk.path,before,digest)
  return digest
 }
 internal fun hasMarkers(input:java.io.InputStream,expected:List<String> = markers):Boolean {
  val pending=expected.toMutableSet();val buffer=ByteArray(65536);var tail="";val overlap=expected.maxOf {it.length}-1
  while(pending.isNotEmpty()){val size=input.read(buffer);if(size<0)break;if(size==0)continue;val window=tail+String(buffer,0,size,Charsets.ISO_8859_1);pending.removeAll {window.contains(it)};tail=window.takeLast(overlap)}
  return pending.isEmpty()
 }
 fun installation(context:Context,profile:JSONObject,pin:String):BsDirectTunContract.Installation {
  require(supportedProfile(profile)&&advertised(context,profile)&&pin.matches(Regex("[0-9a-f]{64}"))&&BsNativeContract.pin(context)==pin){"BS_NATIVE_MODULE_OR_PROFILE_CHANGED"}
  return BsDirectTunContract.resolve(context,profile)
 }
 private fun bundledRouter(context:Context):BundledRouter? {
  if(BuildConfig.NATIVE_ROUTER_PINS.isEmpty())return null
  val abi=EnginePackage.abi(android.os.Build.SUPPORTED_ABIS.toList(),android.os.Process.is64Bit())
  val digest=BuildConfig.NATIVE_ROUTER_PINS.split(',').single {it.startsWith("$abi=")}.substringAfter('=')
  val app=context.applicationInfo
  val apk=java.io.File(app.sourceDir).canonicalFile
  val root=java.io.File(app.nativeLibraryDir).canonicalFile
  val executable=java.io.File(root,"libmcrf_router.so")
  bundledRouter?.takeIf {it.apkPath==apk.path&&it.executablePath==executable.path}?.let {return it}
  require(executable.canonicalFile==executable&&!java.nio.file.Files.isSymbolicLink(executable.toPath())&&
      executable.isFile&&executable.canExecute()&&ClientPrimaryWorkerWire.hashFile(executable)==digest&&
      executable.inputStream().use {hasMarkers(it,routerMarkers+listOfNotNull(PROXY_READY.takeIf {proxyHint()}))}){
      "BS_NATIVE_BUNDLED_ROUTER_IDENTITY"
  }
  return BundledRouter(apk.path,executable.path,ClientPrimaryWorkerWire.hashFile(apk)).also {bundledRouter=it}
 }
 fun routerPin(context:Context):String=bundledRouter(context)?.pin?:pin(context)
 internal fun requireBundledRouter(context:Context):String =
  requireNotNull(bundledRouter(context)){"NATIVE_ROUTER_STANDALONE_BUNDLED_REQUIRED"}.pin

 fun workerPin(context:Context,expected:String):String {
  val embedded=bundledRouter(context)
  if(embedded?.pin==expected)return embedded.pin
  return pin(context).also {require(it==expected){"BS_NATIVE_MODULE_CHANGED"}}
 }
 fun routerInstallation(context:Context,pin:String):BsDirectTunContract.Installation {
  bundledRouter(context)?.takeIf {it.pin==pin}?.let {
   return BsDirectTunContract.Installation(java.io.File(it.executablePath),"2.5.3",standaloneRouter=true)
  }
  require(pin.matches(Regex("[0-9a-f]{64}"))&&BsNativeContract.pin(context)==pin){"BS_NATIVE_MODULE_CHANGED"}
  val app=BsEnginePackage.installation(context)
  require(app.metaData?.get(ROUTER_CAPABILITY)==1){"BS_NATIVE_ROUTER_CAPABILITY_REQUIRED"}
  val root=java.io.File(app.nativeLibraryDir).canonicalFile
  val executable=java.io.File(root,"libcsqtt_transport.so")
  require(executable.canonicalFile.parentFile==root&&!java.nio.file.Files.isSymbolicLink(executable.toPath())&&executable.isFile&&executable.canExecute()&&
      executable.inputStream().use {hasMarkers(it,listOf(ROUTER_LISTENING))}){
   "BS_NATIVE_ROUTER_EXECUTABLE_REQUIRED"
  }
  return BsDirectTunContract.Installation(executable,"2.5.3")
 }
}
