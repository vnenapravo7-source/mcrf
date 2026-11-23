package org.mcrf.client

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import org.json.JSONArray
import java.util.function.Consumer
import java.util.function.Supplier

/** Per-app VPN launch and freeze rules for Anubis. */
internal object AppLaunchGroupsUi {
    enum class Mode(val key:String,val title:String,val hint:String) {
        WITH_VPN("launchAppsWithVpn","Открывать с VPN","MCRF подключит VPN перед запуском."),
        WITHOUT_VPN("launchAppsWithoutVpn","Открывать без VPN","MCRF отключит свой VPN перед запуском."),
        ONLY_VPN("launchAppsOnlyVpn","Только с VPN","Без VPN приложение скрыто. Для запуска удерживайте приложение в списке или используйте ярлык MCRF.");

        val withVpn get()=this==WITH_VPN||this==ONLY_VPN
        val needsManagement get()=this==ONLY_VPN
    }

    const val KEEP_VPN_AFTER_EXIT="anubisKeepVpnAfterExit"

    fun keepVpnAfterExit(context:Context,id:String)=modeFor(context,id)==Mode.WITH_VPN&&
        prefs(context).getBoolean(KEEP_VPN_AFTER_EXIT,false)

    private fun prefs(context:Context)=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
    private fun selected(context:Context,mode:Mode):Set<String> = runCatching {
        val values=JSONArray(prefs(context).getString(mode.key,"[]"))
        (0 until values.length()).map(values::getString).toSet()
    }.getOrDefault(emptySet())

    fun modeFor(context:Context,id:String)=Mode.entries.singleOrNull {id in selected(context,it)}
    fun vpnOnly(context:Context)=selected(context,Mode.ONLY_VPN)
    fun systemLaunchPackages(context:Context)=selected(context,Mode.WITH_VPN)+selected(context,Mode.WITHOUT_VPN)

    fun releaseVpnOnly(context:Context,ids:Set<String>){
        val existing=vpnOnly(context)
        val released=existing.intersect(ids)
        if(released.isEmpty())return
        val vpn=selected(context,Mode.WITH_VPN)
        check(prefs(context).edit().putString(Mode.ONLY_VPN.key,JSONArray((existing-released).toList()).toString())
            .putString(Mode.WITH_VPN.key,JSONArray((vpn+released).toList()).toString()).commit())
    }

    private fun save(activity:MainActivity,mode:Mode,ids:Set<String>,keepVpn:Boolean?){
        val allowed=if(mode.needsManagement)ids.filterTo(mutableSetOf()){ManagedAppFreezeUi.canManage(activity,it)} else ids
        if(allowed!=ids){
            val rejected=(ids-allowed).first()
            activity.toast(ManagedAppFreezeUi.blockReason(activity,rejected)?:"Android защищает это приложение")
        }
        val edit=activity.prefs.edit()
        if(keepVpn!=null)edit.putBoolean(KEEP_VPN_AFTER_EXIT,keepVpn)
        for(group in Mode.entries)
            edit.putString(group.key,JSONArray((if(group==mode)allowed else selected(activity,group)-allowed).toList()).toString())
        check(edit.commit())
        if(allowed.isNotEmpty())ManagedAppFreezeUi.configureAutomatic(activity,ManagedAppFreezeUi.watched(activity)-allowed)
        ManagedAppFreezeUi.observe(activity)
        activity.render()
    }

