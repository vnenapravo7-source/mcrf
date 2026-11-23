package org.mcrf.client

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView

/** The only positive action approves this exact detached preview; dismiss/back/cancel do nothing. */
internal object ChangePreviewUi {
    fun show(activity:MainActivity,title:String,preview:ChangePreview,apply:()->Unit):AlertDialog {
        val body=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(activity.dp(20),activity.dp(16),activity.dp(20),activity.dp(16))
            addView(activity.text("Значения, адреса и секреты скрыты. Если настройки изменятся, потребуется новый предпросмотр.",12,Loft.MUTED),activity.space(0,16))
            preview.groups.forEach {group->
                addView(activity.text(group.kind.title,16,Loft.INK),activity.space(0,12))
                addView(activity.text(group.items.joinToString("\n") {"• $it"}.ifEmpty {"Нет"},14,Loft.MUTED),activity.space(0,16))
            }
        }
        val dialog=ThemedDialogBuilder(activity).setTitle(title)
            .setView(ScrollView(activity).apply {addView(body)})
            .setNegativeButton("Отмена",null).setPositiveButton("Применить",null).show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minimumHeight=activity.dp(48)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            minimumHeight=activity.dp(48)
            setOnClickListener {if(!activity.working()){isEnabled=false;dialog.dismiss();apply()}}
        }
        return dialog
    }
}
