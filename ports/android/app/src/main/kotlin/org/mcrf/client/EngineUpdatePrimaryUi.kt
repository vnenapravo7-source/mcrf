package org.mcrf.client

import android.app.AlertDialog
import android.widget.LinearLayout

/** Main supplies the strict ClientPrimaryCore snapshot validator and selection store. */
object EngineUpdatePrimaryUi {
    interface Host {
        fun selected():String?
        fun validateFullSnapshot(choiceId:String):List<String>
        fun selectValidated(choiceId:String)
        fun confirmationRequired():Boolean=false
    }
    fun requireSwitch(kind:String,installed:Boolean,active:Boolean,failures:List<String>) {
        require(kind in EngineUpdatePrimary.kinds&&installed){"Сначала установи выбранный основной движок."}
        require(!active){"Останови соединение и замер перед сменой движка."}
        require(failures.isEmpty()){failures.joinToString("\n").take(2000)}
    }
    /** Also used for first-launch chooser: null selection stays null until explicit choice succeeds. */
    fun view(activity:MainActivity,host:Host?=null,firstLaunch:Boolean=false):LinearLayout=with(activity){
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="primary-core-chooser"}
        if(!firstLaunch)body.addView(text("Движок основного трафика",16,Loft.INK),space(0,12))
        body.addView(text("Выбери, через что отправлять основной трафик. Выбор можно изменить здесь. Для WG/AWG приоритет у установленного sing-box.",12,Loft.MUTED),space(0,12))
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
            card.addView(text("$label · ${if(selected)"Выбран"else if(installed)"Установлен"else"Не установлен"}",16,Loft.INK).apply{tag="primary-choice-title-$kind"},space(0,8))
            val details=PrimaryCorePresentation.details(kind)
            card.addView(text(details,12,Loft.MUTED).apply{tag="primary-choice-details-$kind"},space(0,8))
            card.addView(text(EngineStorage.description(this@with,kind),12,Loft.MUTED).apply{tag="primary-choice-size-$kind"},space(0,8))
            val reconfirm=selected&&host?.confirmationRequired()==true
            card.addView(button(if(reconfirm)"Использовать для основного трафика"else if(selected)"Выбран"else if(installed)"Выбрать"else"Установить",false).apply{
                tag="primary-choice-action-$kind"
                isEnabled=(!selected||reconfirm)&&(!installed||host!=null)
                setOnClickListener{
                    if(!installed){if(firstLaunch&&host!=null)installPrimaryEngine(kind)else installEngine(kind);return@setOnClickListener}
                    ThemedDialogBuilder(this@with).setTitle("Выбрать $label?")
                        .setMessage(PrimaryCorePresentation.confirmation(kind))
                        .setNegativeButton("Отмена",null).setPositiveButton("Выбрать"){_,_->try{
                            val bridge=requireNotNull(host)
                            requireSwitch(kind,EnginePackages.missing(this@with,setOf(kind)).isEmpty(),active()||ProbeCoordinator.measurements!=null,bridge.validateFullSnapshot(choiceId))
                            bridge.selectValidated(choiceId)
                        }catch(e:Exception){alert("Основной движок",e.message?:"Не удалось подтвердить совместимость.")}}.show()
                }
            },space(0,0))
            if(installed&&!firstLaunch)card.addView(button("Удалить",false).apply{setOnClickListener{EngineStorage.remove(this@with,kind)}},space(8,0))
            body.addView(card,space(if(index==0)0 else 12,0))
        }
        body
    }
}
