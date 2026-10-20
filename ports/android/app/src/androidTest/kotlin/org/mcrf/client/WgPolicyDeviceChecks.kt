package org.mcrf.client

import android.content.Context
import java.net.URI
import java.util.function.Consumer

/** Read-only installed-module decision audit. Never changes the user's selected core. */
object WgPolicyDeviceChecks {
    @JvmStatic fun run(context:Context,passed:Consumer<String>) {
        val before=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        EngineUpdatePrimary.installation(context,"sing-box")
        EngineUpdatePacket.installation(context)
        EngineUpdatePrimary.installation(context,"steer")
        val confirmed=before[ClientPrimarySelection.DUAL_CONFIRMED] as? Boolean?:false
        check(ClientPrimarySelection.needsTrafficChoice(context)==!confirmed)
        passed.accept("Both verified primaries require an explicit main-traffic choice unless already confirmed; inspection is read-only")
        check(ClientPrimarySelection.effectiveWireGuardChoice(context,"steer",true)=="singbox")
        check(ClientPrimarySelection.effectiveWireGuardChoice(context,"singbox",true)=="singbox")
        passed.accept("Both verified modules installed: WG/AWG selects sing-box even when preferred primary is Steer")
        check(ClientPrimarySelection.effectiveWireGuardChoice(context,"steer",false)=="steer")
        passed.accept("Non-WG profiles retain Steer; protocol precedence does not overwrite primary choice")
        if(ClientPrimarySelection.selected(context)=="singbox") {
            val files=context.cacheDir.list()?.toSet().orEmpty()
            val expected=EnginePackage("arm64-v8a","local-policy-fixture",URI("https://github.com/${EnginePackage.REPOSITORY}/releases/download/local-policy-fixture/mcrf-engine-wg-awg-arm64-v8a-release.apk"),1,"0".repeat(64),WgAddonPolicy.KIND)
            var rejected=false
            try {EnginePackages.download(context,expected,{false},{error("WG_POLICY_DOWNLOAD_STARTED")})}
            catch(error:IllegalArgumentException) {rejected=error.message?.contains("sing-box")==true}
            check(rejected && context.cacheDir.list()?.toSet().orEmpty()==files)
            passed.accept("With sing-box selected, direct WG addon download is rejected before cache file or network access")
        }
        check(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==before)
        passed.accept("WG precedence/download audit preserves every primary preference")
    }
}
