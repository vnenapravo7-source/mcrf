package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Opt-in CSQTT-style passive resource HUD, owned by the standalone Beta entry.
 * Original attribution / required notices are retained in ResourceMonitorModel.kt.
 *
 * INTEGRATION (main owns existing-file edits; execution waits for the common gate):
 * - Keep one `private val resourceMonitor = ResourceMonitorController()` per Activity.
 * - After each render creates its viewport FrameLayout: `resourceMonitor.attach(this, viewport)`.
 *   attach() unbinds the old viewport. Explicit detach() before replacement is also safe.
 * - Forward onResume()/onPause()/onStop() to the same methods; onDestroy(): close(). Automatic
 *   Activity callbacks provide a safety net, but explicit hooks handle attachment after resume.
 * - An explicit user toggle calls setEnabled(true/false); the HUD opens the same main window.
 *   Construction and attach never enable monitoring. Opening the shortcut resumes an explicitly
 *   saved hidden mode. Mini size is fixed;
 *   legacy resource-monitor/size is ignored without reading, rewriting or clearing preferences.
 * - onStop, onPause, viewport detachment and disabling stop recording at the last observed sample.
 *   The opted-in flag and frozen summary survive detach/rebind; recording never auto-resumes.
 * - close() is terminal. All public methods, including snapshot(), are main-thread-only.
 *
 * In-app sampling stops with the Activity. Explicit system mode is separately owned by
 * ResourceMonitorOverlayService, which retains no Activity and is not stopped by rotation.
 * Its permission / task removal / screen-lock guards terminate its own lifetime.
 */
