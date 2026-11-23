package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import android.widget.Spinner
import java.util.concurrent.atomic.AtomicReference

/** Checks actual immediate saves; restores the appearance and launcher alias afterwards. */
class ManualAppearanceInstrumentation : Instrumentation() {
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val preferences=targetContext.getSharedPreferences("MainActivity",0)
        val keys=UserThemeColors.keys+listOf(ConnectionAppearance.STYLE,ConnectionAppearance.EXIT,
            BottomNavigationStyle.KEY,PatternWallpaper.KEY,RandomAppearance.KEY,HomeUiTheme.MODE_KEY)
        val original=preferences.all.filterKeys {it in keys}
        val icon=LauncherIcons.current(targetContext)
        val report=StringBuilder()
        var host:MainActivity?=null
        var code=Activity.RESULT_OK
        fun main(block:()->Unit) {
            val failure=AtomicReference<Throwable?>()
            runOnMainSync {try {block()}catch(error:Throwable){failure.set(error)}}
            failure.get()?.let {throw it}
            waitForIdleSync()
        }
        fun findPageScroll(view:View):ScrollView = generateSequence(view) { it.parent as? View }
            .filterIsInstance<ScrollView>().first()
        try {
            preferences.edit().putBoolean(RandomAppearance.KEY,false).putString(HomeUiTheme.MODE_KEY,HomeUiTheme.Mode.DARK.key).commit()
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java)
                .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            host=activity
            val deadline=System.nanoTime()+15_000_000_000L
            while(activity.working()&&System.nanoTime()<deadline)Thread.sleep(50)
            check(!activity.working())
            main {activity.navigate(ClientSettingsNavigation.APPEARANCE)}
            fun root()=activity.window.decorView
            fun spinner(tag:String)=checkNotNull(root().findViewWithTag<Spinner>(tag))
            fun change(tag:String,key:String,values:List<String>) {
                var expected=""
                main {
                    val control=spinner(tag)
                    val next=(control.selectedItemPosition+1)%values.size
                    expected=values[next];control.setSelection(next)
                }
                main {
                    check(preferences.getString(key,null)==expected){"$tag was not saved immediately"}
                    check(root().findViewWithTag<View>("screen-${ClientSettingsNavigation.APPEARANCE}")!=null)
                    check(root().findViewWithTag<View>("theme-colors-save")==null)
                }
            }
            change("connection-window-style",ConnectionAppearance.STYLE,ConnectionAppearance.choices.keys.toList())
            change("connection-exit-style",ConnectionAppearance.EXIT,ConnectionAppearance.exits.keys.toList())
            change("manual-wallpaper",PatternWallpaper.KEY,PatternWallpaper.choices.keys.toList())
            var position=0
            main {
                val scroll=findPageScroll(root().findViewWithTag("theme-colors-settings"))
                scroll.scrollTo(0,activity.dp(180));position=scroll.scrollY
            }
            change("manual-wallpaper",PatternWallpaper.KEY,PatternWallpaper.choices.keys.toList())
            main {check(findPageScroll(root().findViewWithTag("theme-colors-settings")).scrollY==position){"Editor jumped"}}
            if(Build.VERSION.SDK_INT>=29) {
                val key=UserThemeColors.keys.first()
                val before=preferences.getString(key,null)
                fun palette():View=WindowInspector.getGlobalWindowViews().last {it.findViewWithTag<View>("color-picker-preview")!=null}
                main {root().findViewWithTag<View>(key).performClick()}
                main {palette().findViewWithTag<View>("color-swatch-#009944").performClick()}
                main {check(preferences.getString(key,null)==before);palette().findViewById<View>(android.R.id.button2).performClick()}
                main {check(preferences.getString(key,null)==before){"Cancel saved a colour"};root().findViewWithTag<View>(key).performClick()}
                main {palette().findViewWithTag<View>("color-swatch-#009944").performClick();palette().findViewById<View>(android.R.id.button1).performClick()}
                main {check(preferences.getString(key,null)=="#009944");check(root().findViewWithTag<View>("appearance-settings")!=null)}
            }
            val nextIcon=LauncherIcons.choices.first {it.id!=icon.id}
            main {root().findViewWithTag<View>("launcher-icon-${nextIcon.id}").performClick()}
            main {check(LauncherIcons.current(activity).id==nextIcon.id){"Icon was not applied immediately"}}
            report.append("PASS immediate spinners/wallpaper/colour/icon; colour cancel; retained editor scroll\n")
        } catch(error:Throwable) {
            code=Activity.RESULT_CANCELED;report.append("FAIL ").append(error.stackTraceToString())
        } finally {
            main {
                val edit=preferences.edit();keys.forEach(edit::remove)
                original.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value)}}
                edit.commit();LauncherIcons.select(targetContext,icon.id)
                UserThemeColorsUi.configure(targetContext);Loft.apply(Loft.dark)
                host?.finish()
            }
            finish(code,Bundle().apply {putString("stream",report.toString())})
        }
    }
}
