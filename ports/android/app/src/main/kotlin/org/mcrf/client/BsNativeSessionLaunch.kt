package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.MessageDigest
import java.util.Base64

/** Immutable per-child policy; never points at a mutable global geo directory. */
internal class BsNativeSessionLaunch(
    captured:BsNativeRoutingSnapshot.Captured,
    val physical:Network,
    underlay:JSONObject,
    private val ownedVpn:()->Network,
    private val openTun:(BsNativeTunPlan.Plan)->android.os.ParcelFileDescriptor,
    val proxyOnly:Boolean=false,
    val connectionOwner:((BsNativeOwnerChannel.Query)->BsNativeOwnerChannel.Owner)?=null,
    val serverRoutingMode:String="mcrf",
    val observedRoute:((BsNativeRoutedFlow)->Unit)?=null
) {
    private val document=captured.bundle().toString().toByteArray(Charsets.UTF_8)
    private val inbounds=captured.tunInbounds().toString()
    private val context=JSONObject(underlay.toString()).also {
        require(it.keys().asSequence().toSet()==setOf("kind","expensive","constrained","ssid","bssid"))
        require(it.getString("kind") in setOf("wifi","cellular","ethernet","other"))
        require(it.get("expensive") is Boolean&&it.get("constrained") is Boolean)
        require(it.getString("ssid").length<=256&&it.getString("bssid").length<=64)
    }.toString().toByteArray(Charsets.UTF_8)
    init {require(ServerRoutingMode.valid(serverRoutingMode)&&document.size in 1..24*1024*1024&&physical.networkHandle>0&&(!proxyOnly||captured.tunInbounds().length()==0))}
    fun proxyNetworkContext():String {
        check(proxyOnly)
        return context.toString(Charsets.UTF_8)
    }
    class Document internal constructor(private val file:File,val digest:String):AutoCloseable {
        private var closed=false
        @Synchronized override fun close(){if(closed)return
            val plan=File(file.parentFile,"routing-tun-plan.json")
            if(plan.exists()||java.nio.file.Files.isSymbolicLink(plan.toPath()))check(!java.nio.file.Files.isSymbolicLink(plan.toPath())&&plan.isFile&&plan.delete()) {"BS_NATIVE_PLAN_CLEANUP"}
            check(!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.isFile&&file.delete()) {"BS_NATIVE_DOCUMENT_CLEANUP"};closed=true}
        override fun toString()="BsNativeDocument(redacted)"
    }
    /** Full native policy admission runs before retiring any current core.
     * The signed child must exit without configuring a network or listener. */
    fun validate(context:Context,installation:BsDirectTunContract.Installation,cancelled:()->Boolean) {
        check(!cancelled()){ "Подготовка БС отменена." }
        val directory=File(context.cacheDir,"mcrf-bs-admit-${java.util.UUID.randomUUID()}")
        check(directory.mkdir());android.system.Os.chmod(directory.path,0x1c0)
        var saved:Document?=null;var process:Process?=null;var reader:Thread?=null
        val output=java.util.concurrent.atomic.AtomicReference<ByteArray?>();val invalid=java.util.concurrent.atomic.AtomicBoolean()
        try {
            val captured=write(directory);saved=captured
            val arguments=if(proxyOnly){
                require(installation.standaloneRouter)
                listOf("--mcrf-router-proxy-admit")
            }else if(installation.standaloneRouter)listOf("--mcrf-router-admit")
                else listOf("-peer","192.0.2.1:9","-listen","127.0.0.1:19090","-n","1","-vk","admission-only")
            val builder=ProcessBuilder(listOf(installation.executable.absolutePath)+arguments).directory(directory).redirectErrorStream(true)
            builder.environment().apply {
                keys.filter {it.startsWith("MCRF_BS_")||it.startsWith("MCRF_DIRECT_TUN_")}.toList().forEach(::remove)
                listOf("MCRF_PROCESS_OWNER","CSQTT_CONFIG_FILE","LD_PRELOAD","LD_LIBRARY_PATH","CSQTT_EVENTS").forEach(::remove)
                put("MCRF_BS_RUST_MODE",installation.mode);put("MCRF_BS_ADMISSION_PARENT_PID",android.os.Process.myPid().toString());put("MCRF_BS_ROUTING_ADMIT_ONLY","1");put("MCRF_BS_ROUTING_SESSION_SHA256",captured.digest)
                put("MCRF_BS_SERVER_ROUTING",if(installation.standaloneRouter)"mcrf"else serverRoutingMode)
                remove("CSQTT_PASSWORD")
                if(!installation.standaloneRouter)put("CSQTT_PASSWORD","admission-only")
                put("XDG_CACHE_HOME",directory.absolutePath)
            }
            val child=builder.start();process=child
            reader=Thread({try {child.inputStream.use {input->val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true){val count=input.read(buffer);if(count<0)break;if(bytes.size()+count>4096){invalid.set(true);child.destroyForcibly();break};bytes.write(buffer,0,count)};output.set(bytes.toByteArray())
            }}catch(_:Exception){invalid.set(true)}},"bs-routing-admission-output").apply {isDaemon=true;start()}
            val deadline=android.os.SystemClock.elapsedRealtime()+15_000
            while(!child.waitFor(20,java.util.concurrent.TimeUnit.MILLISECONDS)) {
                check(!cancelled()){ "Подготовка БС отменена." };check(android.os.SystemClock.elapsedRealtime()<deadline){ "БС: проверка правил не завершилась вовремя." }
            }
            val drain=checkNotNull(reader);drain.join(1000)
            val admissionOutput=output.get()
            val admitted=admissionOutput?.contentEquals("MCRF_BS_ROUTING_ADMITTED ${captured.digest}\n".toByteArray(Charsets.US_ASCII))==true
            if(!cancelled()&&(!admitted||child.exitValue()!=0||drain.isAlive||invalid.get())){
                // Never emit arbitrary native output: it may include profile data.
                val diagnostic=admissionOutput?.toString(Charsets.UTF_8).orEmpty()
                val code=Regex("""\bBS_NATIVE_(?:ADMISSION_[A-Z_]+|BUNDLE_[A-Z_]+)\b""")
                    .find(diagnostic)?.value?.take(64)
                android.util.Log.w("MCRF.BsAdmission","exit=${child.exitValue()} bytes=${admissionOutput?.size ?: 0} code=${code ?: "UNKNOWN"}")
            }
            check(!cancelled()&&!drain.isAlive&&!invalid.get()&&child.exitValue()==0&&
                admitted){ "БС: подготовленные правила не поддерживаются новым движком." }
        }finally {
            var interrupted=Thread.interrupted()
            try {try {
                process?.let {child->if(child.isAlive)child.destroyForcibly();val deadline=android.os.SystemClock.elapsedRealtime()+2000
                    while(child.isAlive&&android.os.SystemClock.elapsedRealtime()<deadline)try {child.waitFor(20,java.util.concurrent.TimeUnit.MILLISECONDS)}catch(_:InterruptedException){interrupted=true}
                    child.inputStream.close();check(!child.isAlive){"BS_NATIVE_ADMISSION_PROCESS"}}
            }finally {try {
                reader?.let {drain->val deadline=android.os.SystemClock.elapsedRealtime()+1000;while(drain.isAlive&&android.os.SystemClock.elapsedRealtime()<deadline)try{drain.join(20)}catch(_:InterruptedException){interrupted=true};check(!drain.isAlive){"BS_NATIVE_ADMISSION_READER"}}
            }finally {try{saved?.close()}finally{check(directory.delete()){ "BS_NATIVE_ADMISSION_CLEANUP" }}}}
            }finally{if(interrupted)Thread.currentThread().interrupt()}
        }
    }
    fun readPlan(directory:File,digest:String,hostPackage:String):BsNativeTunPlan.Plan {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        val file=File(directory,"routing-tun-plan.json")
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC,0)
        val bytes=java.io.FileInputStream(fd).use {input->
            val stat=android.system.Os.fstat(fd)
            check(android.system.OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_nlink==1L&&(stat.st_mode and 0x1ff)==0x180&&stat.st_size in 1L..2L*1024*1024)
            val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;check(result.size()+count<=2*1024*1024);result.write(buffer,0,count)}
            check(result.size().toLong()==stat.st_size);result.toByteArray()
        }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}==digest)
        return BsNativeTunPlan.parse(JSONObject(String(bytes,Charsets.UTF_8)),org.json.JSONArray(inbounds),hostPackage)
    }
    fun open(plan:BsNativeTunPlan.Plan)=openTun(plan)
    fun write(directory:File):Document {
        val file=File(directory,"routing-session.json")
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath())&&directory.isDirectory&&file.createNewFile())
        try {
            android.system.Os.chmod(file.path,0x180)
            java.io.FileOutputStream(file).use {it.write(document);it.fd.sync()}
            return Document(file,MessageDigest.getInstance("SHA-256").digest(document).joinToString(""){"%02x".format(it.toInt()and 255)})
        }catch(error:Exception){if(!file.delete())error.addSuppressed(java.io.IOException("BS_NATIVE_DOCUMENT_CLEANUP"));throw error}
    }
    fun networkCommand(context:Context,generation:Long):ByteArray {
        require(generation>0)
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val vpn=ownedVpn();val caps=checkNotNull(manager.getNetworkCapabilities(vpn))
        check(vpn!=physical&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        if(Build.VERSION.SDK_INT>=30)check(caps.ownerUid==android.os.Process.myUid())
        val links=checkNotNull(manager.getLinkProperties(vpn))
        val v4=links.linkAddresses.any {it.address is Inet4Address}
        val v6=links.linkAddresses.any {it.address is Inet6Address}
        check(v4||v6)
        check(manager.getNetworkCapabilities(physical)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false)
        return command(generation,vpn.networkHandle,v4,v6,this.context)
    }
    companion object {
        internal fun command(generation:Long,vpn:Long,v4:Boolean,v6:Boolean,context:ByteArray):ByteArray {
            require(generation>0&&vpn>0&&(v4||v6)&&context.size<=2048)
            return "BS_NATIVE_NETWORK|$generation|$vpn|${if(v4)1 else 0}|${if(v6)1 else 0}|${Base64.getEncoder().encodeToString(context)}\n".toByteArray(Charsets.US_ASCII)
        }
    }
    override fun toString()="BsNativeSessionLaunch(redacted)"
}
