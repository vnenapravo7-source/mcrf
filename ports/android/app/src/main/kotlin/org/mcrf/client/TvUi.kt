package org.mcrf.client

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/** Presentation rules for Android TV and compatible devices. */
internal object TvUi {
    fun enabled(context: Context): Boolean =
        BuildConfig.FORCE_TV_UI ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    const val SHOW_AUTO_BS = "tv-show-auto-bs"
    const val SHOW_VK_TURN = "tv-show-vk-turn"

    fun showAutoBs(activity: MainActivity) = activity.prefs.getBoolean(SHOW_AUTO_BS, false)
    fun showVkTurn(activity: MainActivity) = activity.prefs.getBoolean(SHOW_VK_TURN, false)

    fun settingsVisible(activity: MainActivity, page: String) = when (page) {
        ClientSettingsNavigation.DEPLOY -> false
        ClientSettingsNavigation.VK -> showVkTurn(activity)
        else -> true
    }

    fun firstAction(root: View): View? {
        val controls = ArrayList<View>()
        root.addFocusables(controls, View.FOCUS_FORWARD)
        return controls.firstOrNull(::isAction)
    }

    private fun isAction(view: View) = view.isEnabled && view.isShown &&
        (view.isClickable || view.isLongClickable || view is android.widget.EditText)

    fun outlineFocus(root: View) {
        if (!enabled(root.context)) return
        val density = root.resources.displayMetrics.density
        val ring = FocusRing(density)
        val controlBounds = Rect()
        val visibleBounds = Rect()
        val controlLocation = IntArray(2)
        val rootLocation = IntArray(2)
        root.overlay.add(ring)
        root.viewTreeObserver.addOnPreDrawListener {
            val focused = root.findFocus()
            val target = if (focused is android.widget.ListView) focused.selectedView else focused
            controlBounds.setEmpty()
            if (root.hasWindowFocus() && target != null && target !== root &&
                (isAction(target) || focused is android.widget.ListView && target.isShown && target.isEnabled)
            ) {
                focused?.defaultFocusHighlightEnabled = false
                if (target.getLocalVisibleRect(visibleBounds) &&
                    visibleBounds.width() == target.width && visibleBounds.height() == target.height
                ) {
                    root.getLocationInWindow(rootLocation)
                    target.getLocationInWindow(controlLocation)
                    val x = controlLocation[0] - rootLocation[0]
                    val y = controlLocation[1] - rootLocation[1]
                    controlBounds.set(x, y, x + target.width, y + target.height)
                    if (target.parent is BottomNavigationView) {
                        val maximum = (88 * density).toInt()
                        if (target.width > maximum) controlBounds.inset((target.width - maximum) / 2, 0)
                    }
                }
            }
            if (ring.bounds != controlBounds) ring.bounds = controlBounds
            true
        }
    }

    private class FocusRing(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frame = RectF()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            frame.set(bounds)
            frame.inset(1.5f * density, 1.5f * density)
            if (frame.isEmpty) return
            val radius = minOf(14 * density, frame.height() / 2)
            paint.style = Paint.Style.FILL
            paint.color = 0x206A55D8
            canvas.drawRoundRect(frame, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.color = if (android.graphics.Color.luminance(Loft.INK) > .5f) Color.WHITE else 0xFF352979.toInt()
            paint.strokeWidth = 2 * density
            canvas.drawRoundRect(frame, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated by Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
