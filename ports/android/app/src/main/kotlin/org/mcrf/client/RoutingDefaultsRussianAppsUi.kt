package org.mcrf.client

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.widget.*

object RoutingDefaultsRussianAppsUi {
    private val loader=java.util.concurrent.Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-ru-app-picker").apply {isDaemon=true}}
    private var current=java.lang.ref.WeakReference<AlertDialog>(null)
    @JvmStatic @JvmOverloads fun show(activity:MainActivity,store:RoutingStore,onShown:(AlertDialog)->Unit = {}) {
        if(activity.isFinishing||activity.isDestroyed)return
        current.get()?.takeIf {it.isShowing&&it.context===activity}?.let {return}
        val host=FrameLayout(activity)
        val loading=activity.text("Проверяем установленные приложения…",13,Loft.MUTED).apply {setPadding(activity.dp(20),activity.dp(20),activity.dp(20),activity.dp(20))}
        host.addView(loading)
        val dialog=ThemedDialogBuilder(activity).setTitle("RU-app · напрямую").setView(host).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create()
        var pending:java.util.concurrent.Future<*>?=null
        dialog.setOnDismissListener {pending?.cancel(true)}
        AppPickerWindow.prepare(activity,dialog);dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
        current=java.lang.ref.WeakReference(dialog)
        pending=loader.submit {try {
        // Presence stays fresh. Load resource labels ONLY for matching RU apps,
        // not every system/application package installed on the device.
        val installed=activity.packageManager.getInstalledApplications(0).associateBy {it.packageName}
        val candidates=RoutingDefaultsRussianApps.installed(installed.keys)
        val labels=candidates.associate {item->
            if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
            item.packageName to runCatching {installed.getValue(item.packageName).loadLabel(activity.packageManager).toString().take(256)}.getOrDefault(item.name)
        }
        val editing=store.snapshotWithGeneration();val snapshot=editing.first;val direct=RoutingDefaultsRussianAppsPolicy.selected(snapshot)
        val shown=candidates.map {it.packageName}.toSet();val selected=direct.filter {it in shown}.toMutableSet()
        activity.runOnUiThread { if(!dialog.isShowing||activity.isFinishing||activity.isDestroyed)return@runOnUiThread;with(activity) {
            val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(8))}
            val search=editor("Поиск по названию или категории").apply {setSingleLine(true)};form.addView(search)
            val summary=text("",12,Loft.MUTED);form.addView(summary,space(8,8))
            val freeze=button("Замораживать при включённом VPN",true).apply {
                tag="ru-app-freeze-on-vpn"
                textSize=12f
                setPadding(dp(6),paddingTop,dp(6),paddingBottom)
                maxLines=Int.MAX_VALUE
                ellipsize=null
            }
            fun count(){
                summary.text="Установлено из справочника: ${candidates.size} / ${RoutingDefaultsRussianApps.items.size} · выбрано: ${selected.size}"
                freeze.isEnabled=selected.isNotEmpty()
            }
            val rows=AppSelectionList(this,selected) {count()}
            form.addView(rows,LinearLayout.LayoutParams(-1,0,1f))
            fun visible()=candidates.filter {RoutingDefaultsRussianApps.matches(it,labels.getValue(it.packageName),search.text.toString())}
            fun render(){
                rows.submit(visible().map {AppSelectionList.Item(it.packageName,it.name,"${it.category} · ${it.packageName}")});count()
            }
            form.addView(button("Выбрать/снять все",false).apply {setOnClickListener {
                val displayed=visible().map {it.packageName}.toSet()
                if(selected.containsAll(displayed))selected.removeAll(displayed)else selected.addAll(displayed)
                render()
            }},space(8,0))
            freeze.setOnClickListener {
                val submitted=selected.toSet()
                if(submitted.isEmpty())return@setOnClickListener
                val vpnOnly=AppLaunchGroupsUi.vpnOnly(this)
                val blocked=submitted.mapNotNull { id ->
                    val reason=if(id in vpnOnly)"правило «Только при подключении VPN»" else ManagedAppFreezeUi.blockReason(this,id)
                    reason?.let {"${labels.getValue(id)} — $it"}
                }
                if(blocked.isNotEmpty()){
                    alert("Заморозка при подключении VPN",blocked.joinToString("\n"))
                    return@setOnClickListener
                }
                try {
                    ManagedAppFreezeUi.configureAutomatic(this,ManagedAppFreezeUi.watched(this)+submitted)
                }catch(_:Exception){
                    alert("Заморозка при подключении VPN","Не удалось сохранить список Anubis. Попробуйте снова.")
                    return@setOnClickListener
                }
                toast("Список автоматической заморозки сохранён")
                dialog.dismiss()
                if(!ManagedAppFreezeUi.authorized()){
                    navigate(ClientSettingsNavigation.ANUBIS)
                }
            }
            form.addView(freeze,space(8,0))
            search.addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun afterTextChanged(s:Editable?)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=render()
            });render()
            host.removeAllViews();host.addView(form,FrameLayout.LayoutParams(-1,-1))
            fun commit(allow:Boolean,confirmed:Set<String> = emptySet()){
                val submittedSelection=selected.toSet()
                runAfterSuccess(MainActivity.Operation {
                val stillInstalled=packageManager.getInstalledApplications(0).map {it.packageName}.toSet()
                require(stillInstalled.containsAll(submittedSelection)){"Список установленных приложений изменился. Открой выбор заново."}
                store.editInGeneration(editing.second){current ->
                    val desired=(RoutingDefaultsRussianAppsPolicy.selected(current)-shown)+submittedSelection
                    val currentConflicts=RoutingDefaultsRussianAppsPolicy.conflicts(current,desired)
                    require(!allow||confirmed.containsAll(currentConflicts)){"Политики изменились. Открой выбор заново и подтверди новые конфликты."}
                    val next=RoutingDefaultsRussianAppsPolicy.apply(current,shown,submittedSelection,allow)
                    current.put("rules",next.getJSONArray("rules")).put("vpnApps",next.getJSONArray("vpnApps"))
                    if(next.has("appMode"))current.put("appMode",next.get("appMode"))
                }
                "Исключения RU-app сохранены"
            },true,Runnable {
                McrfVpnService.reloadActiveRoutes(this@with)
                dialog.dismiss()
            })}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current=store.snapshot();val desired=(RoutingDefaultsRussianAppsPolicy.selected(current)-shown)+selected
                val conflicts=RoutingDefaultsRussianAppsPolicy.conflicts(current,desired)
                if(conflicts.isEmpty())commit(false)else {
                    val scope=if("legacy-include-scope" in conflicts)"\n\nСтарый режим «Только выбранные» будет заменён обработкой всех приложений VPN. Оставшиеся политики «Только VPN» сохранятся." else ""
                    ThemedDialogBuilder(this).setTitle("Исключить приложения RU-app из VPN?")
                        .setMessage("Android будет направлять их трафик напрямую. Выбранные приложения будут удалены из «Только VPN». Остальные правила сохранятся."+scope)
                        .setNegativeButton("Назад",null).setPositiveButton("Применить напрямую"){_,_->commit(true,conflicts)}.show()
                }            };dialog.setOnDismissListener {pending?.cancel(true);rows.close()};form.isFocusableInTouchMode=true;form.requestFocus();onShown(dialog)
        }}
        }catch(_:java.util.concurrent.CancellationException){/* Closed picker owns cancellation. */}
        catch(_:Exception){activity.runOnUiThread {if(dialog.isShowing)loading.text="Не удалось прочитать приложения. Закройте окно и попробуйте снова."}}
        }
    }
}
