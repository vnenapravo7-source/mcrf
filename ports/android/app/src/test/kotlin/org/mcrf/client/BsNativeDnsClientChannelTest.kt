package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
class BsNativeDnsClientChannelTest{
    @Test fun nonAliasResolversRequireExplicitPolicyModeAndRemainUnicastUnscoped(){
        for(destination in listOf("8.8.8.8","192.168.1.1","2001:db8::53")){
            lateinit var channel:BsNativeDnsClientChannel
            val source=if(':' in destination)"fd00::2" else "10.89.0.2"
            channel=BsNativeDnsClientChannel(42,{true},allowPolicyDestinations=true){channel.answer("MCRF_BS_DNS_CLIENT 42 1 17 $source 41234 $destination");true}
            assertEquals(RouteRules.literal(destination),channel.query(17,32769).destination);channel.close()
        }
        for(destination in listOf("0.0.0.0","127.0.0.1","224.0.0.1","169.254.1.1","fe80::1","fd00::1%wlan0")){
            lateinit var channel:BsNativeDnsClientChannel
            val source=if(':' in destination)"fd00::2" else "10.89.0.2"
            channel=BsNativeDnsClientChannel(42,{true},allowPolicyDestinations=true){assertThrows(IllegalArgumentException::class.java){channel.answer("MCRF_BS_DNS_CLIENT 42 1 17 $source 41234 $destination")};false}
            assertThrows(IOException::class.java){channel.query(17,32769)};channel.close()
        }
    }
    @Test fun immutableMappingPreservesOriginalTupleWithoutAcceptingAssertedUid(){
        lateinit var channel:BsNativeDnsClientChannel
        channel=BsNativeDnsClientChannel(42,{true}){bytes->
            assertEquals("BS_DNS_CLIENT|42|1|17|32769\n",bytes.toString(Charsets.US_ASCII))
            channel.answer("MCRF_BS_DNS_CLIENT 42 1 17 10.89.0.2 41234 198.19.255.53");true
        }
        val client=channel.query(17,32769);assertEquals(41234,client.sourcePort);assertEquals("10.89.0.2",client.source.hostAddress);assertEquals(17,client.protocol)
        assertEquals("BsDnsClient(redacted)",client.toString());channel.close()
    }
    @Test fun missingMappingOrPipeFailureNeverBecomesDefaultCaller(){
        lateinit var missing:BsNativeDnsClientChannel
        missing=BsNativeDnsClientChannel(42,{true}){missing.answer("MCRF_BS_DNS_CLIENT 42 1 NONE");true}
        assertThrows(IOException::class.java){missing.query(6,32769)};missing.close()
        val failed=BsNativeDnsClientChannel(42,{true}){false};assertThrows(IOException::class.java){failed.query(17,32769)};failed.close()
    }
    @Test fun mismatchedProtocolStaleGenerationMalformedAliasAndDuplicateAnswersFail(){
        lateinit var channel:BsNativeDnsClientChannel
        channel=BsNativeDnsClientChannel(42,{true}){
            for(line in listOf("MCRF_BS_DNS_CLIENT 41 1 NONE","MCRF_BS_DNS_CLIENT 42 01 NONE","MCRF_BS_DNS_CLIENT 42 1 6 10.89.0.2 41234 198.19.255.53","MCRF_BS_DNS_CLIENT 42 1 17 10.89.0.2 41234 192.0.2.53"))assertThrows(IllegalArgumentException::class.java){channel.answer(line)}
            channel.answer("MCRF_BS_DNS_CLIENT 42 1 17 10.89.0.2 41234 198.19.255.53")
            assertThrows(IllegalArgumentException::class.java){channel.answer("MCRF_BS_DNS_CLIENT 42 1 NONE")};true
        }
        assertEquals(17,channel.query(17,32769).protocol);channel.close()
    }
    @Test fun closeUnblocksPendingQueryAndLateReplyNeverRevivesIt(){
        val sent=CountDownLatch(1);val channel=BsNativeDnsClientChannel(42,{true}){sent.countDown();true};val worker=Executors.newSingleThreadExecutor()
        try{val request=worker.submit<Boolean>{try{channel.query(6,32769);false}catch(_:IOException){true}}
            assertTrue(sent.await(1,TimeUnit.SECONDS));channel.close();assertTrue(request.get(1,TimeUnit.SECONDS))
            assertTrue(channel.answer("MCRF_BS_DNS_CLIENT 42 1 NONE"));assertThrows(IllegalStateException::class.java){channel.query(6,32769)}
        }finally{channel.close();worker.shutdownNow()}
    }
}
