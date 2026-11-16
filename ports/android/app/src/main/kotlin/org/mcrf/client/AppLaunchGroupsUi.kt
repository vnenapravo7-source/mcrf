package org.mcrf.client

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import java.util.function.Consumer
import java.util.function.Supplier

/** Per-app VPN launch and freeze rules for Anubis. */
internal object AppLaunchGroupsUi {
    enum class Mode(val key:String,val title:String,val hint:String) {
        WITH_VPN("launchAppsWithVpn","Открывать с VPN","MCRF подключит VPN перед запуском."),
        WITHOUT_VPN("launchAppsWithoutVpn","Открывать без VPN","MCRF отключит свой VPN перед запуском."),
        ONLY_VPN("launchAppsOnlyVpn","Только с VPN","Без VPN приложение скрыто. Откройте его отсюда или через ярлык MCRF."),
        WITHOUT_VPN_NOTIFICATIONS("launchAppsWithoutVpnNotifications","Без VPN + уведомления",
            "При активном VPN приложение скрыто. После отключения восстанавливается и снова получает уведомления.");

        val withVpn get()=this==WITH_VPN||this==ONLY_VPN
        val needsShizuku get()=this==ONLY_VPN||this==WITHOUT_VPN_NOTIFICATIONS
    }

    private fun prefs(context:Context)=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
    private fun selected(context:Context,mode:Mode):Set<String> = runCatching {
        val values=JSONArray(prefs(context).getString(mode.key,"[]"))
        (0 until values.length()).map(values::getString).toSet()
    }.getOrDefault(emptySet())

    fun modeFor(context:Context,id:String)=Mode.entries.singleOrNull {id in selected(context,it)}
    fun vpnOnly(context:Context)=selected(context,Mode.ONLY_VPN)
    fun withoutVpnNotifications(context:Context)=selected(context,Mode.WITHOUT_VPN_NOTIFICATIONS)

    fun releaseWithoutVpnNotifications(context:Context,ids:Set<String>){
        val existing=withoutVpnNotifications(context)
        val released=existing.intersect(ids)
        if(released.isEmpty())return
        val without=selected(context,Mode.WITHOUT_VPN)
        check(prefs(context).edit()
            .putString(Mode.WITHOUT_VPN_NOTIFICATIONS.key,JSONArray((existing-released).toList()).toString())
            .putString(Mode.WITHOUT_VPN.key,JSONArray((without+released).toList()).toString()).commit())
    }
    fun systemLaunchPackages(context:Context)=selected(context,Mode.WITH_VPN)+selected(context,Mode.WITHOUT_VPN)

    fun releaseVpnOnly(context:Context,ids:Set<String>){
        val existing=vpnOnly(context)
        val released=existing.intersect(ids)
        if(released.isEmpty())return
        val vpn=selected(context,Mode.WITH_VPN)
        check(prefs(context).edit().putString(Mode.ONLY_VPN.key,JSONArray((existing-released).toList()).toString())
            .putString(Mode.WITH_VPN.key,JSONArray((vpn+released).toList()).toString()).commit())
    }

    private fun save(activity:MainActivity,mode:Mode,ids:Set<String>){
        val allowed=if(mode.needsShizuku)ids.filterTo(mutableSetOf()){ManagedAppFreezeUi.canManage(activity,it)} else ids
        if(allowed!=ids){
            val rejected=(ids-allowed).first()
            activity.toast(ManagedAppFreezeUi.blockReason(activity,rejected)?:"Android защищает это приложение")
        }
        val edit=activity.prefs.edit()
        for(group in Mode.entries)
            edit.putString(group.key,JSONArray((if(group==mode)allowed else selected(activity,group)-allowed).toList()).toString())
        check(edit.commit())
        if(allowed.isNotEmpty())ManagedAppFreezeUi.configureAutomatic(activity,ManagedAppFreezeUi.watched(activity)-allowed)
        ManagedAppFreezeUi.observe(activity)
        activity.render()
    }

