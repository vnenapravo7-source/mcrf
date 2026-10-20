package org.mcrf.client

import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.function.Consumer

/** Valid local import intents; no native transport is started or external login attempted. */
object DeepLinkDeviceChecks {
    @JvmStatic fun run(instrumentation: android.app.Instrumentation, passed: Consumer<String>) {
        val context = instrumentation.targetContext
        val before = ProfileStore.snapshot(context)
        val preferences = context.getSharedPreferences("MainActivity",0).all.toMap()
        val name = "Isolated link ${UUID.randomUUID()}"
        val calls=JSONArray().put("fixture-call-0123456789abcdef")
        val links=SwgProfile.TYPES.map { type -> SwgProfile.share(JSONObject().put("type",type).put("server","fixture.invalid").put("server_port",56000)
            .put("name",name).put("password","isolated-fixture").put("hashes",calls).put("workers",9)) } +
            OpenFluxProfile.share(JSONObject().put("type","openflux").put("name",name).put("negotiate",true).put("secret","isolated-fixture-key")
                .put("transports",JSONArray().put(JSONObject().put("type","direct").put("dial","127.0.0.1:25999"))))
        val added=mutableListOf<String>()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun await(predicate:()->Boolean):Boolean { val end=System.nanoTime()+12_000_000_000L;while(System.nanoTime()<end){if(predicate())return true;Thread.sleep(100)};return predicate() }
        try {
            check(await { !activity.working() })
            for(link in links) {
                val expected=ServerImport.read(link).single()
                val incoming=Intent(Intent.ACTION_VIEW,Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
                check(context.packageManager.queryIntentActivities(incoming,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name==MainActivity::class.java.name })
                passed.accept("Android routes ${expected.protocol.uppercase()} config links to MCRF")
                added.add(expected.id)
                instrumentation.runOnMainSync { instrumentation.callActivityOnNewIntent(activity,incoming) }
                check(await { Connections(context).apply { load() }.entries().any { it.id==expected.id } }) {
                    val field=MainActivity::class.java.getDeclaredField("engineImport").apply { isAccessible=true }
                    val state=(field.get(activity) as EngineImportUi).state
                    "Config was not imported: phase=${state?.phase}, error=${ClientEvents.redact(state?.error)}, busy=${activity.working()}"
                }
                check(incoming.data==null)
                passed.accept("${expected.protocol.uppercase()} intent imports its config without a second paste or transport start")
                // The dialog closes on the next UI tick after the atomic write.
                Thread.sleep(1000)
            }
            check(!McrfVpnService.status.startsWith("VPN подключён")&&!McrfVpnService.status.startsWith("Прокси "))
            passed.accept("Config import never starts a connection or consumes a one-use CSQTT binding")
        } finally {
            val catalogue=Connections(context).apply { load() };added.forEach(catalogue::remove)
            instrumentation.runOnMainSync { activity.finish() }
        }
        check(ProfileStore.snapshot(context)==before && context.getSharedPreferences("MainActivity",0).all==preferences)
        passed.accept("Only isolated import entries removed; all real connections and preferences preserved")
    }
}
