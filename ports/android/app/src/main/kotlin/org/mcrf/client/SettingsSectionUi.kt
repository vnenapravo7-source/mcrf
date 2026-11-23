package org.mcrf.client

import android.widget.LinearLayout

/** A failed optional settings section must not take down the activity or reset its data. */
internal object SettingsSectionUi {
    fun fold(activity:MainActivity,parent:LinearLayout,id:String,title:String,expanded:Boolean=false,content:()->LinearLayout):LinearLayout {
        val body=CompactUi.fold(activity,parent,title,expanded)
        body.tag=id
        body.addView(build(activity,id,title,content))
        return body
    }
    internal fun <T> read(build:()->T,onError:()->T):T = try {build()}
        catch(_:Exception){onError()}
        catch(_:LinkageError){onError()}

    fun build(activity:MainActivity,id:String,title:String,content:()->LinearLayout):LinearLayout = with(activity) {
        val host=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag=id}
        fun load() {
            val section=read(content) {
                card().apply {
                    tag="$id-error"
                    addView(text(title,16,Loft.INK),space(0,8))
                    addView(text("Не удалось открыть настройки. Сохранённые данные не изменены. Попробуйте ещё раз.",13,Loft.ERROR),space(0,12))
                    addView(button("Повторить",false).apply {setOnClickListener {load()}})
                }
            }
            host.removeAllViews();host.addView(section)
        }
        load();host
    }
}
