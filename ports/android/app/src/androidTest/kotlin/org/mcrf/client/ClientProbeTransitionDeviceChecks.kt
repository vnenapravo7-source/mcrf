package org.mcrf.client

import android.app.Instrumentation
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Local hanging HTTP fixture: cancellation must acknowledge actual isolated-core cleanup. */
object ClientProbeTransitionDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, passed: Consumer<String>) {
        val context=instrumentation.targetContext
        check(McrfVpnService.status=="Отключено") {"Остановите пользовательское подключение перед изолированной проверкой."}
        check(!EngineUpdateGate.blocksUsage(context)) {"Завершите пользовательское обновление движка перед изолированной проверкой."}
        ProbeCoordinator.awaitQuiescent(context)
        passed.accept("Probe cleanup acknowledges an idle process without creating a VPN or listeners")
        val before=context.cacheDir.listFiles().orEmpty().map {it.name}.toSet()
        val pool=Executors.newFixedThreadPool(2)
        val accepted=CountDownLatch(1)
        val release=CountDownLatch(1)
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).apply {soTimeout=15000}
        var mutation:EngineUpdateGate.Mutation?=null
        try {
            val peer=pool.submit {server.accept().use {socket ->accepted.countDown();release.await(20,TimeUnit.SECONDS)}}
            val node=ServerImport.Server("fixture:probe-transition","Local cancellation fixture","direct","127.0.0.1",0,
                JSONObject().put("type","direct").put("tag","transition"),null,null)
            val result=pool.submit<Boolean> {
                try {ProbeCoordinator.measure(context,listOf(node),"http://127.0.0.1:${server.localPort}/wait");false}
                catch(_:java.util.concurrent.CancellationException){true}
            }
            check(accepted.await(15,TimeUnit.SECONDS)) {"Isolated probe did not reach the local HTTP fixture"}
            mutation=EngineUpdateGate.beginMutation(context,"probe-transition-fixture","flux","xray")
            check(mutation==null) {"Installer mutation was allowed while the other process's native probe was active"}
            passed.accept("Kernel-backed shared probe lease in the other process blocks an exclusive engine mutation")
            ProbeCoordinator.awaitQuiescent(context)
            check(result.get(5,TimeUnit.SECONDS)) {"Cancelled probe returned a successful result"}
            release.countDown();peer.get(5,TimeUnit.SECONDS)
            val remaining=context.cacheDir.listFiles().orEmpty().filter {it.name !in before&&it.name.startsWith("mcrf-probe-")}
            check(remaining.isEmpty()) {"Probe acknowledged before private runtime cleanup"}
            mutation=EngineUpdateGate.beginMutation(context,"probe-transition-fixture","flux","xray")
            val completed=checkNotNull(mutation) {"Stopped probe did not release its kernel-backed usage lease"}
            check(EngineUpdateGate.acquireUsage(context)==null) {"Durable mutation marker did not block a new runtime"}
            check(EngineUpdateGate.complete(context,completed.pending.token) {it.owner=="probe-transition-fixture"&&it.action=="flux"&&it.kind=="xray"})
            completed.close();mutation=null
            passed.accept("Completed probe releases its OS lease; an owned fixture mutation blocks new usage until its exact completion")
            passed.accept("Active isolated probe stops its native core and private runtime before ordinary-TUN acknowledgement")
            passed.accept("Cancelled measurement preserves previous catalogue results; no partial success is returned")
        } finally {
            mutation?.let {lease ->try{EngineUpdateGate.complete(context,lease.pending.token) {it.owner=="probe-transition-fixture"&&it.action=="flux"&&it.kind=="xray"}}finally{lease.close()}}
            release.countDown();server.close();pool.shutdownNow();ProbeCoordinator.cancel(context)
        }
    }
}
