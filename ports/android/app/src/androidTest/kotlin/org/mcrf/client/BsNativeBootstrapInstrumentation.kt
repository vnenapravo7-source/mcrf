package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*

/** Owned fixture: real Main launch/owner channel + original dispatcher
 * TUN startup and native DNS/direct echo. No provider credentials or throughput. */
open class BsNativeBootstrapInstrumentation:Instrumentation(){
    protected open val physicalFixture = false
    private var arguments=Bundle()
    override fun onCreate(args:Bundle?){arguments=args?:Bundle();super.onCreate(args);start()}
    override fun onStart(){
        val result=Bundle();var code=Activity.RESULT_CANCELED;var phase="preflight"
        var tun:ParcelFileDescriptor?=null;var session:CsqttDirectTunSession?=null;var ruleDirectory:File?=null;var preparationDirectory:File?=null
        var origin: PhysicalEcho? = null
        var saved: ClientPrimaryVpnDeviceChecks.Saved? = null
        var ownedFiles: BsNativeOwnedRuntimeFiles? = null
        val preparationPreferences=mutableSetOf<String>()
        val failure=java.util.concurrent.atomic.AtomicBoolean()
        try {
            check(BuildConfig.DEBUG&&Build.VERSION.SDK_INT>=29&&!McrfVpnService.hasActiveSession())
            if (physicalFixture) {
                check(Build.SUPPORTED_ABIS.first() == "arm64-v8a" &&
                    Build.HARDWARE !in setOf("ranchu", "goldfish") && !Build.FINGERPRINT.startsWith("generic"))
            } else check(Build.MODEL.contains("sdk",true))
            val manager=targetContext.getSystemService(ConnectivityManager::class.java)
            check(manager.allNetworks.none {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true})
            if (physicalFixture) ClientPrimaryVpnDeviceChecks.requireIdle(targetContext, manager)
            check(VpnService.prepare(targetContext)==null)
            val physical=checkNotNull(manager.activeNetwork);check(manager.getNetworkCapabilities(physical)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false)
            if (physicalFixture) saved = ClientPrimaryVpnDeviceChecks.Saved.capture(targetContext)
            val before=ProfileStore.snapshot(targetContext)
            val metadata=JSONObject(context.assets.open("bs-owned-native-udp-fixture.json").bufferedReader().use {it.readText()})
            check(metadata.getBoolean("bootstrap"))
            val testedGeneration=metadata.optString("generation","2.5.3");check(testedGeneration in setOf("2.5.1","2.5.3"))
            val fixture=File(context.applicationInfo.nativeLibraryDir,"libmcrf_owned_native_udp_fixture.so")
            check(fixture.length()==metadata.getLong("bytes")&&Build.SUPPORTED_ABIS.first()==metadata.getString("abi"))
            val fixtureSha = java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
                .joinToString(""){"%02x".format(it.toInt()and 255)}
            check(fixtureSha == metadata.getString("sha256"))
            if (physicalFixture) {
                check(fixtureSha == arguments.getString("fixtureSha256") && metadata.getString("abi") == "arm64-v8a" &&
                    testedGeneration == arguments.getString("generation")) { "OWNED_PHYSICAL_FIXTURE_PIN" }
                origin = PhysicalEcho(InetAddress.getByName("127.0.0.1"))
            }
            val peerIp = if (physicalFixture) "198.51.100.44" else "10.0.2.2"
            val echoPort = origin?.port ?: checkNotNull(arguments.getString("echoPort")).toInt()
            val echoPayload = origin?.payload ?: "MCRF-owned-bind".toByteArray()
            require(echoPort in 1..65535)
            val facade=McrfVpnService();ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply {isAccessible=true}.invoke(facade,targetContext)
            val document=JSONObject().put("route",JSONObject().put("final","direct").put("rules",JSONArray().put(JSONObject().put("action","sniff")).put(JSONObject().put("action","hijack-dns").put("protocol","dns"))))
                .put("outbounds",JSONArray().put(JSONObject().put("type","socks").put("tag","carrier")).put(JSONObject().put("type","direct").put("tag","direct")))
                .put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","hosts").put("tag","owned").put("predefined",JSONObject().put("owned.invalid","198.51.100.9")))).put("final","owned"))
                .put("inbounds",JSONArray().put(JSONObject().put("type","tun").put("tag","tun").put("address",JSONArray(listOf("10.89.0.2/32"))).put("mtu",1500).put("route_address",JSONArray(listOf("198.19.255.53/32","198.51.100.53/32","$peerIp/32"))).put("include_package",JSONArray(listOf(targetContext.packageName)))))
            if (physicalFixture) document.getJSONObject("route").getJSONArray("rules").put(
                JSONObject().put("action", "route-options").put("network", "tcp")
                    .put("ip_cidr", JSONArray().put("$peerIp/32")).put("port", echoPort)
                    .put("override_address", "127.0.0.1"))
            val proxyPort=java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
            document.getJSONArray("inbounds").put(JSONObject().put("type","mixed").put("tag","owned-local-proxy").put("listen","127.0.0.1").put("listen_port",proxyPort))
            val routingBytes=context.assets.open("bs-owned-bootstrap-routes.srs").use {it.readBytes()}
            check(java.security.MessageDigest.getInstance("SHA-256").digest(routingBytes).joinToString(""){ "%02x".format(it.toInt()and 255)}==metadata.getString("srsSha256"))
            val directory=File(targetContext.cacheDir,"bs-owned-rules-${java.util.UUID.randomUUID()}");check(directory.mkdir());android.system.Os.chmod(directory.path,0x1c0);ruleDirectory=directory
            val ruleFile=File(directory,"owned.srs");ruleFile.writeBytes(routingBytes);android.system.Os.chmod(ruleFile.path,0x180)
            val declaration=JSONObject().put("type","local").put("tag","owned-set").put("format","binary").put("path",ruleFile.path)
            document.getJSONObject("route").put("rule_set",JSONArray().put(declaration)).getJSONArray("rules").put(JSONObject().put("rule_set","owned-set").put("action","reject"))
            phase="private SRS capture"
            fun rejected(action:()->Unit){var rejected=false;try{action()}catch(_:IllegalArgumentException){rejected=true};check(rejected)}
            val link=File(directory,"link.srs");android.system.Os.symlink(ruleFile.path,link.path);declaration.put("path",link.path)
            rejected {BsNativeRuleSetFiles.capture(targetContext,document,"carrier"){false}};check(link.delete());declaration.put("path",ruleFile.path)
            var cancelled=false;try{BsNativeRuleSetFiles.capture(targetContext,document,"carrier"){true}}catch(_:InterruptedIOException){cancelled=true};check(cancelled)
            val captured=BsNativeRuleSetFiles.capture(targetContext,document,"carrier"){false}
            ruleFile.writeBytes(routingBytes.copyOf().also {it[3]=6});rejected {BsNativeRuleSetFiles.capture(targetContext,document,"carrier"){false}}
            // The admitted bundle must retain its own bytes after the source disappears.
            check(ruleFile.delete())
            var owned:Network?=null
            val launch=BsNativeSessionLaunch(captured,physical,
                JSONObject().put("kind","wifi").put("expensive",false).put("constrained",false).put("ssid","").put("bssid",""),
                {checkNotNull(owned)},
                {plan->
                    val builder=facade.Builder().setSession("MCRF owned bootstrap proof").setMtu(plan.mtu).setBlocking(false)
                    plan.addresses.forEach {val parts=it.split('/');builder.addAddress(parts[0],parts[1].toInt())}
                    plan.routes.forEach {val parts=it.split('/');builder.addRoute(parts[0],parts[1].toInt())}
                    plan.dns.forEach(builder::addDnsServer);plan.includedApps.forEach(builder::addAllowedApplication);plan.excludedApps.forEach(builder::addDisallowedApplication)
                    val descriptor=checkNotNull(builder.establish());tun=descriptor
                    val deadline=SystemClock.elapsedRealtime()+4000
                    while(owned==null&&SystemClock.elapsedRealtime()<deadline){owned=manager.allNetworks.firstOrNull {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true};if(owned==null)Thread.sleep(10)}
                    checkNotNull(owned);descriptor
                })
            val effective=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-$testedGeneration").put("server","192.0.2.1").put("server_port",9).put("password","owned-fixture-only").put("workers",1).put("hashes",JSONArray(listOf("owned-fixture-only")))
            val installation=BsDirectTunContract.Installation(fixture,testedGeneration)
            phase="ordinary default profile compilation without starting a primary engine"
            val preparationRoot=File(targetContext.cacheDir,"bs-owned-prepare-${java.util.UUID.randomUUID()}");check(preparationRoot.mkdir());preparationDirectory=preparationRoot
            val preparationFiles=File(preparationRoot,"files").apply {check(mkdir())};val preparationCache=File(preparationRoot,"cache").apply {check(mkdir())}
            val isolated=object:ContextWrapper(targetContext){
                override fun getApplicationContext():android.content.Context=this
                override fun getFilesDir()=preparationFiles
                override fun getCacheDir()=preparationCache
                override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {val key=preparationRoot.name+"-"+name;preparationPreferences+=key;return targetContext.getSharedPreferences(key,mode)}
            }
            val ordinary=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","direct")).put(JSONObject().put("type","socks").put("tag","carrier").put("server","127.0.0.1").put("server_port",1).put("version","5")))
                .put("route",JSONObject().put("final","carrier").put("rules",JSONArray()))
                .put("dns",JSONObject(document.getJSONObject("dns").toString()))
                .put("mcrf_carrier",JSONObject(effective.toString()).put("outbound_tag","carrier"))
            val normal=BsNativeProfilePreparation.capture(isolated,ordinary,null,"carrier",proxyPort,false,physical,null){false};normal.requireCurrent(isolated)
            check(normal.captured.listeners().length()==1&&normal.captured.tunInbounds().length()==1)
            val normalLaunch=BsNativeSessionLaunch(normal.captured,physical,JSONObject().put("kind","wifi").put("expensive",false).put("constrained",false).put("ssid","").put("bssid",""),{error("admission must not request VPN")},{error("admission must not open TUN")})
            normalLaunch.validate(isolated,installation){false}
            val rootCount=checkNotNull(normal.captured.certificates()).getJSONArray("roots").length();check(rootCount>0)
            val mozillaConfig=normal.compiled().put("certificate",JSONObject().put("store","mozilla"))
            val mozilla=BsNativeRuleSetFiles.capture(isolated,mozillaConfig,"carrier"){false};check(mozilla.certificates()!!.getJSONArray("roots").length()==159)
            BsNativeSessionLaunch(mozilla,physical,JSONObject().put("kind","wifi").put("expensive",false).put("constrained",false).put("ssid","").put("bssid",""),{error("admission must not request VPN")},{error("admission must not open TUN")}).validate(isolated,installation){false}

            phase="native admission before TUN or retirement"
            val admissionBefore=targetContext.cacheDir.listFiles()?.filter {it.name.startsWith("mcrf-bs-admit-")}?.map {it.name}?.toSet().orEmpty()
            fun rejectedAdmission(action:()->Unit){var rejected=false;try{action()}catch(_:IllegalStateException){rejected=true};check(rejected)}
            rejectedAdmission{launch.validate(targetContext,installation){true}}
            val invalid=JSONObject(document.toString());invalid.getJSONObject("route").remove("rule_set");invalid.getJSONObject("route").put("rules",JSONArray().put(JSONObject().put("action","sniff").put("sniffer",JSONArray(listOf("unsupported")))))
            val invalidLaunch=BsNativeSessionLaunch(BsNativeRoutingSnapshot.capture(invalid,"carrier",emptyMap()),physical,JSONObject().put("kind","ethernet").put("expensive",false).put("constrained",false).put("ssid","").put("bssid",""),{error("admission must not request VPN")},{error("admission must not open TUN")})
            rejectedAdmission{invalidLaunch.validate(targetContext,installation){false}}
            launch.validate(targetContext,installation){false}
            check(tun==null&&manager.allNetworks.none {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true})
            check(targetContext.cacheDir.listFiles()?.filter {it.name.startsWith("mcrf-bs-admit-")}?.map {it.name}?.toSet().orEmpty()==admissionBefore)
            phase="Main native bootstrap"
            val launchContext = if (physicalFixture)
                BsNativeOwnedRuntimeFiles.instrumentation(targetContext, context, effective, fixture, fixtureSha).also { ownedFiles = it }
                else targetContext
            session=CsqttDirectTunSession.start(launchContext,effective,installation,42,{false},{error("legacy TUN callback must not run")},{failure.set(true)},launch)
            check(session.isAlive()&&!failure.get());val vpn=checkNotNull(owned)
            for(address in listOf("198.19.255.53","198.51.100.53")){
                val query=PacketDnsWire.query("owned.invalid",1,1771)
                phase="UDP DNS $address"
                DatagramSocket(null).use {socket->vpn.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(InetAddress.getByName(address),53);socket.soTimeout=5000;socket.send(DatagramPacket(query,query.size));val reply=DatagramPacket(ByteArray(2048),2048);socket.receive(reply);check(PacketDnsWire.addresses(query,reply.data.copyOfRange(reply.offset,reply.offset+reply.length))==listOf(InetAddress.getByName("198.51.100.9")))}
                phase="TCP DNS half-close $address"
                Socket().use {socket->socket.bind(InetSocketAddress(0));vpn.bindSocket(socket);socket.soTimeout=5000;socket.connect(InetSocketAddress(address,5353),5000);DataOutputStream(socket.getOutputStream()).apply {writeShort(query.size);write(query);flush()};socket.shutdownOutput();val input=DataInputStream(socket.getInputStream());val reply=ByteArray(input.readUnsignedShort());input.readFully(reply);check(PacketDnsWire.addresses(query,reply)==listOf(InetAddress.getByName("198.51.100.9")))}
            }
            phase="native mixed TCP DNS with actual loopback Android owner"
            Socket("127.0.0.1",proxyPort).use {socket->socket.soTimeout=5000;val output=DataOutputStream(socket.getOutputStream());val input=DataInputStream(socket.getInputStream())
                output.write(byteArrayOf(5,1,0));output.flush();check(input.readUnsignedByte()==5&&input.readUnsignedByte()==0)
                output.write(byteArrayOf(5,1,0,1,198.toByte(),51,100,53));output.writeShort(5353);output.flush();val ack=ByteArray(10);input.readFully(ack);check(ack[0].toInt()==5&&ack[1].toInt()==0)
                val query=PacketDnsWire.query("owned.invalid",1,1881);output.writeShort(query.size);output.write(query);output.flush();socket.shutdownOutput();val reply=ByteArray(input.readUnsignedShort());input.readFully(reply);check(PacketDnsWire.addresses(query,reply)==listOf(InetAddress.getByName("198.51.100.9")))
            }
            phase="native SOCKS TCP direct loopback uses explicit local binding"
            java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {echo->echo.soTimeout=5000
                val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>();val payload="SSH-2.0-local-owned\r\n".toByteArray()
                val worker=Thread {try{echo.accept().use {peer->peer.soTimeout=5000;check(peer.inetAddress.isLoopbackAddress);val body=peer.getInputStream().readBytes();check(body.contentEquals(payload));peer.getOutputStream().write(body);peer.shutdownOutput()}}catch(error:Throwable){failure.set(error)}}.apply {isDaemon=true;start()}
                Socket("127.0.0.1",proxyPort).use {socket->socket.soTimeout=5000;val output=DataOutputStream(socket.getOutputStream());val input=DataInputStream(socket.getInputStream());output.write(byteArrayOf(5,1,0));output.flush();check(input.readUnsignedByte()==5&&input.readUnsignedByte()==0);output.write(byteArrayOf(5,1,0,1,127,0,0,1));output.writeShort(echo.localPort);output.flush();val ack=ByteArray(10);input.readFully(ack);check(ack[1].toInt()==0);output.write(payload);output.flush();socket.shutdownOutput();check(input.readBytes().contentEquals(payload))}
                worker.join(5000);check(!worker.isAlive);failure.get()?.let {throw it}
            }
            phase="native SOCKS UDP DNS with actual TCP and UDP Android owners"
            var udpProxyPort=0
            Socket("127.0.0.1",proxyPort).use {socket->socket.soTimeout=5000;val output=DataOutputStream(socket.getOutputStream());val input=DataInputStream(socket.getInputStream())
                output.write(byteArrayOf(5,1,0));output.flush();check(input.readUnsignedByte()==5&&input.readUnsignedByte()==0)
                output.write(byteArrayOf(5,3,0,1,0,0,0,0,0,0));output.flush();val ack=ByteArray(10);input.readFully(ack);check(ack[0].toInt()==5&&ack[1].toInt()==0&&ack[3].toInt()==1);udpProxyPort=((ack[8].toInt()and 255)shl 8)or(ack[9].toInt()and 255)
                DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)).use {udp->udp.connect(InetAddress.getByName("127.0.0.1"),udpProxyPort);udp.soTimeout=5000
                    val query=PacketDnsWire.query("owned.invalid",1,1991);val packet=byteArrayOf(0,0,0,1,198.toByte(),51,100,53,0x14,0xe9.toByte())+query;udp.send(DatagramPacket(packet,packet.size));val answer=DatagramPacket(ByteArray(2048),2048);udp.receive(answer);val bytes=answer.data.copyOfRange(answer.offset,answer.offset+answer.length);check(bytes.size>10&&bytes.take(10)==packet.take(10));check(PacketDnsWire.addresses(query,bytes.copyOfRange(10,bytes.size))==listOf(InetAddress.getByName("198.51.100.9")))
                    phase="native SOCKS UDP direct loopback uses explicit local binding"
                    DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)).use {echo->echo.soTimeout=5000;val payload="owned-local-udp".toByteArray();val prefix=byteArrayOf(0,0,0,1,127,0,0,1,(echo.localPort ushr 8).toByte(),echo.localPort.toByte());val wire=prefix+payload;udp.send(DatagramPacket(wire,wire.size));val packet=DatagramPacket(ByteArray(1024),1024);echo.receive(packet);check(packet.address.isLoopbackAddress&&packet.data.copyOfRange(packet.offset,packet.offset+packet.length).contentEquals(payload));echo.send(packet);val reply=DatagramPacket(ByteArray(1024),1024);udp.receive(reply);check(reply.data.copyOfRange(reply.offset,reply.offset+reply.length).contentEquals(wire))}
                };socket.shutdownOutput()
            }
            phase="SRS AdGuard block before direct connector"
            Socket().use {socket->socket.bind(InetSocketAddress(0));vpn.bindSocket(socket);socket.soTimeout=3000;socket.connect(InetSocketAddress(peerIp,echoPort),3000)
                var blocked=false;try{socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: sub.example.org\r\n\r\n".toByteArray());blocked=socket.getInputStream().read()==-1}catch(_:SocketException){blocked=true};check(blocked)}
            origin?.assertNoContact()
            phase="TCP direct owned echo"
            val echo = origin?.start()
            Socket().use {socket->socket.bind(InetSocketAddress(0));vpn.bindSocket(socket);socket.soTimeout=5000;socket.connect(InetSocketAddress(peerIp,echoPort),5000);socket.getOutputStream().write(echoPayload);socket.shutdownOutput();val response=ByteArray(echoPayload.size);DataInputStream(socket.getInputStream()).readFully(response);check(response.contentEquals(echoPayload));check(socket.getInputStream().read()==-1)}
            echo?.get(6000, java.util.concurrent.TimeUnit.MILLISECONDS)
            phase="health probe must not use the physically reachable direct route"
            val activeSession=checkNotNull(session)
            check(activeSession.health("http://$peerIp:$echoPort/owned",200)==-1L)
            origin?.assertNoContact()
            phase="pending health probe cancellation during native stop"
            val probeFailure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val probe=Thread {try {activeSession.health("http://$peerIp:$echoPort/owned",30000);probeFailure.set(AssertionError("probe completed before stop"))}catch(error:Throwable){probeFailure.set(error)}}.apply {isDaemon=true;start()}
            val probeLock=CsqttDirectTunSession::class.java.getDeclaredField("probeLock").apply {isAccessible=true}.get(activeSession)
            val probesField=CsqttDirectTunSession::class.java.getDeclaredField("probes").apply {isAccessible=true}
            val probeDeadline=android.os.SystemClock.elapsedRealtime()+2000
            while(synchronized(probeLock){(probesField.get(activeSession) as Map<*,*>).isEmpty()}&&probe.isAlive&&android.os.SystemClock.elapsedRealtime()<probeDeadline)Thread.sleep(5)
            check(synchronized(probeLock){(probesField.get(activeSession) as Map<*,*>).isNotEmpty()})
            phase="native stop and private-file cleanup"
            val nativeProcess=CsqttDirectTunSession::class.java.getDeclaredField("child").apply {isAccessible=true}.get(session) as java.lang.Process
            session.close();session=null;check(nativeProcess.exitValue()==0) {"OWNED_NATIVE_EXIT_${nativeProcess.exitValue()}"}
            probe.join(2000);check(!probe.isAlive&&probeFailure.get()!=null&&probeFailure.get() !is AssertionError)
            java.net.ServerSocket(proxyPort,1,InetAddress.getByName("127.0.0.1")).use {}
            java.net.DatagramSocket(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"),udpProxyPort)).use {}
            if(arguments.getString("worker")=="true"){
                phase="signed BS private worker validation and deferred TUN"
                check(physicalFixture||Build.FINGERPRINT.startsWith("generic")||Build.HARDWARE in setOf("ranchu","goldfish"))
                check(targetContext.packageManager.getApplicationInfo(BsEngineContract.PACKAGE,0).flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY!=0)
                tun?.close();tun=null
                val workerHost=object:ClientPrimaryHostPlatform(){fun attach(){attachBaseContext(targetContext);vpnMode=true}}.apply {attach();onCreate()}
                val stopping=java.util.concurrent.atomic.AtomicBoolean()
                val underlay=JSONObject().put("kind","wifi").put("expensive",false).put("constrained",false).put("ssid","").put("bssid","")
                val normalized=checkNotNull(Csqtt25Profile.fromJson("owned-worker",JSONObject(effective.toString()).put("workers",10).put("hashes",JSONArray(listOf("owned_fixture_only_123456")))).carrier)
                val payload=JSONObject().put("schema",1).put("bundle",captured.bundle()).put("profile",normalized).put("physical",physical.networkHandle).put("underlay",underlay).put("metered",false).put("tunnelTag","carrier")
                val pin=BsNativeContract.pin(targetContext)
                try{
                    ClientPrimaryWorker.connectBs(targetContext,43,null,pin,true).use {validation->validation.validateBs(payload)}
                    val platform=workerHost.forRun(44,{stopping.get()},{plan->
                        val builder=facade.Builder().setSession("MCRF owned private worker").setMtu(plan.mtu).setBlocking(false)
                        (plan.routes.inet4Address+plan.routes.inet6Address).forEach {cidr->val parts=cidr.split('/');builder.addAddress(parts[0],parts[1].toInt())}
                        (plan.routes.inet4Range+plan.routes.inet6Range).forEach {cidr->val parts=cidr.split('/');builder.addRoute(parts[0],parts[1].toInt())}
                        plan.dnsServers.forEach(builder::addDnsServer);plan.includePackage.forEach(builder::addAllowedApplication);plan.excludePackage.forEach(builder::addDisallowedApplication)
                        checkNotNull(builder.establish()).also {tun=it}
                    }){_,_->}
                    ClientPrimaryWorker.connectBs(targetContext,44,platform,pin).use {worker->
                        worker.validateBs(payload);check(tun==null);worker.startBs();check(worker.state=="RUNNING")
                        check(worker.health("carrier","http://$peerIp:$echoPort/owned",200)==-1L)
                        val query=PacketDnsWire.query("owned.invalid",1,2111)
                        Socket("127.0.0.1",proxyPort).use {socket->socket.soTimeout=5000;val output=DataOutputStream(socket.getOutputStream());val input=DataInputStream(socket.getInputStream());output.write(byteArrayOf(5,1,0));output.flush();check(input.readUnsignedByte()==5&&input.readUnsignedByte()==0);output.write(byteArrayOf(5,1,0,1,198.toByte(),51,100,53));output.writeShort(5353);output.flush();val ack=ByteArray(10);input.readFully(ack);check(ack[1].toInt()==0);output.writeShort(query.size);output.write(query);output.flush();socket.shutdownOutput();val reply=ByteArray(input.readUnsignedShort());input.readFully(reply);check(PacketDnsWire.addresses(query,reply)==listOf(InetAddress.getByName("198.51.100.9")))}
                        worker.stats()
                    }
                }finally{stopping.set(true);workerHost.onDestroy();tun?.close();tun=null}
                java.net.ServerSocket(proxyPort,1,InetAddress.getByName("127.0.0.1")).use {}
                result.putString("worker","signed module validation/start/TUN/owner/DNS/health/stop accepted")
            }
            check(!failure.get()&&ProfileStore.snapshot(targetContext)==before)
            if (physicalFixture) {
                result.putString("physicalBootstrap", "ARM64_ORIGINAL_DISPATCHER_VPN_FD_OWNER_ACCEPTED")
                result.putString("generation", testedGeneration)
                result.putString("fixtureSha256", fixtureSha)
                result.putBoolean("providerTraffic", false)
            }
            result.putString("scope","Main ordinary default profile compiler + captured Android and159Mozilla roots + no-I/O native admission valid/unsupported/cancel + private native TUN projection + Android owners + original dispatcher TUN + DNS UDP/TCP/half-close + private SRS v5 symlink/cancel/invalid rejection + source deletion isolation + AdGuard block + native mixed TCP/UDP DNS with actual loopback owners + listener release + direct owned echo + cleanup; no provider")
            code=Activity.RESULT_OK
        }catch(error:Throwable){result.putString("phase",phase);result.putString("failure",error.javaClass.simpleName+": "+error.message)}
        finally {runCatching {session?.close()};runCatching {tun?.close()};ruleDirectory?.let {directory->directory.listFiles()?.forEach {it.delete()};directory.delete()}
            preparationPreferences.forEach {targetContext.deleteSharedPreferences(it)}
            preparationDirectory?.let {root->check(root.canonicalFile.parentFile==targetContext.cacheDir.canonicalFile&&root.name.startsWith("bs-owned-prepare-"));fun remove(file:File){check(!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.canonicalPath.startsWith(root.canonicalPath));file.listFiles()?.forEach(::remove);check(file.delete())};remove(root)}
            if (physicalFixture) {
                try {
                    origin?.close()
                    ownedFiles?.close()
                    saved?.let { check(ClientPrimaryVpnDeviceChecks.Saved.capture(targetContext) == it) { "OWNED_PROFILE_PARITY" } }
                    val manager = targetContext.getSystemService(ConnectivityManager::class.java)
                    val deadline = SystemClock.elapsedRealtime() + 4000
                    fun vpnPresent() = manager.allNetworks.any {
                        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    }
                    while (vpnPresent() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                    check(!vpnPresent()) { "OWNED_TUN_NOT_RETIRED" }
                    if (code == Activity.RESULT_OK) result.putBoolean("ownedCleanupAndProfileParity", true)
                } catch (error: Throwable) {
                    code = Activity.RESULT_CANCELED
                    result.putString("cleanupFailure", error.javaClass.simpleName + ": " + error.message)
                }
            }
        }
        finish(code,result)
    }

    private class PhysicalEcho(address: InetAddress) : AutoCloseable {
        private val listener = ServerSocket(0, 1, address)
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val port: Int = listener.localPort
        val payload = ("MCRF-owned-" + java.util.UUID.randomUUID()).toByteArray(Charsets.US_ASCII)

        fun assertNoContact() {
            listener.soTimeout = 250
            try {
                listener.accept().use { error("OWNED_FORBIDDEN_ORIGIN_CONTACT") }
            } catch (_: SocketTimeoutException) {}
        }

        fun start() = worker.submit {
            listener.soTimeout = 5000
            listener.accept().use { socket ->
                socket.soTimeout = 5000
                check(socket.inetAddress == listener.inetAddress && socket.localAddress == listener.inetAddress)
                val input = DataInputStream(socket.getInputStream())
                val received = ByteArray(payload.size)
                input.readFully(received)
                check(received.contentEquals(payload) && input.read() == -1)
                socket.getOutputStream().write(received)
                socket.shutdownOutput()
            }
        }

        override fun close() {
            try { listener.close() }
            finally {
                worker.shutdownNow()
                check(worker.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }
}

class BsNativePhysicalBootstrapInstrumentation : BsNativeBootstrapInstrumentation() {
    override val physicalFixture = true
}




