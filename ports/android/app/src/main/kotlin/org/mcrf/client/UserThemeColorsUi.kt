package org.mcrf.client

import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout

object UserThemeColorsUi {
    private fun connection(activity:MainActivity,onChanged:()->Unit):LinearLayout=with(activity) {
        AppearanceSettingsUi.block(this,"Оформление подключения","appearance-connection") {
            LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                if(!TvUi.enabled(this@with)){
                    if(!orbitEnabled())addView(ConnectionAppearanceUi.unifiedScreen(this@with,onChanged),space(0,8))
                    addView(ConnectionAppearanceUi.editor(this@with,onChanged),space(0,8))
                }
                val label="Индикатор подключения"
                addView(text(label,14,Loft.INK),space(0,4))
                addView(AppearanceSettingsUi.choiceButton(this@with,label,
                    ConnectionIndicator.selected(this@with).label,"appearance-connection-indicator") {
                    ConnectionIndicator.choose(this@with)
                },space(0,12))
                if(!TvUi.enabled(this@with))addView(AppearanceMotion.settings(this@with))
            }
        }
    }

    private fun automatic(activity:MainActivity):LinearLayout=with(activity) {
        AppearanceSettingsUi.block(this,"Случайное оформление","appearance-random-card") {
            LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                addView(android.widget.Switch(this@with).apply {
                    text="Рандомное оформление каждый раз";tag="random-appearance-on-open"
                    tune(this)
                    isChecked=prefs.getBoolean(RandomAppearance.KEY,false)
                    setOnCheckedChangeListener {_,checked->prefs.edit().putBoolean(RandomAppearance.KEY,checked).apply()}
                },space(0,8))
                addView(button("Применить рандомное оформление",false).apply {
                    tag="random-appearance-now"
                    setOnClickListener {
                        if(!working())runAfterSuccess({applyRandom(this@with);""},false,Runnable {applyUserThemeColors()})
                    }
                })
            }
        }
    }
    private fun applyRandom(activity:MainActivity) {
        val keys=UserThemeColors.keys+listOf(PatternWallpaper.KEY,ConnectionAppearance.STYLE,ConnectionAppearance.EXIT,
            BottomNavigationStyle.KEY,BottomNavigationStyle.POSITION)
        val current=keys.associateWith {activity.prefs.getString(it,"").orEmpty()}.toMutableMap()
        for (scope in AppTypography.Scope.entries) current[scope.key]=AppTypography.selected(activity,scope)
        current[PatternWallpaper.KEY]=WallpaperCombinations.canonical(current[PatternWallpaper.KEY].orEmpty())
        current[ConnectionAppearance.STYLE]=ConnectionAppearanceUi.style(activity)
        current[ConnectionAppearance.EXIT]=ConnectionAppearance.exit(current[ConnectionAppearance.EXIT])
        current[BottomNavigationStyle.KEY]=BottomNavigationStyle.resolve(current[BottomNavigationStyle.KEY],current[BottomNavigationStyle.POSITION])
        val edit=activity.prefs.edit()
        for ((key,value) in RandomAppearance.changes(current,PatternWallpaper.choices.keys.filter {it!="none"},amoled=activity.orbitEnabled())) {
            if (key==AppearanceMotion.MATH||key==AppearanceMotion.MATH_BODY) edit.putBoolean(key,value.toBooleanStrict())
            else edit.putString(key,value)
        }
        check(edit.commit()) {"Не удалось сохранить случайное оформление."}
    }
    fun randomizeForeground(activity:MainActivity):Boolean {
        if(TvUi.enabled(activity))return false
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
    fun view(activity:MainActivity,onUnifiedChanged:()->Unit={activity.render()}):LinearLayout=with(activity) {
        val tv=TvUi.enabled(activity)
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="appearance-settings"
            if(!tv){
                addView(AppearanceSettingsUi.theme(this@with),space(0,12))
                addView(card().apply {
                    tag="appearance-mode-card"
                    addView(AppearanceSettingsUi.interfaceMode(this@with,onUnifiedChanged))
                },space(0,12))
            }
            addView(connection(this@with,onUnifiedChanged),space(0,12))
            if(!tv){
                addView(AppearanceSettingsUi.block(this@with,"Навигация","appearance-navigation") {
                    AppearanceSettingsUi.navigation(this@with)
                },space(0,12))
                addView(automatic(this@with),space(0,12))
                addView(card().apply {
                    addView(android.widget.Switch(this@with).apply {
                        text="Анимация запуска Swarm";tag="cold-launch-swarm"
                        tune(this)
                        isChecked=prefs.getBoolean(ColdLaunchSwarm.KEY,true)
                        setOnCheckedChangeListener {_,enabled->prefs.edit().putBoolean(ColdLaunchSwarm.KEY,enabled).apply()}
                    })
                    addView(text("При каждом холодном запуске приложения. Возврат из фона не повторяет анимацию; системное отключение анимаций учитывается.",12,Loft.MUTED))
                },space(0,12))
            }
            addView(card().apply {
                tag="appearance-fonts"
                fun fontRow(scope:AppTypography.Scope,title:String,tagName:String,glitchKey:String){
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
                    if(!tv)addView(android.widget.Switch(this@with).apply {
                        tag=glitchKey
                        text="Glitch effect"
                        isChecked=prefs.getBoolean(glitchKey,true)
                        tune(this)
                        setOnCheckedChangeListener {_,enabled->
                            prefs.edit().putBoolean(glitchKey,enabled).apply()
                            render()
                        }
                    },space(0,0))
                }
                fontRow(AppTypography.Scope.INTERFACE,"Шрифт интерфейса","appearance-interface-font",AppearanceMotion.MATH_BODY)
                divider(this,4,12)
                fontRow(AppTypography.Scope.DISPLAY,"Шрифт акцентов","appearance-display-font",AppearanceMotion.MATH)
                addView(text("VPN подключён · 42 мс",14,Loft.MUTED).apply {
                    tag="display-font-sample"
                    contentDescription="Образец шрифта. VPN подключён. 42 миллисекунды"
                },space(0,0))
            },space(0,12))
            if(!tv)addView(UsageFeaturesUi.settings(this@with,"Отображение функционала"),space(0,12))
            if(!tv)addView(AppearanceSettingsUi.block(this@with,"Настройки бокового меню","appearance-edge") {EdgePanelAppearance.view(this@with)},space(0,12))
            if(!tv&&!orbitEnabled())addView(colorsAndWallpaper(this@with) {applyUserThemeColors()},space(0,12))
            addView(card().apply {
                tag="appearance-app-icon"
                LauncherIconsUi.add(this@with,this,onUnifiedChanged) {choice->
                    try {LauncherIcons.select(this@with,choice.id);onUnifiedChanged()}
                    catch(error:Exception){alert("Иконка приложения",error.message?:"Не удалось сменить иконку.");onUnifiedChanged()}
                }
            },space(0,12))
            if(!tv&&canEditInterface())addView(SettingsUiNodes(this@with).actionRow("Конструктор интерфейса","Порядок и видимость элементов","arrow","Открыть конструктор интерфейса"){
                openInterfaceConstructor()
            }.apply {tag="open-layout-constructor"},space(0,12))
        }
    }
    private fun colorsAndWallpaper(activity:MainActivity,onApplied:()->Unit):LinearLayout=with(activity) {
        fun save(key:String,value:String) {
            if(prefs.getString(key,null)==value)return
            prefs.edit().putString(key,value).apply()
            onApplied()
        }
        card().apply {
            tag="theme-colors-settings"
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
