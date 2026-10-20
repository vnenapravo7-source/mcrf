package org.mcrf.client

import android.app.Instrumentation
import android.content.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Platform
import java.io.File
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** No VPN/consent/profile/account action. Real module JNI must reject a non-TUN pipe. */
object PacketNativeDeviceChecks {
    @JvmStatic fun run(test: Instrumentation, passed: java.util.function.Consumer<String>) {
        val context = test.targetContext
        val generation = SystemClock.elapsedRealtimeNanos()
        val owner = Binder()
        val endpoint = AtomicReference<IBinder>()
        val connected = CountDownLatch(1); val dead = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { endpoint.set(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val component = ComponentName(context, ClientPrimaryProbeWorkerService::class.java)
        val service = context.packageManager.getServiceInfo(component, 0)
        check(!service.exported && service.processName == context.packageName+":primaryProbe")
        var bound = false; var claimed = false; var pid = -1; var input: File? = null
        val pipes = ParcelFileDescriptor.createPipe()
        try {
            bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(8, TimeUnit.SECONDS))
            val worker = requireNotNull(endpoint.get())
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(ClientPrimaryWorkerWire.DESCRIPTOR)
                request.writeBundle(Bundle().apply {putString("command","BIND");putBinder("owner",owner);putLong("generation",generation)})
                check(worker.transact(ClientPrimaryWorkerWire.TRANSACTION,request,reply,0)); reply.readException()
                val result = requireNotNull(reply.readBundle(context.classLoader))
                check(result.getInt("uid") == android.os.Process.myUid() && result.getLong("generation") == generation)
                pid = result.getInt("pid"); check(pid>0 && pid!=android.os.Process.myPid())
                claimed = true; worker.linkToDeath({dead.countDown()},0)
            } finally {request.recycle();reply.recycle()}
            fun command(name: String, extra: Bundle.()->Unit): Bundle {
                val done = CountDownLatch(1); val result = AtomicReference<Bundle>()
                val callback = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(code: Int, data: Bundle) {result.set(data);done.countDown()}
                }
                val parcel = Parcel.obtain()
                val carrier = try {callback.writeToParcel(parcel,0);parcel.setDataPosition(0);ResultReceiver.CREATOR.createFromParcel(parcel)} finally {parcel.recycle()}
                val fields = Bundle().apply {putString("command",name);putBinder("owner",owner);putLong("generation",generation);putParcelable("receiver",carrier);extra()}
                val data = Parcel.obtain()
                try { data.writeInterfaceToken(ClientPrimaryWorkerWire.DESCRIPTOR);data.writeBundle(fields)
                    check(worker.transact(ClientPrimaryWorkerWire.TRANSACTION,data,null,IBinder.FLAG_ONEWAY))
                } finally {data.recycle()}
                check(done.await(15,TimeUnit.SECONDS)) {"Packet native command timed out"}
                return requireNotNull(result.get())
            }
            val prepareCalls = AtomicInteger()
            val platform = Proxy.newProxyInstance(Platform::class.java.classLoader,arrayOf(Platform::class.java)) {_,method,_ ->
                if(method.name == "protectAndBind") {prepareCalls.incrementAndGet();false}
                else throw UnsupportedOperationException("PACKET_FIXTURE_NO_PLATFORM_ACTION")
            } as Platform
            val platformBinder = ClientPrimaryPlatformBinder.stub(platform,generation)
            val apk = File(context.packageManager.getApplicationInfo(ClientPacketBackendLoader.PACKAGE,0).sourceDir)
            val initialized = command("INIT_PACKET") {putString("apkSha256",ClientPrimaryWorkerWire.hashFile(apk));putBinder("platform",platformBinder)}
            check(initialized.getString("status") == "PACKET_INITIALIZED") {"Verified packet module initialization failed"}
            passed.accept("Verified WG/AWG module loaded with real JNI in separate same-UID worker")
            val key = Base64.getEncoder().encodeToString(ByteArray(32) {(it+1).toByte()})
            val conf = "[Interface]\nPrivateKey=$key\nAddress=10.77.0.1/32\nMTU=1280\n[Peer]\nPublicKey=$key\nEndpoint=127.0.0.1:51820\nAllowedIPs=10.77.0.2/32"
            input = ClientPrimaryWorkerWire.writeInput(context,JSONObject().put("profile",conf).put("settings","{}")
                .put("dial","127.0.0.1").put("dnsBootstrap",JSONArray()).put("vpnNetworkHandle",0))
            val rejected = command("START_PACKET") {putString("file",input!!.path);putParcelable("tun",pipes[0]);putBoolean("ownedBlockFixture",false)}
            check(rejected.getString("error") == "PACKET_NATIVE_TUN_REJECTED") {"Rejection did not reach native TUN validation"}
            check(!input!!.exists() && prepareCalls.get() == 0) {"Invalid TUN reached network setup or input leaked"}
            // Original pipe belongs to fixture, not backend: rejection must not consume it.
            check(pipes[0].fd >= 0 && pipes[1].fd >= 0)
            passed.accept("Native backend rejects non-TUN descriptor before sockets; host original remains owned")
        } finally {
            val worker = endpoint.get()
            if(claimed && worker?.isBinderAlive == true) {
                val data = Parcel.obtain()
                try {data.writeInterfaceToken(ClientPrimaryWorkerWire.DESCRIPTOR);data.writeBundle(Bundle().apply {
                    putString("command","STOP");putBinder("owner",owner);putLong("generation",generation);putParcelable("receiver",ResultReceiver(null))
                });worker.transact(ClientPrimaryWorkerWire.TRANSACTION,data,null,IBinder.FLAG_ONEWAY)} catch(_: DeadObjectException) {} finally {data.recycle()}
            }
            try { if(claimed) check(dead.await(15,TimeUnit.SECONDS)) {"Owned packet worker death missing"} }
            finally {if(bound)context.unbindService(connection);pipes.forEach {it.close()}; input?.let {ClientPrimaryWorkerWire.removeInput(context,it)}}
            if(claimed) {
                val until = SystemClock.elapsedRealtime()+3000
                while(File("/proc/$pid").exists() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(20)
                check(!File("/proc/$pid").exists()) {"Packet worker PID remains alive"}
            }
        }
        passed.accept("Owned module worker retired and private inputs cleaned; no VPN or user-state change")
    }
}
