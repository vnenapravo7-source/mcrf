package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored for common P0. Does not execute shell, SSH, Go, cloud calls or tests per edit. */
class AndroidDeployS3Test {
    private val operation="01234567-89ab-cdef-0123-456789abcdef"
    private fun options()=JSONObject().put("create",true).put("s3_endpoint","https://storage.fixture.invalid")
        .put("s3_bucket","fixture-bucket").put("s3_region","fixture-region").put("s3_prefix","fixture/")
        .put("s3_access_key","fixture-access").put("s3_secret_key","fixture-secret")
    private fun target()=AndroidDeployTarget("vps.fixture.invalid",sshPassword="fixture-ssh",options=options(),operationId=operation)
    private fun result(t:AndroidDeployTarget)=JSONObject().put("recipe_id",S3BypassProfile.KIND).put("operation_id",t.operationId)
        .put("service",AndroidDeployS3.service(t.operationId)).put("core_commit",S3BypassProfile.COMMIT)
        .put("ready",true).put("provision_requested",true).put("provision_complete",true)
        .put("connections",JSONArray().put(AndroidDeployS3.link(AndroidDeployS3.profile(t.options,t.operationId))))
    @Test fun serviceRecipeDoesNotRequireAnInventedPanel(){
        val t=target().copy(panelPort=22,panelUser="",panelPassword="",domain="unused")
        AndroidDeployPlan.validate(S3BypassProfile.KIND,t)
        AndroidDeployPlan.validateResult(result(t),t)
        assertFalse(AndroidDeployPresentation.action(true,S3BypassProfile.KIND).contains("панель"))
    }
    @Test fun generatedScriptPinsSourcesAndDoesNotExposePlainStorageKeys(){
        val t=target();val script=AndroidDeployS3.build(t,"/tmp/mcrf-deploy-"+"a".repeat(32))
        assertTrue(script.contains(S3BypassProfile.COMMIT));assertTrue(script.contains(AndroidDeployS3.GO_VERSION))
        assertTrue(script.contains("GOTOOLCHAIN=local"));assertTrue(script.contains("-mod=readonly"))
        assertTrue(script.contains("text.count(call)!=1"));assertTrue(script.contains("bucket lifecycle is controlled by its owner"))
        assertFalse(script.contains("fixture-access"));assertFalse(script.contains("fixture-secret"))
        assertFalse(script.contains("curl latest"));assertFalse(script.contains("installer.sh"))
    }
    @Test fun resultCannotBindToAnotherOperationOrStorageTarget(){
        val t=target();val wrong=target().copy(operationId="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertThrows(IllegalArgumentException::class.java){AndroidDeployS3.validateResult(result(wrong),t)}
        val other=target().copy(options=options().put("s3_bucket","other-fixture"))
        assertThrows(IllegalArgumentException::class.java){AndroidDeployS3.validateResult(result(other),t)}
        assertThrows(IllegalArgumentException::class.java){AndroidDeployS3.validateResult(result(t).put("ready","true"),t)}
    }
    @Test fun unsafeRootAndUnsupportedPanelOnlyModeAreRejected(){
        for(prefix in listOf("","../foreign/")){
            assertThrows(IllegalArgumentException::class.java){AndroidDeployPlan.validate(S3BypassProfile.KIND,target().copy(options=options().put("s3_prefix",prefix)))}
        }
        assertThrows(IllegalArgumentException::class.java){AndroidDeployPlan.validate(S3BypassProfile.KIND,target().copy(options=options().put("create",false)))}
    }
}
