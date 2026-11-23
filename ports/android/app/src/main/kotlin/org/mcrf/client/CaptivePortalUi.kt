package org.mcrf.client

import android.view.View
import android.widget.LinearLayout
import android.widget.Switch

object CaptivePortalUi {
    fun settings(activity: MainActivity, host: LinearLayout) = with(activity) {
        host.addView(Switch(this).apply {
            text = "Детектор captive portal"; tag = "beta-captive-portal"; tune(this)
            isChecked = prefs.getBoolean(CaptivePortalPolicy.KEY, false)
            var restoring = false
            setOnCheckedChangeListener { _, value -> if (!restoring) {
                if (!prefs.edit().putBoolean(CaptivePortalPolicy.KEY, value).commit()) {
                    restoring = true; isChecked = !value; restoring = false
                    toast("Не удалось сохранить настройку")
                }
                ProbeCoordinator.presentationChanged()
            } }
        }, space(0, 12))
        host.addView(text("Определяет сеть со страницей входа. Ручная проверка отправляет HTTPS-запрос Google; страница входа открывается по кнопке.", 13, Loft.MUTED), space(0, 12))
        host.addView(statusCard(this), space(0, 16))
    }

    /** Compatibility for the old home slot; all detector controls now live in Beta. */
    fun card(activity:MainActivity):View=View(activity).apply {visibility=View.GONE}

    private fun statusCard(activity: MainActivity): View = with(activity) {
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "captive-portal-card"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val label = text("", 13, Loft.INK)
        val check = button("Проверить доступ в интернет", false).apply { minimumHeight = dp(48) }
        val login = button("Открыть страницу входа", false).apply {
            minimumHeight = dp(48)
            setOnClickListener { try { CaptivePortalMonitor.openLogin(this@with) }
                catch (_: Exception) { toast("Откройте настройки сети Android и выполните вход") } }
        }
        host.addView(label, space(0, 12)); host.addView(check, space(0, 12)); host.addView(login)
        fun refresh() {
            host.visibility = if (CaptivePortalMonitor.enabled(this)) View.VISIBLE else View.GONE
            val state = CaptivePortalMonitor.state(this)
            label.text = CaptivePortalPolicy.label(state)
            login.visibility = if (state == CaptivePortalState.PORTAL || state == CaptivePortalState.POSSIBLE_PORTAL) View.VISIBLE else View.GONE
        }
        check.setOnClickListener {
            check.isEnabled = false
            if (!CaptivePortalMonitor.probe(this) { runOnUiThread { check.isEnabled = true; refresh() } }) check.isEnabled = true
        }
        var lease: AutoCloseable? = null
        val expiryRefresh = object : Runnable {
            override fun run() {
                refresh()
                if (host.isAttachedToWindow) host.postDelayed(this, 5000)
            }
        }
        val observer: () -> Unit = { host.post { refresh() } }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                lease = CaptivePortalMonitor.watch(this@with); ProbeCoordinator.observe(observer); refresh()
                host.postDelayed(expiryRefresh, 5000)
            }
            override fun onViewDetachedFromWindow(view: View) {
                ProbeCoordinator.unobserve(observer); lease?.close(); lease = null
                host.removeCallbacks(expiryRefresh)
            }
        })
        refresh(); host
    }
}
