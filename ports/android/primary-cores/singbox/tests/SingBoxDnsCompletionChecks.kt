package org.mcrf.engines.singbox
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
object SingBoxDnsCompletionChecks {
    @JvmStatic fun main(args:Array<String>) {
        var checks=0
        fun verify(value:Boolean){check(value);checks++}
        val completed=SingBoxDnsCompletion();var replies=0
        verify(completed.active);completed.finish {replies++};completed.finish {replies+=100}
        verify(!completed.active && replies==1)
        val cancelled=SingBoxDnsCompletion();var packets=0
        cancelled.finish { };if(cancelled.active)packets++
        verify(packets==0)
        val callbackFailure=SingBoxDnsCompletion()
        try {callbackFailure.finish {throw IllegalStateException("fixture")}}catch(_:IllegalStateException) { }
        callbackFailure.finish {packets++};verify(!callbackFailure.active && packets==0)
        repeat(32) {
            val state=SingBoxDnsCompletion();val count=AtomicInteger();val ready=CountDownLatch(2);val startGate=CountDownLatch(1)
            val tasks=List(2) {Thread {ready.countDown();startGate.await();state.finish {count.incrementAndGet()}}.apply {start()} }
            check(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));startGate.countDown();tasks.forEach {it.join(2000);check(!it.isAlive)}
            verify(count.get()==1 && !state.active)
        }
        println("PASS $checks DNS completion checks including32 concurrent cancel/reply races; no nativeDNS/IPC execution")
    }
}
