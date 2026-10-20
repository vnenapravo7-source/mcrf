package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Stable alias names survive upgrades; the actual component state is the source of truth. */
object LauncherIcons {
    data class Choice(val id:Int,val title:String,val image:Int) {
        val alias:String get()="org.mcrf.client.Launcher"+if(id==0)"Default" else id.toString()
    }
    val choices=listOf(
        Choice(0,"Стандартная",R.drawable.mcrf_logo),
        Choice(1,"Фиолетовый клоун",R.drawable.launcher_choice_1),
        Choice(4,"Тёмный клоун",R.drawable.launcher_choice_4),
        Choice(5,"Маска",R.drawable.launcher_choice_5),
        Choice(6,"Щит и туннель",R.drawable.launcher_choice_6),
        Choice(7,"Соединение",R.drawable.launcher_choice_7),
        Choice(11,"Плетёный ромб",R.drawable.launcher_choice_11),
        Choice(13,"Ромб-портал",R.drawable.launcher_choice_13)
    )
    private fun component(context:Context,choice:Choice)=ComponentName(context.packageName,choice.alias)
    fun current(context:Context):Choice=choices.firstOrNull {choice->
        when(context.packageManager.getComponentEnabledSetting(component(context,choice))){
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED->true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT->choice.id==0
            else->false
        }
    }?:choices.first()
    @Synchronized fun select(context:Context,id:Int) {
        val selected=choices.single {it.id==id}
        val manager=context.packageManager
        val states=choices.associateWith {manager.getComponentEnabledSetting(component(context,it))}
        fun state(choice:Choice)=if(choice==selected)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if(Build.VERSION.SDK_INT>=33){
            manager.setComponentEnabledSettings(choices.map {
                PackageManager.ComponentEnabledSetting(component(context,it),state(it),PackageManager.DONT_KILL_APP)
            })
        }else{
            // Never leave the package without a launchable component, even on older Android.
            try{
                manager.setComponentEnabledSetting(component(context,selected),state(selected),PackageManager.DONT_KILL_APP)
                choices.filter {it!=selected}.forEach {manager.setComponentEnabledSetting(component(context,it),state(it),PackageManager.DONT_KILL_APP)}
            }catch(error:Exception){
                states.filterValues {it==PackageManager.COMPONENT_ENABLED_STATE_ENABLED}.forEach {(choice,value)->
                    runCatching {manager.setComponentEnabledSetting(component(context,choice),value,PackageManager.DONT_KILL_APP)}
                }
                states.forEach {(choice,value)->runCatching {manager.setComponentEnabledSetting(component(context,choice),value,PackageManager.DONT_KILL_APP)}}
                throw error
            }
        }
    }
}
