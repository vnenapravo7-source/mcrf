package org.mcrf.client

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*

/** Only the retained refresh interval: the former subscription-options screen has been removed. */
object SubscriptionGroupingRefreshUi {
    fun chooseInterval(context:Context,anchor:View,changed:()->Unit={}):Boolean {
        ThemedActionMenu(context,anchor).apply {
            SubscriptionSchedule.labels.forEachIndexed {index,label ->menu.add(0,index,index,label)}
            menu.add(0,SubscriptionSchedule.hours.size,SubscriptionSchedule.hours.size,"Другой интервал…")
            setOnMenuItemClickListener {item ->
                if(item.itemId==SubscriptionSchedule.hours.size)showInterval(context,changed)
                else try {SubscriptionUpdates.setInterval(context,SubscriptionSchedule.hours[item.itemId]*60);changed()}
                catch(_:Exception){Toast.makeText(context,"Не удалось сохранить расписание",Toast.LENGTH_LONG).show()}
                true
            }
            show()
        }
        return true
    }
    fun showInterval(context:Context,changed:()->Unit={}) {
        val density=context.resources.displayMetrics.density
        fun dp(value:Int)=(value*density).toInt()
        val input=EditText(context).apply {
            setSingleLine();inputType=InputType.TYPE_CLASS_NUMBER;setTextColor(Loft.INK)
            textSize=16f;minimumHeight=dp(48)
            background=Loft.fieldBackground(context)
            setPadding(dp(12),dp(10),dp(12),dp(10))
            setText(SubscriptionOptionsStore.load(context).intervalMinutes.toString());selectAll()
            contentDescription="Интервал обновления подписок, минут"
        }
        val form=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(8))
            addView(TextView(context).apply {text="Интервал в минутах: минимум 30.\n0 — обновлять вручную. По умолчанию — 360 минут (6 часов). Android может отложить запуск.";textSize=14f;setTextColor(Loft.MUTED)})
            addView(input,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        }
        val dialog=ThemedDialogBuilder(context).setTitle("Обновление подписок")
            .setView(form).setNegativeButton("Отмена",null).setPositiveButton("Применить",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val minutes=input.text.toString().trim().toIntOrNull() ?: throw IllegalArgumentException("Введите целое число минут.")
                SubscriptionUpdates.setInterval(context,minutes);changed();dialog.dismiss()
            } catch(error:Exception){input.error=error.message ?: "Не удалось сохранить расписание"}
        }}
        dialog.show()
    }
}
