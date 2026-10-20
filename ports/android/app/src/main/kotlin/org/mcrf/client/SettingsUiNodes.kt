package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Layout nodes only. Owners provide their real editors and persistence callbacks. */
class SettingsUiNodes(private val activity: MainActivity) {
    fun section(title: String, explanation: String, content: View): LinearLayout = with(activity) {
        card().apply {
            addView(text(title, 18, Loft.INK), LinearLayout.LayoutParams(-1, -2))
            if (explanation.isNotBlank()) addView(text(explanation, 12, Loft.MUTED), space(8, 12))
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Title and version wrap independently of the fixed, accessible action target. */
    fun actionRow(title: String, detail: String, glyph: String, actionLabel: String, action: () -> Unit): LinearLayout = with(activity) {
        card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(14), dp(8), dp(6), dp(8))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15, Loft.INK), LinearLayout.LayoutParams(-1, -2))
                if (detail.isNotBlank()) addView(text(detail, 12, Loft.MUTED), space(5, 0))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(glyph, actionLabel).apply { setOnClickListener { action() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
            setOnClickListener { action() }
        }
    }

    fun append(page: LinearLayout, node: View, separator: Boolean = true) = with(activity) {
        if (separator && page.childCount > 0) divider(page, 0, 16)
        page.addView(node, space(0, 16))
    }
}
