package org.mcrf.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.function.Consumer

/** New expanded package only. Real Android Keystore/storage/manifest/tickets;
 * isolated files and preferences, no remote deploy, VK/Cloudflare registration,
 * VPN switch, location permission request or mutation of user rule groups. */
object ExpandedP2DeviceChecks {
    @JvmStatic fun run(test:Instrumentation,temporary:File,passed:Consumer<String>){
        val target=test.targetContext;val before=ProfileStore.snapshot(target)
        val prefix="ExpandedP2-"+UUID.randomUUID().toString();val preferences=HashSet<String>()
        val scoped=object:ContextWrapper(target){
            override fun getFilesDir()=temporary
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                val isolated="$prefix-$name";preferences.add(isolated)
                return target.getSharedPreferences(isolated,mode)
            }
        }
        try{
            val store=AndroidDeployStore(scoped)
            val a=store.newDeployment("swg","Первый",JSONObject().put("host","192.0.2.1").put("sshPassword","fixture-A"))
            val b=store.newDeployment("swg","Второй",JSONObject().put("host","198.51.100.1").put("sshPassword","fixture-B"))
            val reopened=AndroidDeployStore(scoped).workspace()
            check(a!=b&&reopened.getJSONObject("drafts").length()==2)
            check(reopened.getJSONObject("drafts").getJSONObject(a).getJSONObject("values").getString("sshPassword")=="fixture-A")
            check(reopened.getJSONObject("selected").getString("swg")==b)
            store.selectDeployment("swg",a)
            check(AndroidDeployStore(scoped).selectedDeployment("swg")==a)
            passed.accept("57: named same-protocol VPS drafts and credentials survive real encrypted Android reload independently")

            val commands=listOf("rules","rules","auto-bs")
            val tickets=commands.map {QuickTileCommand.issue(scoped,it)}
            check(tickets.distinct().size==3)
            tickets.forEachIndexed {index,ticket->
                val intent=Intent().putExtra(QuickTileCommand.EXTRA,ticket)
                check(QuickTileCommand.consume(scoped,intent)==commands[index])
                check(QuickTileCommand.consume(scoped,Intent().putExtra(QuickTileCommand.EXTRA,ticket))==null)
            }
            passed.accept("56: rapid private QS tickets retain order and cannot be replayed; no connection action invoked")
            for((type,label) in listOf(VpnQuickTile::class.java to "VPN",BsQuickTile::class.java to "БС",RulesQuickTile::class.java to "Rules",AutoBsQuickTile::class.java to "Авто-БС")){
                val service=target.packageManager.getServiceInfo(ComponentName(target,type),0)
                check(service.exported&&service.permission=="android.permission.BIND_QUICK_SETTINGS_TILE")
                check(service.loadLabel(target.packageManager).toString()==label)
            }
            passed.accept("56: installed APK exposes all four labelled tiles with system-only binding permission")

            val routing=RoutingStore(scoped).apply {load()}
            val state=routing.snapshot();RoutingGroups.ensure(state)
            check(RoutingGroups.groups(state).map {it.second}==listOf("VPN","БС","Авто VPN + БС"))
            RoutingGroups.select(state,"bs");routing.save(state)
            val again=RoutingStore(scoped).apply {load()}.snapshot()
            check(RoutingGroups.active(again)=="bs")
            RoutingGroups.select(again,"auto");routing.save(again)
            check(RoutingGroups.active(RoutingStore(scoped).apply {load()}.snapshot())=="auto")
            passed.accept("55: three routing slots switch and survive encrypted storage without altering user routes")

            check(!BsWifiStore.load(scoped).pauseOnWifi)
            BsWifiStore.save(scoped,BsWifiSettings(trustedEnabled=true,trustedSsids=listOf("Fixture-WiFi")))
            val wifi=BsWifiStore.load(scoped)
            check(wifi.shouldPause(true,setOf("Fixture-WiFi"))&&!wifi.shouldPause(true,emptySet())&&!wifi.shouldPause(false,setOf("Fixture-WiFi")))
            check(BsScreenOffSettings.load(scoped).mode=="balanced")
            BsScreenOffSettings.save(scoped,BsScreenOffSettings("stable"))
            check(BsScreenOffSettings.load(scoped).holdCpu(false,true))
            check(!BsScreenOffSettings.load(scoped).holdCpu(true,true))
            passed.accept("54: WiFi trust and screen-off options persist privately; unknown SSID is not trusted, defaults remain opt-in")
        }finally{
            preferences.forEach {check(target.deleteSharedPreferences(it)){"Fixture preference cleanup failed"}}
            check(ProfileStore.snapshot(target)==before){"Expanded fixture modified user stores"}
        }
    }
}
