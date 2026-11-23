package org.mcrf.client

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout

/** UI only: the owner supplies loaded metadata and persists through its own worker. */
internal object VkTurnSubnetsUi {
    /** Compact VkTurn entry with both the selected CIDR and independent IP count. */
    fun row(
        activity: MainActivity,
        selection: VkTurnSubnets.Selection,
        onSelect: (VkTurnSubnets.Selection) -> Unit,
    ): View = with(activity) {
        var current = VkTurnSubnets.validate(selection)
        val summary = text(VkTurnSubnets.summary(current), 13, Loft.MUTED).apply {
            tag = "vk-turn-subnet-summary"
        }
        LinearLayout(this).apply {
            tag = "vk-turn-subnet-row"
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CompactUi.choiceBackground(activity)
            addView(text("Подсеть TURN", 14, Loft.INK), space(0, 4))
            addView(summary, space(0, 0))
            contentDescription = "Подсеть TURN · ${summary.text}"
            isFocusable = true
            setOnClickListener {
                show(activity, current) { next ->
                    onSelect(next)
                    current = next
                    summary.text = VkTurnSubnets.summary(next)
                    contentDescription = "Подсеть TURN · ${summary.text}"
                }
            }
        }
    }

    /** Bounded rounded popup, using the app's native picker without text/IME input.
     * Cancel, outside tap and Back leave the owner's state unchanged.
     */
    fun show(
        activity: MainActivity,
        selection: VkTurnSubnets.Selection,
        onSelect: (VkTurnSubnets.Selection) -> Unit,
    ): AlertDialog = with(activity) {
        val initial = VkTurnSubnets.validate(selection)
        val subnetIds = listOf("") + VkTurnSubnets.pools.map { it.id }
        val subnetLabels = listOf("Автоматически") + VkTurnSubnets.pools.map { it.cidr }
        val subnet = picker(subnetLabels.toTypedArray(), subnetIds.indexOf(initial.subnetId)).apply {
            tag = "vk-turn-subnet-picker"
            contentDescription = "Подсеть TURN"
            CompactUi.outline(activity, this)
        }
        val count = picker(
            VkTurnSubnets.ipLimits.map { if (it == "ALL") "Все" else it }.toTypedArray(),
            VkTurnSubnets.ipLimits.indexOf(initial.ipLimit),
        ).apply {
            tag = "vk-turn-subnet-count"
            contentDescription = "Количество IP"
            CompactUi.outline(activity, this)
        }
        val message = text("", 12, Loft.MUTED).apply {
            tag = "vk-turn-subnet-status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            // Full-width CIDR picker avoids truncating network labels on narrow screens.
            addView(text("Подсеть", 12, Loft.MUTED), space(0, 6))
            addView(subnet, space(0, 12))
            addView(field("Количество IP", count), space(0, 12))
            addView(message, space(0, 0))
        }
        val title = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(12), 0)
            addView(text("Подсеть TURN", 18, Loft.INK).apply { setAccessibilityHeading(true) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton("info", "Источник списка TURN и лицензия").apply {
                setOnClickListener { alert("CSQTT · список TURN", VkTurnSubnets.UPSTREAM_NOTICE) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val dialog = ThemedDialogBuilder(activity, .72f)
            .setCustomTitle(title)
            .setView(CompactUi.scroll(activity, body, .48f))
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()

        fun draft() = VkTurnSubnets.Selection(
            subnetIds[subnet.selectedItemPosition],
            VkTurnSubnets.ipLimits[count.selectedItemPosition],
        )
        fun refresh() {
            val result = runCatching { VkTurnSubnets.validate(draft()) }
            message.setTextColor(if (result.isSuccess) Loft.MUTED else Loft.ACCENT)
            message.text = result.fold(
                onSuccess = {
                    if (it.subnetId.isEmpty()) "Адреса из ответа VK. Применится при следующем подключении CSQTT 2.5."
                    else "${VkTurnSubnets.summary(it)} · Применится при следующем подключении CSQTT 2.5."
                },
                onFailure = { it.message ?: "Проверьте подсеть и количество IP." },
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = result.isSuccess
        }
        subnet.onItemSelectedListener = selectedListener { refresh() }
        count.onItemSelectedListener = selectedListener { refresh() }
        dialog.setOnShowListener {
            refresh()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val next = VkTurnSubnets.validate(draft())
                    onSelect(next)
                    dialog.dismiss()
                } catch (error: IllegalArgumentException) {
                    message.setTextColor(Loft.ACCENT)
                    message.text = error.message ?: "Проверьте подсеть и количество IP."
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog
    }
}
