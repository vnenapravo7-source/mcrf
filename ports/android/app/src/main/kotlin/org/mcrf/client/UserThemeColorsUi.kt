package org.mcrf.client

import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout

object UserThemeColorsUi {
    /** First three rows; kept as a separate node for the existing Appearance host. */
    fun randomOnOpenSettings(activity:MainActivity,onModeChanged:()->Unit={activity.render()}):LinearLayout=with(activity) {
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="appearance-primary-settings"
            addView(card().apply {
                tag="appearance-mode-card"
                addView(AppearanceSettingsUi.interfaceMode(this@with,onModeChanged))
            },space(0,12))
            if(orbitEnabled())addView(AppearanceSettingsUi.navigation(this@with),space(0,0))
            else addView(card().apply {
            tag="appearance-random-card"
            addView(android.widget.Switch(this@with).apply {
                text="Рандомное оформление каждый раз";tag="random-appearance-on-open"
                tune(this)
                isChecked=prefs.getBoolean(RandomAppearance.KEY,false)
                setTextColor(Loft.INK);textSize=14f;minHeight=dp(48)
                setOnCheckedChangeListener {_,checked->prefs.edit().putBoolean(RandomAppearance.KEY,checked).apply()}
            },space(0,12))
            addView(button("Применить рандомное оформление",false).apply {
                tag="random-appearance-now"
                setOnClickListener {
                    if(!working())runAfterSuccess({applyRandom(this@with);""},false,Runnable {applyUserThemeColors()})
                }
            },space(0,0))
            },space(0,0))
        }
    }
    private fun applyRandom(activity:MainActivity) {
        val keys=UserThemeColors.keys+listOf(PatternWallpaper.KEY,ConnectionAppearance.STYLE,ConnectionAppearance.EXIT,BottomNavigationStyle.KEY,BottomNavigationStyle.POSITION)
        val current=keys.associateWith {activity.prefs.getString(it,"").orEmpty()}.toMutableMap()
        current[PatternWallpaper.KEY]=WallpaperCombinations.canonical(current[PatternWallpaper.KEY].orEmpty())
        current[ConnectionAppearance.STYLE]=ConnectionAppearance.style(current[ConnectionAppearance.STYLE])
        current[ConnectionAppearance.EXIT]=ConnectionAppearance.exit(current[ConnectionAppearance.EXIT])
        current[BottomNavigationStyle.KEY]=BottomNavigationStyle.resolve(current[BottomNavigationStyle.KEY],current[BottomNavigationStyle.POSITION])
        val edit=activity.prefs.edit()
        RandomAppearance.changes(current,PatternWallpaper.choices.keys.filter {it!="none"}).forEach {(key,value)->edit.putString(key,value)}
        check(edit.commit()) {"Не удалось сохранить случайное оформление."}
    }
    fun randomizeForeground(activity:MainActivity):Boolean {
        if(activity.orbitEnabled())return false
        if(!RandomAppearance.foreground.enter(activity.prefs.getBoolean(RandomAppearance.KEY,false)))return false
        applyRandom(activity)
        return true
    }
    fun configure(activity:android.content.Context) {
        val preferences=activity.getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE)
        AppearanceSettings.removeRetiredPreferences(preferences)
        fun choice(first:String,second:String)=runCatching {UserThemeColors.choice(preferences.getString(first,null),preferences.getString(second,null))}.getOrNull()
        HomeUiTheme.configure(choice(UserThemeColors.keys[0],UserThemeColors.keys[1]),choice(UserThemeColors.keys[2],UserThemeColors.keys[3]))
        PatternWallpaper.configure(activity,preferences.getString(PatternWallpaper.KEY,"none").orEmpty())
    }
    /** Remaining rows follow randomOnOpenSettings in the existing host. */
    fun view(activity:MainActivity,onUnifiedChanged:()->Unit={activity.render()}):LinearLayout=with(activity) {
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="appearance-settings"
            if(!orbitEnabled())addView(SettingsUiNodes(this@with).actionRow("Ручные настройки оформления","","arrow","Открыть ручные настройки оформления") {
                showManual(this@with)
            }.apply {tag="appearance-manual-open"},space(0,20))
            if(orbitEnabled())LauncherIconsUi.add(this@with,this,onUnifiedChanged) {choice->
                try {LauncherIcons.select(this@with,choice.id);onUnifiedChanged()}
                catch(error:Exception){alert("Иконка приложения",error.message?:"Не удалось сменить иконку.");onUnifiedChanged()}
            }
            addView(card().apply {addView(ConnectionAppearanceUi.unifiedScreen(this@with,onUnifiedChanged))},space(0,12))
            addView(AppearanceSettingsUi.block(this@with,"Индикатор подключения","appearance-indicator") {
                button(ConnectionIndicator.selected(this@with).label,false).apply {
                    tag="appearance-connection-indicator"
                    setOnClickListener {ConnectionIndicator.choose(this@with)}
                }
            },space(0,12))
            addView(card().apply {
                tag="appearance-fonts"
                fun fontRow(scope:AppTypography.Scope,title:String,tagName:String){
                    val choices=(AppTypography.choices(scope)+mapOf(AppTypography.CUSTOM to "Свой файл TTF/OTF…")).entries.toList()
                    val selected=choices.indexOfFirst {it.key==AppTypography.selected(this@with,scope)}.coerceAtLeast(0)
                    val custom=choices[selected].key==AppTypography.CUSTOM
                    fun importFont(){
                        startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"),
                            scope.request)
                    }
                    addView(text(title,14,Loft.INK).apply {isAccessibilityHeading=true},space(0,6))
                    addView(button(if(custom)"Свой шрифт" else choices[selected].value,false).apply {
                        tag=tagName
                        contentDescription="$title. ${choices[selected].value}"
                        setOnClickListener {
                            DropdownPopup.show(this@with,this,title,choices.map {DropdownPopup.Item(it.value)},selected) {index->
                                val key=choices[index].key
                                if(key==AppTypography.CUSTOM&&!AppTypography.hasFile(this@with,scope,key))importFont() else {
                                    prefs.edit().putString(scope.key,key).apply()
                                    applyUserThemeColors()
                                }
                            }
                        }
                    },space(0,8))
                    if(custom)addView(button("Заменить файл…",false).apply {
                        setOnClickListener {importFont()}
                    },space(0,8))
                }
                fontRow(AppTypography.Scope.INTERFACE,"Шрифт интерфейса","appearance-interface-font")
                divider(this,4,12)
                fontRow(AppTypography.Scope.DISPLAY,"Шрифт акцентов","appearance-display-font")
                addView(text("VPN подключён · 42 мс",14,Loft.MUTED).apply {
                    tag="display-font-sample"
                    contentDescription="Образец шрифта. VPN подключён. 42 миллисекунды"
                },space(0,0))
            },space(0,12))
            if(canEditInterface())addView(SettingsUiNodes(this@with).actionRow("Конструктор интерфейса","Порядок и видимость элементов","arrow","Открыть конструктор интерфейса"){
                openInterfaceConstructor()
            }.apply {tag="open-layout-constructor"},space(0,12))
            addView(AppearanceSettingsUi.block(this@with,"Отображение функционала","appearance-features") {UsageFeaturesUi.settings(this@with)},space(0,12))
            addView(AppearanceSettingsUi.block(this@with,"Настройки бокового меню","appearance-edge") {EdgePanelAppearance.view(this@with)},space(0,12))
            addView(AppearanceSettingsUi.block(this@with,"Статистика подключения в уведомлении","appearance-notification") {NotificationTrafficUi.view(this@with)},space(0,12))
            addView(AppearanceSettingsUi.block(this@with,"Плитки Android","appearance-tiles") {AppearanceSettingsUi.tiles(this@with)},space(0,0))
        }
    }
    fun showManual(activity:MainActivity)=with(activity) {
        val scroll=android.widget.ScrollView(this).apply {isFillViewport=false;tag="manual-appearance-scroll"}
        val scope="dialog:appearance-manual"
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            constructorDialogControl(scope)?.let {addView(it,LinearLayout.LayoutParams(-1,dp(40)))}
            addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        }
        lateinit var shown:android.app.AlertDialog
        fun refresh() {
            val position=scroll.scrollY
            applyUserThemeColors()
            scroll.removeAllViews()
            scroll.addView(manualEditor(this@with,::refresh).also {bindConstructorDialog(it,scope)})
            // Restore the editor before its next frame, just like the host page.
            scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw():Boolean {
                    scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    scroll.scrollTo(0,position)
                    return true
                }
            })
            shown.window?.setBackgroundDrawable(GradientDrawable().apply {setColor(Loft.dialogSurface());cornerRadius=dp(24).toFloat()})
            val titleId=resources.getIdentifier("alertTitle","id","android")
            shown.findViewById<android.widget.TextView>(titleId)?.setTextColor(Loft.INK)
            shown.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.let {StandardButtons.style(this@with,it,it.text.toString())}
        }
        scroll.addView(manualEditor(this,::refresh).also {bindConstructorDialog(it,scope)})
        shown=ThemedDialogBuilder(this).setTitle("Ручные настройки оформления")
            .setView(body)
            .setNegativeButton("Закрыть",null).create()
        shown.show()
        shown.window?.setLayout((resources.displayMetrics.widthPixels-dp(32)).coerceAtLeast(dp(240)),(resources.displayMetrics.heightPixels*.80f).toInt())
        shown
    }
    private fun manualEditor(activity:MainActivity,onApplied:()->Unit):LinearLayout=with(activity) {
        val connectionEditor=ConnectionAppearanceUi.editor(this,onApplied)
        fun save(key:String,value:String) {
            if(prefs.getString(key,null)==value)return
            prefs.edit().putString(key,value).apply()
            onApplied()
        }
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(24),dp(16),dp(16))
            tag="theme-colors-settings"
            addView(connectionEditor.view,space(0,12))
            addView(text("Фоновый узор",18,Loft.INK),space(0,8))
            val openedWallpaper=WallpaperCombinations.canonical(prefs.getString(PatternWallpaper.KEY,"none").orEmpty())
            val wallpaper=picker(PatternWallpaper.choices.values.toTypedArray(),PatternWallpaper.choices.keys.indexOf(openedWallpaper).coerceAtLeast(0))
            var previousWallpaper=wallpaper.selectedItemPosition
            wallpaper.tag="manual-wallpaper"
            wallpaper.onItemSelectedListener=selectedListener {position->
                if(position!=previousWallpaper) {
                    if(working())wallpaper.setSelection(previousWallpaper)
                    else {previousWallpaper=position;save(PatternWallpaper.KEY,PatternWallpaper.choices.keys.elementAt(position))}
                }
            }
            addView(wallpaper,space(0,8))
            addView(text("Два цвета оформления",18,Loft.INK),space(0,8))
            addView(text("Первый — акценты и начало градиента, второй — его конец и подсветка. Отдельно для каждой темы. Яркость кнопок подстраивается для читаемости.",12,Loft.MUTED),space(0,16))
            fun pair(title:String,keys:List<String>,defaults:List<String>) {
                divider(this,0,12);addView(text(title,16,Loft.INK),space(0,8))
                val row=LinearLayout(this@with)
                keys.forEachIndexed {index,key->
                    val control=ColorButton(this@with,if(index==0)"Первый цвет"else"Второй цвет").apply {
                        tag=key;value=prefs.getString(key,"").orEmpty().ifBlank {defaults[index]}
                        setOnClickListener {if(!working())UserColorPicker.show(this@with,value){chosen->save(key,chosen)}}
                    }
                    row.addView(control,LinearLayout.LayoutParams(0,dp(56),1f).apply {if(index==0)rightMargin=dp(4)else leftMargin=dp(4)})
                }
                addView(row,space(0,12))
            }
            fun night()=pair("Тёмная тема"+(if(Loft.dark)" · текущая"else""),UserThemeColors.keys.take(2),listOf("#B8A3D2","#373040"))
            fun day()=pair("Светлая тема"+(if(!Loft.dark)" · текущая"else""),UserThemeColors.keys.drop(2),listOf("#006467","#CFE5DD"))
            if(Loft.dark){night();day()}else{day();night()}
            LauncherIconsUi.add(this@with,this,onApplied) {choice->
                try {LauncherIcons.select(this@with,choice.id);onApplied()}
                catch(error:Exception){alert("Иконка приложения",error.message?:"Не удалось сменить иконку.");onApplied()}
            }
            divider(this,0,12)
            addView(button("Стандартные цвета",false).apply {tag="theme-colors-reset";setOnClickListener {
                if(UserThemeColors.keys.none {prefs.contains(it)}){toast("Уже используются стандартные цвета");return@setOnClickListener}
                if(!working())this@with.runAfterSuccess({
                    val edit=prefs.edit();UserThemeColors.keys.forEach(edit::remove)
                    check(edit.commit()){"Не удалось восстановить цвета."};""
                },false,Runnable(onApplied))
            }},space(0,0))
        }
    }
    class ColorButton(private val activity:MainActivity,private val label:String):android.widget.Button(activity){
        var value:String="#000000"
            set(value){
                val color=UserThemeColors.parse(value);field=UserThemeColors.hex(color)
                contentDescription="$label · $field"
                background=GradientDrawable().apply {setColor(color);cornerRadius=activity.dp(14).toFloat();setStroke(activity.dp(1),Loft.LINE)}
                setTextColor(if(UserThemeColors.contrast(color,android.graphics.Color.BLACK)>UserThemeColors.contrast(color,android.graphics.Color.WHITE))android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        init {backgroundTintList=null;stateListAnimator=null;elevation=0f;text="";contentDescription=label;isAllCaps=false;minHeight=activity.dp(56);minimumHeight=activity.dp(56);setPadding(0,0,0,0)}
    }
}
