package org.mcrf.client

import android.net.DnsResolver
import android.net.InetAddresses
import android.net.Network
import android.os.CancellationSignal
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Exact private DEBUG fixture only. Exercises real kernel TCP/UDP reflection
 * AND Android framework raw DNS through the aliases. A direct request to the
 * peer DNS address would not prove the managed worker and is not used here. */
object PacketManagedDnsFixture {
    fun verify(network:Network,fixture:ClientPacketFixture.Result,live:()->Unit){
        check(BuildConfig.DEBUG&&fixture.managedDns!=null)
        val nonce=fixture.body.removePrefix("mcrf-fixture-")
        fun verify(query:ByteArray,answer:ByteArray,ipv6:Boolean){
            val parsed=PacketDnsWire.response(query,answer)
            require(!parsed.truncated){"PACKET_MANAGED_DNS_TRUNCATED"}
            if(fixture.encryptedDns){require(PacketDnsWire.question(answer,true).flags and 15==fixture.dnsRcode){"PACKET_MANAGED_TLS_RCODE"}}
            else if(ipv6&&fixture.ipv4Only){require(PacketDnsWire.u16(answer,6)==0&&PacketDnsWire.question(answer,true).flags and 15==0){"PACKET_MANAGED_DNS_IPV4_POLICY"}}
            else PacketFixtureDns.verify(query,answer,ipv6)
        }
        for(alias in fixture.dns)for(tcp in listOf(false,true))for(ipv6 in listOf(false,true)){
            live();val target=InetAddresses.parseNumericAddress(alias)
            val query=PacketFixtureDns.query(nonce,ipv6,SecureRandom().nextInt(65536))
            val answer=if(tcp)Socket().use {socket->
                network.bindSocket(socket);socket.connect(InetSocketAddress(target,53),3000);socket.soTimeout=if(fixture.encryptedDns)12000 else 4000
                DataOutputStream(socket.getOutputStream()).apply {writeShort(query.size);write(query);flush()}
                val input=DataInputStream(socket.getInputStream());val size=input.readUnsignedShort();require(size in 12..4096)
                ByteArray(size).also {input.readFully(it)}
            }else DatagramSocket(null).use {socket->
                network.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(target,53);socket.soTimeout=if(fixture.encryptedDns)12000 else 4000
                socket.send(DatagramPacket(query,query.size));val packet=DatagramPacket(ByteArray(4097),4097);socket.receive(packet)
                require(packet.length in 12..4096);packet.data.copyOfRange(packet.offset,packet.offset+packet.length)
            }
            live();verify(query,answer,ipv6)
        }
        for(ipv6 in listOf(false,true)){
            live();val query=PacketFixtureDns.query(nonce,ipv6,SecureRandom().nextInt(65536))
            val cancellation=CancellationSignal();val answer=CompletableFuture<ByteArray?>()
            try{
                DnsResolver.getInstance().rawQuery(network,query,DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
                    Executor {it.run()},cancellation,object:DnsResolver.Callback<ByteArray>{
                        override fun onAnswer(packet:ByteArray,rcode:Int){if(rcode==fixture.dnsRcode)answer.complete(packet.clone())else answer.completeExceptionally(IllegalStateException("PACKET_MANAGED_FRAMEWORK_RCODE"))}
                        override fun onError(error:DnsResolver.DnsException){
                            val errno=(error.cause as? android.system.ErrnoException)?.errno?:-1
                            if(fixture.encryptedDns&&ClientPacketFixture.frameworkRejection(fixture.dnsRcode,error.code,errno))answer.complete(null)
                            else answer.completeExceptionally(IllegalStateException("PACKET_MANAGED_FRAMEWORK_FAILED_${error.code}_$errno"))
                        }
                    })
                val received=answer.get(if(fixture.encryptedDns)12L else 5L,TimeUnit.SECONDS)
                if(received!=null)verify(query,received,ipv6)else require(fixture.encryptedDns&&fixture.dnsRcode==2){"PACKET_MANAGED_FRAMEWORK_FALSE_SUCCESS"}
                live()
            }finally{cancellation.cancel()}
        }
        if(fixture.encryptedDns){
            // A failed TLS/hostname check must not poison the permitted data path.
            live();Socket().use {socket->
                network.bindSocket(socket);socket.connect(InetSocketAddress(InetAddresses.parseNumericAddress("10.77.0.1"),8080),3000);socket.soTimeout=4000
                socket.getOutputStream().apply {write("GET ${fixture.path} HTTP/1.1\r\nHost: 10.77.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));flush()}
                PacketFixtureHttp.verify(socket.getInputStream(),fixture.body)
            };live()
        }
    }
}
