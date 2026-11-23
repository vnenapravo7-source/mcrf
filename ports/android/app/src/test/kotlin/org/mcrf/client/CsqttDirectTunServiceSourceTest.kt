package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; not a substitute for lifecycle/FD tests on Android. */
class CsqttDirectTunServiceSourceTest {
    private fun source(name:String)=File("src/main/kotlin/org/mcrf/client/$name.kt").readText()
    private fun section(text:String,first:String,last:String):String {
        val begin=text.indexOf(first);val end=text.indexOf(last,begin+first.length)
        require(begin>=0&&end>begin)
        return text.substring(begin,end)
    }
    @Test fun failedAdmissionCannotInvalidateAnExistingRunEpoch() {
        val source=source("McrfVpnService")
        val start=section(source,"private fun handleStartCommand(","// P046_BEGIN")
        assertTrue(start.indexOf("queueDirectStartAdmission(Intent(intent),flags,startId)")<start.indexOf("val requested = ++epoch"))
        assertTrue(start.indexOf("val requested = ++epoch")<start.indexOf("releaseBsPowerLease()"))
        val admission=section(source,"private fun queueDirectStartAdmission(","private fun releaseDirectAdmission(")
        assertTrue(admission.contains("serial==directAdmissionSerial&&liveEpoch==epoch"))
        assertTrue(admission.contains("BsDirectTunContract.resolve(this,effective)"))
        assertTrue(admission.contains("handleStartCommand(intent,flags,startId,ready)"))
        for(forbidden in listOf("++epoch","epoch--","closeCore(","ProbeCoordinator.cancelRuntime","preferences.edit()"))
            assertFalse(admission.contains(forbidden))
    }
    @Test fun savedBsFlagCannotHijackOrdinaryVpnOrItsAutoPolicy() {
        val source=source("McrfVpnService")
        val requested=section(source,"private fun directTunRequested(","private var routeServerSession")
        assertTrue(requested.contains("explicitBs:Boolean=directTunBsContext"))
        assertFalse(requested.contains("pendingWhitelists"))
        assertFalse(requested.contains("mcrf_carrier"))
        val start=section(source,"private fun handleStartCommand(","// P046_BEGIN")
        assertTrue(start.contains("(!intent.hasExtra(\"pendingWhitelists\")||intent.getBooleanExtra(\"pendingWhitelists\",false))"))
        assertTrue(start.contains("directTunBsContext=admitted?.direct?:pendingWhitelists?:false"))
        val auto=section(source,"private fun autoAllowed()","private fun ")
        assertFalse(auto.contains("directTunSelected()"))
        assertFalse(auto.contains("directTunRequested()"))
    }
    @Test fun uiCapabilityCacheDoesNotHashInstalledElfsEveryRefresh() {
        val support=source("CsqttDirectTunSupport")
        assertTrue(support.contains("OBSERVATION_TTL_MS=24*60*60*1000L"))
        assertTrue(support.contains("Intent.ACTION_PACKAGE_REPLACED"))
        assertTrue(support.contains("intent?.data?.schemeSpecificPart!=BsEngineContract.PACKAGE"))
        assertTrue(support.contains("return if(cached!=null)cached.reason else CHECKING"))
        assertFalse(support.contains("+10_000"))
        val service=source("McrfVpnService")
        val native=section(service,"private fun startDirectTun(","/** Fail closed")
        assertTrue(native.indexOf("BsDirectTunContract.resolve(this,effective)")<native.indexOf("closeCore(keepTun=true)"))
    }
    @Test fun directStatusesStayConciseAndKeepTheConnectedParserPrefix() {
        val source=source("McrfVpnService")
        for(status in listOf("Подключение… · прямой TUN","VPN подключён · прямой TUN","VPN приостановлен · прямой TUN"))
            assertTrue(source.contains("status=\"$status\""))
        for(old in listOf("status=\"Подключение… · Direct Rust","status=\"VPN подключён · Direct Rust",
            "status=\"VPN приостановлен · Direct","status=\"VPN приостановлен · переключение Direct"))
            assertFalse(source.contains(old))
        assertTrue(source.contains("LastConnectionFailure.remember(diagnostic.detail)"))
    }
}