class ResourceMonitorController : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val deliveryLock = Any()
    private var pendingDelivery = false
    private var acceptingDelivery = false
    private val sampler: ResourceMonitorSampler = ResourceMonitorSampler(changed = ::postRender)
    private var activity: Activity? = null
    private var root: FrameLayout? = null
    private var hud: ResourceMonitorHud? = null
    private var settingsPopup: AlertDialog? = null
    private var metricsView: ResourceMonitorMetricsView? = null
    private var foreground = false
    private var closed = false
    private var usingOverlay = false
    var enabled: Boolean = false
        private set

    private val overlayChanged: () -> Unit = {
        if (ResourceMonitorOverlayService.running) {
            usingOverlay = true
        }
        // The system service owns sampling while active, but never the local
        // visibility toggle. Its samples drive both HUDs through this observer.
        updateSampling()
    }

    private val delivery = Runnable {
        val accepted = synchronized(deliveryLock) {
            pendingDelivery = false
            acceptingDelivery
        }
        if (accepted && canSample()) render()
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = updateSampling()
        override fun onViewDetachedFromWindow(view: View) { detach() }
    }
    private val bounds = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampHud() }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(target: Activity) { if (target === activity) onResume() }
        override fun onActivityPaused(target: Activity) { if (target === activity) onPause() }
        override fun onActivityStopped(target: Activity) { if (target === activity) onStop() }
        override fun onActivityDestroyed(target: Activity) { if (target === activity) close() }
        override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(target: Activity) = Unit
        override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
    }

    fun attach(activity: Activity, root: FrameLayout): ResourceMonitorController {
        mainThread()
        check(!closed) { "Resource monitor is closed" }
        if (this.activity === activity && this.root === root) return this
        val sameActivity = this.activity == null || this.activity === activity
        detach()
        if (!sameActivity) foreground = false
        this.activity = activity
        this.root = root
        ResourceMonitorOverlayService.observe(overlayChanged)
        overlayChanged()
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        root.addOnAttachStateChangeListener(attachment)
        root.addOnLayoutChangeListener(bounds)
        updateSampling()
        return this
    }

    fun setEnabled(value: Boolean) {
        mainThread()
        if (closed) return
        if (value && !ResourceMonitorOverlayService.running) usingOverlay = false
        enabled = value
        updateSampling()
    }

    fun onResume() { mainThread(); if (!closed) { foreground = true; updateSampling() } }
    fun onPause() { mainThread(); foreground = false; updateSampling() }
    fun onStop() { mainThread(); foreground = false; updateSampling() }

    /** Also suitable as an owner's stop callback; preserves the frozen summary. */
    fun stop() { mainThread(); activity?.let { ResourceMonitorOverlayService.stop(it) }; setEnabled(false) }

    private fun currentSampler() = ResourceMonitorOverlayService.currentSampler() ?: sampler
    private fun live() = canSample() || ResourceMonitorOverlayService.running
    fun startRecording() { mainThread(); if (live()) { currentSampler().startRecording(); render() } }
    fun stopRecording() { mainThread(); currentSampler().stopRecording(); render() }
    fun resetRecording() {
        mainThread()
        if (ResourceMonitorOverlayService.running || usingOverlay) ResourceMonitorOverlayService.resetRecording()
        else sampler.resetRecording()
        render()
    }
    fun snapshot(): ResourceMonitorSnapshot {
        mainThread()
        return if (ResourceMonitorOverlayService.running || usingOverlay) ResourceMonitorOverlayService.snapshot() else sampler.snapshot()
    }

    internal fun bindMetrics(view: ResourceMonitorMetricsView?) {
        mainThread()
        metricsView = view
        render()
    }

    // Keep the historical preference key portable; it now controls HUD visibility,
    // never access to the settings window.
    val indicatorHidden:Boolean get()=(activity as? MainActivity)?.prefs?.getBoolean("resourceMonitorQuickStart",false)==true
    fun setIndicatorHidden(hidden:Boolean) {
        mainThread()
        (activity as? MainActivity)?.prefs?.edit()?.putBoolean("resourceMonitorQuickStart",hidden)?.apply()
        updateSampling()
    }
    fun openShortcut() {
        // Opening is an explicit user action. A saved hidden mode must not leave
        // a checked switch paired with a disabled sampler after process restart.
        if (indicatorHidden && !enabled && !ResourceMonitorOverlayService.running && !ResourceMonitorOverlayService.starting) setEnabled(true)
        showSettings()
    }

    fun showSettings() {
        mainThread()
        val host = activity as? MainActivity ?: return
        if (closed || !foreground || host.isFinishing || host.isDestroyed || settingsPopup?.isShowing == true) return
        settingsPopup = ResourceMonitorSettingsUi.show(host, this) { settingsPopup = null }
    }

    fun detach() {
        mainThread()
        stopSampling()
        ResourceMonitorOverlayService.unobserve(overlayChanged)
        activity?.application?.unregisterActivityLifecycleCallbacks(lifecycle)
        root?.removeOnAttachStateChangeListener(attachment)
        root?.removeOnLayoutChangeListener(bounds)
        root = null
        activity = null
    }

    override fun close() {
        mainThread()
        if (closed) return
        detach()
        closed = true
        enabled = false
        foreground = false
        sampler.close()
    }

    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun canSample() = !closed && enabled && foreground &&
        root?.isAttachedToWindow == true && activity?.let { !it.isFinishing && !it.isDestroyed } == true

    private fun updateSampling() {
        if (!canSample()) { stopSampling(dismissDialogs = !foreground); render(); return }
        if (indicatorHidden) removeHud() else if (hud == null) addHud()
        if (ResourceMonitorOverlayService.running) {
            synchronized(deliveryLock) { acceptingDelivery = false }
            sampler.stop()
        } else {
            usingOverlay = false
            synchronized(deliveryLock) { acceptingDelivery = true }
            sampler.start()
        }
        render()
    }

    private fun stopSampling(dismissDialogs: Boolean = true) {
        synchronized(deliveryLock) {
            acceptingDelivery = false
            handler.removeCallbacks(delivery)
            pendingDelivery = false
        }
        sampler.stop()
        if (dismissDialogs) {
            settingsPopup?.dismiss()
            settingsPopup = null
        }
        removeHud()
    }

    private fun removeHud() { (hud?.parent as? ViewGroup)?.removeView(hud); hud = null }

    private fun postRender() {
        synchronized(deliveryLock) {
            if (acceptingDelivery && !pendingDelivery) {
                pendingDelivery = true
                handler.post(delivery)
            }
        }
    }

    private fun addHud() {
        val host = activity ?: return
        val viewport = root ?: return
        hud = ResourceMonitorHud(host, open = ::showSettings, move = { dx, dy ->
            hud?.let { it.translationX += dx; it.translationY += dy; clampHud() }
        }).apply {
            addOnLayoutChangeListener(bounds)
        }
        val width = ResourceMonitorLayout.widthPx(
            viewport.width - viewport.paddingLeft - viewport.paddingRight - dp(16), viewport.resources.displayMetrics.density)
        viewport.addView(hud, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
    }

    private fun clampHud() {
        val view = hud ?: return
        val viewport = root ?: return
        val available = viewport.width - viewport.paddingLeft - viewport.paddingRight - dp(16)
        val width = ResourceMonitorLayout.widthPx(available, viewport.resources.displayMetrics.density)
        if (view.layoutParams.width != width) view.layoutParams = view.layoutParams.apply { this.width = width }
        val minX = viewport.paddingLeft.toFloat()
        val minY = viewport.paddingTop.toFloat()
        val maxX = (viewport.width - viewport.paddingRight - view.width).toFloat().coerceAtLeast(minX)
        val maxY = (viewport.height - viewport.paddingBottom - view.height).toFloat().coerceAtLeast(minY)
        view.x = view.x.coerceIn(minX, maxX)
        view.y = view.y.coerceIn(minY, maxY)
    }

    /** Compatibility entry point; there is only one monitor window. Never enables sampling. */
    fun showDetails() = showSettings()

    private fun render() {
        val value = snapshot()
        hud?.render(value)
        metricsView?.render(value, live())
    }

    private fun dp(value: Int) = (value * (activity?.resources?.displayMetrics?.density ?: 1f)).toInt()
}
