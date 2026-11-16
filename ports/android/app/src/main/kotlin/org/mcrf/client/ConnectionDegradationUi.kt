package org.mcrf.client

import android.os.Handler
import android.os.Looper
import android.widget.Switch

internal object ConnectionDegradationUi {
    fun chooseServer(activity:MainActivity)=with(activity) {
        val loading=ThemedDialogBuilder(this).setTitle("Выбрать сервер").setMessage("Загрузка подключений…").setNegativeButton("Позже",null).show()
        AndroidDeployIo.submit({Connections(applicationContext).apply {load()}.entries().toList()}) {result->
            if(!loading.isShowing||isFinishing||isDestroyed)return@submit
            loading.dismiss()
            result.onSuccess {servers->
                val body=EditorLayout.body(activity)
                body.addView(text("Смена произойдёт после нажатия на сервер.",12,Loft.MUTED),space(0,12))
                val chooser=ThemedDialogBuilder(activity).setTitle("Выбрать сервер").setView(CompactUi.scroll(activity,body)).setNegativeButton("Позже",null).create()
                if(servers.isEmpty())body.addView(text("Нет сохранённых серверов",13,Loft.MUTED))
                servers.forEach {server->body.addView(button(server.name,false).apply {
                    gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(48)
                    setOnClickListener {
                        when {
                            working()->toast("Дождитесь завершения подключения")
                            catalogue.entries().none {it.id==server.id}->toast("Подключения ещё загружаются. Повторите выбор.")
                            else->{select(server.id);chooser.dismiss()}
                        }
                    }
                },space(0,8))}
                chooser.show()
            }.onFailure {alert("Выбрать сервер","Не удалось прочитать подключения. Повторите позже.")}
        }
    }
    fun show(activity:MainActivity)=with(activity) {
        val settings=DiagnosticsDegradationMonitor.settings(this)
        val body=EditorLayout.body(this)
        body.addView(text("Предупредит о повторных отказах или росте задержки сервера.",12,Loft.MUTED),space(0,12))
        val controls=CompactUi.group(this,body,"Наблюдение")
        val enabled=Switch(this).apply {text="Следить за подключением";tune(this);isChecked=settings.enabled;minHeight=dp(48)}
        controls.addView(enabled)
        val times=listOf(60,180,300,900)
        val interval=AdaptivePolicyUi.alignedPicker(activity,arrayOf("1 мин","3 мин","5 мин","15 мин"),times.indexOf(settings.intervalSeconds).coerceAtLeast(0))
        EditorLayout.field(this,controls,"Интервал",interval)
        val result=CompactUi.group(this,body,"Текущее подключение")
        val status=text("",13,Loft.INK);result.addView(status)
        result.addView(button("Выбрать другой сервер",false).apply {setOnClickListener {chooseServer(activity)}},space(12,0))
        body.addView(SettingsHelp.view(this,"Когда предупредит","5 успешных проб задают обычную задержку. После 3 ухудшений подряд — уведомление с выбором сервера.\n\nСмена сети сбрасывает замеры. Наблюдение работает, пока жив процесс MCRF. Разрешите уведомления в Android."),space(0,8))
        val handler=Handler(Looper.getMainLooper())
        val dialog=ThemedDialogBuilder(this).setTitle("Ухудшение соединения · Beta").setView(CompactUi.scroll(this,body))
            .setNegativeButton("Закрыть",null).setPositiveButton("Сохранить",null).create()
        fun render() {
            val s=DiagnosticsDegradationMonitor.snapshot
            val summary=when {
                !s.enabled->"Наблюдение выключено"
                !active()->"Ожидаем подключение MCRF"
                s.issue!=DiagnosticIssue.NONE->when(s.issue) {
                    DiagnosticIssue.CONNECT_FAILED,DiagnosticIssue.TIMEOUT,DiagnosticIssue.TLS_FAILED,DiagnosticIssue.DNS_FAILED->"Большинство проверочных узлов не отвечает. Проба повторится автоматически."
                    DiagnosticIssue.PATH_CHANGED->"Подключение изменилось. Собираем новую базу."
                    else->"Нет пригодных измерений. Проба повторится автоматически."
                }
                s.baselineSamples<settings.minimumBaseline->"Собираем базу: ${s.baselineSamples}/${settings.minimumBaseline} успешных проб"
                else->"Проверок: ${s.completedRounds}\n"+(s.last?.let {"Было ${it.baselineMs} мс → ${it.observedMs?.let {v->"$v мс"}?:"нет ответа"}"}?:"Ухудшений не обнаружено")
            }
            status.text=s.server.ifBlank {"Нет активного подключения"}+"\n"+summary
        }
        val observation=DiagnosticsDegradationMonitor.observe {handler.post {if(dialog.isShowing)render()}}
        dialog.setOnDismissListener {observation.close();handler.removeCallbacksAndMessages(null)}
        dialog.show();render()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {button->
            button.isEnabled=false
            val next=settings.copy(enabled=enabled.isChecked,targetIds=setOf("connection"),intervalSeconds=times[interval.selectedItemPosition])
            AndroidDeployIo.submit({DiagnosticsDegradationMonitor.configure(applicationContext,next)}) {saved->
                if(dialog.isShowing&&!isDestroyed){button.isEnabled=true;if(saved.isSuccess)dialog.dismiss()else toast("Не удалось сохранить наблюдение")}
            }
        }
    }
}
