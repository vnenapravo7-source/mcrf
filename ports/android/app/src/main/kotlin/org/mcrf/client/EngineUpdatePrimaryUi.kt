package org.mcrf.client

import android.app.AlertDialog
import android.widget.LinearLayout

/** Main supplies the strict ClientPrimaryCore snapshot validator and selection store. */
object EngineUpdatePrimaryUi {
    interface Host {
        fun selected():String?
        fun validateFullSnapshot(choiceId:String):List<String>
        fun selectValidated(choiceId:String)
        fun selectValidated(choiceId:String,onSelected:()->Unit) {
            selectValidated(choiceId)
            if(selected()==choiceId)onSelected()
        }
        fun confirmationRequired():Boolean=false
    }
    private fun confirm(activity:MainActivity,kind:String,accept:(Boolean)->Unit)=with(activity) {
        val singBoxInstalled=try {packageManager.getApplicationInfo(EngineModules.packageName("sing-box"),0);true}
            catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
        val offer=PrimaryCorePresentation.offerWgAddon(kind,EnginePackages.missing(this,setOf(WgAddonPolicy.KIND)).isEmpty())
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(4),dp(20),0)
            addView(text(PrimaryCorePresentation.confirmation(kind),13,Loft.MUTED),space(0,8))
        }
        val addon=android.widget.CheckBox(this).apply {
            text="Добавить WireGuard / AmneziaWG";textSize=13f;setTextColor(Loft.INK)
            minimumHeight=dp(48);isChecked=!singBoxInstalled;tag="primary-install-wg-addon"
        }
        if(offer)content.addView(addon)
        ThemedDialogBuilder(this).setTitle("Выбрать ${if(kind=="sing-box")"sing-box"else"Steer"}?")
            .setView(content).setNegativeButton("Отмена",null)
            .setPositiveButton("Выбрать"){_,_->accept(offer&&addon.isChecked)}.show()
    }
    fun requireSwitch(kind:String,installed:Boolean,active:Boolean,failures:List<String>) {
        require(kind in EngineUpdatePrimary.kinds&&installed){"Сначала установи выбранный основной движок."}
        require(!active){"Останови соединение и замер перед сменой движка."}
        require(failures.isEmpty()){failures.joinToString("\n").take(2000)}
    }
    /** Also used for first-launch chooser: null selection stays null until explicit choice succeeds. */
    fun view(activity:MainActivity,host:Host?=null,firstLaunch:Boolean=false):LinearLayout=with(activity){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="primary-core-chooser"}
        body.addView(text("Основной трафик обслуживает выбранный движок.",12,Loft.MUTED),space(0,12))
        if(!firstLaunch&&UsageFeatures.visible(ClientSettingsNavigation.XRAY,UsageFeaturesUi.selected(this))) {
            var restoringFallback=false
            val fallback=android.widget.Switch(this).apply {
                text="Использовать запасной при сбое";tag="primary-fallback-switch";tune(this)
                isChecked=prefs.getBoolean(PrimaryFallbackPolicy.KEY,false)
                setOnCheckedChangeListener {_,checked->
                    if(restoringFallback)return@setOnCheckedChangeListener
                    if(!prefs.edit().putBoolean(PrimaryFallbackPolicy.KEY,checked).commit()) {
                        restoringFallback=true
                        isChecked=!checked
                        restoringFallback=false
                        alert("Движки","Не удалось сохранить настройку.")
                    }
                }
            }
            body.addView(fallback,space(0,4))
            body.addView(text("Если основной движок не подключится, один раз попробуем установленный совместимый запасной. Основной выбор не изменится. Для WG/AWG переключения нет.",12,Loft.MUTED),space(0,12))
        }
        EngineUpdatePrimary.kinds.forEachIndexed{index,kind->
            val installed=EnginePackages.missing(this@with,setOf(kind)).isEmpty()
            val choiceId=EngineUpdatePrimary.choiceId(kind)
            val selected=host?.selected()==choiceId
            val label=if(kind=="sing-box")"sing-box"else"Steer"
            val card=LinearLayout(this@with).apply{
                orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16))
                background=Loft.glass(this@with,16)
                tag="primary-choice-card-$kind"
            }
            val heading=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
            heading.addView(text(label,16,Loft.INK).apply{tag="primary-choice-title-$kind"},LinearLayout.LayoutParams(0,-2,1f))
            if(installed&&!firstLaunch)heading.addView(iconButton("trash","Удалить $label").apply {setOnClickListener {EngineStorage.remove(this@with,kind)}},LinearLayout.LayoutParams(dp(48),dp(48)))
            card.addView(heading,space(0,8))
            val version=runCatching {packageManager.getPackageInfo(EngineModules.installation(this@with,kind).packageName,0).versionName}.getOrNull()
            card.addView(text(listOfNotNull(version,if(selected)"Выбран"else if(installed)"Установлен"else"Не установлен").joinToString(" · "),13,Loft.MUTED),space(0,8))
            val details=PrimaryCorePresentation.details(kind)
            if(firstLaunch)card.addView(text(details,12,Loft.MUTED).apply{tag="primary-choice-details-$kind"},space(0,8))
            else CompactUi.fold(this,card,"Возможности").addView(text(details,12,Loft.MUTED).apply {tag="primary-choice-details-$kind"})
            card.addView(text(EngineStorage.description(this@with,kind),12,Loft.MUTED).apply{tag="primary-choice-size-$kind"},space(0,8))
            val reconfirm=selected&&host?.confirmationRequired()==true
            card.addView(button(if(reconfirm)"Использовать для основного трафика"else if(selected)"Выбран"else if(installed)"Выбрать"else"Установить",!selected).apply{
                tag="primary-choice-action-$kind"
                gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                isEnabled=(!selected||reconfirm)&&(!installed||host!=null)
                setOnClickListener{
                    if(!installed){if(firstLaunch&&host!=null)installPrimaryEngine(kind)else installEngine(kind);return@setOnClickListener}
                    confirm(this@with,kind){installAddon->try{
                            val bridge=requireNotNull(host)
                            requireSwitch(kind,EnginePackages.missing(this@with,setOf(kind)).isEmpty(),active()||ProbeCoordinator.measurements!=null,bridge.validateFullSnapshot(choiceId))
                            bridge.selectValidated(choiceId) {if(installAddon)installEngine(WgAddonPolicy.KIND)}
                        }catch(e:Exception){alert("Основной движок",e.message?:"Не удалось подтвердить совместимость.")}}
                }
            },space(0,0))
            body.addView(card,space(if(index==0)0 else 12,0))
        }
        body
    }

    internal fun updatesView(activity:MainActivity,host:Host,firstLaunch:Boolean=false,onDraft:((()->Unit)->Unit)?=null,optionalOnboarding:Boolean=false):LinearLayout=with(activity) {
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="primary-core-chooser"}
        body.addView(text("Основной движок",18,Loft.INK),space(0,8))
        val chosen=host.selected()
        if(!optionalOnboarding)body.addView(text(if(chosen==null)"Выбери движок для основного трафика." else "Основной трафик обслуживает выбранный движок.",12,Loft.MUTED),space(0,8))
        if(optionalOnboarding)body.addView(text(
            "Если вы будете пользоваться только неклассическими подключениями (обход БС, OpenFlux и т. д.), основной движок не нужен.",
            13,Loft.INK).apply {tag="primary-onboarding-optional-hint"},space(0,12))
        lateinit var selector:ModeSwitch
        val addonInstalled=EnginePackages.missing(this,setOf(WgAddonPolicy.KIND)).isEmpty()
        val addon=android.widget.CheckBox(this).apply {
            text="Добавить WireGuard / AmneziaWG";textSize=13f;setTextColor(Loft.INK);minimumHeight=dp(48)
            tag="primary-install-wg-addon"
            isChecked=EnginePackages.missing(this@with,setOf("sing-box")).isNotEmpty()
            visibility=if(firstLaunch&&chosen=="steer"&&!addonInstalled)android.view.View.VISIBLE else android.view.View.GONE
        }
        fun choose(kind:String) {
            val id=EngineUpdatePrimary.choiceId(kind)
            if(EnginePackages.missing(this,setOf(kind)).isNotEmpty()){
                if(firstLaunch)installPrimaryEngine(kind)else installEngine(kind)
                return
            }
            if(!firstLaunch&&host.selected()==id&&!host.confirmationRequired())return
            val applyChoice:(Boolean)->Unit={installAddon->
                    try {
                        requireSwitch(kind,EnginePackages.missing(this,setOf(kind)).isEmpty(),active()||ProbeCoordinator.measurements!=null,host.validateFullSnapshot(id))
                        host.selectValidated(id) {
                            selector.setMode(host.selected()=="singbox",true)
                            if(installAddon)installEngine(WgAddonPolicy.KIND)
                        }
                    }catch(e:Exception){alert("Основной движок",e.message?:"Не удалось подтвердить совместимость.")}
                }
            if(firstLaunch&&onDraft!=null)applyChoice(PrimaryCorePresentation.offerWgAddon(kind,addonInstalled)&&addon.isChecked)
            else confirm(this,kind,applyChoice)
        }
        // First-launch selection is a draft until the explicit dialog action.
        // The settings screen retains its validated immediate-selection flow.
        var draft=if(chosen=="steer")"steer"else"sing-box"
        selector=ModeSwitch(this,chosen!="steer",java.util.function.Consumer {singBox->
            val kind=if(singBox)"sing-box"else"steer"
            if(firstLaunch&&onDraft!=null){
                draft=kind;selector.setMode(singBox,true)
                addon.visibility=if(PrimaryCorePresentation.offerWgAddon(kind,addonInstalled))android.view.View.VISIBLE else android.view.View.GONE
            }else choose(kind)
        },
            listOf("sing-box","Steer"),listOf("",""),true).apply {
            tag="primary-core-selector"
            if(chosen==null)contentDescription="Основной движок не выбран. Выбери sing-box или Steer."
        }
        onDraft?.invoke {choose(draft)}
        body.addView(selector,space(0,if(optionalOnboarding)8 else 12))
        if(firstLaunch&&onDraft!=null) {
            if(!optionalOnboarding)body.addView(text("Подтвердите выбор кнопкой «Выбрать». Проверим совместимость сохранённых подключений и настроек.",12,Loft.MUTED),space(0,8))
            body.addView(addon,space(0,8))
        }
        val stacked=!firstLaunch&&contentWidthDp()<370
        val cards=LinearLayout(this).apply {
            tag="primary-core-summary";orientation=if(stacked)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        EngineUpdatePrimary.kinds.forEachIndexed {index,kind->
            val installed=EnginePackages.missing(this,setOf(kind)).isEmpty()
            val info=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;tag="primary-choice-card-$kind"
                setPadding(dp(12),dp(12),dp(12),dp(12));background=Loft.glass(this@with,12)
            }
            val label=if(kind=="sing-box")"sing-box"else"Steer"
            info.addView(text(label,14,Loft.INK))
            if(!firstLaunch&&!installed){
                info.isFocusable=true
                info.setOnClickListener {choose(kind)}
                info.addView(text("Скачать и установить",12,Loft.ACCENT).apply {
                    minimumHeight=dp(48)
                    gravity=android.view.Gravity.CENTER_VERTICAL
                    contentDescription="Скачать и установить $label"
                    tag="primary-summary-install-$kind"
                    setOnClickListener {choose(kind)}
                })
            }
            info.addView(text(if(!installed)"Не установлен"else if(chosen==EngineUpdatePrimary.choiceId(kind))"Выбран"else"Установлен",11,Loft.MUTED),space(4,4))
            info.addView(text(EngineStorage.description(this,kind),11,Loft.MUTED).apply {tag="primary-choice-size-$kind"},space(0,6))
            info.addView(text(EngineUpdateScreenPolicy.primarySummary(kind),11,Loft.MUTED).apply {tag="primary-choice-details-$kind"},LinearLayout.LayoutParams(-1,-2,1f))
            info.addView(text("Все особенности",11,Loft.ACCENT).apply {
                minimumHeight=dp(48);gravity=android.view.Gravity.CENTER_VERTICAL
                contentDescription="Все особенности ${if(kind=="sing-box")"sing-box"else"Steer"}"
                setOnClickListener {alert("Возможности движка",PrimaryCorePresentation.details(kind))}
            },space(6,0))
            val params=if(stacked)space(if(index>0)8 else 0,0)
                else LinearLayout.LayoutParams(0,-1,1f).apply {if(index>0)marginStart=dp(8)}
            cards.addView(info,params)
        }
        body.addView(cards,space(0,8))
        if(!firstLaunch&&UsageFeatures.visible(ClientSettingsNavigation.XRAY,UsageFeaturesUi.selected(this))) {
            val fallback=android.widget.Switch(this).apply {
                text="Использовать запасной при сбое";tag="primary-fallback-switch";tune(this)
                isChecked=prefs.getBoolean(PrimaryFallbackPolicy.KEY,false)
            }
            var restoring=false
            fallback.setOnCheckedChangeListener {_,checked->
                if(!restoring&&!prefs.edit().putBoolean(PrimaryFallbackPolicy.KEY,checked).commit()) {
                    restoring=true;fallback.isChecked=!checked;restoring=false;alert("Движки","Не удалось сохранить настройку.")
                }
            }
            body.addView(fallback,space(0,4))
            body.addView(text("При сбое один раз попробуем совместимый установленный запасной. Выбор основного не изменится; WG/AWG не переключается.",12,Loft.MUTED))
        }
        body
    }
}
