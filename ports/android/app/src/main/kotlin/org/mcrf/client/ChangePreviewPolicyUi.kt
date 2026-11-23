package org.mcrf.client

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch

internal object ChangePreviewPolicyUi {
    fun show(activity:MainActivity,refresh:()->Unit) {
        AndroidDeployIo.submit({ChangePreviewPolicy.prepare(activity)}) ready@{result->
            if(activity.isFinishing||activity.isDestroyed)return@ready
            result.onSuccess {review->
                val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(16),activity.dp(20),activity.dp(16))}
                body.addView(activity.text("Разрешить автоматическое применение этих профилей и расписаний? Изменённые профили потребуется подтвердить снова. Сейчас настройки не переключатся.",12,Loft.MUTED),activity.space(0,12))
                val allow=Switch(activity).apply {
                    text="Разрешаю автопрофилям сети перенастраивать активный VPN";activity.tune(this);minimumHeight=activity.dp(48)
                    isChecked=false
                }
                body.addView(allow,activity.space(0,16))
                body.addView(activity.text("Выключено — профиль ждёт отключения VPN. Расписание имеет отдельное разрешение.",12,Loft.MUTED),activity.space(0,12))
                review.preview.groups.forEach {group->
                    CompactUi.group(activity,body,group.kind.title).addView(activity.text(group.items.joinToString("\n"){"• $it"}.ifEmpty {"Нет"},13,Loft.MUTED))
                }
                val dialog=ThemedDialogBuilder(activity).setTitle("Автоприменение профилей")
                    .setView(CompactUi.scroll(activity,body)).setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",null).show()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minimumHeight=activity.dp(48)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    minimumHeight=activity.dp(48)
                    setOnClickListener {
                        if(activity.working())return@setOnClickListener
                        val activeIntent=allow.isChecked;isEnabled=false
                        AndroidDeployIo.submit({ChangePreviewPolicy.approve(activity,review,activeIntent)}) approved@{applied->
                            if(activity.isFinishing||activity.isDestroyed)return@approved
                            isEnabled=true
                            applied.onSuccess {dialog.dismiss();refresh()}
                                .onFailure {activity.alert("Автополитика","Настройки изменились или подтверждение не сохранено. Откройте предпросмотр заново.")}
                        }
                    }
                }
            }.onFailure {activity.alert("Автополитика","Не удалось подготовить снимки. Проверьте сохранённые профили и маршрутизацию.")}
        }
    }
}
