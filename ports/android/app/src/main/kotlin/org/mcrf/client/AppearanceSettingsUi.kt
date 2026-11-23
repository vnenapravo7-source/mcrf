package org.mcrf.client

import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout

/** Appearance-only controls. MainActivity owns navigation and screen reconstruction. */
internal object AppearanceSettingsUi {
    fun interfaceMode(activity:MainActivity,onChanged:()->Unit):LinearLayout=with(activity) {
        val store=ExperienceModeStore(this)
        val initial=store.load()
        if(TvUi.enabled(this))return@with LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="appearance-interface-mode"
            addView(text("Режим интерфейса · ${initial.title}",14,Loft.INK))
        }
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

    fun choiceButton(activity:MainActivity,label:String,value:String,tagName:String,open:(View)->Unit):View=with(activity) {
        button(value,false).apply {
            tag=tagName
            minimumHeight=dp(48)
            gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            contentDescription="$label. $value"
            val arrow=icon("down",Loft.MUTED).apply {setBounds(0,0,dp(16),dp(16))}
            setCompoundDrawablesRelative(null,null,arrow,null)
            compoundDrawablePadding=dp(12)
            setOnClickListener {if(!working())open(this)}
        }
    }

    fun choice(
        activity:MainActivity,label:String,options:List<String>,selection:Int,tagName:String,onSelected:(Int)->Unit,
    ):View=choiceButton(activity,label,options[selection],tagName){anchor->
        DropdownPopup.show(activity,anchor,label,options.map {DropdownPopup.Item(it)},selection,changed=onSelected)
    }

    fun theme(activity:MainActivity):LinearLayout=with(activity) {
        val themes=HomeUiTheme.Mode.entries
        block(this,"Тема","appearance-theme-card") {
            choice(this,"Тема",themes.map {it.label},HomeUiTheme.mode(prefs).ordinal,"appearance-theme"){index->
                val next=themes[index]
                prefs.edit().putString(HomeUiTheme.MODE_KEY,next.key)
                    .putString("theme",if(next==HomeUiTheme.Mode.LIGHT)"light"else"dark").apply()
                applyUserThemeColors()
            }
        }
    }

    fun navigation(activity:MainActivity):LinearLayout=with(activity) {
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            fun addChoice(label:String,key:String,values:Map<String,String>,selected:String,tagName:String){
                val entries=values.entries.toList()
                addView(text(label,14,Loft.INK),space(0,4))
                addView(choice(this@with,label,entries.map {it.value},entries.indexOfFirst {it.key==selected},tagName){index->
                    prefs.edit().putString(key,entries[index].key).apply()
                    render()
                },space(0,10))
            }
            val position=BottomNavigationStyle.position(prefs.getString(BottomNavigationStyle.POSITION,null))
            addChoice("Расположение разделов",BottomNavigationStyle.POSITION,BottomNavigationStyle.positions,position,"section-navigation-position")
            addChoice("Оформление навигации",BottomNavigationStyle.KEY,BottomNavigationStyle.choicesFor(position),
                BottomNavigationStyle.resolve(prefs.getString(BottomNavigationStyle.KEY,null),position),"bottom-navigation-style")
            addView(android.widget.CheckBox(this@with).apply {
                tag="section-swipes-enabled"
                text="Включить свайпы разделов"
                setTextColor(Loft.INK);textSize=14f;minHeight=dp(48)
                isChecked=prefs.getBoolean("sectionSwipesEnabled",false)
                setOnCheckedChangeListener {_,enabled->prefs.edit().putBoolean("sectionSwipesEnabled",enabled).apply()}
            },space(0,8))
        }
    }
}
