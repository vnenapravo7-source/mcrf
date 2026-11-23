package org.mcrf.client

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch

/** Presentation settings; the requested settings hold action works by default. */
internal object AppearanceMotion {
    const val SLIDER = "appearanceTurnSlider"
    const val GRID = "appearanceAmoledLoadingGrid"
    const val EYE = "appearanceParticleEye"
    const val MATH = "appearanceMathText"
    const val MATH_BODY = "appearanceMathBodyText"
    const val CLASSIC = "classic"
    const val JELLY = "jelly"
    const val BUDGET = "budget"
    const val DEFAULT_SLIDER = BUDGET

    data class Action(val label: String, val icon: String, val run: () -> Unit)

    private fun enabled(a: MainActivity, key: String) = !TvUi.enabled(a) && a.prefs.getBoolean(key, false)

    fun settings(a: MainActivity): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        if (TvUi.enabled(a)) {
            visibility = View.GONE
            return@apply
        }
        val styles = listOf(CLASSIC to "Обычный", JELLY to "Упругий Jelly", BUDGET to "Диаграмма")
        val selected = styles.indexOfFirst {it.first == a.prefs.getString(SLIDER, DEFAULT_SLIDER)}
            .takeIf {it>=0} ?: styles.lastIndex
        addView(a.text("Регулятор потоков TURN",14,Loft.INK),a.space(0,4))
        addView(AppearanceSettingsUi.choice(a,"Регулятор потоков TURN",styles.map {it.second},selected,"appearance-turn-slider-style") {index->
            a.prefs.edit().putString(SLIDER,styles[index].first).apply()
            a.render()
        },a.space(0,8))
        addView(Switch(a).apply {
            tag = GRID
            text = "Пиксельная загрузка AMOLED"
            isChecked = enabled(a, GRID)
            a.tune(this)
            setOnCheckedChangeListener { _, checked ->
                a.prefs.edit().putBoolean(GRID, checked).apply()
                a.render()
            }
        }, a.space(0, 0))
    }

    fun pixelLoading(a: MainActivity) = enabled(a, GRID) && HomeUiTheme.amoled

    fun loading(a: MainActivity): View {
        if (!pixelLoading(a)) {
            return ProgressBar(a).apply { tag = "catalogue-loading-progress" }
        }
        return BeautifulLoadingGrid(a).apply {
            tag = "catalogue-loading-progress"
            minimumHeight = a.dp(18)
        }
    }

    fun eye(a: MainActivity): EyeParticleButton? = if (a.orbitEnabled() && enabled(a, EYE)) EyeParticleButton(a) else null

    fun bindSettingsPill(
        a: MainActivity,
        anchor: View,
        actions: List<Action>,
        opensDown: Boolean? = null,
        onTrackingChanged: (Boolean) -> Unit = {},
    ) {
        if (TvUi.enabled(a) || actions.isEmpty()) {
            anchor.setOnTouchListener(null)
            anchor.setOnLongClickListener(null)
            anchor.isLongClickable = false
            return
        }
        AppearanceMotionPill.bind(a, anchor, actions, opensDown, onTrackingChanged)
    }

    fun bindNavigationPill(a: MainActivity, navigation: BottomNavigationView, opensDown: Boolean) {
        val anchor = navigation.findViewWithTag<View>("nav-${ClientSettingsNavigation.ROOT}") ?: return
        bindSettingsPill(a, anchor, quickSettingsActions(a), opensDown, navigation::setQuickSettingsTracking)
    }

    fun quickSettingsActions(a: MainActivity): List<Action> = with(ClientSettingsNavigation) {
        listOf(
            APPEARANCE to "moon",
            BEHAVIOR to "shield",
            VK to "vk",
            UPDATES to "refresh",
            DEPLOY to "servers",
            VPN to "proxy",
            ADVANCED to "cog",
            DNS to "routes",
            BETA to "gear",
            DIAGNOSTICS to "ping",
        ).filter { (page, _) -> a.quickSettingsDestinationVisible(page) }
            .map { (page, icon) -> Action(page, icon) { a.navigate(page) } }
    }
}

internal abstract class AppearanceMotionView(context: Context) : View(context) {
    private val visibleBounds = Rect()
    protected fun motionVisible() =
        motionStyleAnimationsEnabled(context) && isAttachedToWindow && isShown &&
            windowVisibility == VISIBLE && getLocalVisibleRect(visibleBounds)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
}