    fun view(activity:MainActivity):LinearLayout=with(activity){
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="anubis-settings"
            val rows=mutableListOf<()->Unit>()
            val refresh={rows.forEach {it()}}
            addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view:android.view.View){ManagedAppFreezeUi.addStateListener(refresh);refresh()}
                override fun onViewDetachedFromWindow(view:android.view.View){ManagedAppFreezeUi.removeStateListener(refresh)}
            })
            addView(SettingsUiNodes(this@with).actionRow(
                "Запуск из Android",
                "Служба MCRF в спецвозможностях переключает VPN после запуска. Ярлык MCRF — до запуска.",
                "arrow","Настроить запуск из Android"
            ){
                AnubisLaunchObserver.openSettings(this@with)
            },space(0,12))
            addView(SettingsUiNodes(this@with).actionRow("Плитка Anubis","Открывает этот раздел из шторки","plus","Добавить плитку Anubis"){
                addAnubisTile(this@with)
            },space(0,12))
            addView(SettingsUiNodes(this@with).actionRow(
                "Скрывать при подключении VPN",
                "${ManagedAppFreezeUi.watched(this@with).size} выбрано · Требуется Shizuku",
                "arrow","Выбрать приложения для автозаморозки"
            ){
                val watched=ManagedAppFreezeUi.watched(this@with)
                AppPicker.showLoading(this@with,"Скрывать при подключении VPN",Supplier {watched},Consumer {chosen->
                    val eligible=chosen.filter {id->
                        ManagedAppFreezeUi.canManage(this@with,id)&&
                            (id in watched||packageManager.getLaunchIntentForPackage(id)!=null)
                    }.toSet()
                    val edit=prefs.edit()
                    for(mode in Mode.entries)
                        edit.putString(mode.key,JSONArray((selected(this@with,mode)-eligible).toList()).toString())
                    check(edit.commit())
                    ManagedAppFreezeUi.configureAutomatic(this@with,eligible)
                    render()
                    if(!ManagedAppFreezeUi.authorized())ManagedAppFreezeUi.show(this@with)
                })
            },space(0,12))
            Mode.entries.forEach {mode->
                val group=selected(this@with,mode)
                addView(card().apply {
                    tag="anubis-group-${mode.name.lowercase()}"
                    addView(text(mode.title,17,Loft.INK).apply {isAccessibilityHeading=true},space(0,3))
                    addView(text(mode.hint,12,Loft.MUTED),space(0,8))
                    addView(button("Выбрать приложения",false).apply {
                        setOnClickListener {
                            AppPicker.showLoading(this@with,mode.title,Supplier {group},Consumer {chosen->
                                save(this@with,mode,chosen.filter {
                                    it!=packageName&&
                                        (it in group||it in ManagedAppFreezeUi.watched(this@with)||packageManager.getLaunchIntentForPackage(it)!=null)
                                }.toSet())
                                if(mode.needsShizuku&&!ManagedAppFreezeUi.authorized())ManagedAppFreezeUi.show(this@with)
                            })
                        }
                    },space(0,5))
                    for(id in group){
                        val info=runCatching {packageManager.getApplicationInfo(id,0)}.getOrNull()
                        val label=info?.let {packageManager.getApplicationLabel(it).toString()}?:id
                        addView(LinearLayout(this@with).apply {
                            gravity=Gravity.CENTER_VERTICAL
                            val appIcon=ImageView(this@with).apply {
                                importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                if(info!=null)setImageDrawable(runCatching {packageManager.getApplicationIcon(info)}.getOrNull())
                            }
                            addView(appIcon,LinearLayout.LayoutParams(dp(28),dp(28)).apply {marginEnd=dp(8)})
                            val action=button(label,false).apply {
                                tag="launch-group-app"
                                setOnClickListener {launchFromGroup(id,mode.withVpn)}
                            }
                            addView(action,LinearLayout.LayoutParams(0,-2,1f))
                            val grayscale=ColorMatrixColorFilter(ColorMatrix().apply {setSaturation(0f)})
                            rows.add {
                                val frozen=ManagedAppFreezeUi.isFrozen(this@with,id)
                                val note=if(frozen)"заморожено" else if(mode.needsShizuku)
                                    ManagedAppFreezeUi.blockReason(this@with,id) else null
                                action.text=note?.let {"$label · $it"}?:label
                                appIcon.colorFilter=if(frozen)grayscale else null
                            }
                            val more=iconButton("more","Действия для $label")
                            fun options(){
                                val frozen=ManagedAppFreezeUi.isFrozen(this@with,id)
                                val groups=Mode.entries.filter {it!=mode}
                                val choices=buildList {
                                    add(DropdownPopup.Item("Закрепить ярлык"))
                                    add(DropdownPopup.Item(if(frozen)"Восстановить"else"Заморозить вручную"))
                                    groups.forEach {add(DropdownPopup.Item("Перенести · ${it.title}"))}
                                }
                                DropdownPopup.show(this@with,more,label,choices,-1){index->
                                    when(index){
                                        0->AppLaunchShortcuts.pin(this@with,id,label)
                                        1->if(frozen)ManagedAppFreezeUi.restoreOne(this@with,id)
                                            else ManagedAppFreezeUi.freezeOne(this@with,id)
                                        else->groups.getOrNull(index-2)?.let {save(this@with,it,selected(this@with,it)+id)}
                                    }
                                }
                            }
                            more.setOnClickListener {options()}
                            action.setOnLongClickListener {options();true}
                            addView(more,LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(4)})
                        },space(0,4))
                    }
                },space(0,12))
            }
            addView(LinearLayout(this@with).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(button("Заморозка · Shizuku",false).apply {
                    tag="anubis-freeze";setOnClickListener {ManagedAppFreezeUi.show(this@with)}
                },LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(6)})
                addView(button("Проверка сети",false).apply {
                    tag="anubis-network-check";setOnClickListener {AnubisNetworkCheck.show(this@with)}
                },LinearLayout.LayoutParams(0,-2,1f))
            },space(0,12))
        }
    }

    private fun addAnubisTile(activity:MainActivity)=with(activity){
        val spec=QuickTileAvailability.tiles.first {it.service==AnubisQuickTile::class.java}
        runCatching {QuickTileAvailability.setEnabled(this,spec,true)}
        if(android.os.Build.VERSION.SDK_INT>=33){
            runCatching {
                getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                    android.content.ComponentName(this,spec.service),spec.title,
                    android.graphics.drawable.Icon.createWithResource(this,spec.icon),mainExecutor
                ) {result->
                    if(result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED||
                        result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)toast("Плитка доступна в шторке")
                    else toast("Плитку можно добавить через редактор шторки")
                }
            }.onFailure {toast("Добавьте плитку через редактор шторки")}
        }else toast("Добавьте плитку через редактор шторки")
    }
}
