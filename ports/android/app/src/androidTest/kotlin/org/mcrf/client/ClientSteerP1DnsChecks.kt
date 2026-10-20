package org.mcrf.client

import android.net.InetAddresses
import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/** Queued common-P1 Android checks, NOT host DNS mocks. Every request is bound
 * to the caller's already-owned actual Steer VPN. No OS/default-network DNS,
 * physical fallback, public name, saved setting, extra VPN or permission grant.
 * Peer independently records raw upstream answers; these are managed aliases.
 * This alone does NOT prove different/shared UID routing or encrypted DNS. */
object ClientSteerP1DnsChecks {
    @JvmStatic fun runTls(network:Network,nonce:String,live:()->Unit,progress:(String)->Unit):JSONArray {
        require(BuildConfig.DEBUG&&nonce.matches(Regex("[0-9a-f]{32}")))
        val rows=JSONArray()
        for(channel in listOf("global","rule","trust"))for(tcp in listOf(false,true))for(qtype in listOf(1,28)){
            live();progress("STEER_TLS_${channel.uppercase(java.util.Locale.ROOT)}_${if(tcp)"TCP"else"UDP"}_$qtype")
            val query=query("$nonce.$channel.owned.invalid",qtype,SecureRandom().nextInt(65536))
            val target=InetAddresses.parseNumericAddress("10.89.0.1")
            val reply=if(tcp)Socket().use {socket->
                network.bindSocket(socket);socket.soTimeout=18000;socket.connect(InetSocketAddress(target,53),3000)
                DataOutputStream(socket.getOutputStream()).apply {writeShort(query.size);write(query);flush()}
                val input=DataInputStream(socket.getInputStream());val size=input.readUnsignedShort();require(size in 12..4096)
                ByteArray(size).also {input.readFully(it)}
            }else DatagramSocket(null).use {socket->
                network.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(target,53);socket.soTimeout=18000
                socket.send(DatagramPacket(query,query.size));val packet=DatagramPacket(ByteArray(4097),4097);socket.receive(packet)
                require(packet.length in 12..4096);packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
            }
            PacketDnsWire.response(query,reply);val expected=if(channel=="global")3 else 2
            require(PacketDnsWire.question(reply,true).flags and 15==expected){"STEER_TLS_RCODE"};live()
            rows.put(JSONObject().put("channel",channel).put("transport",if(tcp)"tcp"else"udp").put("qtype",qtype).put("rcode",expected))
        }
        validateTlsEvidence(rows);return rows
    }
    @JvmStatic fun validateTlsEvidence(rows:JSONArray){
        require(rows.length()==12){"STEER_TLS_EVIDENCE_COUNT"};val seen=hashSetOf<String>()
        repeat(rows.length()){index->val row=rows.getJSONObject(index)
            require(row.keys().asSequence().toSet()==setOf("channel","transport","qtype","rcode"))
            require(row.opt("channel") is String&&row.getString("channel") in setOf("global","rule","trust")&&
                row.opt("transport") is String&&row.getString("transport") in setOf("udp","tcp")&&
                row.opt("qtype") is Int&&row.getInt("qtype") in setOf(1,28)&&row.opt("rcode") is Int&&
                row.getInt("rcode")==if(row.getString("channel")=="global")3 else 2){"STEER_TLS_EVIDENCE_FIELDS"}
            require(seen.add("${row.getString("channel")}:${row.getString("transport")}:${row.getInt("qtype")}")){"STEER_TLS_EVIDENCE_DUPLICATE"}
        }
    }
    @JvmStatic fun run(network:Network,nonce:String,live:()->Unit,progress:(String)->Unit,verifyAlias:(ByteArray,Int)->String):JSONArray {
        require(BuildConfig.DEBUG&&nonce.matches(Regex("[0-9a-f]{32}")))
        val evidence=JSONArray()
        for(channel in listOf("global","rule"))for(tcp in listOf(false,true))for(qtype in listOf(1,28)){
            val step="P1_DNS_${channel.uppercase(java.util.Locale.ROOT)}_${if(tcp)"TCP" else "UDP"}_$qtype"
            progress(step+"_QUERY")
            live()
            val query=query("$nonce.$channel.owned.invalid",qtype,SecureRandom().nextInt(65536))
            val response=if(tcp)Socket().use { socket ->
                network.bindSocket(socket);socket.soTimeout=3000
                socket.connect(InetSocketAddress(InetAddresses.parseNumericAddress("10.89.0.1"),53),3000)
                val output=DataOutputStream(socket.getOutputStream());output.writeShort(query.size);output.write(query);output.flush()
                val input=DataInputStream(socket.getInputStream());val length=input.readUnsignedShort()
                require(length in 12..4096) {"P1_DNS_FRAME"}
                ByteArray(length).also {input.readFully(it)}
            } else DatagramSocket(null).use { socket ->
                network.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.soTimeout=3000
                socket.connect(InetAddresses.parseNumericAddress("10.89.0.1"),53)
                socket.send(DatagramPacket(query,query.size))
                val packet=DatagramPacket(ByteArray(4097),4097);socket.receive(packet)
                require(packet.length in 12..4096) {"P1_DNS_DATAGRAM"}
                packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
            }
            progress(step+"_ANSWER");live();val alias=answer(query,response,qtype)
            // The address must be a managed alias, never the real synthetic peer
            // answer. Actual native alias use/HTTP is a separate traffic check.
            require(!alias.contentEquals(InetAddresses.parseNumericAddress(if(qtype==1)"198.18.0.2" else "fd77::2").address)) {"P1_DNS_NOT_MANAGED"}
            progress(step+"_NATIVE");val nativeResponse=verifyAlias(alias,qtype);live()
            require(nativeResponse.matches(Regex("[0-9a-f]{64}")))
            evidence.put(JSONObject().put("channel",channel).put("transport",if(tcp)"tcp" else "udp").put("qtype",qtype).put("nativeResponseSha256",nativeResponse)
                .put("answerSha256",MessageDigest.getInstance("SHA-256").digest(response).joinToString(""){"%02x".format(it.toInt()and 255)}))
        }
        // Ordinary sockets of the VERY SAME host UID must not acquire a managed
        // DNS control lease just by guessing a reserved alias. Wrong tuple/port,
        // both families, followed by another permitted native HTTP in the caller.
        for(host in listOf("10.89.4.1","fdfe:dcba:9876:dd53::1")){
            progress(if(':' in host)"P1_DNS_CONTROL_V6" else "P1_DNS_CONTROL_V4")
            live()
            try{Socket().use {socket->
                network.bindSocket(socket);socket.soTimeout=1500
                socket.connect(InetSocketAddress(InetAddresses.parseNumericAddress(host),853),1500)
                socket.getOutputStream().write(nonce.toByteArray(Charsets.US_ASCII));socket.getOutputStream().flush()
                require(socket.getInputStream().read()==-1) {"P1_DNS_CONTROL_ALIAS_LEAK"}
            }}catch(_:java.net.SocketException){}catch(_:java.net.SocketTimeoutException){}
            live()
        }
        progress("P1_DNS_EVIDENCE");validateEvidence(evidence);return evidence
    }
    @JvmStatic fun validateEvidence(evidence:JSONArray){
        require(evidence.length()==8) {"P1_DNS_EVIDENCE_COUNT"}
        val seen=hashSetOf<String>()
        repeat(evidence.length()){index->val row=evidence.getJSONObject(index)
            require(row.keys().asSequence().toSet()==setOf("channel","transport","qtype","answerSha256","nativeResponseSha256"))
            require(row.opt("channel") is String&&row.getString("channel") in setOf("global","rule")&&
                row.opt("transport") is String&&row.getString("transport") in setOf("tcp","udp")&&
                row.opt("qtype") is Int&&row.getInt("qtype") in setOf(1,28)&&
                row.opt("answerSha256") is String&&row.getString("answerSha256").matches(Regex("[0-9a-f]{64}"))&&
                row.opt("nativeResponseSha256") is String&&row.getString("nativeResponseSha256").matches(Regex("[0-9a-f]{64}")))
            require(seen.add("${row.getString("channel")}:${row.getString("transport")}:${row.getInt("qtype")}"))
        }
    }
    private fun query(name:String,qtype:Int,id:Int):ByteArray=ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeShort(id);writeShort(0x0100);writeShort(1);repeat(3){writeShort(0)}
            name.split('.').forEach {label->val raw=label.toByteArray(Charsets.US_ASCII);require(raw.size in 1..63);writeByte(raw.size);write(raw)}
            writeByte(0);writeShort(qtype);writeShort(1)
        }
    }.toByteArray()
    private fun answer(query:ByteArray,reply:ByteArray,qtype:Int):ByteArray {
        fun u16(at:Int):Int {require(at>=0&&at+2<=reply.size);return ((reply[at].toInt()and 255)shl 8)or(reply[at+1].toInt()and 255)}
        require(reply.size>=query.size+12&&reply[0]==query[0]&&reply[1]==query[1]&&u16(2)==0x8180&&
            u16(4)==1&&u16(6)==1&&u16(8)==0&&u16(10)==0&&reply.copyOfRange(12,query.size).contentEquals(query.copyOfRange(12,query.size))) {"P1_DNS_REPLY"}
        val start=query.size;val length=if(qtype==1)4 else 16
        require(u16(start)==0xc00c&&u16(start+2)==qtype&&u16(start+4)==1&&u16(start+10)==length&&reply.size==start+12+length) {"P1_DNS_ANSWER"}
        return reply.copyOfRange(start+12,reply.size)
    }
}
