package org.mcrf.client

import android.app.StatusBarManager
import android.content.ComponentName
import android.os.Build
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout

/** Appearance-only controls. MainActivity owns navigation and screen reconstruction. */
internal object AppearanceSettingsUi {
    fun interfaceMode(activity:MainActivity,onChanged:()->Unit):LinearLayout=with(activity) {
        val store=ExperienceModeStore(this)
        val initial=store.load()
        val choice=picker(ExperienceMode.entries.map {it.title}.toTypedArray(),initial.ordinal).apply {tag="experience-mode"}
        var restoring=false
        choice.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                if(restoring)return
                val previous=store.load()
                val next=ExperienceMode.entries[position]
                if(next==previous)return
                if(working()) {
                    restoring=true;choice.setSelection(previous.ordinal);restoring=false
                    return
                }
                runAfterSuccess({store.save(next);""},false,Runnable(onChanged))
            }
        }
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="appearance-interface-mode"
            addView(text("Режим интерфейса",14,Loft.INK),space(0,6))
            addView(choice,space(0,0))
        }
    }

    fun block(activity:MainActivity,title:String,key:String,content:()->View):LinearLayout=with(activity) {
        card().apply {
            tag=key
            addView(text(title,17,Loft.INK).apply {
                tag="$key-toggle";isAccessibilityHeading=true
            },space(0,10))
            addView(content())
        }
    }

    fun navigation(activity:MainActivity):LinearLayout=with(activity) {
        card().apply {
            fun choice(label:String,key:String,values:Map<String,String>,selected:String,tagName:String){
                val entries=values.entries.toList()
                val row=LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL;tag="$tagName-row"}
                row.addView(text(label,14,Loft.INK),space(0,4))
                row.addView(button(values.getValue(selected),false).apply {
                    tag=tagName
                    setOnClickListener {
                        DropdownPopup.show(this@with,this,label,entries.map {DropdownPopup.Item(it.value)},entries.indexOfFirst {it.key==selected}){index->
                            prefs.edit().putString(key,entries[index].key).apply()
                            render()
                        }
                    }
                },space(0,10))
                addView(row)
            }
            val position=BottomNavigationStyle.position(prefs.getString(BottomNavigationStyle.POSITION,null))
            choice("Расположение разделов",BottomNavigationStyle.POSITION,BottomNavigationStyle.positions,position,"section-navigation-position")
            choice("Оформление навигации",BottomNavigationStyle.KEY,BottomNavigationStyle.choicesFor(position),
                BottomNavigationStyle.resolve(prefs.getString(BottomNavigationStyle.KEY,null),position),"bottom-navigation-style")
        }
    }
    fun tiles(activity:MainActivity):LinearLayout=with(activity) {
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="appearance-quick-tiles"
            addView(text("Выключение отключает плитку в шторке. Включение делает её доступной для добавления; порядок задаёт Android.",12,Loft.MUTED),space(0,8))
            addView(text(if(Build.VERSION.SDK_INT>=33)"При включении подтвердите добавление в Android. Кнопка + повторяет запрос." else "Добавление: шторка → редактор плиток → MCRF.",12,Loft.MUTED),space(0,12))
            QuickTileAvailability.tiles.filter {canEditInterface()||it.service!=AnubisQuickTile::class.java}.forEach {spec->
                val row=LinearLayout(this@with).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
                val add=iconButton("plus","Добавить плитку ${spec.title} в шторку")
                fun requestAdd() {
                    if(Build.VERSION.SDK_INT<33)return
                    try {
                        getSystemService(StatusBarManager::class.java).requestAddTileService(
                            ComponentName(this@with,spec.service),spec.title,QuickTileAvailability.previewIcon(this@with,spec.icon),mainExecutor
                        ) {result->
                            when(result) {
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED->toast("Плитка добавлена")
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED->toast("Плитка уже добавлена")
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED->toast("Добавление отменено. Плитка доступна в редакторе шторки.")
                                else->toast("Добавьте плитку через редактор шторки Android")
                            }
                        }
                    }catch(_:Exception){toast("Добавьте плитку через редактор шторки Android")}
                }
                val initial=runCatching {QuickTileAvailability.enabled(this@with,spec)}
                val toggle=android.widget.Switch(this@with).apply {
                    text=spec.title;tag="quick-tile-enabled-${spec.service.simpleName}"
                    tune(this)
                    isChecked=initial.getOrDefault(false);isEnabled=initial.isSuccess
                    contentDescription="Плитка ${spec.title} доступна в шторке"
                    var restoring=false
                    setOnCheckedChangeListener {_,enabled->
                        if(!restoring)try {
                            QuickTileAvailability.setEnabled(this@with,spec,enabled)
                            add.isEnabled=enabled
                            if(enabled)requestAdd()
                        }catch(_:Exception){
                            restoring=true;isChecked=!enabled;restoring=false
                            toast("Не удалось изменить доступность плитки")
                        }
                    }
                }
                row.addView(toggle,LinearLayout.LayoutParams(0,-2,1f))
                if(initial.isFailure)row.contentDescription="Не удалось прочитать состояние плитки ${spec.title}"
                if(Build.VERSION.SDK_INT>=33) {
                    add.isEnabled=toggle.isChecked && toggle.isEnabled
                    add.setOnClickListener {requestAdd()}
                    row.addView(add,LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
                }
                addView(row,space(0,8))
            }
        }
    }
}
