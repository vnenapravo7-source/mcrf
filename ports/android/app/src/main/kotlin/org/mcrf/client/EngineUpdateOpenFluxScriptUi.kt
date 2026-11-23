package org.mcrf.client

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout

/** One explicit flow, owned by this attached view; detached UI cannot cancel another attempt. */
internal object EngineUpdateOpenFluxScriptUi {
    fun view(activity:MainActivity):LinearLayout=with(activity) {
        val body=EngineTableUi.create(this).apply {tag="openflux-official-script-updates"}
        appendRows(activity,body)
        body
    }
    fun appendRows(activity:MainActivity,table:LinearLayout)=with(activity) {
        EngineTableUi.heading(this,table,"JS-транспорты OpenFlux")
        EngineUpdateOpenFluxScriptPolicy.ids.forEachIndexed {index,id->
            transportView(activity,id,index==0,table)
        }
    }
    private fun transportView(activity:MainActivity,id:String,inspectOnAttach:Boolean,table:LinearLayout):LinearLayout=with(activity) {
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="openflux-script-$id"}
        body.addView(text(id,13,Loft.INK),space(0,4))
        val status=text("",11,Loft.MUTED);body.addView(status)
        val inspect=iconButton("refresh","Восстановить $id после сбоя").apply {visibility=View.GONE}
        val check=iconButton("refresh","Проверить обновление $id")
        val apply=iconButton("download","Установить обновление $id").apply {visibility=View.GONE}
        val rollback=iconButton("history","Вернуть предыдущую версию $id").apply {visibility=View.GONE}
        val cancel=iconButton("close","Отменить обновление $id").apply {visibility=View.GONE}
        val actions=LinearLayout(this).apply {gravity=android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL}
        listOf(inspect,check,apply,rollback,cancel).forEach {button->
            actions.addView(button,LinearLayout.LayoutParams(dp(44),dp(48)))
        }
        EngineTableUi.row(this,table,body,actions)
        val handler=Handler(Looper.getMainLooper());var owned:EngineUpdateRun?=null
        fun available()=body.isAttachedToWindow&&!isFinishing&&!isDestroyed&&hasWindowFocus()
        fun idle()=!active()&&ProbeCoordinator.measurements==null
        inspect.setOnClickListener {if(available())owned=EngineUpdates.inspectScripts(applicationContext)?:owned}
        check.setOnClickListener {if(available()&&idle())owned=EngineUpdates.checkScript(applicationContext,id)?:owned}
        apply.setOnClickListener {
            val candidate=EngineUpdates.scriptSnapshot().candidates[id]
            if(available()&&idle()&&candidate!=null)owned=EngineUpdates.applyScript(applicationContext,id,candidate.sha)?:owned
        }
        rollback.setOnClickListener {
            val current=EngineUpdates.scriptSnapshot().entries.singleOrNull {it.id==id}
            if(available()&&idle()&&!current?.previousSha.isNullOrEmpty())ThemedDialogBuilder(activity)
                .setTitle("Вернуть предыдущую версию?").setMessage("Транспорт $id вернётся с ${current?.version} на ${current?.previousVersion}. Применится при следующем подключении.")
                .setNegativeButton("Отмена",null).setPositiveButton("Вернуть"){_,_->
                    if(available()&&idle())owned=EngineUpdates.rollbackScript(applicationContext,id,requireNotNull(current).previousSha)?:owned
                }.show()
        }
        cancel.setOnClickListener {owned?.cancel()}
        val refresh=object:Runnable {override fun run() {
            if(!body.isAttachedToWindow||isFinishing||isDestroyed)return
            val snapshot=EngineUpdates.scriptSnapshot()
            val current=snapshot.entries.singleOrNull {it.id==id};val candidate=snapshot.candidates[id]
            status.text=listOfNotNull(current?.version?:"Не установлен",
                candidate?.takeIf {it.sha!=current?.sha}?.let {"Обновление: ${it.version}"},
                snapshot.message.takeIf {owned!=null||snapshot.recoveryRequired}).joinToString("\n")
            val busy=EngineUpdates.isBusy();val free=idle()&&!busy&&!snapshot.recoveryRequired
            inspect.isEnabled=!busy&&idle();check.isEnabled=free
            apply.isEnabled=free&&snapshot.verified&&candidate!=null&&current!=null&&candidate.currentSha==current.sha&&candidate.sha!=current.sha
            rollback.isEnabled=free&&snapshot.verified&&current!=null&&current.previousSha.isNotEmpty()&&current.previousSha!=current.sha
            inspect.visibility=if(snapshot.recoveryRequired)View.VISIBLE else View.GONE
            apply.visibility=if(apply.isEnabled)View.VISIBLE else View.GONE
            check.visibility=if(apply.isEnabled||snapshot.recoveryRequired)View.GONE else View.VISIBLE
            rollback.visibility=if(rollback.isEnabled)View.VISIBLE else View.GONE
            cancel.visibility=if(owned?.active==true)View.VISIBLE else View.GONE
            handler.postDelayed(this,1000)
        }}
        body.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {
                owned=if(inspectOnAttach&&idle())EngineUpdates.inspectScripts(applicationContext)else null
                handler.post(refresh)
            }
            override fun onViewDetachedFromWindow(v:View) {handler.removeCallbacks(refresh);owned?.cancel();owned=null}
        })
        body
    }
}
