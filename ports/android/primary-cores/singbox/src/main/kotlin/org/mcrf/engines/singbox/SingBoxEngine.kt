package org.mcrf.engines.singbox

import android.content.Context
import io.nekohasekai.libbox.*
import org.mcrf.engine.spi.v1.Engine
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Installed module entry, never compiled/packaged into base. Context bootstrap is host-owned.
 * Only validated DEFERRED VPN or NONE proxy requests may start this adapter. */
class SingBoxEngine(context: Context): Engine {
    private val application=context.applicationContext
    private val once=AtomicBoolean()
    private val stop=CountDownLatch(1)
    @Volatile private var runningGeneration=0L
    private val metrics=SingBoxMetricState()
    private val commands=Any()
    private var commandServer:CommandServer?=null
    private var commandsReady=false
    private val commandClients=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CommandClient,Boolean>())
    init { go.Seq.setContext(application) }
    private val capabilityJson: String by lazy { JSONObject().put("schema",1).put("kind","sing-box").put("spi",1).put("coreApi",1)
        .put("nativeVersion",Libbox.version()).put("fullNativeConfig",true).put("tunPlanMode","deferred-native-options")
        .put("proxyOnly",true).put("isolatedProbe",true).put("trafficSource","native-status-callback").put("blockedAttemptsAvailable",false)
        .put("oneRunPerProcess",true).toString() }
    override fun capabilitiesJson(): String=capabilityJson
    private fun capabilitySha256(): String=java.security.MessageDigest.getInstance("SHA-256")
        .digest(capabilityJson.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
    override fun validate(serverUrl: String,immutableSettingsJson: String): Engine.Validation = try {
        val config=SingBoxRuntimeConfig.parse(immutableSettingsJson)
        Libbox.checkConfig(config.nativeJson())
        if(config.mode!="vpn")Engine.Validation.proxyValidated(serverUrl,immutableSettingsJson)
        else Engine.Validation.deferredVpnValidated(serverUrl,immutableSettingsJson,capabilitySha256())
    } catch(_:Exception) {Engine.Validation(false,"SINGBOX_CONFIG","config")}
    override fun validate(profile: String,settings: String,mode: Engine.Mode): Engine.Validation {
        val result=validate(profile,settings)
        return if(result.supported && result.mode!=mode)Engine.Validation(false,"CORE_MODE_MISMATCH","mode") else result
    }
    override fun validate(profile: String,settings: String,mode: Engine.Mode,acquisition: Engine.TunAcquisition): Engine.Validation {
        val result=validate(profile,settings,mode)
        return if(result.supported && result.tunAcquisition!=acquisition)Engine.Validation(false,"CORE_TUN_ACQUISITION_MISMATCH","tunAcquisition") else result
    }
    override fun tunPlanJson(serverUrl: String,immutableSettingsJson: String): String {
        val config=SingBoxRuntimeConfig.parse(immutableSettingsJson)
        return JSONObject(config.preflightTunPlan()).put("tunAcquisition",if(config.mode=="vpn")"DEFERRED" else "NONE")
            .put("capabilitySha256",capabilitySha256()).toString()
    }
    override fun run(request: Engine.Request,host: Engine.Host) {
        require(once.compareAndSet(false,true)) { "FRESH_CORE_PROCESS_REQUIRED" }
        val config=SingBoxRuntimeConfig.parse(request.settingsJson)
        require(request.borrowedTunFd == -1 && request.vpnNetworkHandle==0L) { "CORE_TUN_MODE_FD" }
        require(request.tunAcquisition==if(config.mode=="vpn")Engine.TunAcquisition.DEFERRED else Engine.TunAcquisition.NONE) { "CORE_TUN_ACQUISITION_MISMATCH" }
        require((config.mode!="vpn")== (request.mode==Engine.Mode.PROXY)) { "CORE_REQUEST_MODE_MISMATCH" }
        val platform=host.platform()
        val directory=File(request.stateDirectory).canonicalFile
        require(directory.isDirectory && directory.path.startsWith(application.cacheDir.canonicalPath+File.separator) &&
            directory.name.startsWith("mcrf-run-") && !java.nio.file.Files.isSymbolicLink(File(request.stateDirectory).toPath())) { "CORE_STATE_DIRECTORY" }
        val adapter=SingBoxPlatformAdapter(request.generation,host,platform,config) {stop.count==0L}
        val active=SingBoxCallbackGuard(request.generation,{stop.count==0L || host.stopping(request.generation)},{false})
        runningGeneration=request.generation
        var server:CommandServer?=null;var telemetry:CommandClient?=null
        try {
            active.current() // An already-cancelled generation must not initialize/start listeners.
            val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
            Libbox.setup(SetupOptions().apply {setBasePath(directory.path);setWorkingPath(directory.path);setTempPath(directory.path)
                setFixAndroidStack(true);setLogMaxLines(1000);setCommandServerListenPort(port);setCommandServerSecret(UUID.randomUUID().toString())})
            Libbox.checkConfig(config.nativeJson())
            active.current()
            val handler=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(CommandServerHandler::class.java)) { proxy,method,args ->
                when(method.name) {
                    "equals"->proxy===args?.get(0);"hashCode"->System.identityHashCode(proxy);"toString"->"SingBoxCommandHandler"
                    "serviceStop"->{requestStop();null}
                    "serviceReload"->throw IOException("RECONNECT_REQUIRED")
                    "getSystemProxyStatus"->SystemProxyStatus()
                    "writeDebugMessage"->null // never forward native messages containing profile secrets
                    else->throw IOException("ANDROID_COMMAND_UNSUPPORTED_${method.name}")
                }
            } as CommandServerHandler
            val started=Libbox.newCommandServer(handler,adapter.native);server=started
            active.current();started.start();active.current();started.startOrReloadService(config.nativeJson(),OverrideOptions())
            active.current()
            synchronized(commands) {commandServer=started;commandsReady=true}
            val options=CommandClientOptions().apply {addCommand(Libbox.CommandStatus);setStatusInterval(2_000_000_000L)}
            val callbacks=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(CommandClientHandler::class.java)) { proxy,method,args ->
                when(method.name) {
                    "equals"->proxy===args?.get(0);"hashCode"->System.identityHashCode(proxy);"toString"->"SingBoxTrafficHandler"
                    "writeStatus"->{val value=args!![0] as StatusMessage
                        if(value.getTrafficAvailable() && stop.count!=0L && !host.stopping(request.generation)) {
                            metrics.observed(value.getUplinkTotal(),value.getDownlinkTotal())
                            platform.traffic(request.generation,value.getUplinkTotal(),value.getDownlinkTotal())
                        };null}
                    "connected","disconnected","clearLogs"->null
                    else->throw IOException("UNSUBSCRIBED_CORE_EVENT_${method.name}")
                }
            } as CommandClientHandler
            telemetry=Libbox.newCommandClient(callbacks,options).also {it.connect()}
            active.current()
            host.state(request.generation,"RUNNING","") // lifecycle only, not connectivity/reachability
            while(!host.stopping(request.generation) && !stop.await(250,TimeUnit.MILLISECONDS)) { }
        } finally {
            closeCommands()
            // Main's STOP ACK additionally waits for worker death and its owned TUN/PFD closure.
            try { try {telemetry?.disconnect()} finally {
                try {server?.closeService()} finally {try {server?.close()} finally {adapter.close()}}
            } } finally {runningGeneration=0;metrics.clear()}
        }
    }
    private fun closeCommands() {
        val clients=synchronized(commands) {commandsReady=false;commandServer=null;commandClients.toList()}
        clients.forEach {try {it.disconnect()}catch(_:Exception) { }}
    }
    override fun requestStop() {stop.countDown();closeCommands()}
    private fun tag(value:String) {require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size<=1024 && value.none(Char::isISOControl)) {"CORE_TAG"}}
    private fun <T> withCommand(run: (CommandClient)->T): T {
        val (client,generation)=synchronized(commands) {
            require(commandsReady && stop.count!=0L && runningGeneration>0 && commandClients.size<32) {"CORE_COMMAND_UNAVAILABLE"}
            val next=Libbox.newStandaloneCommandClient();commandClients.add(next);next to runningGeneration
        }
        try {
            val result=run(client)
            synchronized(commands) {require(commandsReady && stop.count!=0L && runningGeneration==generation) {"CORE_COMMAND_CANCELLED"}}
            return result
        } finally {synchronized(commands) {commandClients.remove(client)};try {client.disconnect()}catch(_:Exception) { }}
    }
    /** Real private RPC; failures/cancellation return -1, never exception text/profile URLs. */
    fun urlTest(tag:String,url:String,timeoutMillis:Int): Long = try {
        tag(tag);require(timeoutMillis in 1..30000) {"CORE_TEST_TIMEOUT"}
        val parsed=java.net.URI(url);require(parsed.scheme in setOf("http","https") && !parsed.host.isNullOrEmpty() && parsed.userInfo==null && url.length<=4096) {"CORE_TEST_URL"}
        withCommand {client -> val result=client.urlTestOutbound(tag,url,timeoutMillis);if(result.getError().isEmpty() && result.getDelay()>=0)result.getDelay().toLong() else -1L}
    } catch(_:Exception) {-1L}
    fun resetNetwork() {
        try {synchronized(commands) {require(commandsReady && stop.count!=0L && runningGeneration>0);commandServer!!.resetNetwork()}}
        catch(_:Exception) {throw IOException("CORE_NETWORK_RESET_FAILED")}
    }
    fun selectOutbound(group:String,tag:String) {
        try {tag(group);tag(tag);withCommand {it.selectOutbound(group,tag)}}catch(_:Exception) {throw IOException("CORE_SELECT_FAILED")}
    }
    fun selectedOutbound(group:String): String = try {
        tag(group);withCommand {client -> var selected="";val groups=client.getGroups()
            while(groups.hasNext()) {val next=groups.next();if(next.getTag()==group)selected=next.getSelected()};selected}
    } catch(_:Exception) {""}
    override fun counters(): Engine.Counters = Engine.Counters.unavailable()
    override fun stats(): Engine.Stats = metrics.snapshot()
    override fun control(request: Engine.ControlRequest): Engine.ControlReply {
        require(request.generation==runningGeneration && runningGeneration>0) { "CORE_CONTROL_STALE_GENERATION" }
        return when(request.command) {
            Engine.Command.STOP->{requestStop();Engine.ControlReply("STOP_REQUESTED_NOT_TERMINATED",null)}
            Engine.Command.RELOAD->Engine.ControlReply("FRESH_WORKER_REQUIRED",null)
            Engine.Command.STATS->Engine.ControlReply.forStats(stats())
        }
    }
}
