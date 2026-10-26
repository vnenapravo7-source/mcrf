package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer
import org.json.JSONObject

/** Explicit final-batch mode only. No UI/network/account/installer/user preferences or active VPN.
 * The draft phase writes fake values only beneath this isolated UID's own temporary root. */
object Post55LocalDeviceChecks {
    @JvmStatic fun run(base:Context,temporary:File,passed:Consumer<String>) {
        check(base.packageName=="org.mcrf.p3test") {"Post55 checks require the isolated review UID"}
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode>=10056) {"Old APK cannot verify post55 sources"}
        check(!RemoteServerOperationGate.busy()) {"Do not disturb another operation"}
        check(temporary.canonicalFile.parentFile==base.cacheDir.canonicalFile&&!temporary.exists()&&temporary.mkdir())
        val isolated=object:ContextWrapper(base) {
            override fun getFilesDir()=temporary
            override fun getApplicationContext():Context=this
        }
        val store=AndroidDeployStore(isolated)
        var requests=0
        val vk=VkRelayAccount(store,{requests++;error("No VK request is permitted")},{error("No provider wait is permitted")})
        val backend=AndroidDeployBackend(store,vk)
        val states=mutableListOf<AndroidDeployState>()
        fun verify(value:Boolean,message:String){check(value){message};passed.accept(message)}
        try {
            backend.close() // An empty executor is stopped BEFORE any task may be submitted.
            backend.listener={states.add(it)}
            repeat(2) {
                val target=AndroidDeployTarget("192.0.2.1",sshPassword="fixture-not-a-server-secret",
                    panelUser="fixture",panelPassword="fixture-panel-password",options=JSONObject().put("create",false))
                var rejected=false
                try {backend.start("3xui",target)}catch(_:RejectedExecutionException){rejected=true}
                check(rejected) {"Stopped executor must reject, never run SSH"}
                check(!backend.state.running&&backend.state.phase=="Не запущено") {"Failed submission must leave a stopped state"}
                check(backend.state.error.isNotBlank()&&!backend.state.error.contains("fixture-")) {"Failure must be actionable and contain no fixture secret"}
                check(!RemoteServerOperationGate.busy()) {"Failed submission must release its own remote lease"}
            }
            verify(states.size==4&&states.map {it.running}==listOf(true,false,true,false),"Rejected deploy submissions publish stopped state on initial attempt and retry")
            verify(!RemoteServerOperationGate.busy(),"Rejected deploy submission releases its exact operation lease")
            verify(requests==0,"Rejected deploy submission never requests VK or waits for a provider")
            verify(temporary.listFiles()?.isEmpty()==true,"Rejected deploy submission performs no encrypted profile or draft write")
        } finally {backend.close()}
        val draft=DeploymentDraftRetry()
        var creates=0
        val parent="192.0.2.1:22/3xui/fixture-parent"
        val first=JSONObject().put("host","192.0.2.1").put("options",JSONObject().put("create",false))
        val id=draft.save({creates++;store.newDeployment("3xui","first",first,parent)},{error("No prior draft")})
        val edited=JSONObject(first.toString()).put("host","192.0.2.2")
        val same=draft.save({creates++;error("Must reuse draft")},{store.saveDeployment(it,"edited",edited)})
        val drafts=store.workspace().getJSONObject("drafts")
        verify(creates==1&&id==same&&drafts.length()==1,"Rejected-start retry keeps one real encrypted deployment draft and the same ID")
        val saved=drafts.getJSONObject(id)
        verify(saved.getString("name")=="edited"&&saved.getJSONObject("values").getString("host")=="192.0.2.2"&&saved.getString("panelResultKey")==parent,
            "Retry stores edited fields while retaining the existing panel association")
    }
}
