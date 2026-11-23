package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Bounded owned-emulator UI checks. No provider traffic or throughput measurement. */
class RequestR2Instrumentation:Instrumentation() {
    private var controlsOnly=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);controlsOnly=arguments?.getString("controlsOnly")=="true";start()}
    private fun main(action:()->Unit){val e=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(t:Throwable){e.set(t)}};e.get()?.let {throw it}}
    private fun find(v:View,p:(View)->Boolean):View? {if(p(v))return v;if(v is ViewGroup)repeat(v.childCount){find(v.getChildAt(it),p)?.let {return it}};return null}
    private fun window(p:(View)->Boolean)=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {find(it,p)}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(50)};error(message)}
    private fun capture(name:String){waitForIdleSync();Thread.sleep(200);val image=requireNotNull(uiAutomation.takeScreenshot());val dir=File(targetContext.getExternalFilesDir(null),"request-r4-final-ui").apply {mkdirs()};File(dir,"$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    private fun dismiss(){sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync()}
    override fun onStart(){val report=Bundle();var activity:MainActivity?=null
        val prefs=targetContext.getSharedPreferences("MainActivity",0);val saved=prefs.all.toMap()
        var snapshots:CatalogueSnapshots?=null;var original:CatalogueSnapshot?=null
        var clipboard:android.content.ClipData?=null;var clipboardCaptured=false
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            prefs.edit().putString(ClientPrimarySelection.KEY,"singbox").putString("theme","dark").putString(ConnectionAppearance.STYLE,ConnectionAppearance.TOGGLE)
                .putBoolean(ConnectionControlsDock.HIDDEN,false).putBoolean(EdgePanelAppearance.HIDDEN,false).putBoolean("resourceMonitorQuickStart",false).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host=activity!!;await("Catalogue loading"){!host.working()};main {check(ClientPrimarySelection.selected(host)=="singbox");check(window {it.tag=="primary-choice-scroll"}==null)}
            main {clipboard=host.getSystemService(android.content.ClipboardManager::class.java).primaryClip;clipboardCaptured=true}
            val state=Connections::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host.catalogue) as CatalogueSnapshots
            snapshots=state;original=state.readable()
            val data=ConnectionCatalogue("").apply {
                replaceSubscription("https://one.example/sub",ServerImport.read("socks5://192.0.2.1:1080#Server-A"),JSONObject().put("title","Y06.11-7"))
                replaceSubscription("https://outright7.site/sub",ServerImport.read("socks5://192.0.2.2:1080#Server-B"))
            }
            data.recordExitCountry(data.entries().first().id,"JP",System.currentTimeMillis())
            data.subscriptions().forEach {prefs.edit().putBoolean(ServerGroupDisclosure.key(it.getString("id")),true).commit()}
            main {state.replace {CatalogueSnapshot(data)};host.navigate("Главная");host.render()};waitForIdleSync()
            fun tagged(tag:String)=requireNotNull(window {it.tag==tag}){"Missing $tag"}
            main {
                check(tagged("connection-controls-hide").rotation==0f)
                check(tagged("home-mode-switch").parent===tagged("connection-mode-actions"))
                check(tagged("connection-controls-hide").parent===tagged("home-mode-switch").parent)
                check(find(tagged("connection-controls-block")){it is TextView&&it.text.toString()=="Управление"}==null)
                check(find(tagged("connection-controls-block")){it.tag=="auto-bs-control"}!=null)
                check(find(tagged("connection-controls-block")){it.tag=="server-actions"}!=null)
                check(window {it.tag=="server-card-title"}==null)
                tagged("connection-controls-hide").performClick()
            }
            Thread.sleep(600)
            main {check(tagged("connection-controls-holder").visibility==View.GONE);check(!prefs.getBoolean(EdgePanelAppearance.HIDDEN,true));tagged("edge-open-controls").performClick()}
            waitForIdleSync();capture("01-controls-docked")
            main {check(tagged("connection-controls-block").isShown);check(tagged("connection-controls-block").background==null);check((tagged("connection-controls-block").parent as View).clipToOutline);check(tagged("connection-controls-hide").rotation==180f);tagged("connection-controls-hide").performClick()}
            Thread.sleep(600);main {check(tagged("connection-controls-holder").isShown);check(tagged("connection-controls-block").background is Loft.Glass);check(tagged("connection-controls-hide").rotation==0f)}
            if(controlsOnly){
                capture("00-controls-restored-arrow")
                main{host.navigate(ClientSettingsNavigation.ROOT);host.navigate(ClientSettingsNavigation.VK)};waitForIdleSync()
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync()
                main{check(window{it.tag=="screen-${ClientSettingsNavigation.ROOT}"}!=null){"System Back failed from VK"};host.navigate(ClientSettingsNavigation.VK)};waitForIdleSync()
                main{tagged("nav-${ClientSettingsNavigation.ROOT}").performClick()};waitForIdleSync()
                main{check(window{it.tag=="screen-${ClientSettingsNavigation.ROOT}"}!=null){"Selected Settings tab did not open root"}}
                capture("00-settings-returned-from-vk")
                report.putString("result","PASS: inline arrow0/floating arrow180/actual restore arrow click; Android BACK from VK to Settings root; selected Settings tab from VK to root; saved state restored")
                finish(android.app.Activity.RESULT_OK,report);return
            }
            main {AutoExclusionsUi.show(host)};waitForIdleSync()
            main {check(tagged("auto-exclusions-text") is EditText);check(tagged("auto-exclusions-geo") is EditText);tagged("auto-exclusions-countries").performClick()}
            waitForIdleSync()
            main {
                val anchor=tagged("auto-exclusions-countries");val popup=tagged("auto-exclusions-countries-popup")
                val a=IntArray(2).also {anchor.getLocationOnScreen(it)};val b=IntArray(2).also {popup.getLocationOnScreen(it)}
                check(kotlin.math.abs(b[1]-a[1]-anchor.height)<=host.dp(1)){"Country popup detached from field: delta=${b[1]-a[1]-anchor.height}"}
                check(kotlin.math.abs(b[0]-a[0])<=host.dp(1)&&popup.width==anchor.width)
                val footer=anchor.rootView.findViewById<View>(android.R.id.button1)
                val end=IntArray(2).also {footer.getLocationOnScreen(it)}
                check(b[1]+popup.height<=end[1]-host.dp(8)){"Country popup covers dialog actions"}
            }
            capture("02-country-multiselect")
            main {check(window {it is CheckBox&&it.text.toString().endsWith(" · JP")}!=null);val box=window {it is CheckBox&&it.text.toString().endsWith(" · RU")} as CheckBox;box.performClick();check(box.isChecked)}
            main {(window {it.tag=="auto-exclusions-countries-done"}?:error("Country picker footer missing")).performClick()};waitForIdleSync()
            main {(window {it is Button&&it.text.toString()=="Закрыть"}?:error("Exclusions close missing")).performClick()};waitForIdleSync()
            main {check(window {it.tag=="auto-exclusions-text"&&it.isShown}==null)}
            main {host.navigate(ClientSettingsNavigation.ROOT);host.navigate(ClientSettingsNavigation.APPEARANCE)};waitForIdleSync()
            main {val scroll=find(host.window.decorView){it is ScrollView} as ScrollView;scroll.scrollTo(0,host.dp(140))}
            val position=AtomicReference<Int>();main {position.set((find(host.window.decorView){it is ScrollView} as ScrollView).scrollY);host.navigate(ClientSettingsNavigation.WHITELISTS)}
            main {
                val navigation=window {it.tag=="top-section-navigation"||it.tag=="bottom-section-navigation"}
                val settings=navigation?.let {find(it){v->v.isClickable&&v.contentDescription?.toString()?.startsWith("Настройки")==true}}
                    ?:error("Settings navigation target missing")
                settings.performClick()
            };waitForIdleSync()
            main {check(window {it.tag=="screen-${ClientSettingsNavigation.APPEARANCE}"}!=null);check((find(host.window.decorView){it is ScrollView} as ScrollView).scrollY==position.get())}
            main {host.navigate(ClientSettingsNavigation.ROOT);tagged("settings-invite").performClick()};capture("03-invite-popup");main {
                check(tagged("invite-code-settings").background==null){"Invite must have one dialog surface"}
                val title=IntArray(2).also {tagged("invite-dialog-title").getLocationOnScreen(it)}
                val binding=IntArray(2).also {tagged("invite-binding-title").getLocationOnScreen(it)}
                check(title[0]==binding[0]){"Invite headings have unequal start insets"}
            };main {(window {it is Button&&it.text.toString()=="Закрыть"}?:error("Invite close missing")).performClick()};waitForIdleSync()
            main {host.resourceMonitor.showSettings()};waitForIdleSync()
            main {(tagged("resource-monitor-quick-start") as Switch).isChecked=true};main {(window {it is Button&&it.text.toString()=="Закрыть"}?:error("Monitor close missing")).performClick()};waitForIdleSync()
            main {host.resourceMonitor.openShortcut();check(host.resourceMonitor.enabled);check(window {it.tag=="resource-monitor-settings"}!=null);check(window {it is ResourceMonitorHud}==null)}
            Thread.sleep(1100)
            main {(tagged("resource-monitor-quick-start") as Switch).isChecked=false};main {(window {it is Button&&it.text.toString()=="Закрыть"}?:error("Monitor close missing")).performClick()};waitForIdleSync()
            main {val hud=window {it is ResourceMonitorHud}?:error("HUD missing");val lines=ArrayList<String>();fun collect(v:View){if(v is TextView&&v.text.isNotEmpty())lines.add(v.text.toString());if(v is ViewGroup)repeat(v.childCount){collect(v.getChildAt(it))}};collect(hud);check(lines.size==2&&!lines.any {it.startsWith("MCRF")})}
            capture("04-monitor-two-rows");main {host.resourceMonitor.setEnabled(false)}
            main {state.replace {CatalogueSnapshot(data)};host.navigate("Главная");host.render()};waitForIdleSync()
            val sub=data.subscriptions().first();val id=sub.getString("id")
            main {val header=tagged("server-group-toggle-$id");header.requestRectangleOnScreen(Rect(0,0,header.width,header.height),true)};waitForIdleSync()
            main {val header=tagged("server-group-toggle-$id");check(header.height<=host.dp(56))
                val first=IntArray(2).also {header.getLocationOnScreen(it)}
                val next=tagged("server-group-toggle-${data.subscriptions()[1].getString("id")}")
                val second=IntArray(2).also {next.getLocationOnScreen(it)}
                check(second[1]-first[1]-header.height in host.dp(2)..host.dp(8))
                val more=find(header){it.contentDescription?.startsWith("Действия с подпиской")==true}!!;more.performClick()}
            capture("05-subscription-menu");main {check(window {it is TextView&&it.text=="Данные подписки"}!=null);check(window {it is TextView&&it.text=="Изменить ссылку подписки"}!=null)};dismiss()
            val bounds=Rect();main {check(tagged("server-group-toggle-$id").getGlobalVisibleRect(bounds))}
            fun swipe(delta:Float){val x=bounds.centerX().toFloat();val y=bounds.centerY().toFloat();val down=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0));for(i in 1..8){sendPointerSync(MotionEvent.obtain(down,down+i*18L,MotionEvent.ACTION_MOVE,x+delta*i/8,y,0))};sendPointerSync(MotionEvent.obtain(down,down+160,MotionEvent.ACTION_UP,x+delta,y,0));Thread.sleep(250)}
            swipe(-host.dp(90).toFloat());capture("06-subscription-manage");main {check(tagged("server-ping-subscription-$id").contentDescription=="Обновить подписку")}
            swipe(host.dp(90).toFloat());swipe(host.dp(90).toFloat());main {check(tagged("server-share-tray-subscription-$id").visibility==View.VISIBLE);tagged("server-share-subscription-$id").performClick()};capture("07-subscription-share")
            main {
                check(window {it is TextView&&it.text.toString()=="Копировать профиль с настройками MCRF"}==null)
                (window {it is Button&&it.text.toString()=="Копировать ссылку подписки"}?:error("Subscription copy action missing")).performClick()
            };waitForIdleSync()
            main {check(host.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()==sub.getString("url"))}

            main {
                host.navigate("Главная")
                MainActivity::class.java.getDeclaredMethod("offerPrimaryChoice",Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(host,true)
            };waitForIdleSync()
            val originalChoice=prefs.getString(ClientPrimarySelection.KEY,null)
            main {
                val selector=tagged("primary-core-selector") as ModeSwitch
                check(!tagged("primary-install-wg-addon").isShown){"WG addon visible for sing-box"}
                selector.getChildAt(1).performClick()
                check(tagged("primary-install-wg-addon").isShown==EnginePackages.missing(host,setOf(WgAddonPolicy.KIND)).isNotEmpty())
                check(prefs.getString(ClientPrimarySelection.KEY,null)==originalChoice)
                check(selector.getChildAt(1).isSelected)
                check(tagged("primary-choice-confirm").isShown)
                val scroll=tagged("primary-choice-scroll");check(scroll.paddingTop>=host.dp(16))
            };capture("09-primary-chooser-draft")
            main {
                (tagged("primary-core-selector") as ModeSwitch).getChildAt(0).performClick()
                check(!tagged("primary-install-wg-addon").isShown)
            };capture("10-primary-singbox-without-addon")
            main {(window {it is Button&&it.text.toString()=="Позже"}?:error("Chooser cancel missing")).performClick()};waitForIdleSync()
            main {check(prefs.getString(ClientPrimarySelection.KEY,null)==originalChoice);check(window {it.tag=="primary-choice-scroll"}==null)}
            // Exercise the same choice view with a recorder Host, without changing runtime engines.
            var confirm:(()->Unit)?=null;var selected="steer";var validations=0
            main {
                val body=EngineUpdatePrimaryUi.updatesView(host,object:EngineUpdatePrimaryUi.Host {
                    override fun selected()=selected
                    override fun validateFullSnapshot(choiceId:String):List<String>{validations++;return emptyList()}
                    override fun selectValidated(choiceId:String){selected=choiceId}
                },true,onDraft={confirm=it})
                val selector=find(body){it.tag=="primary-core-selector"} as ModeSwitch
                selector.getChildAt(0).performClick();check(selected=="steer"&&validations==0)
                requireNotNull(confirm).invoke();check(selected=="singbox"&&validations==1)
            }
            val previousStatus=McrfVpnService.status;val previousId=McrfVpnService.selectedId
            try {
                main {
                    state.replace {CatalogueSnapshot(data)};McrfVpnService.status="VPN подключён";McrfVpnService.selectedId=data.entries().first().id
                    (find(host.window.decorView){it is ScrollView} as ScrollView).scrollTo(0,0)
                    MainActivity::class.java.getDeclaredMethod("updateHomeControl").apply {isAccessible=true}.invoke(host)
                };waitForIdleSync();capture("08-connected-caption-owned-fixture")
                main {
                    val caption=tagged("connection-progress-caption") as TextView
                    check(caption.layout.lineCount<=2&&caption.text.toString().contains("192.0.2.1"))
                    check(caption.text.toString().lines().last()!="192.0.2.1")
                }
            } finally {main {McrfVpnService.status=previousStatus;McrfVpnService.selectedId=previousId}}
            report.putString("result","PASS: controls dock/restore, countries, nested settings/scroll, invite popup, quick monitor/two rows, subscription dropdown/gestures, balanced caption; ten owned screenshots + chooser draft/confirm/inset/WG-only-Steer, anchored country geometry/compact actions, aligned single Invite surface, headerless controls/mode-row arrow")
            finish(android.app.Activity.RESULT_OK,report)
        }catch(e:Throwable){report.putString("failure",e.stackTraceToString());finish(android.app.Activity.RESULT_CANCELED,report)}finally {
            main {
                if(clipboardCaptured){val manager=targetContext.getSystemService(android.content.ClipboardManager::class.java);clipboard?.let {manager.setPrimaryClip(it)}?:manager.clearPrimaryClip()}
                activity?.resourceMonitor?.setEnabled(false);original?.let {old->snapshots?.replace {old}};activity?.finish()}
            val edit=prefs.edit().clear();saved.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}};edit.commit()
        }
    }
}
