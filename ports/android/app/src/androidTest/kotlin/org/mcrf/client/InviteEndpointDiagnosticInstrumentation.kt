package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.net.URI
import org.json.JSONObject

/** Read-only diagnosis of the existing binding; never redeems, imports or emits credentials. */
class InviteEndpointDiagnosticInstrumentation: Instrumentation() {
    private var applySync=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);applySync=arguments?.getString("applySync")=="true";start()}
    override fun onStart(){
        val report=Bundle()
        try {
            val state=InviteCodeSync.state(targetContext)
            check(state!=null){"No existing binding; no redemption attempted"}
            val method=InviteCodeSync::class.java.getDeclaredMethod("request",JSONObject::class.java).apply {isAccessible=true}
            val manifest=method.invoke(InviteCodeSync,JSONObject().put("action","sync").put("token",state.token)) as InviteCodeSync.Manifest
            report.putInt("standaloneParsedServers",manifest.serverLinks.sumOf {ServerImport.read(it).size})
            for((label,url) in listOf("subscription" to manifest.subscription,"bypass" to manifest.bypass)) {
                if(url==null){report.putString(label,"absent");continue}
                val uri=URI(url)
                report.putString(label+"Host",uri.host)
                try {java.net.InetAddress.getAllByName(uri.host);report.putString(label+"Dns","resolved")}
                catch(_:java.net.UnknownHostException){report.putString(label+"Dns","unresolved")}
                if(label=="subscription")report.putInt("subscriptionPort",uri.port)
                if(label=="bypass")report.putInt("bypassParsedServers",ServerImport.read(SwgInviteSubscription.fetch(url)).size)
            }
            if(applySync) {
                report.putInt("syncedServers",requireNotNull(InviteCodeSync.refreshIfLinked(targetContext)))
                report.putInt("ownedSnapshotServers",Connections(targetContext).apply {load()}.inviteEntries().size)
            }
            finish(Activity.RESULT_OK,report)
        }catch(t:Throwable){report.putString("errorType",(t.cause?:t).javaClass.simpleName);finish(Activity.RESULT_CANCELED,report)}
    }
}
