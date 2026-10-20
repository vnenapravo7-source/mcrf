package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Authored, NOT RUN. Own loopback TCP/UDP only; not native/TUN/provider acceptance. */
class CarrierSocksSwitchTest {
    private class Socks(private val marker:Int,datagrams:Boolean=false):AutoCloseable{
        val retired=AtomicInteger()
        private val closed=AtomicBoolean()
        private val server=ServerSocket(0,8,InetAddress.getByAddress(byteArrayOf(127,0,0,1)))
        private val udp=if(datagrams)DatagramSocket(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127,0,0,1)),0))else null
        private val sockets=ConcurrentHashMap.newKeySet<Socket>()
        private val workers=Executors.newFixedThreadPool(4)
        val endpoint=CarrierSocksSwitch.Endpoint(server.localPort,"fixture-user","fixture-pass",null){retired.incrementAndGet();close()}
        init{workers.execute {while(!closed.get())try{
            val socket=server.accept();sockets.add(socket)
            workers.execute {try{socket.use {client->
                client.soTimeout=3000;val input=DataInputStream(client.getInputStream());val output=client.getOutputStream()
                require(input.readUnsignedByte()==5);ByteArray(input.readUnsignedByte()).also(input::readFully)
                output.write(byteArrayOf(5,2))
                require(input.readUnsignedByte()==1)
                fun credential()=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                require(credential()==endpoint.username&&credential()==endpoint.password)
                output.write(byteArrayOf(1,0))
                require(input.readUnsignedByte()==5);val command=input.readUnsignedByte();require(command in setOf(1,3));input.readUnsignedByte()
                when(input.readUnsignedByte()){1->ByteArray(4).also(input::readFully);4->ByteArray(16).also(input::readFully);3->ByteArray(input.readUnsignedByte()).also(input::readFully);else->error("ATYP")}
                input.readUnsignedShort()
                val port=if(command==3)udp?.localPort?:80 else 80
                output.write(byteArrayOf(5,0,0,1,127,0,0,1,(port ushr 8).toByte(),port.toByte()))
                if(command==3)client.soTimeout=0
                while(true){val next=input.read();if(next<0)break;output.write(byteArrayOf(marker.toByte(),next.toByte()))}
            }}catch(_:Exception){}finally{sockets.remove(socket)}}
        }catch(_:Exception){if(!closed.get())throw IllegalStateException("Owned accept failed")}}}
        init{udp?.let {socket->workers.execute {
            val expected=byteArrayOf(0,0,0,1,1,2,3,4,0,80)
            while(!closed.get())try{
                val packet=DatagramPacket(ByteArray(128),128);socket.receive(packet)
                require(packet.address.isLoopbackAddress&&packet.length==11&&packet.data.copyOfRange(0,10).contentEquals(expected))
                val reply=expected+byteArrayOf(marker.toByte(),packet.data[10])
                socket.send(DatagramPacket(reply,reply.size,packet.address,packet.port))
            }catch(_:Exception){if(!closed.get())throw IllegalStateException("Owned datagram fixture failed")}
        }}}
        override fun close(){
            if(!closed.compareAndSet(false,true))return
            server.close();udp?.close();sockets.toList().forEach {runCatching {it.close()}}
            workers.shutdown()
            // Retirement can run on caller, never on one of these owned handlers.
            check(workers.awaitTermination(2,TimeUnit.SECONDS))
        }
    }
    private fun connect(board:CarrierSocksSwitch,command:Int=1,onReply:(ByteArray)->Unit={}):Socket {
        val socket=Socket("127.0.0.1",board.port);socket.soTimeout=2000
        try{
            val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
            output.write(byteArrayOf(5,1,2));assertEquals(5,input.readUnsignedByte());assertEquals(2,input.readUnsignedByte())
            val u=board.username.toByteArray();val p=board.password.toByteArray()
            output.write(byteArrayOf(1,u.size.toByte())+u+byteArrayOf(p.size.toByte())+p)
            assertEquals(1,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte())
            output.write(byteArrayOf(5,command.toByte(),0,1,1,2,3,4,0,80))
            val reply=ByteArray(10).also(input::readFully);assertEquals(0,reply[1].toInt())
            onReply(reply)
            return socket
        }catch(error:Throwable){socket.close();throw error}
    }
    private fun echo(socket:Socket,value:Int,marker:Int){
        socket.getOutputStream().write(value)
        assertEquals(marker,socket.getInputStream().read());assertEquals(value,socket.getInputStream().read())
    }
    private fun await(condition:()->Boolean){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(!condition()){check(System.nanoTime()<end);Thread.sleep(10)}
    }
    @Test fun newFlowsSwitchOldTcpDrainsWithoutForcedClose(){
        Socks(65).use {a->Socks(66).use {b->CarrierSocksSwitch("a",a.endpoint).use {board->
            val old=connect(board)
            try{
                echo(old,1,65);board.selectEndpoint("b",b.endpoint)
                connect(board).use {fresh->echo(fresh,2,66);echo(old,3,65)}
                board.retireDrained();assertEquals(0,a.retired.get())
            }finally{old.close()}
            await {runCatching {board.retireDrained();a.retired.get()==1}.getOrDefault(false)}
            assertEquals("b",board.selectedId())
        }}}}
    @Test fun udpControlAssociationRetainsItsLeaseButDoesNotClaimDatagramProof(){
        Socks(65).use {a->Socks(66).use {b->CarrierSocksSwitch("a",a.endpoint).use {board->
            val association=connect(board,3);board.selectEndpoint("b",b.endpoint)
            board.retireDrained();assertEquals(0,a.retired.get())
            association.close()
            await {board.retireDrained();a.retired.get()==1}
        }}}}
    @Test fun wrongLoopbackPasswordCannotReachTransport(){
        Socks(65).use {a->CarrierSocksSwitch("a",a.endpoint).use {board->
            Socket("127.0.0.1",board.port).use {socket->
                socket.soTimeout=2000;val input=socket.getInputStream();val output=socket.getOutputStream()
                output.write(byteArrayOf(5,1,2));assertEquals(5,input.read());assertEquals(2,input.read())
                output.write(byteArrayOf(1,1,120,1,121));assertEquals(1,input.read());assertEquals(1,input.read());assertEquals(-1,input.read())
            }
        }}
    }
    @Test fun udpPayloadKeepsExactAssociationAcrossNewFlowSelection(){
        Socks(65,true).use {a->Socks(66,true).use {b->CarrierSocksSwitch("a",a.endpoint).use {board->
            fun port(reply:ByteArray)=((reply[8].toInt() and 255) shl 8)or(reply[9].toInt() and 255)
            var oldPort=0;var newPort=0
            connect(board,3){oldPort=port(it)}.use {
                DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {client->
                    client.soTimeout=2000
                    fun exchange(destination:Int,value:Int,marker:Int){
                        val header=byteArrayOf(0,0,0,1,1,2,3,4,0,80)
                        val bytes=header+byteArrayOf(value.toByte())
                        client.send(DatagramPacket(bytes,bytes.size,InetAddress.getByAddress(byteArrayOf(127,0,0,1)),destination))
                        val response=DatagramPacket(ByteArray(128),128);client.receive(response)
                        assertEquals(destination,response.port);assertTrue(response.address.isLoopbackAddress)
                        assertArrayEquals(header+byteArrayOf(marker.toByte(),value.toByte()),response.data.copyOf(response.length))
                    }
                    exchange(oldPort,1,65);board.selectEndpoint("b",b.endpoint)
                    connect(board,3){newPort=port(it)}.use {
                        assertNotEquals(oldPort,newPort)
                        board.retireDrained();assertEquals(0,a.retired.get())
                        exchange(newPort,2,66);exchange(oldPort,3,65)
                    }
                }
            }
            await {board.retireDrained();a.retired.get()==1}
        }}}}
    @Test fun clientHalfCloseStillDrainsQueuedResponseBeforeEof(){
        Socks(65).use {a->CarrierSocksSwitch("a",a.endpoint).use {board->
            connect(board).use {socket->
                val payload=ByteArray(512){it.toByte()}
                socket.getOutputStream().write(payload);socket.shutdownOutput()
                val input=socket.getInputStream()
                payload.forEach {value->assertEquals(65,input.read());assertEquals(value.toInt() and 255,input.read())}
                assertEquals(-1,input.read())
            }
        }}
    }
    @Test fun stopClosesStreamsAndRetiresAllOwnedLeasesOnce(){
        Socks(65).use {a->Socks(66).use {b->
            val board=CarrierSocksSwitch("a",a.endpoint);val old=connect(board)
            board.selectEndpoint("b",b.endpoint);val fresh=connect(board)
            try{
                board.close();board.close()
                assertEquals(1,a.retired.get());assertEquals(1,b.retired.get())
                assertEquals(-1,old.getInputStream().read());assertEquals(-1,fresh.getInputStream().read())
            }finally{old.close();fresh.close();board.close()}
        }}
    }
}
