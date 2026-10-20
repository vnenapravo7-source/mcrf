package org.mcrf.client

import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout

object UserThemeColorsUi {
    fun configure(activity:android.content.Context) {
        val preferences=activity.getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE)
        fun choice(first:String,second:String)=runCatching {UserThemeColors.choice(preferences.getString(first,null),preferences.getString(second,null))}.getOrNull()
        HomeUiTheme.configure(choice(UserThemeColors.keys[0],UserThemeColors.keys[1]),choice(UserThemeColors.keys[2],UserThemeColors.keys[3]))
        PatternWallpaper.configure(activity,preferences.getString(PatternWallpaper.KEY,"none").orEmpty())
    }
    fun view(activity:MainActivity):LinearLayout=with(activity) {
        val fields=linkedMapOf<String,ColorButton>()
        var pendingIcon:LauncherIcons.Choice?=null
        val connectionEditor=ConnectionAppearanceUi.editor(this)
        val openedConnection=connectionEditor.snapshot()
        card().apply {
            tag="theme-colors-settings"
            addView(text("Фоновый узор",18,Loft.INK),space(0,8))
            val openedWallpaper=WallpaperCombinations.canonical(prefs.getString(PatternWallpaper.KEY,"none").orEmpty())
            val wallpaper=picker(PatternWallpaper.choices.values.toTypedArray(),PatternWallpaper.choices.keys.indexOf(openedWallpaper).coerceAtLeast(0))
            addView(wallpaper,space(0,8))
            addView(button("Применить узор",false).apply {setOnClickListener {
                if(!working()){
                    val selected=PatternWallpaper.choices.keys.elementAt(wallpaper.selectedItemPosition)
                    if(selected==WallpaperCombinations.canonical(prefs.getString(PatternWallpaper.KEY,"none").orEmpty())){toast("Нет изменений");return@setOnClickListener}
                    this@with.run({check(prefs.edit().putString(PatternWallpaper.KEY,selected).commit()){"Не удалось сохранить узор."};""},false,Runnable {applyUserThemeColors()})
                }
            }},space(0,16))
            divider(this,0,12)
            addView(text("Два цвета оформления",18,Loft.INK),space(0,8))
            addView(text("Первый — акценты и начало градиента, второй — его конец и подсветка. Отдельно для каждой темы. Яркость кнопок подстраивается для читаемости.",12,Loft.MUTED),space(0,16))
            fun pair(title:String,keys:List<String>,defaults:List<String>) {
                divider(this,0,12);addView(text(title,16,Loft.INK),space(0,8))
                val row=LinearLayout(this@with)
                keys.forEachIndexed {index,key->
                    val control=ColorButton(this@with,if(index==0)"Первый цвет"else"Второй цвет").apply {
                        tag=key;value=prefs.getString(key,"").orEmpty().ifBlank {defaults[index]}
                        setOnClickListener {UserColorPicker.show(this@with,value){chosen->value=chosen}}
                    }
                    fields[key]=control;row.addView(control,LinearLayout.LayoutParams(0,dp(56),1f).apply {if(index==0)rightMargin=dp(4)else leftMargin=dp(4)})
                }
                addView(row,space(0,12))
            }
            fun night()=pair("Тёмная тема"+(if(Loft.dark)" · текущая"else""),UserThemeColors.keys.take(2),listOf("#B8A3D2","#373040"))
            fun day()=pair("Светлая тема"+(if(!Loft.dark)" · текущая"else""),UserThemeColors.keys.drop(2),listOf("#006467","#CFE5DD"))
            if(Loft.dark){night();day()}else{day();night()}
            val openedColors=UserThemeColors.keys.map {fields.getValue(it).value}
            fun read():List<Int> = UserThemeColors.keys.map {key->
                val input=fields.getValue(key)
                UserThemeColors.parse(input.value)
            }
            val randomSummary=text("",12,Loft.MUTED).apply {tag="random-appearance-summary"}
            addView(button("Рандомное оформление",false).apply {tag="random-appearance";setOnClickListener {
                val current=UserThemeColors.keys.map {fields.getValue(it).value}
                val preset=AppearancePresets.all.filter {it.colors!=current}.random()
                UserThemeColors.keys.forEachIndexed {index,key->fields.getValue(key).value=preset.colors[index]}
                val previousIcon=pendingIcon?.id?:LauncherIcons.current(this@with).id
                pendingIcon=LauncherIcons.choices.filter {it.id!=previousIcon}.random()
                val nextWallpaper=(1 until PatternWallpaper.choices.size).filter {it!=wallpaper.selectedItemPosition}.random()
                wallpaper.setSelection(nextWallpaper)
                val connectionLook=connectionEditor.randomize()
                randomSummary.text="${preset.name} · узор: ${wallpaper.selectedItem} · $connectionLook. Для сохранения нажми «Применить»."
            }},space(0,8))
            addView(randomSummary,space(0,8))
            addView(button("Применить оформление",false).apply {tag="theme-colors-save";setOnClickListener {try {
                if(working())return@setOnClickListener
                val colors=read()
                val wallpaperId=PatternWallpaper.choices.keys.elementAt(wallpaper.selectedItemPosition)
                val iconChanged=pendingIcon?.let {it.id!=LauncherIcons.current(this@with).id}?:false
                if(colors.map(UserThemeColors::hex)==openedColors&&wallpaperId==openedWallpaper&&!iconChanged&&connectionEditor.snapshot()==openedConnection){toast("Нет изменений");return@setOnClickListener}
                val edit=prefs.edit()
                connectionEditor.write(edit)
                UserThemeColors.keys.forEachIndexed {index,key->edit.putString(key,UserThemeColors.hex(colors[index]))}
                edit.putString(PatternWallpaper.KEY,PatternWallpaper.choices.keys.elementAt(wallpaper.selectedItemPosition))
                val chosenIcon=pendingIcon
                this@with.run({
                    val previousIcon=if(chosenIcon!=null)LauncherIcons.current(this@with)else null
                    chosenIcon?.let {LauncherIcons.select(this@with,it.id)}
                    if(!edit.commit()){
                        if(previousIcon!=null)LauncherIcons.select(this@with,previousIcon.id)
                        error("Не удалось сохранить оформление.")
                    }
                    ""
                },false,Runnable {applyUserThemeColors()})
            }catch(error:Exception){alert("Оформление",error.message?:"Проверь цвета.")}}},space(0,8))
            addView(button("Применить",false).apply {tag="theme-colors-save-bottom";setOnClickListener {
                this@with.window.decorView.findViewWithTag<android.view.View>("theme-colors-save").performClick()
            }},space(0,12))
            divider(this,0,12)
            addView(button("Стандартные цвета",false).apply {tag="theme-colors-reset";setOnClickListener {
                if(!working())this@with.run({
                    val edit=prefs.edit();UserThemeColors.keys.forEach(edit::remove)
                    check(edit.commit()){"Не удалось восстановить цвета."};""
                },false,Runnable {applyUserThemeColors()})
            }},space(0,0))
            // Keep both actions above the editors; randomization remains a draft until Apply.
            val random=findViewWithTag<android.widget.Button>("random-appearance")
            val apply=findViewWithTag<android.widget.Button>("theme-colors-save")
            removeView(random);removeView(apply)
            apply.text="Применить"
            random.setSingleLine(false);random.maxLines=2
            random.setPadding(dp(6),dp(6),dp(6),dp(6))
            random.setAutoSizeTextTypeUniformWithConfiguration(11,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            apply.setPadding(dp(6),dp(6),dp(6),dp(6))
            apply.setSingleLine(true);apply.setHorizontallyScrolling(false)
            apply.setAutoSizeTextTypeUniformWithConfiguration(11,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            val actions=LinearLayout(this@with).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(random,LinearLayout.LayoutParams(0,dp(64),1f).apply {rightMargin=dp(4)})
                addView(apply,LinearLayout.LayoutParams(dp(100),dp(64)).apply {leftMargin=dp(4)})
            }
            fun separated(content:android.view.View)=LinearLayout(this@with).apply {
                orientation=LinearLayout.VERTICAL
                addView(content,space(0,12));divider(this,0,12)
            }
            addView(separated(actions),0,space(0,0))
            addView(separated(SettingsUiNodes(this@with).actionRow("Минимализм","Выбор функций и скрытие ненужных разделов","arrow","Открыть: Минимализм"){navigate(ClientSettingsNavigation.MINIMALISM)}.apply {tag="settings-page-${ClientSettingsNavigation.MINIMALISM}"}),1,space(0,0))
            addView(separated(connectionEditor.view),2,space(0,0))
            LauncherIconsUi.add(this@with,this)
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
