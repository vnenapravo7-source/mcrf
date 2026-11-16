package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Platform
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** One BS child owned by the existing private primary-worker lifecycle.
 * It never constructs another primary core or substitutes an unsupported profile. */
internal class BsNativeWorkerBackend(private val context:Context,private val generation:Long,
    private val artifactPin:String,private val parentStopping:()->Boolean):AutoCloseable {
    private data class Prepared(val digest:String,val profile:JSONObject?,val launch:BsNativeSessionLaunch,
                                val installation:BsDirectTunContract.Installation,val tag:String)
    private var prepared:Prepared?=null
    @Volatile private var session:CsqttDirectTunSession?=null
    @Volatile private var vpn:Network?=null
    @Volatile private var tun:ParcelFileDescriptor?=null
    @Volatile private var platform:Platform?=null
    private val stopped=AtomicBoolean();private val started=AtomicBoolean();private val failed=AtomicBoolean()
    private fun stopping()=stopped.get()||parentStopping()
    fun prepare(payload:JSONObject):String {
        check(!started.get()&&!stopping()){"BS_NATIVE_PREPARE_STATE"}
        require(payload.keys().asSequence().toSet()==setOf("schema","bundle","profile","physical","underlay","metered","tunnelTag")&&payload.opt("schema")==1)
        require(payload.get("physical") is Long||payload.get("physical") is Int);require(payload.get("metered") is Boolean)
        val handle=payload.getLong("physical");require(handle>0)
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val physical=manager.allNetworks.firstOrNull {it.networkHandle==handle}?:error("BS_NATIVE_PHYSICAL_LOST")
        require(manager.getNetworkCapabilities(physical)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false)
        val captured=BsNativeRoutingSnapshot.fromBundle(payload.getJSONObject("bundle"));val metered=payload.getBoolean("metered")
        val profile=if(payload.isNull("profile"))null else JSONObject(payload.getJSONObject("profile").toString())
        val proxyOnly=captured.tunInbounds().length()==0
        val installation=if(profile==null){
            require(captured.policy().optInt("schema")==2){"BS_NATIVE_ROUTER_EGRESS_REQUIRED"}
            BsNativeContract.routerInstallation(context,artifactPin)
        }else{
            require(checkNotNull(Csqtt25Profile.fromJson("bs-native",profile).carrier).toString()==profile.toString()) {"BS_NATIVE_EFFECTIVE_PROFILE_REQUIRED"}
            BsNativeContract.installation(context,profile,artifactPin)
        }
        if(proxyOnly)require(profile==null&&installation.standaloneRouter&&BsNativeContract.proxyHint()){"BS_NATIVE_PROXY_CAPABILITY_REQUIRED"}
        val launch=BsNativeSessionLaunch(captured,physical,payload.getJSONObject("underlay"),{checkNotNull(vpn)}, {plan->
            check(!proxyOnly){"BS_NATIVE_PROXY_TUN_FORBIDDEN"}
            check(started.get()&&!stopping()){"BS_NATIVE_TUN_BEFORE_START"}
            val routes=Platform.Routes(plan.addresses.filter {':' !in it},plan.addresses.filter {':' in it},plan.routes.filter {':' !in it},plan.routes.filter {':' in it},emptyList(),emptyList(),plan.routes.filter {':' !in it},plan.routes.filter {':' in it})
            val projected=Platform.TunPlan(plan.mtu,true,true,metered,routes,plan.dns,plan.includedApps,plan.excludedApps,Platform.HttpProxy(false,"",0,emptyList()))
            // Platform returns a BORROWED worker-local FD. Retain only our dup;
            // Main keeps the master descriptor and applies the generation gate.
            val descriptor=ParcelFileDescriptor.fromFd(checkNotNull(platform).openTun(generation,projected));tun=descriptor
            val deadline=SystemClock.elapsedRealtime()+5000
            while(!stopping()&&SystemClock.elapsedRealtime()<deadline&&vpn==null){
                vpn=manager.allNetworks.firstOrNull {network->val caps=manager.getNetworkCapabilities(network)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true&&(Build.VERSION.SDK_INT<30||caps.ownerUid==android.os.Process.myUid())&&
                    manager.getLinkProperties(network)?.linkAddresses?.let {addresses->addresses.size==plan.addresses.size&&plan.addresses.all {cidr->val pieces=cidr.split('/');addresses.any {it.address==InetAddress.getByName(pieces[0])&&it.prefixLength==pieces[1].toInt()}}}==true}
                if(vpn==null)Thread.sleep(10)
            }
            check(!stopping()&&vpn!=null){"BS_NATIVE_VPN_NETWORK_REQUIRED"};descriptor
        },proxyOnly)
        launch.validate(context,installation,::stopping)
        check(!stopping()){"BS_NATIVE_PREPARE_CANCELLED"}
        val tag=payload.getString("tunnelTag");require(tag.length in 1..255&&tag.none(Char::isISOControl))
        val digest=ClientPrimaryWorkerWire.sha256(payload.toString().toByteArray(Charsets.UTF_8))
        prepared=Prepared(digest,profile,launch,installation,tag);return digest
    }
    fun run(digest:String,platform:Platform,ready:()->Unit) {
        val capture=checkNotNull(prepared);check(capture.digest==digest&&started.compareAndSet(false,true)&&!stopping()){"BS_NATIVE_RUN_STATE"}
        this.platform=platform
        try {
            BsNativeContract.workerPin(context,artifactPin)
            val native=CsqttDirectTunSession.start(context,capture.profile,capture.installation,generation,::stopping,
                {error("BS_NATIVE_LEGACY_TUN_FORBIDDEN")},{failed.set(true)},capture.launch)
            session=native;check(native.isAlive()&&!failed.get()&&!stopping()){"BS_NATIVE_START_FAILED"};ready()
            while(!stopping()&&!failed.get()&&native.isAlive())Thread.sleep(20)
            check(stopping()){"BS_NATIVE_CHILD_EXITED"}
        }finally{close()}
    }
    fun counters():LongArray=checkNotNull(session){"BS_NATIVE_NOT_RUNNING"}.counters()
    fun selected()=prepared?.tag.orEmpty()
    fun health(url:String,timeout:Int):Long=checkNotNull(session){"BS_NATIVE_NOT_RUNNING"}.health(url,timeout)
    fun startupHealth(urls:List<String>,timeout:Int):Long=checkNotNull(session){"BS_NATIVE_NOT_RUNNING"}.startupHealth(urls,timeout)
    fun requestStop(){stopped.set(true);session?.requestStop()}
    override fun close(){requestStop();try{session?.close();session=null}finally{tun?.close();tun=null;vpn=null;platform=null}}
}
