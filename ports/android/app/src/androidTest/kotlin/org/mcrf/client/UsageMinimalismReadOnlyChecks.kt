package org.mcrf.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Spinner
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference

/** All63 presentation combinations via explicit view inputs. No preference edits, installs or network actions. */
object UsageMinimalismReadOnlyChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val activity=test.startActivitySync(Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity")
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun main(action:()->Unit) {
            val error=AtomicReference<Throwable?>()
            test.runOnMainSync {try{action()}catch(failure:Throwable){error.set(failure)}}
            error.get()?.let {throw it}
        }
        var dialog:AlertDialog?=null
        try {
            val deadline=SystemClock.elapsedRealtime()+10000
            var ready=false
            while(!ready&&SystemClock.elapsedRealtime()<deadline){main {ready=activity.window.decorView.findViewWithTag<View>("home-servers")!=null};if(!ready)SystemClock.sleep(40)}
            check(ready){"Home not ready"}
            val preferences=activity.prefs.all.toMap()
            val protected=ProfileStore.snapshot(activity)
            val routing=RoutingStore(activity).apply {load()}
            val savedFakeIp=AdvancedDnsConfig.value(routing.snapshot()).optBoolean("fakeIp")
            val savedHttpProxy=ClientTunStore(activity).load().httpProxy
            val catalogue=activity.assets.open("protocol-catalogue.json").use {JSONArray(String(it.readBytes(),Charsets.UTF_8))}
            for(mask in 1 until (1 shl UsageFeatures.Feature.values().size)){
                val features=UsageFeatures.Feature.values().filterIndexed {index,_->mask and (1 shl index)!=0}.toSet()
                val proxy=UsageFeatures.Feature.PROXY in features
                val vpn=UsageFeatures.Feature.VPN in features
                val warp=UsageFeatures.Feature.WARP in features
                val turn=UsageFeatures.Feature.WHITELIST in features
                val usesTun=features.any {it!=UsageFeatures.Feature.PROXY}
                main {
                    val tun=ClientTunUi(activity,features).advancedView()
                    check((tun.findViewWithTag<View>("setting.httpProxy")!=null)==(savedHttpProxy||proxy&&usesTun)){"System VPN proxy leaked or repair hidden: $mask"}
                    check((tun.findViewWithTag<View>("setting.httpBypass")!=null)==(savedHttpProxy||proxy&&usesTun)){"System proxy bypass leaked or repair hidden: $mask"}
                    check((tun.findViewWithTag<View>("setting.customInbounds")!=null)==proxy){"Local proxy editor lost: $mask"}
                    val dns=AdvancedDnsEditor(activity,routing,features).advancedView()
                    check((dns.findViewWithTag<View>("dns-setting.fakeIp")!=null)==(savedFakeIp||usesTun)){"FakeIP leaked or repair hidden: $mask"}
                    check((dns.findViewWithTag<View>("dns-setting.fakeNoAAAA")!=null)==(savedFakeIp||usesTun)){"FakeIP IPv6 leaked or repair hidden: $mask"}
                    check(dns.findViewWithTag<View>("dns-setting.cache")!=null){"Shared DNS cache hidden"}
                    val extra=ProtocolsSheet.visibleAdditional(activity,features)
                    check(extra.none {it in EngineModules.primaryCores}){"Primary cores counted as additional"}
                    check(("tgws" in extra)==(proxy||turn)){"Telegram engine/catalogue disagree: $mask"}
                    check(("openflux" in extra)==(UsageFeatures.Feature.OPENFLUX in features)){"OpenFlux independence lost: $mask"}
                    check((S3BypassProfile.KIND in extra)==(UsageFeatures.Feature.S3_BYPASS in features)){"S3 independence lost: $mask"}
                    check(ProtocolsSheet.installed(activity,extra) in 0..extra.size){"Invalid installed additional count"}
                    dialog=ProtocolsSheet.show(activity,features=features)
                    val root=requireNotNull(dialog).window!!.decorView
                    repeat(catalogue.length()){index->
                        val item=catalogue.getJSONObject(index)
                        val expected=when {
                            item.getString("engine")=="sing-box-lx"->vpn||proxy||warp&&item.getString("name")=="WireGuard / AmneziaWG"
                            item.getString("name")=="Telegram WS"->proxy||turn
                            item.getString("name").equals("OpenFlux",true)->UsageFeatures.Feature.OPENFLUX in features
                            item.getString("name").equals("S3 Bypass",true)->UsageFeatures.Feature.S3_BYPASS in features
                            else->turn
                        }
                        check((root.findViewWithTag<View>("protocol-row-$index")!=null)==expected){"Catalogue mode mismatch: $mask/$index"}
                    }
                    requireNotNull(dialog).dismiss();dialog=null
                    // Real editor windows, explicit presentation input, no Save/picker/network actions.
                    for(savedMode in listOf("addresses","apps","in-apps","except-apps")){
                        val original=org.json.JSONObject().put("id","minimalism-readonly").put("name","Readonly")
                            .put("text",if(savedMode=="addresses")"example.com" else "example.com\napp:org.example.test")
                            .put("mode",savedMode).put("target","proxy").put("enabled",true)
                        dialog=RoutingPage(activity,routing,features=features).edit(original)
                        val editor=requireNotNull(dialog).window!!.decorView
                        val condition=requireNotNull(editor.findViewWithTag<Spinner>("route-condition"))
                        val expectedCount=if(usesTun)5 else if(savedMode=="addresses")2 else 3
                        check(condition.adapter.count==expectedCount){"Route app conditions leaked: $mask/$savedMode"}
                        check(condition.selectedItem.toString()==when(savedMode){"addresses"->"Только домены/IP";"apps"->"Только приложения";"in-apps"->"Домены/IP внутри приложений";else->"Домены/IP кроме приложений"}){"Saved route condition remapped"}
                        check((editor.findViewWithTag<View>("route-apps")!=null)==(usesTun||savedMode!="addresses")){"Route repair path missing: $mask/$savedMode"}
                        requireNotNull(dialog).dismiss();dialog=null
                    }
                }
                test.waitForIdleSync()
            }
            var confirmations=0
            main {
                dialog=RoutingAppPoliciesUi.confirm(activity){confirmations++}
                requireNotNull(dialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            test.waitForIdleSync()
            main {
                check(confirmations==0){"Cancel dispatched global-policy mutation"};dialog=null
                dialog=RoutingAppPoliciesUi.confirm(activity){confirmations++}
                val positive=requireNotNull(dialog).getButton(AlertDialog.BUTTON_POSITIVE)
                positive.performClick();positive.performClick()
            }
            test.waitForIdleSync()
            main {check(confirmations==1){"Global-policy confirmation not exactly once"};dialog=null}
            check(activity.prefs.all==preferences){"Read-only presentation checks edited preferences"}
            check(ProfileStore.snapshot(activity)==protected){"Read-only presentation checks edited encrypted stores"}
            return "PASS63combinations: six independent features; TUN-only FakeIP/system HTTP proxy hidden in proxy-only; shared DNS/local proxy retained; protocol catalogue filtered; preferences/encrypted stores unchanged; no installs/network/actions"
        }finally{main {dialog?.dismiss();activity.finish()}}
    }
}
