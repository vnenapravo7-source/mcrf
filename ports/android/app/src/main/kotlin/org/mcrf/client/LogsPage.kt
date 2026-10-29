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
    fun build(page:LinearLayout)=with(activity) {
        val terminal=prefs.getBoolean("logsTerminal",false)
        var all=emptyList<LogEntries.Entry>();var shown=all;var reading=false;var lastRead=0L;var loaded=false
        var engineInfo="Движки: сведения ещё не прочитаны.";var inventoryReadAt:Long?=null
        val group=picker(LogEntries.Group.entries.map {it.label}.toTypedArray(),0).apply {tag="logs-filter";contentDescription="Фильтр логов"}
        fun systemInfo()="MCRF ${BuildConfig.VERSION_NAME} · versionCode=${BuildConfig.VERSION_CODE}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\n${EngineLogInventory.snapshotAge(SystemClock.elapsedRealtime(),inventoryReadAt)}\n$engineInfo"
        val count=text("Читаем логи…",11,Loft.MUTED)
        val rows=object:BaseAdapter(){
            override fun getCount()=shown.size
            override fun getItem(position:Int)=shown[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                val item=shown[position]
                val row=LinearLayout(this@with).apply {gravity=Gravity.TOP;setPadding(dp(6),dp(10),dp(6),dp(10));minimumHeight=dp(64)}
                if(terminal){
                    row.orientation=LinearLayout.VERTICAL
                    val tone=when(item.level){LogEntries.Level.ERROR->Loft.ERROR;LogEntries.Level.WARNING->if(Loft.dark)0xffe5be6c.toInt()else 0xff84580a.toInt();LogEntries.Level.SUCCESS->if(Loft.dark)0xff55d38a.toInt()else 0xff16763c.toInt();else->Loft.ACCENT}
                    val time=if(item.timestamp.length>=19)item.timestamp.substring(11)else item.timestamp
                    val label=when(item.level){LogEntries.Level.SUCCESS->"SUCCESS";LogEntries.Level.ERROR->"ERROR";LogEntries.Level.WARNING->"WARN";else->item.source.uppercase(java.util.Locale.ROOT)}
                    val line=android.text.SpannableString("$time [$label]").apply {setSpan(android.text.style.ForegroundColorSpan(tone),time.length+1,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                    row.addView(text("",11,Loft.MUTED).apply {text=line;typeface=Typeface.MONOSPACE})
                    row.addView(text(item.message,13,Loft.INK).apply {typeface=Typeface.MONOSPACE;setPadding(0,dp(4),0,0);setHorizontallyScrolling(false)})
                    row.contentDescription="${item.timestamp}, ${item.level.label}, ${item.kind.label}, ${item.message}"
                    return row
                }
                val whenText=if(item.timestamp.length>=19)item.timestamp.substring(11)+"\n"+item.timestamp.substring(5,10) else item.timestamp
                row.addView(text(whenText,10,Loft.MUTED).apply {typeface=Typeface.MONOSPACE},LinearLayout.LayoutParams(dp(56),-2))
                val tone=when(item.level){LogEntries.Level.ERROR->Loft.ERROR;LogEntries.Level.WARNING->if(Loft.dark)0xffe5be6c.toInt()else 0xff84580a.toInt();LogEntries.Level.SUCCESS->Loft.ACCENT;else->Loft.MUTED}
                row.addView(LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL;addView(text(item.level.label,11,tone));addView(text(item.kind.label,9,Loft.MUTED),space(4,0))},LinearLayout.LayoutParams(dp(68),-2))
                row.addView(text(item.message,12,Loft.INK).apply {setHorizontallyScrolling(false);breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE},LinearLayout.LayoutParams(0,-2,1f))
                row.contentDescription="${item.timestamp}, ${item.level.label}, ${item.kind.label}, ${item.message}"
                return row
            }
        }
        val table=ListView(this).apply {tag="logs-table";contentDescription="Таблица логов";adapter=rows;divider=ColorDrawable(Loft.LINE);dividerHeight=dp(1);setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnItemClickListener {_,_,position,_->val item=shown[position]
                ThemedDialogBuilder(this@with).setTitle(item.kind.label+" · "+item.level.label)
                    .setMessage("${item.message}\n\n${item.timestamp}\nИсточник: ${item.source}\n\nТехнические подробности:\n${item.details}")
                    .setNegativeButton("Закрыть",null).setPositiveButton("Копировать подробности") {_,_->getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MCRF · логи",LogEntries.export(listOf(item),systemInfo())));toast("Подробности скопированы")}.show()
            }
        }
        fun filter(){shown=LogEntries.filter(all,LogEntries.Group.entries.getOrElse(group.selectedItemPosition){LogEntries.Group.ALL});rows.notifyDataSetChanged();count.text=if(all.isEmpty())"Событий пока нет" else "${shown.size} из ${all.size} · новые сверху"}
        fun reload(){if(reading)return;reading=true;lastRead=SystemClock.elapsedRealtime();val executor=Executors.newSingleThreadExecutor()
            val needsInventory=EngineLogInventory.refreshDue(SystemClock.elapsedRealtime(),inventoryReadAt)
            executor.execute {try{val inventory=if(needsInventory)EngineLogInventory.read(applicationContext)else null;val readAt=SystemClock.elapsedRealtime();val fresh=LogEntries.read(ClientEvents.read(this@with));runOnUiThread {reading=false;inventory?.let {engineInfo=it;inventoryReadAt=readAt};if(!isDestroyed&&page.isAttachedToWindow&&(!loaded||fresh!=all)){loaded=true;all=fresh;filter()}}}
                catch(_:Exception){runOnUiThread {reading=false;loaded=false;count.text="Не удалось прочитать логи"}}
                finally{executor.shutdown()}}
        }
        val actions=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;tag="logs-actions"}
        actions.addView(group,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(8)})
        actions.addView(iconButton("copy","Копировать логи").apply {tag="logs-copy";setOnClickListener {getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MCRF · логи",LogEntries.export(shown,systemInfo())));toast("Логи и информация о системе скопированы")}},LinearLayout.LayoutParams(dp(48),dp(48)))
        page.addView(actions,space(0,16))
        page.addView(button("Отправить логи разработчику",false).apply {
            tag="logs-send-developer"
            setOnClickListener {
                isEnabled=false;text="Отправляем…"
                val export=LogEntries.export(all,systemInfo())
                val executor=Executors.newSingleThreadExecutor()
                executor.execute {try {DeveloperLogDelivery.send(export);runOnUiThread {if(!isDestroyed){toast("Логи отправлены разработчику");isEnabled=true;text="Отправить логи разработчику"}}}
                    catch(_:Exception){runOnUiThread {if(!isDestroyed){toast("Не удалось отправить логи. Повторите позже.");isEnabled=true;text="Отправить логи разработчику"}}}
                    finally {executor.shutdown()}}
            }
        },LinearLayout.LayoutParams(-1,dp(48)).apply {bottomMargin=dp(14)})
        page.addView(count,space(0,18))
        val header=LinearLayout(this).apply {setPadding(dp(6),dp(8),dp(6),dp(8));setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(text("Время",11,Loft.MUTED),LinearLayout.LayoutParams(dp(56),-2));addView(text("Тип",11,Loft.MUTED),LinearLayout.LayoutParams(dp(68),-2));addView(text("Событие",11,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f))}
        val frame=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="logs-frame";setPadding(dp(12),dp(8),dp(12),dp(8));background=Loft.glass(this@with);clipToOutline=true}
        if(!terminal)frame.addView(header)
        frame.addView(table,LinearLayout.LayoutParams(-1,0,1f))
        table.setPadding(0,0,dp(3),dp(12));table.clipToPadding=true
        page.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        group.onItemSelectedListener=selectedListener {filter()}
        pageRefresh={if(SystemClock.elapsedRealtime()-lastRead>=3000)reload()}
        reload()
    }
}
