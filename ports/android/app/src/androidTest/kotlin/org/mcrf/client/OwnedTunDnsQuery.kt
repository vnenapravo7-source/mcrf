package org.mcrf.client

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** API29+ owned-network query; no process-wide bind, system resolver fallback or public name. */
internal object OwnedTunDnsQuery {
    fun lookup(network:Network,host:String,missing:Boolean):List<InetAddress> {
        check(Build.VERSION.SDK_INT>=29)
        require(host.matches(Regex("(?:missing-)?[0-9a-f]{32}\\.fixture\\.test")))
        check(missing==host.startsWith("missing-"))
        val done=CountDownLatch(1)
        val answer=AtomicReference<List<InetAddress>>()
        val code=AtomicReference<Int>()
        val failed=AtomicReference<Int>()
        val cancellation=CancellationSignal()
        val executor=Executors.newSingleThreadExecutor {task->Thread(task,"owned-tun-dns").apply {isDaemon=true}}
        try {
            DnsResolver.getInstance().query(network,host,DnsResolver.TYPE_A,
                DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,executor,cancellation,
                object:DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(result:List<InetAddress>,rcode:Int){answer.set(result);code.set(rcode);done.countDown()}
                    override fun onError(error:DnsResolver.DnsException){failed.set(error.code);done.countDown()}
                })
            check(done.await(8,TimeUnit.SECONDS)){"Owned VPN DNS query timed out; no fallback acceptance"}
            check(failed.get()==null){"Owned VPN DNS query failed; transport failure is not an NXDOMAIN proof"}
            val result=checkNotNull(answer.get())
            if(missing)check(code.get()==3&&result.isEmpty()){"Owned unknown hostname must return NXDOMAIN"}
            else check(code.get()==0&&result.size==1&&result.single().hostAddress=="198.18.0.2"){
                "Owned hostname must resolve through this VPN to exactly the benchmark IPv4 destination"
            }
            return result
        } finally {cancellation.cancel();executor.shutdownNow()}
    }
}
