package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Offline assets use the production copy/hash/signer/ABI checks; no network opener is invoked. */
class OfflineBundledInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart() {
        val report=Bundle();val copied=mutableListOf<File>()
        try {
            check(BuildConfig.DEBUG&&BuildConfig.OFFLINE_EDITION)
            check(!McrfVpnService.hasActiveSession())
            val packages=BundledEngines.available(targetContext,setOf("csqtt","csqtt25","qwdtt","sing-box"))
            check(packages.size==2&&packages.all {it.url.scheme=="mcrf-bundled"})
            val rows=JSONArray()
            for(expected in packages) {
                val file=EnginePackages.download(targetContext,expected,{false},{})
                copied.add(file)
                check(file.isFile&&file.length()==expected.bytes)
                EnginePackages.verifyArchive(targetContext,file,expected.engine,expected.versionCode)
                rows.put(JSONObject().put("kind",expected.engine).put("path",file.name).put("sha256",expected.sha256).put("bytes",expected.bytes))
            }
            val receipt=File(targetContext.cacheDir,"request-offline-verified.json")
            receipt.writeText(JSONObject().put("packages",rows).put("networkUsed",false).toString())
            report.putString("result","PASS: two embedded signed modules extracted and verified without catalogue download")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){copied.forEach {it.delete()};report.putString("failure",e.javaClass.name+": "+e.message);finish(Activity.RESULT_CANCELED,report)}
    }
}