    private fun ruleDetail(count:Int,hint:String):CharSequence {
        val prefix="Выбрано: "
        val amount=count.toString()
        return SpannableString("$prefix$amount · $hint").apply {
            setSpan(ForegroundColorSpan(Loft.ACCENT),prefix.length,prefix.length+amount.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun view(activity:MainActivity):LinearLayout=with(activity){
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="anubis-settings"
            addView(text("Переключает VPN при открытии выбранных приложений и замораживает их по заданным правилам.",13,Loft.MUTED),space(0,12))
            val nodes=SettingsUiNodes(this@with)
            val installed=AnubisLaunchObserver.installed(this@with)
            val permitted=installed && AnubisLaunchObserver.setupComplete(this@with)
            val migrating=!installed && AnubisModuleInstall.isExistingInstall(this@with)
            if(!installed&&!migrating){
                addView(nodes.actionRow(
                    "Скачать пакет Anubis",
                    "Установите дополнительный модуль и подтвердите установку в Android.",
                    "arrow","Скачать пакет Anubis"
                ){AnubisModuleInstall.start(this@with)}
                    .apply {tag="anubis-module-install"},space(0,12))
            }
            if(!permitted){
                addView(nodes.actionRow(
                    "Разрешите Anubis следить за запуском приложений",
                    when {
                        installed -> AnubisLaunchObserver.setupHint(this@with)
                        migrating -> "Правила сохранены. Нажмите, чтобы восстановить Anubis после обновления."
                        else -> "Этот шаг станет доступен после установки пакета."
                    },
                    "arrow","Настроить разрешение Anubis"
                ){
                    if(installed)AnubisLaunchObserver.openSettings(this@with)
                    else if(migrating)AnubisModuleInstall.start(this@with)
                    else toast("Сначала установите пакет Anubis.")
                }.apply {tag="anubis-accessibility-setup"},space(0,12))
                if(!installed)addView(text(
                    "Play Защита может заблокировать пакет Anubis. Если вы доверяете официальному APK: Google Play → профиль → Play Защита → настройки → временно отключите сканирование. Включите его снова сразу после установки.",
                    12,Loft.MUTED
                ),space(0,12))
                return@apply
            }
            addView(nodes.actionRow(
                "Способ управления приложениями",
                ManagedAppFreezeUi.providerSummary(this@with),
                "arrow","Выбрать способ управления приложениями"
            ){ManagedAppFreezeUi.show(this@with)}
                .apply {tag="anubis-provider"},space(0,12))
            divider(this,8,16)
            addView(LinearLayout(this@with).apply {
                orientation=LinearLayout.VERTICAL
                tag="anubis-app-rules"
                addView(text("Правила для приложений",17,Loft.INK).apply {isAccessibilityHeading=true},space(0,12))
                val hidden=ManagedAppFreezeUi.watched(this@with)
                addView(nodes.actionRow(
                    "Скрывать при подключении VPN",
                    ruleDetail(hidden.size,"Приложения становятся недоступны при активном VPN."),
                    "arrow","Выбрать приложения, скрываемые при активном VPN"
                ){
                    AppPicker.showLoading(this@with,"Скрывать при подключении VPN",Supplier {hidden},Consumer {chosen->
                        val eligible=chosen.availableForRule(this@with,hidden).filterTo(mutableSetOf()) {
                            ManagedAppFreezeUi.canManage(this@with,it)
                        }
                        if(eligible.size!=chosen.size){
                            val rejected=(chosen-eligible).first()
                            toast(ManagedAppFreezeUi.blockReason(this@with,rejected)?:"Приложение недоступно для этого правила")
                        }
                        val edit=prefs.edit()
                        for(mode in Mode.entries)
                            edit.putString(mode.key,JSONArray((selected(this@with,mode)-eligible).toList()).toString())
                        check(edit.commit())
                        ManagedAppFreezeUi.configureAutomatic(this@with,eligible)
                        render()
                        askForProvider(this@with,eligible)
                    },selectedFirst=true)
                }.apply {tag="anubis-group-hide_while_vpn"},space(0,10))
                Mode.entries.forEach {mode->
                    val group=selected(this@with,mode)
                    addView(nodes.actionRow(
                        mode.title,ruleDetail(group.size,mode.hint),"arrow","Выбрать приложения · ${mode.title}"
                    ){
                        val keepVpn=if(mode==Mode.WITH_VPN)android.widget.Switch(this@with).apply {
                            text="Оставлять VPN после выхода из приложения"
                            tag="anubis-keep-vpn-after-exit"
                            tune(this)
                            isChecked=prefs.getBoolean(KEEP_VPN_AFTER_EXIT,false)
                            minHeight=dp(48)
                        }else null
                        var picker:android.app.AlertDialog?=null
                        picker=AppPicker.showLoading(this@with,mode.title,Supplier {group},Consumer {chosen->
                            val eligible=chosen.availableForRule(this@with,group)
                            save(this@with,mode,eligible,keepVpn?.isChecked)
                            if(mode.needsManagement)askForProvider(this@with,eligible)
                        },selectedFirst=true,itemActions={anchor,id->
                            if(id !in group)false else {
                                showAppActions(this@with,anchor,id){picker?.dismiss()}
                                true
                            }
                        },extraControl=keepVpn)
                    }.apply {tag="anubis-group-${mode.name.lowercase()}"},space(0,10))
                }
            },space(0,12))
        }
    }

    private fun showAppActions(activity:MainActivity,anchor:android.view.View,id:String,onLaunch:()->Unit){
        val manager=activity.packageManager
        val label=runCatching {manager.getApplicationLabel(manager.getApplicationInfo(id,0)).toString()}.getOrDefault(id)
        val actions=listOf(DropdownPopup.Item("Открыть"),DropdownPopup.Item("Закрепить ярлык"))
        DropdownPopup.show(activity,anchor,label,actions,-1){index->
            when(index){
                0->{onLaunch();AppLaunchShortcuts.requestLaunch(activity,id)}
                1->AppLaunchShortcuts.pin(activity,id,label)
            }
        }
    }

    private fun Set<String>.availableForRule(activity:MainActivity,existing:Set<String>)=
        filterTo(mutableSetOf()) {
            it!=activity.packageName&&(it in existing||activity.packageManager.getLaunchIntentForPackage(it)!=null)
        }

    private fun askForProvider(activity:MainActivity,eligible:Set<String>){
        if(eligible.isNotEmpty()&&AnubisLaunchObserver.enabled(activity)&&!ManagedAppFreezeUi.authorized())
            ManagedAppFreezeUi.show(activity)
    }
}
