package org.mcrf.client

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

class LogsPage(private val activity:MainActivity) {
    private var historyDialog:AlertDialog?=null
    fun build(page:LinearLayout)=with(activity) {
        val terminal=false
        val compact=contentWidthDp()<370
        var all=emptyList<LogEntries.Entry>();var shown=all;var reading=false;var lastRead=0L;var loaded=false
        var engineInfo="Движки: сведения ещё не прочитаны.";var inventoryReadAt:Long?=null
        val group=picker(LogEntries.Group.entries.map {it.label}.toTypedArray(),0).apply {tag="logs-filter";contentDescription="Фильтр логов"}
        fun systemInfo()="MCRF ${BuildConfig.VERSION_NAME} · versionCode=${BuildConfig.VERSION_CODE}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\n${EngineLogInventory.snapshotAge(SystemClock.elapsedRealtime(),inventoryReadAt)}\n$engineInfo"
        val count=text("Читаем логи…",11,Loft.MUTED)
        fun tone(dark:Int,light:Int)=if(Loft.dark)dark else light
        val amber=tone(0xffe8c470.toInt(),0xff805200.toInt())
        val violet=tone(0xffcfb8ff.toInt(),0xff6535a1.toInt())
        fun labelChip(value:String,size:Int,color:Int,filled:Boolean)=text(value,size,color).apply {
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
            if(filled){
                background=android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius=dp(5).toFloat()
                    setColor(SurfacePalette.withAlpha(color,if(Loft.dark)34 else 27))
                }
                setPadding(dp(5),dp(2),dp(5),dp(2))
            }
        }
        fun levelLabel(level:LogEntries.Level,size:Int):TextView {
            val color=when(level){
                LogEntries.Level.SUCCESS->tone(0xff86d9aa.toInt(),0xff176240.toInt())
                LogEntries.Level.ERROR->tone(0xffffa5a2.toInt(),0xff9e2f35.toInt())
                LogEntries.Level.WARNING,LogEntries.Level.INFO->amber
                LogEntries.Level.DEBUG->Loft.MUTED
            }
            val filled=level in setOf(LogEntries.Level.SUCCESS,LogEntries.Level.WARNING,LogEntries.Level.ERROR)
            return labelChip(level.label,size,color,filled).apply {tag="log-level-${level.name}"}
        }
        fun kindLabel(kind:LogEntries.Kind,size:Int):TextView {
            val color=when(kind){
                LogEntries.Kind.ANUBIS->violet
                LogEntries.Kind.DNS,LogEntries.Kind.ENGINE->tone(0xffadd0fa.toInt(),0xff31588d.toInt())
                LogEntries.Kind.ROUTING->tone(0xff84d6d0.toInt(),0xff2a6966.toInt())
                else->Loft.MUTED
            }
            return labelChip(kind.label,size,color,kind==LogEntries.Kind.ANUBIS).apply {tag="log-kind-${kind.name}"}
        }
        fun headingLabels(item:LogEntries.Entry,size:Int,time:String?=null)=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            if(time!=null)addView(text(time,size,Loft.MUTED),LinearLayout.LayoutParams(-2,-2).apply {marginEnd=dp(8)})
            addView(levelLabel(item.level,size),LinearLayout.LayoutParams(-2,-2).apply {marginEnd=dp(7)})
            addView(kindLabel(item.kind,size),LinearLayout.LayoutParams(-2,-2))
        }
        val rows=object:BaseAdapter(){
            override fun getCount()=shown.size
            override fun getItem(position:Int)=shown[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                val item=shown[position]
                val row=LinearLayout(this@with).apply {
                    gravity=Gravity.TOP;setPadding(dp(6),dp(10),dp(6),dp(10));minimumHeight=dp(64)
                    orientation=if(compact)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                }
                val whenText=if(item.timestamp.length>=19)item.timestamp.substring(11)+(if(compact)"  " else "\n")+item.timestamp.substring(5,10) else item.timestamp
                if(compact){
                    row.addView(headingLabels(item,11,whenText),space(0,5))
                }else{
                    row.addView(text(whenText,10,Loft.MUTED).apply {typeface=Typeface.MONOSPACE},LinearLayout.LayoutParams(dp(56),-2))
                    row.addView(LinearLayout(this@with).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(levelLabel(item.level,11),LinearLayout.LayoutParams(-2,-2))
                        addView(kindLabel(item.kind,9),LinearLayout.LayoutParams(-2,-2).apply {topMargin=dp(4)})
                    },LinearLayout.LayoutParams(dp(68),-2))
                }
                row.addView(text(item.message,12,Loft.INK).apply {setHorizontallyScrolling(false);breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE;maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END},
                    if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f))
                row.contentDescription="${item.timestamp}, ${item.level.label}, ${item.kind.label}, ${item.message}"
                return row
            }
        }
        val table=ListView(this).apply {tag="logs-table";contentDescription="Таблица логов";adapter=rows;divider=ColorDrawable(Loft.LINE);dividerHeight=dp(1);setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnItemClickListener {_,_,position,_->val item=shown[position]
                val header=LinearLayout(this@with).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(12))
                    addView(headingLabels(item,12))
                    addView(text(item.message,18,Loft.INK).apply {setTypeface(typeface,Typeface.BOLD)},space(5,0))
                }
                val details=LinearLayout(this@with).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),dp(12))
                    item.details.lineSequence().filter {it.isNotBlank()}.forEach {raw->
                        val (metadata,body)=LogEntries.detailParts(raw)
                        addView(LinearLayout(this@with).apply {
                            orientation=LinearLayout.VERTICAL;background=Loft.fieldBackground(this@with)
                            setPadding(dp(12),dp(9),dp(12),dp(9))
                            if(metadata!=null)addView(text(metadata,11,Loft.ACCENT),space(0,4))
                            addView(text(body,12,Loft.INK).apply {
                                typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setLineSpacing(dp(2).toFloat(),1f)
                            })
                        },space(0,6))
                    }
                }
                val scroll=ScrollView(this@with).apply {
                    isFillViewport=false;clipToPadding=false;addView(details)
                }
                ThemedDialogBuilder(this@with,maxHeightFraction=.78f,compactActions=true)
                    .setCustomTitle(header).setView(scroll)
                    .setNegativeButton("Закрыть",null).setPositiveButton("Копировать") {_,_->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MCRF · логи",LogEntries.export(listOf(item),systemInfo())))
                        toast("Подробности скопированы")
                    }.show()
            }
        }
        fun filter(){shown=LogEntries.filter(LogEntries.timeline(all),LogEntries.Group.entries.getOrElse(group.selectedItemPosition){LogEntries.Group.ALL});rows.notifyDataSetChanged();count.text=if(shown.isEmpty())"Событий пока нет" else "Записей: ${shown.size}"}
        fun reload(){if(reading)return;reading=true;lastRead=SystemClock.elapsedRealtime();val executor=Executors.newSingleThreadExecutor()
            val needsInventory=EngineLogInventory.refreshDue(SystemClock.elapsedRealtime(),inventoryReadAt)
            executor.execute {try{val inventory=if(needsInventory)EngineLogInventory.read(applicationContext)else null;val readAt=SystemClock.elapsedRealtime();val fresh=LogEntries.read(ClientEvents.read(this@with));runOnUiThread {reading=false;inventory?.let {engineInfo=it;inventoryReadAt=readAt};if(!isDestroyed&&page.isAttachedToWindow&&(!loaded||fresh!=all)){loaded=true;all=fresh;filter()}}}
                catch(_:Exception){runOnUiThread {reading=false;loaded=false;count.text="Не удалось прочитать логи"}}
                finally{executor.shutdown()}}
        }
        val actions=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;tag="logs-actions"}
        actions.addView(group,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(8)})
        actions.addView(iconButton("copy","Копировать логи").apply {tag="logs-copy";setOnClickListener {getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MCRF · логи",LogEntries.export(LogEntries.filter(all,LogEntries.Group.entries.getOrElse(group.selectedItemPosition){LogEntries.Group.ALL}),systemInfo())));toast("Логи и информация о системе скопированы")}},LinearLayout.LayoutParams(dp(48),dp(48)))
        page.addView(actions,space(0,16))
        page.addView(count,space(0,18))
        val header=LinearLayout(this).apply {setPadding(dp(6),dp(8),dp(6),dp(8));setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(text("Время",11,Loft.MUTED),LinearLayout.LayoutParams(dp(56),-2));addView(text("Тип",11,Loft.MUTED),LinearLayout.LayoutParams(dp(68),-2));addView(text("Событие",11,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f))}
        val frame=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="logs-frame";setPadding(dp(12),dp(8),dp(12),dp(8));background=Loft.glass(this@with);clipToOutline=true}
        if(!terminal&&!compact)frame.addView(header)
        frame.addView(table,LinearLayout.LayoutParams(-1,0,1f))
        table.setPadding(0,0,dp(3),dp(12));table.clipToPadding=true
        page.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        group.onItemSelectedListener=selectedListener {filter()}
        pageRefresh={if(SystemClock.elapsedRealtime()-lastRead>=3000)reload()}
        reload()
    }

    fun showLocalDiagnostics(section:String="history") = with(activity) {
        require(section in setOf("history","journal"))
        if(section=="journal") {
            startActivity(android.content.Intent(this,TrafficCaptureActivity::class.java))
            return@with
        }
        if(historyDialog?.isShowing==true)return@with
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        val scroll=object:ScrollView(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                val cap=(resources.displayMetrics.heightPixels*.58f).toInt()
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(
                    minOf(cap,View.MeasureSpec.getSize(heightMeasureSpec).takeIf {it>0}?:cap),View.MeasureSpec.AT_MOST))
            }
        }.apply {isFillViewport=false;addView(content)}
        val status=text("Загружаем историю…",13,Loft.MUTED)
        content.addView(status,space(0,12))
        val worker=Executors.newSingleThreadExecutor()
        val title=text("История изменений · Beta",20,Loft.INK).apply {
            tag="history-title";gravity=Gravity.START;setPadding(dp(20),dp(20),dp(20),dp(12));setAccessibilityHeading(true)
        }
        val dialog=ThemedDialogBuilder(this).setCustomTitle(title).setView(scroll)
            .setNeutralButton("Откатить восстановление",null).setNegativeButton("Закрыть",null).create()
        historyDialog=dialog
        val closed=java.util.concurrent.atomic.AtomicBoolean(false)
        fun background(action:()->Unit){if(!closed.get())worker.execute {if(!closed.get())action()}}
        content.addView(text("Изменения серверов, правил и настроек. Снимки зашифрованы и хранятся только на устройстве.",12,Loft.MUTED),space(0,12))
        val historyRows=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        content.addView(historyRows)
        content.addView(button("Удалить историю изменений",false).apply {setOnClickListener {
            ThemedDialogBuilder(this@with).setTitle("Удалить историю?").setMessage("Будут удалены только локальные снимки; текущие настройки останутся.")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->background {try{ChangeHistoryStore.local(applicationContext).delete();runOnUiThread {if(!closed.get()){historyRows.removeAllViews();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled=false;status.visibility=View.VISIBLE;status.text="История удалена. Новые изменения будут записываться."}}}catch(_:Exception){runOnUiThread {if(!closed.get())toast("Не удалось удалить историю.")}}}}.show()
        }},space(8,8))
        dialog.setOnDismissListener {closed.set(true);worker.shutdownNow();if(historyDialog===dialog)historyDialog=null}
        dialog.show()
        // Android's neutral action occupies the left side of the dialog footer.
        val undo=dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {tag="history-undo-restore";isEnabled=false}
        undo.setOnClickListener {
            if(active()||working()){toast("Сначала отключите соединение и дождитесь завершения операций.");return@setOnClickListener}
            undo.isEnabled=false
            background {try {
                val history=ChangeHistoryStore.local(applicationContext)
                val request=history.prepareUndo(ChangeHistoryStore.snapshotLocal(applicationContext))
                history.validateRestore(request,{ChangeHistoryStore.snapshotLocal(applicationContext)},{ChangeHistoryStore.validateLocal(applicationContext,it)})
                runOnUiThread {if(!closed.get()&&!isDestroyed){
                    undo.isEnabled=true
                    ChangePreviewUi.show(this@with,"Откатить восстановление?",request.preview) {
                        if(!closed.get()&&!active()&&!working()) {
                            var restored=false
                            run(MainActivity.Operation {
                                check(!active()) {"Сначала отключите соединение."}
                                restored=ChangeHistoryStore.restoreLocal(applicationContext,request){true}
                                "Восстановление отменено. Предыдущие подключения и маршруты возвращены."
                            },false,Runnable {if(restored){dialog.dismiss();recreate()}})
                        }
                    }
                }}
            }catch(_:Exception){runOnUiThread {if(!closed.get()){
                undo.isEnabled=true;status.visibility=View.VISIBLE
                status.text="Не удалось проверить обратную точку. Данные не изменены. Проверьте доступность движков и повторите."
            }}}}
        }
        dialog.window?.setLayout(minOf(dp(560),resources.displayMetrics.widthPixels-dp(32)),ViewGroup.LayoutParams.WRAP_CONTENT)
        background {
            try {
                val history=ChangeHistoryStore.local(applicationContext)
                val undoAvailable=history.hasUndo()
                val summaries=history.summaries(ChangeHistoryStore.snapshotLocal(applicationContext))
                runOnUiThread {if(!closed.get()&&!isDestroyed){
                    undo.isEnabled=undoAvailable
                    status.visibility=if(summaries.isEmpty())View.VISIBLE else View.GONE
                    status.text="Значимых изменений пока нет. Новые изменения появятся здесь."
                    summaries.forEachIndexed {index,summary->historyRows.addView(card().apply {
                        tag="history-restore-card";setPadding(dp(16),dp(16),dp(16),dp(16))
                        background=CompactUi.historyBackground(this@with,index,radiusDp=20)
                        addView(text(evidenceTime(summary.timestamp),16,Loft.INK),space(0,8))
                        val validation=text("Не проверено",12,Loft.MUTED).apply {tag="history-validation-status"}
                        addView(validation,space(0,8))
                        var checkedRequest:ChangeHistoryStore.RestoreRequest?=null
                        val restore=button("Восстановить",false).apply {minimumHeight=dp(48);isEnabled=false}
                        val actions=LinearLayout(this@with)
                        addView(actions)
                        actions.addView(button("Проверить",false).apply {
                            contentDescription="Проверить точку восстановления"
                            minimumHeight=dp(48);isEnabled=summary.changedStores.isNotEmpty()
                            setOnClickListener {
                                if(active()||working()){toast("Сначала отключите соединение и дождитесь завершения операций.");return@setOnClickListener}
                                isEnabled=false;restore.isEnabled=false;checkedRequest=null;validation.text="Проверяем…"
                                background {try {
                                    val request=history.prepareRestore(summary.id,ChangeHistoryStore.snapshotLocal(applicationContext))
                                    history.validateRestore(request,{ChangeHistoryStore.snapshotLocal(applicationContext)},{ChangeHistoryStore.validateLocal(applicationContext,it)})
                                    runOnUiThread {if(!closed.get()){checkedRequest=request;validation.text="Проверка пройдена";restore.isEnabled=true;isEnabled=true}}
                                }catch(error:Exception){runOnUiThread {if(!closed.get()){
                                    validation.text=if(error.message?.startsWith("Настройки изменились")==true)"Настройки изменились. Повторите проверку."
                                        else "Снимок или его движок недоступен для восстановления. Настройки не изменены."
                                    isEnabled=true
                                }}}}
                            }
                        },LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
                        restore.setOnClickListener {
                            if(active()||working()){toast("Сначала отключите соединение и дождитесь завершения операций.");return@setOnClickListener}
                            background {try{
                                val request=checkedRequest?:error("Сначала проверьте точку восстановления.")
                                runOnUiThread {if(!closed.get())ChangePreviewUi.show(this@with,"Восстановить снимок?",request.preview) {
                                        if(!closed.get()&&!active()&&!working()){
                                            var restored=false
                                            run(MainActivity.Operation {
                                                check(!active()) {"Сначала отключите соединение."}
                                                restored=ChangeHistoryStore.restoreLocal(applicationContext,request){true}
                                                "Снимок восстановлен."
                                            },false,Runnable {if(restored){dialog.dismiss();recreate()}})
                                        }
                                    }}
                            }catch(_:Exception){runOnUiThread {if(!closed.get())toast("Снимок недоступен или настройки изменились.")}}}
                        }
                        actions.addView(restore,LinearLayout.LayoutParams(0,-2,1f))
                        addView(text("Состав точки: ${summary.changedStores.joinToString {storeLabel(it)}.ifEmpty {"текущие настройки"}}",12,Loft.MUTED),space(8,8))
                        val groups=historyGroups(summary.changes)
                        if(groups.isEmpty())addView(text("Нет изменений конфигурации",13,Loft.MUTED),space(0,8))
                        else groups.forEach {(title,items)->addView(LinearLayout(this@with).apply {
                            orientation=LinearLayout.VERTICAL
                            background=Loft.fieldBackground(this@with)
                            setPadding(dp(12),dp(9),dp(12),dp(9))
                            addView(text(title,12,Loft.MUTED),space(0,4))
                            addView(text(items.joinToString("\n") {"• $it"},13,Loft.INK))
                        },space(0,6))}
                    },space(0,8))}
                }}
            }catch(_:Exception){runOnUiThread {if(!closed.get()){status.visibility=View.VISIBLE;status.text="Не удалось прочитать историю. Текущие настройки сохранены; историю можно удалить и начать заново."}}}
        }
    }
    private fun evidenceTime(at:Long)=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date(at))
    private fun historyGroups(changes:List<String>):List<Pair<String,List<String>>> {
        val groups=linkedMapOf<String,MutableList<String>>()
        changes.forEach {change->
            val title=when {
                change.startsWith("Маршруты ·")||change.startsWith("Маршрутизация")||change.startsWith("Правила ·")||change.startsWith("Группы маршрутов ·")->"Маршруты"
                change.startsWith("Сервер")||change.startsWith("Подключения")||change.startsWith("Подписки")||change.startsWith("Сохранённые серверы")||change.startsWith("Пул автовыбора")||change.startsWith("Параметры серверов")||change.startsWith("Профиль подключения")->"Подключения"
                change.startsWith("Оформление ·")->"Оформление"
                change.startsWith("DNS ·")||change.startsWith("VPN и прокси ·")->"Сеть и DNS"
                else->"Настройки"
            }
            groups.getOrPut(title){mutableListOf()}.add(change)
        }
        return groups.map {it.key to it.value.toList()}
    }
    private fun storeLabel(name:String)=when(name){"profile.enc"->"профиль";"connections.enc"->"подключения";"routing.enc"->"маршруты";"preferences.enc"->"настройки";else->"неизвестная область"}
}
