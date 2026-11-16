package org.mcrf.client

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

internal object RuleSuggestionUi {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"mcrf-rule-suggestions").apply{isDaemon=true}}
    fun button(activity:MainActivity,store:RoutingStore)=activity.button("Локальные предложения правил · Beta",false).apply {
        tag="routing-suggestions-open";minHeight=activity.dp(48);setOnClickListener{show(activity,store)}
    }
    fun show(activity:MainActivity,store:RoutingStore):Unit=with(activity) {
        val body=EditorLayout.body(this)
        val status=text("Читаем локальные метаданные…",13,Loft.MUTED);body.addView(status)
        val dialog=ThemedDialogBuilder(this).setTitle("Локальные предложения · Beta").setView(body).setNegativeButton("Закрыть",null).show()
        worker.execute {
            try {
                val journal=TrafficJournalStore.local(activity);val enabled=journal.enabled()
                val suggestions=if(enabled)RuleSuggestions.collect(journal.metadataSnapshot(),System.currentTimeMillis())else emptyList()
                val ignored=getSharedPreferences("rule-suggestions",0).getStringSet("ignored",emptySet()).orEmpty().toSet()
                activity.runOnUiThread {if(dialog.isShowing&&!isDestroyed){
                    status.text=if(!enabled)"Журнал выключен. Предложения не читают сохранённые события и не включают запись. Включите её отдельно, если хотите собрать новые метаданные."
                        else "Только уже записанные метаданные TrafficJournal, локально. Никаких сетевых запросов, содержимого трафика или автоматического применения. Уверенность описывает атрибуцию, не безопасность выхода. Локальные/приватные/замаскированные адреса исключены."
                    val visible=suggestions.filter{it.key !in ignored}
                    if(enabled&&visible.isEmpty())body.addView(text("Подходящих наблюдений нет либо предложения проигнорированы.",13,Loft.MUTED),space(12,12))
                    for(item in visible) {
                        val itemCard=card()
                        itemCard.addView(text("${item.kind.label} · ${item.value}",14,Loft.INK))
                        itemCard.addView(text("${item.confidence}\n${item.evidence}",12,Loft.MUTED),space(8,8))
                        itemCard.addView(button("Предпросмотр и действие",false).apply {minHeight=dp(48);setOnClickListener{preview(activity,store,item,itemCard)}})
                        body.addView(itemCard,space(0,12))
                    }
                    body.addView(button("Сбросить список игнорирования",false).apply {minHeight=dp(48);setOnClickListener {
                        getSharedPreferences("rule-suggestions",0).edit().remove("ignored").apply();dialog.dismiss();show(activity,store)
                    }},space(8,0))
                }}
            }catch(_:Exception){activity.runOnUiThread{if(dialog.isShowing&&!isDestroyed)status.text="Не удалось прочитать метаданные. Журнал и маршруты не изменены."}}
        }
    }
    private fun preview(activity:MainActivity,store:RoutingStore,item:RuleSuggestions.Suggestion,card:View)=with(activity) {
        val time=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
        val body=EditorLayout.body(this)
        body.addView(text("${item.kind.label}: ${item.value}\n${item.confidence}\n${item.evidence}\n${time.format(Date(item.first))} — ${time.format(Date(item.last))}\n\nУсловие: ${item.condition}\n${if(item.kind==RuleSuggestions.Kind.DOMAIN)"Точный домен, без автоматического захвата поддоменов."else if(item.kind==RuleSuggestions.Kind.IP)"Один IP, не подсеть. Его назначение может измениться."else"Одно атрибутированное приложение. Требуется системный VPN."}\n\nВыход выбирается в редакторе. Изменение произойдёт только после отдельного «Сохранить».",13,Loft.INK),space(0,12))
        val dialog=ThemedDialogBuilder(this).setTitle("Предпросмотр предложения").setView(body).setNegativeButton("Ничего не менять",null).create()
        body.addView(button("Создать правило…",false).apply {minHeight=dp(48);setOnClickListener {dialog.dismiss();openDraft(activity,store,item.draft(),null)}},space(0,8))
        body.addView(button("Изменить существующее…",false).apply {minHeight=dp(48);setOnClickListener {chooseExisting(activity,store,item);dialog.dismiss()}},space(0,8))
        body.addView(button("Игнорировать это предложение",false).apply {minHeight=dp(48);setOnClickListener {
            val prefs=getSharedPreferences("rule-suggestions",0)
            val ignored=(prefs.getStringSet("ignored",emptySet()).orEmpty().toList()+item.key).takeLast(1000).toSet()
            prefs.edit().putStringSet("ignored",ignored).apply();card.visibility=View.GONE;dialog.dismiss()
        }})
        dialog.show()
    }
    private fun chooseExisting(activity:MainActivity,store:RoutingStore,item:RuleSuggestions.Suggestion) {
        val state=store.snapshot();val a=state.getJSONArray("rules")
        val candidates=(0 until a.length()).map{store.editable(a.getJSONObject(it))}.filter {
            !it.has("nativeMatch")&&!it.optBoolean("invert")&&!it.has("builtinPreset")&&
                it.optString("mode","any") in if(item.kind==RuleSuggestions.Kind.APP)setOf("apps","any")else setOf("addresses","any")
        }
        if(candidates.isEmpty()){activity.alert("Предложения","Нет совместимых простых правил. Создайте отдельное правило; точные/инвертированные условия автоматически не переписываются.");return}
        val revision=store.presentationRevision();val generation=store.generation()
        ThemedDialogBuilder(activity).setTitle("Дополнить условие · будет шире").setItems(candidates.map{it.optString("name")}.toTypedArray()){_,i->
            if(store.presentationRevision()!=revision||store.generation()!=generation){activity.alert("Предложения","Маршруты изменились. Выберите правило заново.");return@setItems}
            val original=candidates[i];val proposal=JSONObject(original.toString())
            val lines=proposal.optString("text").lineSequence().filter{it.isNotBlank()}.toList()
            if(item.condition !in lines)proposal.put("text",(lines+item.condition).joinToString("\n"))
            ThemedDialogBuilder(activity).setTitle("Предпросмотр изменения").setMessage("Добавится альтернатива OR:\n${item.condition}\n\nВыход и остальные условия сохраняются. Правило сможет совпадать с большим числом соединений.\n\n${proposal.optString("name")}\n${TextGeoRules.editable(proposal).optString("text").take(240)}")
                .setNegativeButton("Ничего не менять",null).setPositiveButton("Открыть редактор"){_,_->
                    if(store.presentationRevision()==revision&&store.generation()==generation)openDraft(activity,store,proposal,original.getString("id"))
                    else activity.alert("Предложения","Маршруты изменились. Повторите выбор.")
                }.show()
        }.setNegativeButton("Ничего не менять",null).show()
    }
    private fun openDraft(activity:MainActivity,store:RoutingStore,draft:JSONObject,existing:String?) {
        val snapshot=store.snapshot();val revision=store.presentationRevision();val generation=store.generation()
        val mode=ExperienceModeStore(activity).load();val group=RoutingGroups.active(snapshot)
        RoutingPage(activity,store,draftSaved={edited->
            require(ExperienceModeStore(activity).load()==mode&&RoutingGroups.active(edited)==group){"Режим или группа изменились. Повторите выбор."}
            require(edited.optString("defaultTarget")==snapshot.optString("defaultTarget")){"Предложение не меняет выход остального трафика."}
            val chosen=TemporaryRules.rule(edited,draft.getString("id"))?:error("Правило отсутствует")
            store.editAtRevision(revision,generation){actual->
                if(existing==null){require(TemporaryRules.rule(actual,chosen.getString("id"))==null);RouteRules.insert(actual,chosen)}
                else {
                    val rules=actual.getJSONArray("rules");val index=(0 until rules.length()).firstOrNull{rules.getJSONObject(it).getString("id")==existing}
                    require(index!=null){"Правило удалено; повторите выбор."};rules.put(index,chosen)
                }
            }
        }).edit(draft)
    }
}
