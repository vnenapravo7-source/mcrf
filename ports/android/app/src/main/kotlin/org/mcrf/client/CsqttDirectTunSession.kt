package org.mcrf.client

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Allowlisted diagnostics only: never retain native output, exception messages or causes. */
internal object CsqttDirectTunDiagnostic {
    enum class Phase { START, CONFIG, TUN, IDENTITY, FD_CONNECT, FD_PEER, FD_SEND, ACK, ADOPTED, WORKER_READY, ACTIVE, SWITCH }
    enum class Cause { TIMEOUT, CANCELLED, PROCESS_EXIT, IO, INTERNAL, OWNER, PROTOCOL, CONFIG_INVALID,
        CONFIG_CHANGED, EVENT_INVALID, OUTPUT_EOF, OUTPUT_IO, NATIVE_FATAL, NATIVE_LOST, NATIVE_STOPPED, CAPTCHA, ZERO_ACTIVE, ACK_INVALID }
    const val IDENTITY=1;const val CONFIG=2;const val FD_SENT=4;const val ACK=8;const val ADOPTED=16;const val READY=32;const val ROUTING_READY=64
    data class Failure(val phase:Phase,val cause:Cause,val flags:Int=0) {
        val code get()="DT1/${phase.name}/${cause.name} flags=$flags"
        val detail get()="Прямой TUN: $code."
    }
    class Rejected(val diagnostic:Failure):IOException(diagnostic.code)
    fun failure(error:Exception,phase:Phase)=if(error is Rejected)error.diagnostic else Failure(phase,when(error) {
        is SocketTimeoutException->Cause.TIMEOUT
        is InterruptedException->Cause.CANCELLED
        is IOException->Cause.IO
        else->if(phase==Phase.IDENTITY||phase==Phase.FD_PEER)Cause.OWNER else Cause.INTERNAL
    })
    class State {
        @Volatile var phase=Phase.CONFIG
        private val flags=AtomicInteger()
        private val first=AtomicReference<Failure?>()
        fun mark(flag:Int){flags.getAndUpdate {it or flag}}
        fun retain(cause:Cause,at:Phase=phase):Failure {
            val value=Failure(at,cause,flags.get())
            first.compareAndSet(null,value)
            return checkNotNull(first.get())
        }
        fun retained()=first.get()
    }
}

