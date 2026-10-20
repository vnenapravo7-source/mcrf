package org.mcrf.client

import android.app.Instrumentation
import io.nekohasekai.libbox.Libbox
import java.util.function.Consumer

/** Run independently so a missing Clash build tag cannot mask API/CA/NTP results.
 * Coordinator owns runner integration/ADB; checkConfig creates no public listener. */
object CoreTuningApiDeviceChecks {
    private fun check(instrumentation: Instrumentation, settings: CoreTuningSettings) {
        go.Seq.setContext(instrumentation.targetContext.applicationContext)
        val base = ProfileConfig.build("""{"outbounds":[{"type":"direct","tag":"direct"}]}""",false,10808)
        Libbox.checkConfig(CoreTuningConfig.apply(base,settings).toString())
    }
    @JvmStatic fun singBoxApi(instrumentation: Instrumentation, passed: Consumer<String>) {
        check(instrumentation,CoreTuningSettings(apiEnabled=true,apiSecret="fixture-secret-16"))
        passed.accept("Android native sing-box API service accepted independently (not V2Ray API)")
    }
    @JvmStatic fun mozillaStore(instrumentation: Instrumentation, passed: Consumer<String>) {
        check(instrumentation,CoreTuningSettings(certificateStore="mozilla"))
        passed.accept("Android native Mozilla certificate store accepted independently")
    }
    @JvmStatic fun ntp(instrumentation: Instrumentation, passed: Consumer<String>) {
        check(instrumentation,CoreTuningSettings(ntpEnabled=true))
        passed.accept("Android native NTP configuration accepted independently; system clock writing disabled")
    }
    @JvmStatic fun clashApi(instrumentation: Instrumentation, passed: Consumer<String>) {
        check(instrumentation,CoreTuningSettings(clashEnabled=true,clashSecret="fixture-secret-16"))
        passed.accept("Android native Clash API accepted independently; with_clash_api required")
    }
}
