package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** A settings page; retained tabs retire only their own workers on departure. */
internal object ConnectionDiagnosticsUi {
    fun show(activity: MainActivity) = activity.navigate(ClientSettingsNavigation.DIAGNOSTICS)

    fun build(activity: MainActivity, parent: LinearLayout) = with(activity) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "connection-diagnostics-section" }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(iconButton("back", "Назад").apply { setOnClickListener { onBackPressed() } }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
            addView(text("Диагностика", 22, Loft.INK).apply { setAccessibilityHeading(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val owners = mutableListOf<AutoCloseable>()
        val pages = mutableMapOf<Int, LinearLayout>()
        fun select(index: Int) {
            pages.values.forEach { it.visibility = View.GONE }
            val page = pages.getOrPut(index) {
                LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; host.addView(this, LinearLayout.LayoutParams(-1, -1)) }
            }
            page.visibility = View.VISIBLE
            if (page.childCount > 0) return
            owners.add(ServiceDiagnosticsUi(activity, if (index == 0) ServiceDiagnosticsMode.WHY else ServiceDiagnosticsMode.COMPARE).also {
                it.show(page, onExit = { onBackPressed() })
            })
        }
        body.addView(DiagnosticsUiStyle.selector(activity, ::select), space(8, 12))
        body.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { owners.forEach { runCatching { it.close() } }; owners.clear() }
        })
        parent.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        select(0)
    }
}
