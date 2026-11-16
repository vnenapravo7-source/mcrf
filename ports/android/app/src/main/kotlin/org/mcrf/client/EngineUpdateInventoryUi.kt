package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.Locale

/** The update page shares installation/removal actions with the existing engine manager. */
internal object EngineUpdateInventoryUi {
    fun view(activity:MainActivity,primaryHost:EngineUpdatePrimaryUi.Host,simple:Boolean):LinearLayout=with(activity) {
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="updates-engine-inventory-flat"}
        body.addView(android.widget.CheckBox(this).apply {
            text="Бета-версии приложения и движков"
            tag="mobile-beta-updates"
            isChecked=prefs.getBoolean(MobileBetaUpdates.KEY,false)
            setOnCheckedChangeListener {_, enabled->
                prefs.edit().putBoolean(MobileBetaUpdates.KEY,enabled).apply()
                render()
            }
        })
        body.addView(EngineUpdatePrimaryUi.updatesView(this,primaryHost))
        val maintenance=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;tag="engine-maintenance-actions"}
        fun maintenanceAction(label:String,id:String,action:()->Unit) {
            maintenance.addView(button(label,false).apply {
                tag=id;minimumWidth=0;minWidth=0;minimumHeight=dp(48)
                setPadding(dp(4),dp(8),dp(4),dp(8));setSingleLine(false)
                textSize=11f;setOnClickListener {action()}
            },LinearLayout.LayoutParams(0,-2,1f).apply {if(maintenance.childCount>0)marginStart=dp(4)})
        }
        maintenanceAction("Остановить все","stop-all-engines"){stopProcessesForEngineRemoval()}
        maintenanceAction("Удалить допы","remove-additional-engines") {
            EngineStorage.removeAll(this,EngineStorage.removableKinds(this,EngineModules.kinds+EngineModules.packetCores+BsEngineContract.KIND),"все дополнительные движки")
        }
        maintenanceAction("Удалить все","remove-all-engines") {
            EngineStorage.removeAll(this,EngineStorage.removableKinds(this,ProtocolsSheet.engines),"все движки")
        }
        body.addView(maintenance,space(12,12))
        val extras=EngineTableUi.create(this)
        body.addView(extras)
        EngineTableUi.heading(this,extras,"Дополнительные движки")
        EngineUpdateScreenPolicy.additional(ProtocolsSheet.visibleAdditional(this)).filterNot {if(simple)it=="warpscout"else it=="openflux"}.forEach {kind->
            val installed=EnginePackages.missing(this,setOf(kind)).isEmpty()
            val size=EngineStorage.size(this,kind)
            val label=when(kind) {
                "bs"->EngineUpdateScreenPolicy.BS_LABEL;"tgws"->"Telegram WS";"openflux"->"OpenFlux"
                "warpscout"->"WARP Scanner";WgAddonPolicy.KIND->"WireGuard / AmneziaWG";else->kind.uppercase(Locale.ROOT)
            }
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;tag="additional-engine-$kind"
            }
            item.addView(text(label,13,Loft.INK))
            val removable=size?.packageId==EngineModules.packageName(kind)
            val version=size?.let {runCatching {packageManager.getPackageInfo(it.packageId,0).versionName}.getOrNull()}
            item.addView(text(listOfNotNull(version,size?.let {EngineStorage.format(this,it.bytes)}).joinToString(" · "),11,Loft.MUTED).apply {
                visibility=if(text.isEmpty())View.GONE else View.VISIBLE
            },space(4,0))
            val status=text(if(installed)"Установлен"else"Не установлен",11,Loft.MUTED).apply {accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
            item.addView(status,space(4,0))
            val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {isIndeterminate=true;visibility=View.GONE}
            item.addView(progress,space(0,8))
            var candidate:EnginePackage?=null
            val action=iconButton(if(installed)"refresh"else"download",if(installed)"Проверить обновление $label"else"Установить $label").apply {
                tag="engine-update-action-$kind"
            }
            action.setOnClickListener {
                if(!installed){installEngine(kind);return@setOnClickListener}
                candidate?.let {updateInstalledEngines(listOf(it));return@setOnClickListener}
                action.isEnabled=false;progress.visibility=View.VISIBLE;status.text="Проверяем…";status.setTextColor(Loft.MUTED)
                Thread({
                    val checked=runCatching {EnginePackages.published(kind,prefs.getBoolean(MobileBetaUpdates.KEY,false)).let {it to EnginePackages.installed(applicationContext,it)}}
                    runOnUiThread {
                        if(!item.isAttachedToWindow||isFinishing||isDestroyed)return@runOnUiThread
                        action.isEnabled=true;progress.visibility=View.GONE
                        checked.fold({(asset,current)->
                            candidate=if(current)null else asset
                            status.text=if(current)"Установлена актуальная версия"else"Обновление · ${EngineStorage.format(this@with,asset.bytes)}"
                            status.setTextColor(Loft.MUTED)
                            action.contentDescription=if(current)"Проверить обновление $label"else"Скачать обновление $label"
                        },{
                            status.text="Не удалось проверить обновление. Повторите попытку.";status.setTextColor(Loft.ERROR)
                        })
                    }
                },"mcrf-engine-check").start()
            }
            val actions=LinearLayout(this).apply {
                gravity=Gravity.END or Gravity.CENTER_VERTICAL
                addView(action,LinearLayout.LayoutParams(dp(44),dp(48)))
                if(removable)addView(iconButton("trash","Удалить $label").apply {
                    setOnClickListener {EngineStorage.remove(this@with,kind)}
                },LinearLayout.LayoutParams(dp(44),dp(48)))
            }
            val details=LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                addView(item,LinearLayout.LayoutParams(0,-2,1f))
                EngineUpdateScreenPolicy.additionalHelp(kind).takeIf {it.isNotBlank()}?.let {help->
                    addView(iconButton("info","О движке $label",20).apply {
                        tag="engine-help-$kind";setOnClickListener {alert(label,help)}
                    },LinearLayout.LayoutParams(dp(48),dp(48)).apply {gravity=Gravity.CENTER_VERTICAL})
                }
            }
            EngineTableUi.row(this,extras,details,actions)
        }
        if(!simple&&UsageFeatures.Feature.OPENFLUX in UsageFeaturesUi.selected(this))
            body.addView(EngineUpdateOpenFluxScriptUi.view(this),space(12,0))
        body
    }
}
