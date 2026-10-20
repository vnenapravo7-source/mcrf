package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** New bounded local transport checks queued for the ONE common P1 batch. */
class PacketDnsTransportTest {
    private val loopback=InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    private val query=PacketDnsWire.query("owned.invalid",1,771)
    private val response=PacketDnsWire.reply(query,0,listOf(InetAddress.getByAddress(byteArrayOf(10,77,0,1))))
    private fun now()=System.nanoTime()/1000000
    private class Harness(allow:Boolean=true){
        val fds=Collections.synchronizedSet(hashSetOf<Closeable>());val policies=Collections.synchronizedList(mutableListOf<Boolean>())
        var systemCalls=0
        val transport=PacketDnsTransport({socket,direct->assertTrue(socket.isBound);assertFalse(socket.isConnected);policies+=direct},{_,direct->policies+=direct},
            {_,_->systemCalls++;throw IllegalStateException("no implicit resolver")},{allow},{System.nanoTime()/1000000},{}, {fds+=it},{fds-=it})
    }
    @Test fun udpTruncationRetriesOnlySameEndpointTcpAndClosesEverySocket(){
        ServerSocket(0,1,loopback).use {server->DatagramSocket(InetSocketAddress(loopback,server.localPort)).use {datagram->
            server.soTimeout=3000;datagram.soTimeout=3000
            val executor=Executors.newSingleThreadExecutor()
            val peer=executor.submit {
                val request=DatagramPacket(ByteArray(4096),4096);datagram.receive(request)
                val tc=PacketDnsWire.reply(query,0,truncated=true);datagram.send(DatagramPacket(tc,tc.size,request.address,request.port))
                server.accept().use {socket->socket.soTimeout=3000;val input=DataInputStream(socket.getInputStream());val received=ByteArray(input.readUnsignedShort());input.readFully(received);assertArrayEquals(query,received)
                    DataOutputStream(socket.getOutputStream()).apply {writeShort(response.size);write(response);flush()}}
            }
            try{
                val h=Harness();val endpoint=PacketManagedDnsPlan.Endpoint("udp","127.0.0.1",server.localPort,"","")
                h.transport.Scope(now()+2500).use {scope->assertArrayEquals(response,h.transport.exchange(endpoint,query,false,listOf(loopback),scope))}
                peer.get(3,TimeUnit.SECONDS);assertEquals(listOf(false,false),h.policies);assertTrue(h.fds.isEmpty());assertEquals(0,h.systemCalls)
            }finally{executor.shutdownNow()}
        }}
    }
    @Test fun deniedVpnDestinationDoesNotCreateSocketOrFallBackToSystem(){
        val h=Harness(false);val endpoint=PacketManagedDnsPlan.Endpoint("tcp","127.0.0.1",40553,"","")
        h.transport.Scope(now()+500).use {scope->assertThrows(java.io.IOException::class.java){h.transport.exchange(endpoint,query,false,listOf(loopback),scope)}}
        assertTrue(h.fds.isEmpty());assertTrue(h.policies.isEmpty());assertEquals(0,h.systemCalls)
    }
    @Test fun tlsPlaintextEndpointIsNotAcceptedAsDnsAndNeverFallsBack(){
        ServerSocket(0,1,loopback).use {server->
            server.soTimeout=3000;val executor=Executors.newSingleThreadExecutor()
            val peer=executor.submit {server.accept().use {socket->socket.soTimeout=2000;socket.getOutputStream().write("not TLS\r\n".toByteArray());socket.getOutputStream().flush()}}
            try{
                val h=Harness();val endpoint=PacketManagedDnsPlan.Endpoint("tls","127.0.0.1",server.localPort,"","owned.invalid")
                h.transport.Scope(now()+2000).use {scope->assertThrows(java.io.IOException::class.java){h.transport.exchange(endpoint,query,true,listOf(loopback),scope)}}
                peer.get(3,TimeUnit.SECONDS);assertEquals(listOf(true),h.policies);assertTrue(h.fds.isEmpty());assertEquals(0,h.systemCalls)
            }finally{executor.shutdownNow()}
        }
    }
    @Test fun scopeCancellationUnblocksReadAndRetiresDescriptors(){
        ServerSocket(0,1,loopback).use {server->
            server.soTimeout=3000;val executor=Executors.newSingleThreadExecutor();val timer=Executors.newSingleThreadScheduledExecutor()
            val h=Harness();val scope=h.transport.Scope(now()+2500)
            val peer=executor.submit {server.accept().use {socket->socket.soTimeout=3000;while(socket.getInputStream().read()!=-1){}}}
            val cancel=timer.schedule({scope.close()},150,TimeUnit.MILLISECONDS)
            try{
                val endpoint=PacketManagedDnsPlan.Endpoint("tcp","127.0.0.1",server.localPort,"","")
                assertThrows(java.io.IOException::class.java){scope.use {h.transport.exchange(endpoint,query,false,listOf(loopback),it)}}
                peer.get(3,TimeUnit.SECONDS);assertTrue(h.fds.isEmpty());assertEquals(0,h.systemCalls)
            }finally{cancel.cancel(false);scope.close();executor.shutdownNow();timer.shutdownNow()}
        }
    }
    @Test fun systemResolverRequiresExplicitDirectSelection(){
        val h=Harness();val endpoint=PacketManagedDnsPlan.Endpoint("system","",53,"","")
        h.transport.Scope(now()+500).use {scope->assertThrows(IllegalArgumentException::class.java){h.transport.exchange(endpoint,query,false,emptyList(),scope)}}
        assertEquals(0,h.systemCalls);assertTrue(h.fds.isEmpty())
    }
}
