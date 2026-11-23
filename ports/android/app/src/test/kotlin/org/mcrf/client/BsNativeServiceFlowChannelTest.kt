package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.io.IOException

class BsNativeServiceFlowChannelTest {
    private fun peer()=InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(),0,2,42)),853)
    @Test fun acceptedLeaseIsAcknowledgedBeforeReturnAndRetiredExactlyOnce(){
        val wire=mutableListOf<String>();lateinit var channel:BsNativeServiceFlowChannel
        channel=BsNativeServiceFlowChannel(42,{true},{100}){bytes->
            val line=bytes.toString(Charsets.US_ASCII).trim();wire+=line
            if(line.startsWith("BS_SERVICE_ADD|"))assertTrue(channel.acknowledge("MCRF_BS_SERVICE 42 1 OK"));true
        }
        val lease=channel.register(6,41234,peer(),30100)
        assertEquals(listOf("BS_SERVICE_ADD|42|1|6|41234|192.0.2.42|853|30000"),wire)
        lease.close();lease.close();channel.close();assertEquals("BS_SERVICE_DEL|42|1",wire.last());assertEquals(2,wire.size)
    }
    @Test fun denialAndExpiredAckNeverPermitSocketConnect(){
        for(expired in listOf(false,true)){
            var clock=100L;val wire=mutableListOf<String>();lateinit var channel:BsNativeServiceFlowChannel
            channel=BsNativeServiceFlowChannel(42,{true},{clock}){bytes->
                val line=bytes.toString(Charsets.US_ASCII).trim();wire+=line
                if(line.startsWith("BS_SERVICE_ADD|")){if(expired)clock=200;channel.acknowledge("MCRF_BS_SERVICE 42 1 ${if(expired)"OK" else "DENIED"}")};true
            }
            try{channel.register(17,41234,peer(),200);fail("lease granted")}catch(_:IOException){}
            assertEquals("BS_SERVICE_DEL|42|1",wire.last());channel.close()
        }
    }
    @Test fun malformedStaleAndDuplicateAcknowledgementsAreRejected(){
        lateinit var channel:BsNativeServiceFlowChannel
        channel=BsNativeServiceFlowChannel(42,{true},{100}){bytes->
            if(bytes.toString(Charsets.US_ASCII).startsWith("BS_SERVICE_ADD|")){
                for(line in listOf("MCRF_BS_SERVICE 41 1 OK","MCRF_BS_SERVICE 42 01 OK","MCRF_BS_SERVICE 42 1 yes")){
                    try{channel.acknowledge(line);fail("invalid ACK")}catch(_:IllegalArgumentException){}
                }
                channel.acknowledge("MCRF_BS_SERVICE 42 1 OK")
                try{channel.acknowledge("MCRF_BS_SERVICE 42 1 OK");fail("duplicate ACK")}catch(_:IllegalArgumentException){}
            };true
        }
        channel.register(6,41234,peer(),200).close();channel.close()
    }
    @Test fun closedOrFailedWriterNeverGrantsALease(){
        val channel=BsNativeServiceFlowChannel(42,{true},{100}){false}
        try{channel.register(6,41234,peer(),200);fail("failed writer")}catch(_:IOException){}
        channel.close();try{channel.register(6,41234,peer(),200);fail("closed channel")}catch(_:IllegalStateException){}
    }
}
