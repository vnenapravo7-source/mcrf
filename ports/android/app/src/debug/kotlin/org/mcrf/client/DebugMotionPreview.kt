package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Local controls for viewing production animations without a service or network session. */
internal object DebugMotionPreview {
    @JvmStatic
    fun show(activity: MainActivity) {
        if (!BuildConfig.DEBUG || activity.packageName != "org.mcrf.client.ui20261120" ||
            activity.isFinishing || activity.isDestroyed
        ) return

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val inset = activity.dp(16)
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(activity).apply {
            textSize = 24f
            text = "Математика движения"
            setTextColor(Loft.INK)
        }
        content.addView(title)
        content.addView(activity.button("Повторить Math", false).apply {
            setOnClickListener {
                title.text = "Математика движения"
                MathTextMotion.bind(title)
            }
        })

        val progress = ConnectionStatusProgress(activity, visualOnly = true) {}
        var phase = ConnectionProgressState.Phase.PREPARING
        val grid = BeautifulLoadingGrid(activity) { phase.moving }
        content.addView(grid, LinearLayout.LayoutParams(activity.dp(32), activity.dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        grid.visibility = if (AppearanceMotion.pixelLoading(activity)) View.GONE else View.VISIBLE
        content.addView(progress)
        progress.state(phase)
        val phases = listOf(
            phase to "Подготовка",
            ConnectionProgressState.Phase.CONNECTING to "Соединение",
            ConnectionProgressState.Phase.VERIFYING to "Проверка",
            ConnectionProgressState.Phase.STOPPING to "Отключение",
            ConnectionProgressState.Phase.IDLE to "Сбросить"
        )
        phases.forEach { (value, label) ->
            content.addView(activity.button(label, false).apply {
                setOnClickListener {
                    phase = value
                    grid.invalidate()
                    progress.state(phase)
                }
            })
        }

        val updateButton = activity.button("Прогресс обновления", true)
        val update = AppUpdateProgressAction(updateButton)
        val stop = AppUpdateProgressAction.stopButton(activity, "Остановить предпросмотр обновления")
        val slider = SeekBar(activity).apply {
            max = 100
            contentDescription = "Процент обновления"
        }
        updateButton.setOnClickListener {
            update.download(slider.progress)
            AppUpdateProgressAction.showStop(stop, true)
        }
        stop.setOnClickListener {
            update.ready("Прогресс обновления")
            AppUpdateProgressAction.showStop(stop, false)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                update.download(value)
                AppUpdateProgressAction.showStop(stop, true)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        content.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(updateButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(stop)
        })
        content.addView(slider)

        val values = listOf(1, 2, 3, 4, 6, 8, 12)
        listOf("Jelly" to false, "Budget" to true).forEach { (label, budget) ->
            content.addView(TextView(activity).apply {
                text = label
                setTextColor(Loft.INK)
            })
            content.addView(TurnSliderArt(activity, budget, values, 4).apply {
                max = values.lastIndex
                this.progress = values.indexOf(4)
                setPadding(activity.dp(12), 0, activity.dp(12), 0)
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        bar.contentDescription = "$label, ${values[value]}"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {
                        bar.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    override fun onStopTrackingTouch(bar: SeekBar) {
                        bar.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                })
            }, LinearLayout.LayoutParams(-1, activity.dp(76)))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Предпросмотр анимаций")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Закрыть", null)
            .create()
        fun cleanup() {
            stop.animate().cancel()
        }
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = cleanup()
        })
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(owner: Activity) = Unit
            override fun onActivityResumed(owner: Activity) = Unit
            override fun onActivityPaused(owner: Activity) = Unit
            override fun onActivityStopped(owner: Activity) = Unit
            override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(owner: Activity) {
                if (owner === activity) {
                    cleanup()
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            cleanup()
        }
        dialog.show()
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        MathTextMotion.bind(title)
    }
}
