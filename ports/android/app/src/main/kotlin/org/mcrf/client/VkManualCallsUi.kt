package org.mcrf.client

import android.view.Gravity
import android.widget.LinearLayout

internal object VkManualCallsUi {
    fun generator(activity:MainActivity,account:VkRelayAccount,alive:()->Boolean={true},append:(List<String>)->Unit):LinearLayout {
        val counts=listOf(1,2,3,5,6,10,15,20,30,50)
        var count=6
        return LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            val container=this
            val pending=account.pendingManual()
            if(pending.isNotEmpty())addView(activity.button("Добавить ранее созданные звонки (${VkHashPool.unique(pending.flatMap {it.calls}).size})",false).apply {
                tag="vk-recover-manual"
                setOnClickListener {
                    if(activity.working()||!alive())return@setOnClickListener
                    runCatching {
                        account.preserveGenerated(pending.flatMap {it.calls})
                        append(pending.flatMap {it.calls})
                        account.acknowledgeManual(pending.map {it.group})
                        container.removeView(this)
                    }.onFailure {activity.alert("VK-звонки","Не удалось добавить звонки. Они сохранены для повторного добавления.")}
                }
            })
            val row=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
            addView(row)
            gravity=Gravity.CENTER_VERTICAL
            row.addView(activity.button("Создать звонки VK",false).apply {
                tag="vk-generate-manual"
                setOnClickListener {
                    if(activity.working()){activity.toast("Дождитесь завершения текущей операции");return@setOnClickListener}
                    var created:VkRelayAccount.ManualGeneration?=null
                    val requested=count
                    activity.run(MainActivity.Operation {
                        val result=account.generatedManual(requested){!alive()||activity.isDestroyed||Thread.currentThread().isInterrupted}
                        account.preserveGenerated(result.calls)
                        created=result
                        if(result.problem==null)"Создано звонков: ${result.calls.size}" else "Создано ${result.calls.size} из $requested. ${result.problem}"
                    },false,Runnable {created?.takeIf {alive()&&!activity.isDestroyed}?.let {
                        runCatching {append(it.calls);account.acknowledgeManual(listOf(it.group))}
                            .onFailure {activity.alert("VK-звонки","Звонки сохранены в ручном списке; редактор не обновлён.")}
                    }})
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(activity.button("$count ▾",false).apply {
                tag="vk-generate-count";DropdownStyle.value(activity,this);contentDescription="Количество новых звонков VK"
                setOnClickListener {anchor->DropdownPopup.show(activity,anchor,"Создать звонков",counts.map {DropdownPopup.Item(it.toString())},counts.indexOf(count)){index->count=counts[index];text="$count ▾"}}
            },LinearLayout.LayoutParams(activity.dp(72),activity.dp(48)).apply {marginStart=activity.dp(8)})
        }
    }
}
