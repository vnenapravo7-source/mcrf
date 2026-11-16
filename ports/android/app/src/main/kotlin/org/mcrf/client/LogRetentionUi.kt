package org.mcrf.client

internal object LogRetentionUi {
    fun button(activity:MainActivity)=with(activity) {
        button(LogRetention.label(prefs.getInt(LogRetention.KEY,LogRetention.DEFAULT))+" ▾",false).apply {
            DropdownStyle.apply(activity,this)
            tag="logs-retention";contentDescription="Срок хранения логов"
            setOnClickListener {anchor->
                val current=LogRetention.normalize(prefs.getInt(LogRetention.KEY,LogRetention.DEFAULT))
                DropdownPopup.show(activity,anchor,"Хранить логи",LogRetention.minutes.map {DropdownPopup.Item(LogRetention.label(it))},LogRetention.minutes.indexOf(current)) {index->
                        val value=LogRetention.minutes[index]
                        if(value!=current)run(MainActivity.Operation {
                            check(prefs.edit().putInt(LogRetention.KEY,value).commit())
                            ClientEvents.prune(activity);"Срок хранения логов сохранён"
                        },true)
                }
            }
        }
    }
}
