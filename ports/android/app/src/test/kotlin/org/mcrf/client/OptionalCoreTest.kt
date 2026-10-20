package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OptionalCoreTest {
    private class Child:Process() {
        var alive=true;var allowDeath=false;var forced=false;var interrupt=false;var stopWrites=0;var streamCloses=0
        private val output=object:java.io.OutputStream(){override fun write(b:Int){stopWrites++};override fun close(){streamCloses++}}
        override fun getOutputStream()=output
        override fun getInputStream():java.io.InputStream=object:java.io.ByteArrayInputStream(byteArrayOf()){override fun close(){streamCloses++}}
        override fun getErrorStream():java.io.InputStream=object:java.io.ByteArrayInputStream(byteArrayOf()){override fun close(){streamCloses++}}
        override fun isAlive()=alive
        override fun waitFor():Int {check(!alive);return 0}
        override fun waitFor(timeout:Long,unit:java.util.concurrent.TimeUnit):Boolean {if(interrupt){interrupt=false;throw InterruptedException("fixture")};if(forced&&allowDeath)alive=false;return !alive}
        override fun exitValue():Int {if(alive)throw IllegalThreadStateException();return 0}
        override fun destroy(){}
        override fun destroyForcibly():Process {forced=true;return this}
    }
    @Test fun retryDrainContinuesPastFailureAndDoesNotRepeatSuccessfulChildren(){
        val calls=mutableListOf<String>();var fail=true
        val drain=OptionalCoreRetirement(listOf(AutoCloseable {calls.add("last");if(fail)throw IllegalStateException("fixture")},AutoCloseable {calls.add("first")}))
        try {drain.close();fail("false cleanup ACK")}catch(_:IllegalStateException){}
        assertEquals(listOf("last","first"),calls);assertTrue(drain.isRequested());assertFalse(drain.isComplete())
        fail=false;drain.close();drain.close();assertTrue(drain.isComplete());assertEquals(listOf("last","first","last"),calls)
    }
    @Test fun drainRetainsThrowableAndAttemptsOtherResources(){
        var fail=true;var other=0
        val drain=OptionalCoreRetirement(listOf(AutoCloseable {if(fail)throw AssertionError("fixture")},AutoCloseable {other++}))
        try {drain.close();fail("false cleanup ACK")}catch(_:IllegalStateException){}
        assertEquals(1,other);assertFalse(drain.isComplete());fail=false;drain.close();assertEquals(1,other);assertTrue(drain.isComplete())
    }
    @Test fun processKillRequestIsNotDeathAcknowledgement(){
        val child=Child()
        try {OptionalCoreTermination.stop(child,true);fail("alive child acknowledged")}catch(_:IllegalStateException){}
        assertTrue(child.alive&&child.forced);val writes=child.stopWrites
        child.allowDeath=true;OptionalCoreTermination.stop(child,false);assertFalse(child.alive);assertEquals(writes,child.stopWrites)
    }
    @Test fun forceReleaseKeepsStdioUntilObservedDeath(){
        val child=Child()
        try {OptionalCoreTermination.release(child,false);fail("alive process released")}catch(_:IllegalStateException){}
        assertEquals(0,child.streamCloses)
        child.allowDeath=true;OptionalCoreTermination.release(child,false);assertFalse(child.alive);assertEquals(3,child.streamCloses)
    }
    @Test fun interruptedWaitPreservesInterruptAndRequiresRetry(){
        val child=Child();child.interrupt=true
        try {
            try {OptionalCoreTermination.stop(child,true);fail("interrupted cleanup acknowledged")}catch(_:IllegalStateException){}
            assertTrue(Thread.currentThread().isInterrupted);assertTrue(child.alive&&child.forced)
        }finally {Thread.interrupted()}
        child.allowDeath=true;OptionalCoreTermination.stop(child,false);assertFalse(child.alive)
    }
    @Test fun failedStartWaitsForActualCleanupAckThenThrowsOriginal(){
        val entered=java.util.concurrent.CountDownLatch(1);val returned=java.util.concurrent.CountDownLatch(1)
        val ack=java.util.concurrent.atomic.AtomicBoolean();val calls=java.util.concurrent.atomic.AtomicInteger();val thrown=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val original=IllegalArgumentException("start failed")
        val resource=AutoCloseable {calls.incrementAndGet();entered.countDown();check(ack.get()){"not yet retired"}}
        val thread=Thread {try {OptionalCoreRetirement.fail(original,resource)}catch(error:Throwable){thrown.set(error)}finally{returned.countDown()}}.apply {isDaemon=true;start()}
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        try {assertFalse(returned.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertNull(thrown.get())}
        finally {ack.set(true);thread.join(3000)}
        assertFalse(thread.isAlive);assertSame(original,thrown.get());assertTrue(calls.get()>1)
    }
    @Test fun localRollbackPreservesInterruptOnlyAfterCleanupAck(){
        val ack=java.util.concurrent.atomic.AtomicBoolean();val entered=java.util.concurrent.CountDownLatch(1)
        val seen=java.util.concurrent.atomic.AtomicBoolean();val original=InterruptedException("cancelled start")
        val thrown=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread=Thread {try {OptionalCoreRetirement.fail(original,AutoCloseable {entered.countDown();check(ack.get())})}catch(error:Throwable){thrown.set(error);seen.set(Thread.currentThread().isInterrupted)}}.apply {isDaemon=true;start()}
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));thread.interrupt()
        try {Thread.sleep(75);assertTrue(thread.isAlive);assertNull(thrown.get())}finally {ack.set(true);thread.join(3000)}
        assertFalse(thread.isAlive);assertSame(original,thrown.get());assertTrue(seen.get())
    }
    @Test fun actualProcessOwnerKeepsProcessAndConfigUntilDeathThenRetriesCleanup(){
        val ctor=OptionalCoreProcess::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java).apply {isAccessible=true}
        val owner=ctor.newInstance(1080,"fixture-user-123456","fixture-pass-123456","xray")
        val child=Child();val root=java.nio.file.Files.createTempDirectory("optional-close-").toFile();val config=java.io.File(root,"config.json").apply {writeText("fixture")}
        val processField=OptionalCoreProcess::class.java.getDeclaredField("process").apply {isAccessible=true};processField.set(owner,child)
        OptionalCoreProcess::class.java.getDeclaredField("directory").apply {isAccessible=true}.set(owner,root)
        try {owner.close();fail("live process released")}catch(_:IllegalStateException){}
        assertSame(child,processField.get(owner));assertTrue(config.exists());assertFalse(owner.isClosed())
        child.allowDeath=true;owner.close();owner.close();assertTrue(owner.isClosed());assertNull(processField.get(owner));assertFalse(root.exists())
    }
    @Test fun localFailedStartRetainsActualOwnerAndConfigWhileBlockingForAck(){
        val owner=OptionalCoreProcess::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java).apply {isAccessible=true}.newInstance(1080,"fixture-user-123456","fixture-pass-123456","xray")
        val child=Child();val process=OptionalCoreProcess::class.java.getDeclaredField("process").apply {isAccessible=true};process.set(owner,child)
        val root=java.nio.file.Files.createTempDirectory("optional-local-rollback-").toFile();val config=java.io.File(root,"config.json").apply {writeText("fixture")}
        OptionalCoreProcess::class.java.getDeclaredField("directory").apply {isAccessible=true}.set(owner,root)
        val entered=java.util.concurrent.CountDownLatch(1);val finished=java.util.concurrent.CountDownLatch(1);val ack=java.util.concurrent.atomic.AtomicBoolean()
        val failure=IllegalArgumentException("failed local start");val thrown=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread=Thread {try {OptionalCoreRetirement.fail(failure,AutoCloseable {child.allowDeath=ack.get();entered.countDown();owner.close()})}catch(error:Throwable){thrown.set(error)}finally {finished.countDown()}}.apply {isDaemon=true;start()}
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        try {assertFalse(finished.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertSame(child,process.get(owner));assertTrue(config.exists());assertFalse(owner.isClosed())}
        finally {ack.set(true);thread.join(3000)}
        assertFalse(thread.isAlive);assertSame(failure,thrown.get());assertTrue(owner.isClosed());assertFalse(root.exists())
    }
    @Test fun sessionRetriesFailedWorkerAndStillClosesTheOtherWorker(){
        val ctor=OptionalCoreProcess::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java).apply {isAccessible=true}
        val field=OptionalCoreProcess::class.java.getDeclaredField("process").apply {isAccessible=true}
        val goodChild=Child().apply {allowDeath=true};val badChild=Child()
        val good=ctor.newInstance(1080,"fixture-user-123456","fixture-pass-123456","xray");field.set(good,goodChild)
        val bad=ctor.newInstance(1081,"fixture-user-123456","fixture-pass-123456","xray");field.set(bad,badChild)
        val session=OptionalCoreSession::class.java.getDeclaredConstructor(JSONObject::class.java,List::class.java).apply {isAccessible=true}.newInstance(JSONObject(),listOf(good,bad))
        try {session.close();fail("partial cleanup acknowledged")}catch(_:IllegalStateException){}
        assertTrue(good.isClosed());assertFalse(bad.isClosed());assertFalse(session.isClosed());assertFalse(session.isAlive())
        val writes=goodChild.stopWrites;badChild.allowDeath=true;session.close();session.close()
        assertTrue(session.isClosed());assertTrue(bad.isClosed());assertEquals(writes,goodChild.stopWrites)
    }
    @Test fun directoryCleanupFailureRetainsOwnerUntilRetry(){
        val owner=OptionalCoreProcess::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java).apply {isAccessible=true}.newInstance(1080,"fixture-user-123456","fixture-pass-123456","xray")
        val root=java.nio.file.Files.createTempDirectory("optional-close-foreign-").toFile()
        val foreign=java.io.File(root,"foreign.fixture").apply {writeText("test-only")}
        val directory=OptionalCoreProcess::class.java.getDeclaredField("directory").apply {isAccessible=true};directory.set(owner,root)
        try {owner.close();fail("failed directory cleanup acknowledged")}catch(_:IllegalStateException){}
        assertSame(root,directory.get(owner));assertTrue(foreign.exists());assertFalse(owner.isClosed())
        assertTrue(foreign.delete());owner.close();assertTrue(owner.isClosed());assertFalse(root.exists())
    }
    private fun vless()=JSONObject("""{"type":"vless","tag":"proxy","server":"fixture.example.test","server_port":443,"uuid":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","tls":{"enabled":true,"server_name":"fixture.example.test"}}""")
    private fun reject(run:()->Unit){try {run();fail("invalid input accepted")}catch(_:IllegalArgumentException){}}
    @Test fun defaultsLeaveBuiltinAlone(){assertNull(OptionalCoreConfig.prepare(vless(),OptionalCoreSettings()))}
    @Test fun pageEditsAndResetPreserveOtherPage(){
        val current=OptionalCoreSettings(xrayVlessDefault=true,xrayMuxConcurrency=24,customFragmentDefault=true)
        val edited=OptionalCoreSettings().json().put("fragmentSize","32-32")
        val merged=OptionalCorePages.merge(current,edited,OptionalCorePages.keys("tls"))
        assertTrue(merged.xrayVlessDefault);assertEquals(24,merged.xrayMuxConcurrency);assertEquals("32-32",merged.fragmentSize)
        assertEquals(7,OptionalCorePages.keys("all").size)
    }
    @Test fun primarySwitchNeedsVerifiedSocksUdpForXray(){
        val config=JSONObject().put("outbounds",org.json.JSONArray().put(vless()))
        val caps=setOf("outbound:socks5","socks5:authentication","socks5:tcp")
        val missing=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(xrayVlessDefault=true),CoreTuningSettings(),OptionalCoreCapabilities.Primary("steer",true,caps))
        assertFalse(missing.eligibleForNativeCheck);assertTrue(missing.required.contains("socks5:udp"))
        val ready=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(xrayVlessDefault=true),CoreTuningSettings(),OptionalCoreCapabilities.Primary("sing-box",true,caps+"socks5:udp"))
        assertTrue(ready.eligibleForNativeCheck);assertTrue(ready.nativeCheckRequired);assertTrue(ready.confirmationRequired)
        assertFalse(OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(),CoreTuningSettings(),OptionalCoreCapabilities.Primary("steer",false,emptySet())).eligibleForNativeCheck)
    }
    @Test fun primaryPreflightDoesNotAssumeSteerSupportsExtensions(){
        val config=JSONObject().put("outbounds",org.json.JSONArray().put(vless()))
        val caps=setOf("outbound:socks5","socks5:authentication","socks5:tcp")
        val ready=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(customFragmentDefault=true),CoreTuningSettings(),OptionalCoreCapabilities.Primary("steer",true,caps))
        assertTrue(ready.eligibleForNativeCheck);assertFalse(ready.required.contains("socks5:udp"));assertTrue(ready.notes.any {it.contains("TCP")})
        val conflict=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(customFragmentDefault=true,xrayVlessDefault=true),CoreTuningSettings(),OptionalCoreCapabilities.Primary("sing-box",true,caps))
        assertFalse(conflict.eligibleForNativeCheck);assertTrue(conflict.blockers.any {it.contains("Совместное")})
    }
    @Test fun capabilityPreflightNeverMutatesProfileOrInfersAuthFromProtocol(){
        val source=vless();val config=JSONObject().put("outbounds",org.json.JSONArray().put(source));val before=config.toString()
        val result=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(xrayVlessDefault=true),CoreTuningSettings(),OptionalCoreCapabilities.Primary("steer",true,setOf("socks5")))
        assertFalse(result.eligibleForNativeCheck);assertEquals(4,result.blockers.size);assertEquals(before,config.toString())
    }
    @Test fun malformedProfileBlocksPreflightWithoutLeakingValues(){
        val config=JSONObject().put("outbounds",org.json.JSONArray().put(JSONObject().put("tag","proxy").put("type","xray").put("outbound","private-invalid-value")))
        val result=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(),CoreTuningSettings(),OptionalCoreCapabilities.Primary("sing-box",true,emptySet()))
        assertFalse(result.eligibleForNativeCheck);assertTrue(result.blockers.none {it.contains("private-invalid-value")})
    }
    @Test fun mozillaCapabilityMustExistInActualWorkerDescriptor(){
        val config=JSONObject().put("outbounds",org.json.JSONArray().put(vless()))
        val result=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true),CoreTuningSettings(certificateStore="mozilla",fingerprint="firefox"),OptionalCoreCapabilities.Primary("sing-box",true,setOf("outbound:socks5","socks5:authentication","socks5:tcp")))
        val required=result.requiredWorkerCapabilities.getValue("tls-core")
        assertEquals(setOf("tls-clienthello-record-fragment-size-sleep","tls-mixedcase-sni","utls","certificate-store"),required)
        assertTrue(OptionalCoreCapabilities.workerBlockers(result,mapOf("tls-core" to required.minus("certificate-store"))).single().contains("certificate-store"))
        assertTrue(OptionalCoreCapabilities.workerBlockers(result,mapOf("tls-core" to required)).isEmpty())
    }
    @Test fun workerDiscoveryIsRequiredSeparatelyFromPrimaryCompatibility(){
        val config=JSONObject().put("outbounds",org.json.JSONArray().put(vless()))
        val result=OptionalCoreCapabilities.preflight(config,OptionalCoreSettings(xrayVlessDefault=true,xrayMuxDefault=true),CoreTuningSettings(),OptionalCoreCapabilities.Primary("sing-box",true,setOf("outbound:socks5","socks5:authentication","socks5:tcp","socks5:udp")))
        assertTrue(result.eligibleForNativeCheck);assertEquals(setOf("vless","socks5-udp-associate","mux"),result.requiredWorkerCapabilities.getValue("xray"))
        assertTrue(OptionalCoreCapabilities.workerBlockers(result,emptyMap()).single().contains("не проверен"))
        assertTrue(result.nativeCheckRequired&&result.confirmationRequired)
    }
    @Test fun rangeEdgesAndNegative(){listOf("1","1-16384","16384").forEach {OptionalCoreSettings.range(it,1,16384)};listOf("0","1-16385","2-1","1-2-3","-1","x","99999999999999999999").forEach {reject {OptionalCoreSettings.range(it,1,16384)}}}
    @Test fun jsonStrictAndRoundTrip(){val s=OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true);assertEquals(s,OptionalCoreSettings.fromJson(s.json()));reject {OptionalCoreSettings.fromJson(JSONObject().put("xrayMuxDefault","true"))};reject {OptionalCoreSettings.fromJson(JSONObject().put("unknown",true))}}
    @Test fun extensionMapsExactFieldsWithoutSourceMutation(){val source=vless();val before=source.toString();val r=OptionalCoreConfig.prepare(source,OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true))!!;assertEquals("tls-core",r.kind);assertEquals("10-100",r.outbound.getJSONObject("tls_fragment").getString("size"));assertEquals("2-5",r.outbound.getJSONObject("tls_fragment").getString("sleep"));assertTrue(r.outbound.getJSONObject("tls").getJSONObject("tls_tricks").getBoolean("mixedcase_sni"));assertEquals("fixture.example.test",r.outbound.getJSONObject("tls").getString("server_name"));assertEquals(before,source.toString())}
    @Test fun explicitProfileOverridesDefaults(){val source=vless();source.getJSONObject("tls").put("fragment",false).put("tls_tricks",JSONObject().put("mixedcase_sni",false));val r=OptionalCoreConfig.prepare(source,OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true))!!;assertFalse(r.outbound.has("tls_fragment"));assertFalse(r.outbound.getJSONObject("tls").getJSONObject("tls_tricks").getBoolean("mixedcase_sni"))}
    @Test fun quicNeverGetsTcpExtension(){val source=vless().put("type","hysteria2");assertNull(OptionalCoreConfig.prepare(source,OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true)));source.put("tls_fragment",JSONObject().put("enabled",true));reject {OptionalCoreConfig.prepare(source,OptionalCoreSettings())}}
    @Test fun xrayAndTlsExtensionConflictIsExplicit(){reject {OptionalCoreConfig.prepare(vless(),OptionalCoreSettings(xrayVlessDefault=true,customFragmentDefault=true))}}
    @Test fun nativeXrayTlsDefaultsCannotSilentlyDisappear(){
        listOf("tls","reality").forEach {security ->
            val source=JSONObject().put("type","xray").put("outbound",JSONObject().put("protocol","vless").put("streamSettings",JSONObject().put("security",security)))
            reject {OptionalCoreConfig.prepare(source,OptionalCoreSettings(customFragmentDefault=true))}
            reject {OptionalCoreConfig.prepare(source,OptionalCoreSettings(mixedCaseSniDefault=true))}
            assertEquals("xray",OptionalCoreConfig.prepare(source,OptionalCoreSettings())!!.kind)
        }
    }
    @Test fun nativeXrayWithoutTlsIgnoresInapplicableTlsDefaults(){
        val source=JSONObject().put("type","xray").put("outbound",JSONObject().put("protocol","vless").put("streamSettings",JSONObject().put("security","none")))
        assertEquals("xray",OptionalCoreConfig.prepare(source,OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true))!!.kind)
    }
    @Test fun xrayRealityXhttpAndMuxNativeShapes(){val source=vless();source.put("transport",JSONObject().put("type","xhttp").put("path","/fixture").put("mode","packet-up").put("extra",JSONObject().put("xmux",JSONObject().put("maxConcurrency","4-4"))));source.getJSONObject("tls").put("reality",JSONObject().put("enabled",true).put("public_key","test-fixture-key").put("short_id","abcd"));val r=OptionalCoreConfig.prepare(source,OptionalCoreSettings(xrayVlessDefault=true,xrayMuxDefault=true))!!;assertEquals("xray",r.kind);assertEquals("reality",r.outbound.getJSONObject("streamSettings").getString("security"));assertEquals("test-fixture-key",r.outbound.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("password"));assertEquals("packet-up",r.outbound.getJSONObject("streamSettings").getJSONObject("xhttpSettings").getString("mode"));assertEquals(8,r.outbound.getJSONObject("mux").getInt("concurrency"))}
    @Test fun visionNeverGetsDefaultMux(){val r=OptionalCoreConfig.prepare(vless().put("flow","xtls-rprx-vision"),OptionalCoreSettings(xrayVlessDefault=true,xrayMuxDefault=true))!!;assertFalse(r.outbound.has("mux"))}
    @Test fun xrayDoesNotDropUnknownSettings(){reject {XrayConfig.fromSingBox(vless().put("multiplex",JSONObject().put("enabled",true)))};val source=vless();source.getJSONObject("tls").put("certificate", "fixture certificate");reject {XrayConfig.fromSingBox(source)}}
    @Test fun privateBridgeUsesAuthAndTcp(){val r=OptionalCoreConfig.Request("xray",JSONObject().put("protocol","vless"));reject {OptionalCoreConfig.privateConfig(r,1080,"short","short")};val j=OptionalCoreConfig.privateConfig(r,1080,"fixture-user-123456","fixture-password-123456");assertEquals(1,j.getInt("api"));assertEquals("127.0.0.1:1080",j.getString("socks"));val out=OptionalCoreConfig.bridgeOutbound("proxy",1080,j.getString("socks_user"),j.getString("socks_pass"));assertEquals("tcp",out.getString("network"));assertEquals("fixture-password-123456",out.getString("password"))}
    @Test fun xrayBridgeDoesNotDisableUdp(){val out=OptionalCoreConfig.bridgeOutbound("proxy",1080,"fixture-user-123456","fixture-password-123456","xray");assertFalse(out.has("network"))}
    @Test fun realityAlpnNeverSilentlyDiscarded(){val source=vless();source.getJSONObject("tls").put("reality",JSONObject().put("enabled",true).put("public_key","fixture-key")).put("alpn",org.json.JSONArray().put("h2"));reject {XrayConfig.fromSingBox(source)}}
}
