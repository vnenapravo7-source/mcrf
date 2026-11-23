package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.*
import android.os.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Emulator-only original TCP stack + ordered policy + native DNS/TUN proof. No provider, throughput,
 * saved profile edit, or assertion that the production BS runtime is enabled. */
class BsNativeUdpPolicyInstrumentation:Instrumentation(){
    private var arguments=Bundle()
    override fun onCreate(args:Bundle?){arguments=args?:Bundle();super.onCreate(args);start()}
    override fun onStart(){
        val result=Bundle();var code=Activity.RESULT_CANCELED;var phase="preflight"
        var tun:ParcelFileDescriptor?=null;var child:java.lang.Process?=null;var reader:Thread?=null
        val manager=targetContext.getSystemService(ConnectivityManager::class.java)
        val binding=manager.boundNetworkForProcess;var bindingChanged=false
        val sendLock=Any();val identity=CompletableFuture<Int>();val ready=CompletableFuture<Boolean>();val done=CompletableFuture<Boolean>()
        val ownerCount=AtomicInteger();val readerError=AtomicReference<String?>();val output=StringBuilder()
        val deniedOwners=AtomicInteger();val serviceTcpChecks=AtomicInteger()
        val completion=AtomicReference<String?>()
        fun send(text:String){synchronized(sendLock){requireNotNull(child).outputStream.apply{write((text+"\n").toByteArray(Charsets.US_ASCII));flush()}}}
        try{
            check(BuildConfig.DEBUG&&Build.VERSION.SDK_INT>=29&&Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            check(manager.allNetworks.none{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}){"EXISTING_VPN_NOT_TOUCHED"}
            check(VpnService.prepare(targetContext)==null)
            val physical=requireNotNull(manager.activeNetwork)
            val caps=requireNotNull(manager.getNetworkCapabilities(physical));check(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            val echoPort=arguments.getString("echoPort")!!.toInt();require(echoPort in 1..65535)
            val before=ProfileStore.snapshot(targetContext)
            val metadata=JSONObject(context.assets.open("bs-owned-native-udp-fixture.json").bufferedReader().use{it.readText()})
            val fixture=File(context.applicationInfo.nativeLibraryDir,"libmcrf_owned_native_udp_fixture.so")
            check(Build.SUPPORTED_ABIS.first()==metadata.getString("abi")&&fixture.length()==metadata.getLong("bytes"))
            check(java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==metadata.getString("sha256"))
            val facade=McrfVpnService();ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply{isAccessible=true}.invoke(facade,targetContext)
            tun=requireNotNull(facade.Builder().setSession("MCRF owned native TCP policy proof").setMtu(1500).setBlocking(false)
                .addAddress("10.89.0.2",32).addAddress("fd00::2",128)
                .addRoute("198.19.255.53",32).addRoute("fdfe:dcba:9876:dd54::53",128)
                .addRoute("198.51.100.44",32).addRoute("198.51.100.45",32).addRoute("198.51.100.53",32).addRoute("2001:db8::53",128).addRoute("10.0.2.2",32)
                .addAllowedApplication(targetContext.packageName).establish())
            var found:Network?=null;val end=SystemClock.elapsedRealtime()+4000
            while(found==null&&SystemClock.elapsedRealtime()<end){found=manager.allNetworks.firstOrNull{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true};if(found==null)Thread.sleep(20)}
            val vpn=requireNotNull(found);val name="mcrf-native-udp-${UUID.randomUUID()}"
            val builder=ProcessBuilder(fixture.absolutePath,name,physical.networkHandle.toString(),vpn.networkHandle.toString(),echoPort.toString()).redirectErrorStream(true)
            builder.environment()["MCRF_BS_UNDERLAY_NETWORK"]=physical.networkHandle.toString();builder.environment()["MCRF_BS_ROUTING_POLICY_SHA256"]="0".repeat(64)
            builder.environment()["MCRF_DIRECT_TUN_GENERATION"]="42";builder.environment()["MCRF_DIRECT_TUN_PARENT_PID"]=android.os.Process.myPid().toString()
            child=builder.start();val process=requireNotNull(child)
            reader=Thread({try{
                process.inputStream.bufferedReader().useLines{lines->lines.forEach{line->
                    synchronized(output){if(output.length<16000)output.append(line.take(1000)).append('\n')}
                    val ownerAt=line.indexOf("MCRF_BS_OWNER ")
                    when{
                        ownerAt>=0->{
                            val fields=line.substring(ownerAt).split(' ');check(fields.size==8&&fields[1]=="42")
                            val protocol=fields[3].toInt();check(protocol in listOf(6,17))
                            val source=InetSocketAddress(requireNotNull(RouteRules.literal(fields[4])),fields[5].toInt())
                            val destination=InetSocketAddress(requireNotNull(RouteRules.literal(fields[6])),fields[7].toInt())
                            if(metadata.optBoolean("carrierNat")&&protocol==6&&destination.address.hostAddress=="10.0.2.2"&&destination.port==echoPort)serviceTcpChecks.incrementAndGet()
                            val uid=manager.getConnectionOwnerUid(protocol,source,destination)
                            val packages=if(uid>=0)targetContext.packageManager.getPackagesForUid(uid)?.distinct()?.sorted()?:emptyList()else emptyList()
                            val stable=uid>=0&&manager.getConnectionOwnerUid(protocol,source,destination)==uid&&(targetContext.packageManager.getPackagesForUid(uid)?.distinct()?.sorted()?:emptyList())==packages
                            if(!stable){send("BS_OWNER|42|${fields[2]}|-1|");deniedOwners.incrementAndGet()}else{
                                val encoded=Base64.encodeToString(JSONArray(packages).toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
                                send("BS_OWNER|42|${fields[2]}|$uid|$encoded");ownerCount.incrementAndGet()
                            }
                        }
                        line.contains("MCRF_DIRECT_TUN_IDENTITY ")->{val parts=line.substringAfter("MCRF_DIRECT_TUN_IDENTITY ").split(' ');check(parts[0]=="42");identity.complete(parts[1].toInt())}
                        line.contains("OWNED_NATIVE_UDP_READY")->ready.complete(true)
                        line.contains("OWNED_NATIVE_UDP_DONE")->{if(metadata.optBoolean("carrierNat"))check(line.matches(Regex("OWNED_NATIVE_UDP_DONE packets=\\d+ snat_out=\\d+ snat_in=\\d+")));completion.set(line);done.complete(true)}
                    }
                }}
            }catch(error:Throwable){readerError.set(error.javaClass.simpleName+": "+error.message);ready.completeExceptionally(error);done.completeExceptionally(error)}},"mcrf-owned-dns-events").apply{isDaemon=true;start()}
            val pid=identity.get(5,TimeUnit.SECONDS);val connectEnd=SystemClock.elapsedRealtime()+4000;var accepted=false
            while(!accepted){val socket=LocalSocket();var sent=false;try{
                socket.connect(LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT));check(socket.peerCredentials.uid==android.os.Process.myUid()&&socket.peerCredentials.pid==pid&&process.isAlive)
                socket.soTimeout=6000;socket.setFileDescriptorsForSend(arrayOf(requireNotNull(tun).fileDescriptor));sent=true;socket.outputStream.write(1);socket.outputStream.flush();check(socket.inputStream.read()==1);accepted=true
            }catch(error:IOException){if(sent||SystemClock.elapsedRealtime()>=connectEnd)throw error;Thread.sleep(20)}finally{socket.close()}}
            ready.get(5,TimeUnit.SECONDS)
            // Negative control: default Main socket must enter the unforwarded
            // echo route before process binding, rather than leak to underlay.
            var blocked=false;Socket().use{socket->try{socket.connect(InetSocketAddress("10.0.2.2",echoPort),900)}catch(_:SocketTimeoutException){blocked=true}}
            check(blocked){"DEFAULT_MAIN_SOCKET_DID_NOT_ENTER_TUN"}
            check(manager.bindProcessToNetwork(physical));bindingChanged=true
            Socket().use{socket->socket.soTimeout=3000;socket.connect(InetSocketAddress("10.0.2.2",echoPort),2000);val token="MCRF-owned-bind".toByteArray(Charsets.US_ASCII);socket.getOutputStream().write(token);socket.getOutputStream().flush();val bytes=ByteArray(token.size);DataInputStream(socket.getInputStream()).readFully(bytes);check(bytes.contentEquals(token))}
            val observations=JSONArray();var id=1771
            for(address in listOf("198.19.255.53","fdfe:dcba:9876:dd54::53","198.51.100.53","2001:db8::53")){
                val remote=InetAddress.getByName(address);val query=PacketDnsWire.query("owned.invalid",1,id++)
                fun checkReply(reply:ByteArray){val ips=PacketDnsWire.addresses(query,reply);check(ips==listOf(InetAddress.getByName("198.51.100.9")))}
                phase="$address UDP53"
                for(port in listOf(53,5353))DatagramSocket(null).use{socket->phase="$address UDP$port";vpn.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(remote,port);socket.soTimeout=5000;socket.send(DatagramPacket(query,query.size));val reply=DatagramPacket(ByteArray(4096),4096);socket.receive(reply);checkReply(reply.data.copyOfRange(reply.offset,reply.offset+reply.length))}
                for(port in listOf(53,5353))Socket().use{socket->phase="$address TCP$port";socket.bind(InetSocketAddress(0));vpn.bindSocket(socket);socket.connect(InetSocketAddress(remote,port),5000);socket.soTimeout=5000;DataOutputStream(socket.getOutputStream()).apply{writeShort(query.size);write(query);flush()};if(port==5353)socket.shutdownOutput();val input=DataInputStream(socket.getInputStream());val reply=ByteArray(input.readUnsignedShort());input.readFully(reply);checkReply(reply)}
                observations.put(JSONObject().put("destination",address).put("udp",true).put("tcpPorts",JSONArray(listOf(53,5353))).put("halfClosePort",5353))
            }
            phase="UDP DNS keepalive across accelerated idle window"
            DatagramSocket(null).use{socket->vpn.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(InetAddress.getByName("198.19.255.53"),5353);socket.soTimeout=5000
                repeat(6){round->val query=PacketDnsWire.query("owned.invalid",1,2400+round);socket.send(DatagramPacket(query,query.size));val reply=DatagramPacket(ByteArray(4096),4096);socket.receive(reply);check(PacketDnsWire.addresses(query,reply.data.copyOfRange(reply.offset,reply.offset+reply.length))==listOf(InetAddress.getByName("198.51.100.9")));if(round<5)Thread.sleep(125)}
            }
            for(address in listOf("198.51.100.44","198.51.100.45"))DatagramSocket(null).use{socket->
                phase="$address selected UDP echo";vpn.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(InetAddress.getByName(address),echoPort);socket.soTimeout=5000
                for(size in listOf(0,17,512,1200)){phase="$address UDP payload=$size";val payload=ByteArray(size){(it%251).toByte()}
                    if(size==0)ParcelFileDescriptor.fromDatagramSocket(socket).use{descriptor->check(android.system.Os.sendto(descriptor.fileDescriptor,payload,0,0,0,InetAddress.getByName(address),echoPort)==0)}else socket.send(DatagramPacket(payload,payload.size))
                    val reply=DatagramPacket(ByteArray(2048),2048);socket.receive(reply);check(reply.length==payload.size&&reply.data.copyOfRange(reply.offset,reply.offset+reply.length).contentEquals(payload))}
            }
            if(metadata.optBoolean("carrierNat")){
                phase="TCP proxy through same-session carrier source/port NAT"
                Socket().use {socket->
                    vpn.bindSocket(socket);socket.soTimeout=8000;socket.connect(InetSocketAddress("198.51.100.44",echoPort),5000)
                    val payload="MCRF-owned-bind".toByteArray(Charsets.US_ASCII);socket.getOutputStream().write(payload);socket.getOutputStream().flush();socket.shutdownOutput()
                    val reply=ByteArray(payload.size);DataInputStream(socket.getInputStream()).readFully(reply);check(reply.contentEquals(payload));check(socket.getInputStream().read()==-1)
                }
                result.putInt("tcpProxyEchoBytes",15)
                val expectedServiceChecks=if(metadata.optBoolean("rawCarrier"))0 else 1
                check(serviceTcpChecks.get()==expectedServiceChecks){"Unexpected TCP service ownership checks: ${serviceTcpChecks.get()}, expected $expectedServiceChecks"}
                result.putBoolean("rawCarrier",metadata.optBoolean("rawCarrier"))
                result.putInt("serviceTcpOwnerQueries",serviceTcpChecks.get())
            }
            check(ownerCount.get()>=80&&readerError.get()==null&&deniedOwners.get()==0);result.putInt("deniedOwners",deniedOwners.get());send("OWNED_UDP_STOP");done.get(4,TimeUnit.SECONDS);check(process.waitFor(4,TimeUnit.SECONDS)&&process.exitValue()==0)
            check(ProfileStore.snapshot(targetContext)==before)
            if(metadata.optBoolean("carrierNat"))result.putString("carrierNatCompletion",completion.get())
            result.putString("observations",observations.toString());result.putInt("freshOwnerChecks",ownerCount.get());result.putInt("udpKeepaliveQueries",6);result.putInt("acceleratedUdpKeepaliveQueries",if(metadata.optBoolean("sharedSession"))0 else 6);result.putBoolean("sharedSession",metadata.optBoolean("sharedSession"));result.putString("scope","Real owned emulator TUN/original TCP + UDP policy/session/Hosts DNS UDP53+5353 IPv4+IPv6; Selected Direct/VPN native leased sockets and original direct relay owned echo 0/17/512/1200 bytes; fresh kernel owners; no TURN/provider/upload/production activation.")
            code=Activity.RESULT_OK
        }catch(error:Throwable){result.putString("phase",phase);result.putString("failure",error.javaClass.simpleName+": "+error.message);result.putString("ownerFailure",readerError.get());synchronized(output){result.putString("fixtureOutput",output.toString().takeLast(8000))}}
        finally{
            if(child?.isAlive==true)runCatching{send("OWNED_UDP_STOP");child?.waitFor(2,TimeUnit.SECONDS)}
            if(child?.isAlive==true){child?.destroyForcibly();runCatching{child?.waitFor(3,TimeUnit.SECONDS)}}
            runCatching{reader?.join(2000)}
            if(bindingChanged&&!runCatching{targetContext.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(binding)}.getOrDefault(false)){code=Activity.RESULT_CANCELED;result.putString("bindingRestore","FAILED")}
            runCatching{tun?.close()}
        }
        finish(code,result)
    }
}