/** One direct Rust child and one SCM_RIGHTS adoption. Never owns/closes the service's master TUN. */
internal class CsqttDirectTunSession private constructor(
    private val child:Process,private val token:Long,private val socketName:String,
    private val executable:File,private val directory:File,
    private val cancelled:()->Boolean,private val failed:()->Unit,
    private val nativeRouting:BsNativeSessionLaunch?,private val routingDocument:BsNativeSessionLaunch.Document?,
    private val routerOnly:Boolean,context:Context
):AutoCloseable {
    data class Config(val address:String,val dns:List<String>,val port:Int,val revision:String,val mtu:Int=1500)
    private val hostPackage=context.packageName
    private val nativePlan=AtomicReference<BsNativeTunPlan.Plan?>()
    private val closing=AtomicBoolean()
    private val closed=AtomicBoolean()
    private val stopSent=AtomicBoolean()
    private val failure=AtomicBoolean()
    private val diagnostic=CsqttDirectTunDiagnostic.State()
    private val adopted=AtomicBoolean()
    private val nativeReady=AtomicBoolean()
    private val announced=AtomicBoolean()
    private val configuration=AtomicReference<Config?>()
    private val identity=AtomicReference<CarrierProcessGroupProof.Root?>()
    private val up=AtomicLong()
    private val down=AtomicLong()
    private val streams=AtomicLong(-1)
    private val sessionCreatedAt=SystemClock.elapsedRealtime()
    private val ipc=CarrierIpcWriter(child.outputStream)
    private val routingNetworkReady=AtomicBoolean(nativeRouting==null)
    private val routingReady=AtomicBoolean(nativeRouting==null)
    private val ownerChannel=if(nativeRouting==null||nativeRouting.proxyOnly)null else BsNativeOwnerChannel(token,
        { !closing.get()&&!failure.get()&&!cancelled()&&child.isAlive&&identity.get()!=null&&routingNetworkReady.get() },SystemClock::elapsedRealtime,
        {query->BsNativeOwnerChannel.androidLookup(context.applicationContext,query) { !closing.get()&&!cancelled()&&routingNetworkReady.get()&&identity.get()!=null&&child.isAlive }},
        {reply->ipc.offer(reply).also {if(!it)signalFailure(CsqttDirectTunDiagnostic.Cause.PROTOCOL)}})
    @Volatile private var transfer:LocalSocket?=null
    @Volatile private var transferDeadline=Long.MAX_VALUE
    private val reader=Thread({readOutput(child.inputStream,true)},"mcrf-direct-tun-output").apply {isDaemon=true}
    private val diagnosticReader=Thread({readOutput(child.errorStream,false)},"mcrf-direct-tun-diagnostics").apply {isDaemon=true}
    private val watcher=Thread({
        while(!closing.get()) {
            if(cancelled()){requestStop();return@Thread}
            if(SystemClock.elapsedRealtime()>=transferDeadline){signalFailure(CsqttDirectTunDiagnostic.Cause.TIMEOUT);return@Thread}
            if(!child.isAlive){signalFailure(CsqttDirectTunDiagnostic.Cause.PROCESS_EXIT);return@Thread}
            SystemClock.sleep(50)
        }
    },"mcrf-direct-tun-owner").apply {isDaemon=true}

    fun isAlive()=!closing.get()&&!failure.get()&&child.isAlive&&adopted.get()&&nativeReady.get()&&routingReady.get()
    private val probeLock=Any();private var nextProbe=0L
    private val probes=linkedMapOf<Long,CompletableFuture<Long>>()
    fun health(url:String,timeout:Int)=health(listOf(url),timeout,false)
    fun startupHealth(urls:List<String>,timeout:Int)=health(urls,timeout,true)

    private fun health(urls:List<String>,timeout:Int,retryEarly:Boolean):Long {
        require(timeout in 1..30000&&urls.size in 1..2&&urls.distinct()==urls)
        val encoded=urls.map {url->
            require(url.toByteArray(Charsets.UTF_8).size<=8192&&url.none(Char::isISOControl))
            val uri=java.net.URI(url)
            require(uri.scheme in setOf("http","https")&&uri.host!=null&&uri.userInfo==null&&uri.fragment==null&&(uri.port==-1||uri.port in 1..65535))
            java.util.Base64.getEncoder().encodeToString(url.toByteArray(Charsets.UTF_8))
        }
        val issued=linkedMapOf<Long,CompletableFuture<Long>>()
        val deadline=SystemClock.elapsedRealtime()+timeout
        fun issue()=synchronized(probeLock){
            val remaining=(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
            check(nativeRouting!=null&&isAlive()&&!stopSent.get()&&probes.size+encoded.size<=4&&nextProbe<=Long.MAX_VALUE-encoded.size){"BS_NATIVE_PROBE_STATE"}
            encoded.forEach {url->
                val id=++nextProbe
                val future=CompletableFuture<Long>()
                issued[id]=future;probes[id]=future
                val command="BS_NATIVE_HEALTH|$token|$id|$remaining|$url\n"
                if(!ipc.offer(command.toByteArray(Charsets.US_ASCII)))throw IOException("BS_NATIVE_PROBE_QUEUE")
            }
        }
        return try{
            issue()
            val first=firstHealthy(issued.values.toList())
            if(retryEarly&&timeout>750){
                val early=try{first.get(750,TimeUnit.MILLISECONDS)}catch(_:TimeoutException){-1}
                if(early>=0)return early
                val completed=first.getNow(-1)
                if(completed>=0)return completed
                issue()
            }
            firstHealthy(issued.values.toList()).get((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)+2000,TimeUnit.MILLISECONDS)
        }catch(error:InterruptedException){Thread.currentThread().interrupt();throw IOException("BS_NATIVE_PROBE_INTERRUPTED",error)}
        finally{synchronized(probeLock){issued.keys.forEach(probes::remove)}}
    }
    private fun stopProbes(){val pending=synchronized(probeLock){probes.values.toList().also {probes.clear()}};pending.forEach {it.completeExceptionally(IOException("BS_NATIVE_PROBE_STOPPED"))}}
    fun counters()=longArrayOf(up.get(),down.get(),streams.get())
    fun failureDiagnostic()=diagnostic.retained()?:diagnostic.retain(CsqttDirectTunDiagnostic.Cause.PROCESS_EXIT)
    fun requestStop() {
        val first=stopSent.compareAndSet(false,true)
        stopProbes()
        ownerChannel?.close()
        if(first)ipc.requestStop()
        runCatching {transfer?.close()}
    }
    private fun signalFailure(cause:CsqttDirectTunDiagnostic.Cause,phase:CsqttDirectTunDiagnostic.Phase=diagnostic.phase) {
        if(closing.get())return
        diagnostic.retain(cause,phase)
        if(failure.compareAndSet(false,true)) {
            requestStop()
            if(announced.get())failed()
        }
    }
    private val vkBackoff=VkCredentialBackoffStore(context.applicationContext)
    private val providerLimit=AtomicReference<VkCredentialLimit?>()
    private fun checkLive(deadline:Long) {
        providerLimit.get()?.let {throw VkCredentialLimit.Blocked(it)}
        if(cancelled()||closing.get())throw CsqttDirectTunDiagnostic.Rejected(diagnostic.retain(CsqttDirectTunDiagnostic.Cause.CANCELLED))
        if(failure.get()||!child.isAlive)throw CsqttDirectTunDiagnostic.Rejected(failureDiagnostic())
        if(SystemClock.elapsedRealtime()>=deadline)throw CsqttDirectTunDiagnostic.Rejected(diagnostic.retain(CsqttDirectTunDiagnostic.Cause.TIMEOUT))
    }
    private fun adopt(descriptor:ParcelFileDescriptor,deadline:Long) {
        transferDeadline=deadline
        diagnostic.phase=CsqttDirectTunDiagnostic.Phase.IDENTITY
        val root=checkNotNull(identity.get()) {"DIRECT_TUN_IDENTITY_MISSING"}
        val pid=root.pid
        while(true) {
            checkLive(deadline)
            val socket=LocalSocket();transfer=socket
            var sent=false
            try {
                diagnostic.phase=CsqttDirectTunDiagnostic.Phase.FD_CONNECT
                socket.connect(LocalSocketAddress(socketName,LocalSocketAddress.Namespace.ABSTRACT))
                diagnostic.phase=CsqttDirectTunDiagnostic.Phase.FD_PEER
                val peer=socket.peerCredentials
                check(peer.uid==android.os.Process.myUid()&&peer.pid==pid) {"DIRECT_TUN_PEER"}
                check(verifiedIdentity(pid).birth==root.birth) {"DIRECT_TUN_PROCESS_REPLACED"}
                checkLive(deadline)
                diagnostic.phase=CsqttDirectTunDiagnostic.Phase.FD_SEND
                socket.setFileDescriptorsForSend(arrayOf(descriptor.fileDescriptor))
                socket.soTimeout=(deadline-SystemClock.elapsedRealtime()).coerceIn(1,10_000).toInt()
                sent=true // Never retry/retransfer after SCM_RIGHTS may have crossed into this child.
                diagnostic.mark(CsqttDirectTunDiagnostic.FD_SENT)
                socket.outputStream.write(1);socket.outputStream.flush()
                diagnostic.phase=CsqttDirectTunDiagnostic.Phase.ACK
                // Receipt byte is not adoption; only this owned child's generation-tagged event is.
                if(socket.inputStream.read()!=1)throw CsqttDirectTunDiagnostic.Rejected(diagnostic.retain(CsqttDirectTunDiagnostic.Cause.ACK_INVALID))
                diagnostic.mark(CsqttDirectTunDiagnostic.ACK)
                break
            }catch(error:IOException) {
                if(sent)throw CsqttDirectTunDiagnostic.Rejected(diagnostic.retain(CsqttDirectTunDiagnostic.failure(error,diagnostic.phase).cause))
                checkLive(deadline)
                SystemClock.sleep(50)
            }finally{runCatching {socket.close()};transfer=null}
        }
        while(!adopted.get()||!nativeReady.get()||!routingReady.get()){
            diagnostic.phase=if(!adopted.get())CsqttDirectTunDiagnostic.Phase.ADOPTED else CsqttDirectTunDiagnostic.Phase.WORKER_READY
            checkLive(deadline);SystemClock.sleep(25)
        }
        diagnostic.phase=CsqttDirectTunDiagnostic.Phase.ACTIVE
        transferDeadline=Long.MAX_VALUE
    }
    private val observedStartupEvents=ConcurrentHashMap.newKeySet<String>()
    private fun startupEvent(code:String) {
        if(nativeRouting!=null&&BuildConfig.DEBUG&&observedStartupEvents.add(code))
            android.util.Log.w("MCRF.BsStartup",code)
    }
    private val lastQueueWarnings=mutableMapOf<String,Long>()
    private fun reportQueuePressure(text:String) {
        val path=when {
            "BS_NATIVE_PACKET_DROP QueueTcp" in text->"очередь TCP на входе TUN"
            "BS_NATIVE_PACKET_DROP QueueUdp" in text->"очередь UDP на входе TUN"
            "BS_NATIVE_PACKET_DROP QueueService" in text->"очередь служебных пакетов на входе TUN"
            "BS_NATIVE_PACKET_DROP Queue" in text->"очередь пакетов TUN"
            "BS_NATIVE_TCP_EVENT QueueFull" in text->"очередь TCP-потока"
            "BS_NATIVE_TCP_QUEUE_FULL" in text->"очередь локального стека TCP"
            "BS_NATIVE_SERVICE_ADMISSION_QUEUE" in text->"очередь служебных пакетов"
            else->return
        }
        val now=SystemClock.elapsedRealtime()
        if(lastQueueWarnings[path]?.let {now-it<5000}==true)return
        lastQueueWarnings[path]=now
        android.util.Log.w("MCRF.BsQueue",path)
    }
    private fun diagnosticLine(text:String) {
        if(nativeRouting!=null)reportQueuePressure(text)
        if(nativeRouting!=null&&BuildConfig.DEBUG&&text.startsWith("MCRF_BS_TRAFFIC|")){
            val fields=text.substringAfter('|').split('|')
            val values=fields.mapNotNull {it.toLongOrNull()?.takeIf {n->n>=0}}
            val names=listOf("ms","streams","tx","rx","turnWaits","turnWaitUs","udpDrops","tunQueueDrops","tunWriteDrops",
                "tcpPayload","tcpRepeat","tcpAcked","carrierRejects","workerChanges")
            if(fields.size in setOf(9,names.size)&&values.size==fields.size){
                android.util.Log.i("MCRF.BsTraffic",names.zip(values).joinToString(" "){(name,value)->"$name=$value"})
            }
            return
        }
        if(providerLimit.get()==null)VkCredentialLimit.read(text)?.let {limit->
            val pause=VkCredentialBackoffStore.backoff(limit)
            CsqttProviderCooldown.observe(pause)
            val recorded=runCatching {vkBackoff.observe(limit)}.getOrDefault(pause)
            providerLimit.set(recorded)
            requestStop()
            if(announced.get())failed()
            return
        }
        if(nativeRouting!=null&&!announced.get())when {
            nativeRouting.proxyOnly&&PROXY_STARTUP_CODE.matches(text)->startupEvent(text)
            "[GETCONF][RETRY]" in text->startupEvent("GETCONF_TIMEOUT")
            "WRAP_AUTH_TIMEOUT" in text->startupEvent("WRAP_AUTH_TIMEOUT")
            "[TURN][RETRY]" in text&&"STUN error" !in text->startupEvent("TURN_RETRY")
            "STUN error 401" in text->startupEvent("TURN_401")
            "STUN error 441" in text->startupEvent("TURN_441")
            "STUN error 486" in text->startupEvent("TURN_QUOTA")
            "BS_NATIVE_HEALTH_STAGE " in text->{
                val stage=text.substringAfter("BS_NATIVE_HEALTH_STAGE ").substringBefore(' ')
                if(stage in setOf("Resolve","Connect","Tls","Io","Http","Timeout"))startupEvent("HEALTH_$stage")
            }
        }
    }
    private fun line(text:String) {
        when {
            text.startsWith(BsNativeOwnerChannel.PREFIX)->{
                if(ownerChannel?.offer(text)!=true)signalFailure(CsqttDirectTunDiagnostic.Cause.PROTOCOL)
            }
            text.startsWith("MCRF_BS_NATIVE_PLAN ")&&nativeRouting!=null->{
                val fields=text.removePrefix("MCRF_BS_NATIVE_PLAN ").split(' ')
                check(fields.size==2&&fields[0]==token.toString()) {"BS_NATIVE_PLAN_TOKEN"}
                val plan=nativeRouting.readPlan(directory,fields[1],hostPackage)
                check(nativePlan.compareAndSet(null,plan)) {"BS_NATIVE_PLAN_DUPLICATE"}
            }
            text=="MCRF_BS_NATIVE_NETWORK_READY $token"&&nativeRouting!=null->{
                if(!routingNetworkReady.compareAndSet(false,true))signalFailure(CsqttDirectTunDiagnostic.Cause.PROTOCOL)
            }
            text.startsWith(BsNativeContract.ROUTER_LISTENING)->{
                check(routerOnly&&text=="${BsNativeContract.ROUTER_LISTENING}$token"&&nativeReady.compareAndSet(false,true)){
                    "BS_NATIVE_ROUTER_LISTENING_TOKEN"
                }
                diagnostic.mark(CsqttDirectTunDiagnostic.READY)
            }
            text.startsWith(BsNativeContract.PROXY_READY)->{
                check(nativeRouting?.proxyOnly==true&&text=="${BsNativeContract.PROXY_READY}$token"&&
                    identity.get()!=null&&nativeReady.compareAndSet(false,true)){"BS_NATIVE_PROXY_READY_TOKEN"}
                routingReady.set(true)
                adopted.set(true)
                diagnostic.mark(CsqttDirectTunDiagnostic.READY)
            }
            text.startsWith("MCRF_BS_HEALTH_RESULT")-> {
                check(nativeRouting!=null);val fields=text.split(' ');require(fields.size==4&&fields[0]=="MCRF_BS_HEALTH_RESULT"&&fields[1]==token.toString())
                require(fields[2].matches(Regex("[1-9][0-9]{0,18}")));val id=fields[2].toLong()
                require(fields[3]=="-1"||fields[3].matches(Regex("0|[1-9][0-9]{0,4}")));val latency=fields[3].toLong();require(latency in -1L..30000L)
                if(BuildConfig.DEBUG&&latency<0)android.util.Log.w("MCRF.BsHealth","probe rejected; activeStreams=${streams.get()} sent=${up.get()} received=${down.get()}")
                synchronized(probeLock){require(id<=nextProbe);probes.remove(id)?.complete(latency)}
            }
            text=="MCRF_BS_NATIVE_READY $token"&&nativeRouting!=null->{
                if(routingReady.compareAndSet(false,true))diagnostic.mark(CsqttDirectTunDiagnostic.ROUTING_READY)
                else signalFailure(CsqttDirectTunDiagnostic.Cause.PROTOCOL)
            }
            text.startsWith("MCRF_BS_NATIVE_")->signalFailure(CsqttDirectTunDiagnostic.Cause.PROTOCOL)
            text.startsWith("MCRF_DIRECT_TUN_IDENTITY ")->try {
                val fields=text.removePrefix("MCRF_DIRECT_TUN_IDENTITY ").split(' ')
                check(fields.size==2&&fields[0]==token.toString()) {"DIRECT_TUN_IDENTITY_TOKEN"}
                val pid=checkNotNull(fields[1].toIntOrNull())
                check(fields[1]==pid.toString()&&identity.compareAndSet(null,verifiedIdentity(pid))) {"DIRECT_TUN_IDENTITY_DUPLICATE"}
                diagnostic.mark(CsqttDirectTunDiagnostic.IDENTITY)
            }catch(_:Exception){
                throw CsqttDirectTunDiagnostic.Rejected(diagnostic.retain(CsqttDirectTunDiagnostic.Cause.OWNER,CsqttDirectTunDiagnostic.Phase.IDENTITY))
            }
            text=="MCRF_DIRECT_TUN_ADOPTED $token"->{diagnostic.mark(CsqttDirectTunDiagnostic.ADOPTED);adopted.set(true)}
            text=="MCRF_DIRECT_TUN_LOST $token"->signalFailure(CsqttDirectTunDiagnostic.Cause.NATIVE_LOST)
            text.startsWith("MCRF_DIRECT_TUN_")->signalFailure(CsqttDirectTunDiagnostic.Cause.PROTOCOL) // Wrong/stale token is never accepted.
            text.startsWith("__CSQTT_EVENT__|")-> {
                val pieces=text.split('|',limit=3)
                if(pieces.size!=3)return
                val data=JSONObject(pieces[2])
                when(pieces[1]) {
                    "PROGRESS"->if(data.optString("kind")=="credentials")startupEvent("CREDENTIALS_READY")
                    "CONFIG"-> {
                        val value=try{parseConfig(data.getString("config"))}catch(_:Exception){
                            throw CsqttDirectTunDiagnostic.Rejected(diagnostic.retain(CsqttDirectTunDiagnostic.Cause.CONFIG_INVALID,CsqttDirectTunDiagnostic.Phase.CONFIG))
                        }
                        val prior=configuration.get()
                        if(prior!=null&&prior!=value)signalFailure(CsqttDirectTunDiagnostic.Cause.CONFIG_CHANGED)
                        else {diagnostic.mark(CsqttDirectTunDiagnostic.CONFIG);configuration.compareAndSet(null,value)}
                    }
                    "READY"->{diagnostic.mark(CsqttDirectTunDiagnostic.READY);nativeReady.set(true)}
                    "TUN_LOST"->signalFailure(CsqttDirectTunDiagnostic.Cause.NATIVE_LOST)
                    "STOPPED"->signalFailure(CsqttDirectTunDiagnostic.Cause.NATIVE_STOPPED)
                    "CAPTCHA"->signalFailure(CsqttDirectTunDiagnostic.Cause.CAPTCHA)
                    "ACTIVE_ZERO"->if(!routerOnly&&announced.get())signalFailure(CsqttDirectTunDiagnostic.Cause.ZERO_ACTIVE)
                    "ERROR"->if(data.optBoolean("fatal"))signalFailure(CsqttDirectTunDiagnostic.Cause.NATIVE_FATAL)
                    "STATS"->{
                        val active=if(routerOnly)-1 else data.optLong("active",-1)
                        if(active==0L&&announced.get())signalFailure(CsqttDirectTunDiagnostic.Cause.ZERO_ACTIVE)
                        val previous=streams.getAndSet(active)
                        if(BuildConfig.DEBUG&&nativeRouting!=null&&active!=previous&&active>=0&&SystemClock.elapsedRealtime()-sessionCreatedAt<15_000)
                            android.util.Log.i("MCRF.BsStreams","active=$active transportReady=${announced.get()}")
                        up.set(data.optLong("bytes_up",0).coerceAtLeast(0));down.set(data.optLong("bytes_down",0).coerceAtLeast(0))
                    }
                }
            }
        }
    }
    private fun verifiedIdentity(pid:Int):CarrierProcessGroupProof.Root {
        val root=CarrierProcessGroupProof.stat(pid,File("/proc/$pid/stat").readText())
        check(root.parent==android.os.Process.myPid()&&root.pid!=android.os.Process.myPid()&&
            android.system.Os.stat("/proc/$pid").st_uid==android.os.Process.myUid()&&
            android.system.Os.readlink("/proc/$pid/exe")==executable.canonicalPath&&
            android.system.Os.readlink("/proc/$pid/cwd")==directory.canonicalPath) {"DIRECT_TUN_PROCESS_OWNER"}
        return root
    }
    private fun readOutput(stream:InputStream,control:Boolean) {
        try {
            stream.use {input->
                val bytes=ByteArrayOutputStream(1024);val buffer=ByteArray(4096);var overflow=false
                while(true) {
                    val count=input.read(buffer);if(count<0)break
                    for(index in 0 until count) {
                        val byte=buffer[index].toInt()and 255
                        if(byte==10){
                            if(!overflow){
                                val text=bytes.toString("UTF-8").trimEnd('\r')
                                if(control)line(text)else diagnosticLine(text)
                            }
                            bytes.reset();overflow=false
                        }
                        else if(bytes.size()<16384&&!overflow)bytes.write(byte)else{overflow=true;bytes.reset()}
                    }
                }
            }
        }catch(error:Exception){
            if(!control){
                if(!closing.get())startupEvent("DIAGNOSTIC_OUTPUT_LOST")
                return
            }
            val value=if(error is CsqttDirectTunDiagnostic.Rejected)error.diagnostic
                else CsqttDirectTunDiagnostic.Failure(diagnostic.phase,if(error is IOException)CsqttDirectTunDiagnostic.Cause.OUTPUT_IO else CsqttDirectTunDiagnostic.Cause.EVENT_INVALID)
            signalFailure(value.cause,value.phase)
        }finally{if(control)signalFailure(CsqttDirectTunDiagnostic.Cause.OUTPUT_EOF)}
    }
    @Synchronized override fun close() {
        if(closed.get())return
        closing.set(true);requestStop()
        if(!child.waitFor(3,TimeUnit.SECONDS))child.destroy()
        if(!child.waitFor(3,TimeUnit.SECONDS))child.destroyForcibly()
        if(!child.waitFor(3,TimeUnit.SECONDS))throw IOException("Прямой TUN: ожидаем завершения принадлежащего сеансу Rust.")
        ipc.childExited()
        if(!ipc.awaitCompletion(1000).writerExited)throw IOException("Прямой TUN: канал управления ещё закрывается.")
        runCatching {child.inputStream.close()}
        runCatching {child.errorStream.close()}
        reader.join(1000);diagnosticReader.join(1000);watcher.join(1000)
        if(ownerChannel?.awaitStopped(1000)==false)throw IOException("БС: ожидаем завершения проверки владельцев соединений.")
        if(reader.isAlive||diagnosticReader.isAlive||watcher.isAlive)throw IOException("Прямой TUN: ожидаем завершения принадлежащих сеансу callbacks.")
        routingDocument?.close()
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath())&&directory.listFiles()?.isEmpty()==true&&directory.delete()) {"DIRECT_TUN_DIRECTORY_CLEANUP"}
        closed.set(true)
    }
    override fun toString()="CsqttDirectTunSession(redacted)"

    companion object {
        private val PROXY_STARTUP_CODE=Regex("BS_NATIVE_(?:PROXY_[A-Z_]+|PHYSICAL_REQUIRED|SYSTEM_HOSTS|ADMISSION_PARENT_GUARD)")
        internal fun firstHealthy(attempts:List<CompletableFuture<Long>>):CompletableFuture<Long>{
            require(attempts.isNotEmpty())
            val result=CompletableFuture<Long>()
            attempts.forEach {attempt->attempt.thenAccept {if(it>=0)result.complete(it)}}
            CompletableFuture.allOf(*attempts.toTypedArray()).whenComplete {_,error->
                if(error!=null)result.completeExceptionally(error)
                else result.complete(attempts.firstNotNullOfOrNull {it.getNow(-1).takeIf {value->value>=0}}?:-1)
            }
            return result
        }
        fun effectiveProfile(context:Context,raw:JSONObject):JSONObject {
            CsqttDirectTunSupport.profileReason(raw,true)?.let {throw IOException(it)}
            val effective=CarrierStreamDefaults.apply(VkRelayRuntime.withManualSupplements(raw,VkRelayAccount(AndroidDeployStore(context))),CarrierStreamDefaults.load(context))
            return checkNotNull(Csqtt25Profile.fromJson("direct-tun",effective).carrier)
        }
        fun start(context:Context,effective:JSONObject?,installation:BsDirectTunContract.Installation,
                  generation:Long,cancelled:()->Boolean,openTun:(Config)->ParcelFileDescriptor,failed:()->Unit,
                  nativeRouting:BsNativeSessionLaunch?=null):CsqttDirectTunSession {
            check(generation>0)
            val routerOnly=effective==null
            require(!routerOnly||nativeRouting!=null){"BS_NATIVE_ROUTER_POLICY_REQUIRED"}
            val socketName="mcrf-direct-${UUID.randomUUID()}"
            val proxyOnly=nativeRouting?.proxyOnly==true
            require(!proxyOnly||routerOnly&&installation.standaloneRouter&&BsNativeContract.proxyHint())
            val arguments=if(proxyOnly)listOf(installation.executable.absolutePath,"--mcrf-router-proxy")
                else if(effective==null)listOf(installation.executable.absolutePath,"-tun-uds",socketName)else {
                VkCredentialBackoffStore(context).requireConnectionAllowed(effective)
                val port=DatagramSocket(0).use {it.localPort}
                val host=effective.getString("server");val peer="${if(':' in host)"[$host]"else host}:${effective.getInt("server_port")}"
                val hashes=effective.getJSONArray("hashes").let {a->(0 until a.length()).map(a::getString)}
                val arguments=mutableListOf(installation.executable.absolutePath,"-peer",peer,"-listen","127.0.0.1:$port","-tun-uds",socketName,
                    "-n",effective.getInt("workers").toString(),"--exact-workers","-vk",hashes.joinToString(","),"-vk-hash-mode","manual",
                    "-fingerprint",effective.optString("fingerprint","chrome"),"-obfs",effective.optString("obfs","unified"),
                    "-turn-transport",effective.optString("turn_transport","udp"),"-srtp-mode",Csqtt25Profile.srtpMode(effective),
                    "-vk-auth-mode",effective.optString("vk_auth_mode","vkcalls"),"-captcha-mode",effective.optString("captcha_mode","auto"))
                if(CsqttGooby.active(effective))NativeCarrier.requireOptions(context,effective)
                arguments.addAll(CsqttGooby.arguments(effective))
                effective.optString("client_ids").takeIf {it.isNotEmpty()}?.let {arguments.addAll(listOf("-client-ids",it))}
                effective.optJSONArray("custom_turn_ips")?.takeIf {it.length()>0}?.let {a->arguments.addAll(listOf("-custom-turn-ips",(0 until a.length()).joinToString(","){a.getString(it)}))}
                effective.optLong("generation",0).takeIf {it>0}?.let {arguments.addAll(listOf("-gen",it.toString()))}
                effective.optString("salt").takeIf {it.isNotEmpty()}?.let {arguments.addAll(listOf("-salt",it))}
                arguments
            }
            val directory=File(context.cacheDir,"mcrf-direct-tun-${UUID.randomUUID()}")
            check(directory.mkdir())
            try{android.system.Os.chmod(directory.path,0x1c0)}catch(error:Exception){directory.delete();throw error}
            val routingDocument=try{nativeRouting?.write(directory)}catch(error:Exception){directory.delete();throw error}
            val builder=ProcessBuilder(arguments).directory(directory)
            builder.environment().apply {
                keys.filter {it.startsWith("MCRF_BS_")}.toList().forEach(::remove)
                listOf("MCRF_BS_MODE","MCRF_BS_TRANSPORT","MCRF_BS_LAYOUT","MCRF_PROCESS_OWNER","CSQTT_CONFIG_FILE","LD_PRELOAD","LD_LIBRARY_PATH").forEach(::remove)
                if(nativeRouting!=null){
                    put("MCRF_BS_ROUTING_SESSION_SHA256",checkNotNull(routingDocument).digest)
                    if(proxyOnly)put("MCRF_BS_PROXY_NETWORK",nativeRouting.proxyNetworkContext())
                    else put("MCRF_BS_OWNER_CHANNEL","1")
                    put("MCRF_BS_UNDERLAY_NETWORK",nativeRouting.physical.networkHandle.toString())
                    if(BuildConfig.DEBUG)put("MCRF_BS_TRAFFIC_DIAGNOSTICS","1")
                }
                put("MCRF_BS_RUST_MODE",installation.mode);put("MCRF_DIRECT_TUN_GENERATION",generation.toString())
                put("MCRF_DIRECT_TUN_PARENT_PID",android.os.Process.myPid().toString())
                put("CSQTT_EVENTS","1");put("XDG_CACHE_HOME",directory.absolutePath)
                if(routerOnly){remove("CSQTT_PASSWORD");put("MCRF_BS_ROUTER_ONLY","1")}
                else put("CSQTT_PASSWORD",checkNotNull(effective).getString("password"))
            }
            val process=try{if(cancelled())throw IOException("Прямой TUN: запуск отменён.");builder.start()}
                catch(error:Exception){routingDocument?.close();check(directory.delete());throw error}
            val owner=CsqttDirectTunSession(process,generation,socketName,installation.executable,directory,cancelled,failed,nativeRouting,routingDocument,routerOnly,context.applicationContext)
            try {
                owner.reader.start();owner.diagnosticReader.start();owner.watcher.start()
                val deadline=SystemClock.elapsedRealtime()+60_000
                while((if(routerOnly)!owner.nativeReady.get()else owner.configuration.get()==null)||
                    (!proxyOnly&&nativeRouting!=null&&owner.nativePlan.get()==null)||
                    (proxyOnly&&!owner.isAlive())){
                    owner.checkLive(deadline);SystemClock.sleep(25)
                }
                owner.checkLive(deadline)
                if(proxyOnly){
                    owner.diagnostic.phase=CsqttDirectTunDiagnostic.Phase.ACTIVE
                    owner.announced.set(true)
                    return owner
                }
                owner.diagnostic.phase=CsqttDirectTunDiagnostic.Phase.TUN
                val descriptor=if(nativeRouting==null)openTun(checkNotNull(owner.configuration.get()))
                    else nativeRouting.open(checkNotNull(owner.nativePlan.get()))
                owner.checkLive(deadline)
                if(nativeRouting!=null){
                    check(owner.ipc.offer(nativeRouting.networkCommand(context,generation))) {"BS_NATIVE_NETWORK_QUEUE"}
                    while(!owner.routingNetworkReady.get()){owner.checkLive(deadline);SystemClock.sleep(10)}
                }
                owner.adopt(descriptor,minOf(deadline,SystemClock.elapsedRealtime()+10_000))
                owner.checkLive(deadline);owner.announced.set(true)
                return owner
            }catch(error:Exception){
                val value=owner.diagnostic.retained()?:owner.diagnostic.retain(CsqttDirectTunDiagnostic.failure(error,owner.diagnostic.phase).cause)
                if(BuildConfig.DEBUG)android.util.Log.w("MCRF.BS",value.code+" "+error.javaClass.name+":"+error.stackTrace.take(5).joinToString(","){"${it.className}.${it.methodName}:${it.lineNumber}"})
                OptionalCoreRetirement.drain(owner)
                owner.providerLimit.get()?.let {throw VkCredentialLimit.Blocked(it)}
                throw CsqttDirectTunDiagnostic.Rejected(value)
            }
        }
        internal fun parseConfig(text:String):Config {
            check(text.length<=65536&&text.none {it=='\r'||it=='\n'||it=='\u0000'})
            val match=checkNotNull(Regex("^TUNCONF:([^:]+):(.+):([0-9]{1,5}):([^:\\r\\n]{1,128})(?::([^\\r\\n]*))?$").matchEntire(text.trim()))
            val address=match.groupValues[1].trim()
            fun ipv4(value:String)=RouteRules.literal(value).let {it is java.net.Inet4Address&&!it.isAnyLocalAddress&&!it.isLoopbackAddress&&!it.isMulticastAddress}
            check(ipv4(address))
            val dns=match.groupValues[2].split(',').map {it.trim().removeSurrounding("[","]")}
            // v1 deliberately blocks IPv6, including native-provided IPv6-only DNS; no invented DNS fallback.
            check(dns.size in 1..16&&dns.all(::ipv4))
            val port=match.groupValues[3].toInt();check(port in 0..65535)
            val revision=match.groupValues[4];check(revision.isNotBlank())
            return Config(address,dns,port,revision)
        }
    }
}
