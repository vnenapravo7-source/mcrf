package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BsNativeOwnerChannelTest {
    private fun line(id:Long=1)="MCRF_BS_OWNER 42 $id 6 198.18.0.1 45678 192.0.2.42 443"
    @Test fun requests_have_one_canonical_generation_and_never_resolve_a_hostname(){
        val q=BsNativeOwnerChannel.parse(line(),42);assertEquals(45678,q.sourcePort);assertEquals(443,q.destinationPort)
        for(value in listOf(line().replace("42 1 ","43 1 "),line().replace("42 1 ","42 01 "),
            line().replace("192.0.2.42","example.org"),line()+" extra",line()+"\n",line().replace(" 6 "," 06 "),
            line().replace("45678","65536"),line().replace("192.0.2.42","2001:db8::1"))) {
            assertThrows(IllegalArgumentException::class.java){BsNativeOwnerChannel.parse(value,42)}
        }
    }
    @Test fun a_failed_owner_lookup_is_an_explicit_denial_without_exception_or_address_details(){
        val done=CountDownLatch(1);var response=""
        val channel=BsNativeOwnerChannel(42,{true},{0},{throw IllegalStateException("private platform detail")},{response=String(it);done.countDown();true})
        assertTrue(channel.offer(line()));assertTrue(done.await(2,TimeUnit.SECONDS));channel.close();assertTrue(channel.awaitStopped(2000))
        assertEquals("BS_OWNER|42|1|-1|\n",response)
    }
    @Test fun completed_ids_cannot_be_replayed_and_shared_uid_package_lists_are_retained(){
        val done=CountDownLatch(1);var response=""
        val owner=BsNativeOwnerChannel.Owner(10001,listOf("org.example.one","org.example.two"))
        val channel=BsNativeOwnerChannel(42,{true},{0},{owner},{response=String(it);done.countDown();true})
        assertTrue(channel.offer(line()));assertTrue(done.await(2,TimeUnit.SECONDS));assertFalse(channel.offer(line()))
        channel.close();assertTrue(channel.awaitStopped(2000))
        val packages=String(java.util.Base64.getDecoder().decode(response.trimEnd().split('|')[4]))
        assertTrue(packages.contains("org.example.one")&&packages.contains("org.example.two"))
        assertThrows(IllegalArgumentException::class.java){BsNativeOwnerChannel.Owner(-1,emptyList())}
    }
    @Test fun expiry_and_retirement_suppress_late_callbacks(){
        for(close in listOf(false,true)) {
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val time=AtomicLong();val replies=AtomicLong()
            val channel=BsNativeOwnerChannel(42,{true},time::get,{
                entered.countDown();try{release.await(2,TimeUnit.SECONDS)}catch(_:InterruptedException){}
                BsNativeOwnerChannel.Owner(10001,emptyList())
            },{replies.incrementAndGet();true})
            assertTrue(channel.offer(line()));assertTrue(entered.await(2,TimeUnit.SECONDS))
            if(close)channel.close() else time.set(2000)
            release.countDown()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(channel.pendingRequests()!=0&&System.nanoTime()<deadline)Thread.sleep(1)
            assertEquals(0,channel.pendingRequests());assertEquals(0,replies.get())
            channel.close();assertTrue(channel.awaitStopped(2000))
        }
    }
    @Test fun pending_work_is_bounded_and_close_does_not_drain_new_platform_lookups(){
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val lookups=AtomicLong();val replies=AtomicLong()
        val channel=BsNativeOwnerChannel(42,{true},{0},{
            lookups.incrementAndGet();entered.countDown();try{release.await(2,TimeUnit.SECONDS)}catch(_:InterruptedException){}
            BsNativeOwnerChannel.Owner(10001,emptyList())
        },{replies.incrementAndGet();true})
        for(id in 1L..32L)assertTrue(channel.offer(line(id)))
        assertFalse(channel.offer(line(33)));assertTrue(entered.await(2,TimeUnit.SECONDS))
        channel.close();release.countDown();assertTrue(channel.awaitStopped(2000))
        assertEquals(2,lookups.get());assertEquals(0,replies.get());assertFalse(channel.offer(line(34)))
    }
}
