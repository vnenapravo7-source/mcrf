package org.mcrf.client

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject

/** Read-only credentials: shared spacing, a hidden password and one copy target per field. */
internal object AndroidDeployPanelAccessUi {
    fun show(activity:MainActivity,result:JSONObject):android.app.AlertDialog {
        val body=EditorLayout.body(activity).apply {tag="androidDeploy.panelAccess"}
        fun field(title:String,value:String,display:String=value,monospace:Boolean=false,secret:Boolean=false) {
            val row=LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL
                minimumHeight=activity.dp(48)
                addView(activity.text(display,if(monospace)12 else 14,Loft.INK).apply {
                    tag="androidDeploy.panelAccess.value.$title"
                    setPadding(0,activity.dp(8),activity.dp(12),activity.dp(8))
                    if(monospace)typeface=Typeface.MONOSPACE
                    contentDescription=if(secret)"Пароль панели скрыт"else "$title: $display"
                    setTextIsSelectable(!secret)
                },LinearLayout.LayoutParams(0,-2,1f))
                addView(activity.iconButton("copy","Скопировать: $title").apply {
                    tag="androidDeploy.panelAccess.copy.$title"
                    setOnClickListener {
                        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(AndroidDeployClipboard.sensitive(value))
                        Toast.makeText(activity,"Скопировано",Toast.LENGTH_SHORT).show()
                    }
                },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
            }
            EditorLayout.field(activity,body,title,row)
        }
        field("Адрес панели",result.getString("panel_url"))
        field("Логин",result.getString("username"))
        field("Пароль",result.getString("password"),"••••••••",secret=true)
        val fingerprint=result.getString("certificate_sha256")
        field("Сертификат · SHA-256",fingerprint,fingerprint.chunked(8).joinToString(" "),monospace=true)
        val scroll=ScrollView(activity).apply {
            isFillViewport=false
            isHorizontalScrollBarEnabled=false
            addView(body)
        }
        return ThemedDialogBuilder(activity).setTitle("Доступ к панели").setView(scroll)
            .setPositiveButton("Закрыть",null).show()
    }
}
