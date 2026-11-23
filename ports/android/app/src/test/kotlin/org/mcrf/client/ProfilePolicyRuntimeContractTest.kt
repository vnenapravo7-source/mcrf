package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards only; common compilation and active VPN/phone acceptance are still required. */
class ProfilePolicyRuntimeContractTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun liveBuildBindingsAndSelectionUseEffectivePolicyInsteadOfNormalUiPreferences() {
        val service=source("McrfVpnService")
        assertTrue(service.contains("ProfilePolicyRuntime.preferences(this)"))
        assertTrue(service.contains("runtimeRoutes(if(fixtureMode)null else ProfilePolicyRuntime.active(this),ordinary)"))
        assertTrue(service.contains("val routes=policy?.routing(ordinary)?:ordinary()"))
        val automatic=service.substringAfter("private fun autoAllowed() =").substringBefore('\n')
        assertTrue(automatic.contains("profilePolicyCurrent()"))
        assertTrue(automatic.contains("!directTunOwned&&!directTunStarting&&directTunTransition==null"))
        assertTrue(service.contains("core!==current||!profilePolicyCurrent()"))
        assertTrue(source("Connections").contains("ProfilePolicyRuntime.pool(context,"))
        val binding=source("RoutingConnectionBinding")
        assertTrue(binding.contains("ProfilePolicyRuntime.preferences(context)"))
        assertTrue(binding.contains("if(profile)FallbackChainStore.Settings()"))
        assertTrue(binding.contains("profile&&!p.getBoolean(\"automatic\",false)"))
        assertTrue(source("FallbackChainController").contains("ProfilePolicyRuntime.active(context)?.configuration==null"))
    }
    @Test fun detectionChecksProfileIdentityBeforePublishingAndUsesProfileSettings() {
        val detection=source("BsDetectionMonitor")
        assertTrue(detection.contains("ProfilePolicyRuntime.preferences(app)"))
        assertTrue(detection.contains("ProfilePolicyRuntime.active(app)?.id==authority"))
        assertTrue(detection.contains("if(permitted()&&snapshot()==stamp"))
        val runtime=source("ProfilePolicyRuntime")
        assertTrue(runtime.contains("lease.current(context)"));assertTrue(runtime.contains("requireOrdinaryEditsAllowed"))
        assertTrue(runtime.contains("fun statusHint(context:Context)"))
    }
    @Test fun renewedApprovalAllowsEstablishedActiveVpnReconfigureWithoutPerTickOverwrite() {
        assertTrue(source("ConditionProfilesUi").contains("ChangePreviewPolicy.approve(activity,review,true)"))
        val runtime=source("BehaviorScheduleRuntime")
        assertTrue(runtime.contains("val allowActive=lease.allowActiveNetwork||rule.action.allowActiveVpnReconfiguration"))
        assertTrue(runtime.contains("if(section?.optString(\"activeId\")==rule.action.profileId)"))
        assertTrue(runtime.contains("preserveApproved=true"));assertTrue(runtime.contains("authoritative=true"))
        assertTrue(runtime.contains("fresh==env"));assertTrue(runtime.contains("lease.current(context)"))
        assertTrue(source("ConditionProfilesApply").contains("if(allowActiveVpnReconfiguration&&vpnActive())McrfVpnService.networkProfileChanged(context)"))
        val disable=source("ChangePreviewPolicy").substringAfter("fun disable(context:Context)").substringBefore("private fun canonical")
        assertTrue(disable.contains("ProfilePolicyRuntime.invalidate()"));assertTrue(disable.contains("networkProfileChanged(context)"))
    }
}
