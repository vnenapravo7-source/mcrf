package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** In-memory fixture; no appearance preferences, profiles or VPN state are changed. */
class FloatingNotchInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = StringBuilder()
        var activity: MainActivity? = null
        val wasDark = Loft.dark
        var code = Activity.RESULT_OK
        fun main(block: () -> Unit) {
            val error = AtomicReference<Throwable?>()
            runOnMainSync { try { block() } catch (failure: Throwable) { error.set(failure) } }
            error.get()?.let { throw it }; waitForIdleSync()
        }
        fun bitmap(nav: BottomNavigationView): Bitmap {
            val overhang = (48 * nav.resources.displayMetrics.density).toInt()
            return Bitmap.createBitmap(nav.width, nav.height + overhang, Bitmap.Config.ARGB_8888)
                .also { val canvas = Canvas(it); canvas.translate(0f, overhang.toFloat()); nav.draw(canvas) }
        }
        // Above the bar only the floating circle is drawn. The fill is a theme gradient,
        // so detect rendered alpha instead of requiring one exact accent colour.
        fun circle(nav: BottomNavigationView): Pair<Float, Int> {
            val image = bitmap(nav)
            var minX = image.width; var maxX = -1; var minY = image.height
            val limit = (48 * nav.resources.displayMetrics.density).toInt()
            for (y in 0 until limit) for (x in 0 until image.width) if (Color.alpha(image.getPixel(x, y)) != 0) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y)
            }
            image.recycle(); check(maxX >= minX) { "Floating circle was not drawn" }
            return (minX + maxX + 1) / 2f to minY - limit
        }
        try {
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra("test_no_prompts", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = host
            val deadline = System.nanoTime() + 15_000_000_000L
            while (host.working() && System.nanoTime() < deadline) Thread.sleep(50)
            check(!host.working())
            // Exercise the real page render/click path before the in-memory variants.
            main { host.navigate("Главная") }
            Thread.sleep(900)
            val retained = host.window.decorView.findViewWithTag<BottomNavigationView>("main-navigation")
            val clockField = BottomNavigationView::class.java.getDeclaredField("playback").apply { isAccessible = true }
            fun state() = listOf("targetId", "centerX", "style", "tabs", "slotWidths", "toWidths").joinToString { name ->
                val field = BottomNavigationView::class.java.getDeclaredField(name).apply { isAccessible = true }
                "$name=${field.get(retained)}"
            } + " enabled=${android.animation.ValueAnimator.areAnimatorsEnabled()}"
            val oldParent = retained.parent
            main {
                retained.findViewWithTag<View>("nav-Маршрутизация").performClick()
                check(host.window.decorView.findViewWithTag<View>("main-navigation") === retained)
                check(retained.parent === oldParent)
                check((clockField.get(retained) as BottomNavigationPlayback).progress == 0f) { state() }
            }
            Thread.sleep(100)
            main {
                val p = (clockField.get(retained) as BottomNavigationPlayback).progress
                check(p > 0 && p < 1) { "Real page render skipped animation: $p" }
                retained.findViewWithTag<View>("nav-Логи").performClick()
                check((clockField.get(retained) as BottomNavigationPlayback).progress == 0f)
            }
            Thread.sleep(900)
            report.append("PASS real MainActivity page render/click and rapid retarget\n")
            for (dark in listOf(false, true)) for (count in 3..4) {
                lateinit var root: FrameLayout
                lateinit var nav: BottomNavigationView
                var selected = 0
                var clicks = 0
                var motion = false
                var style = BottomNavigationStyle.FLOATING
                val ids = if (count == 3) listOf(ConnectionAppearance.TAB, "Маршрутизация", "Логи")
                    else listOf("Главная", ClientSettingsNavigation.WHITELISTS, "Маршрутизация", "Логи")
                fun items() = ids.mapIndexed { n, id -> NavigationTab(id,
                    if (id == ConnectionAppearance.TAB) "VPN" else ClientSettingsNavigation.tabTitle(id),
                    when (id) { "Маршрутизация" -> "routes"; "Логи" -> "logs"; else -> "home" }, id, selected == n) }
                fun bind() { nav.submit(items(), style) { id ->
                    clicks++; selected = ids.indexOf(id); bind()
                } }
                main {
                    Loft.apply(dark)
                    val large = host.createConfigurationContext(Configuration(host.resources.configuration).apply { fontScale = 2f })
                    root = FrameLayout(host).apply {
                        clipChildren = false; clipToPadding = false
                        setBackgroundColor(Loft.BACKGROUND)
                        setPadding(0, host.dp(24), 0, host.dp(32))
                    }
                    nav = BottomNavigationView(large, host::icon) { motion }
                    root.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    host.setContentView(root); bind()
                }
                main {
                    check(nav.height == host.dp(FloatingNotchMotion.BAR_HEIGHT))
                    ids.forEach { id ->
                        val cell = nav.findViewWithTag<View>("nav-$id")
                        check(cell.width >= host.dp(48) && cell.height >= host.dp(48))
                        check(!cell.contentDescription.isNullOrBlank() && cell.isFocusable)
                    }
                    val expected = FloatingNotchMotion.center(nav.width.toFloat(), 0, count, 0f)
                    check(abs(circle(nav).first - expected) < 2)
                    check(circle(nav).second < 2)
                    check(nav.bottom <= root.height - root.paddingBottom)
                    nav.findViewWithTag<View>("nav-${ids.last()}").performClick()
                    check(clicks == 1 && nav.findViewWithTag<View>("nav-${ids.last()}").isSelected)
                    check(abs(circle(nav).first - FloatingNotchMotion.center(nav.width.toFloat(), count - 1, count, 0f)) < 2)
                    if (count == 3) {
                        selected = 0; bind()
                        nav.submit(items().map { if (it.id == ConnectionAppearance.TAB)
                            it.copy(title = "БС", description = "Подключения · БС") else it }, style) {}
                        check(nav.findViewWithTag<View>("nav-${ids[0]}").contentDescription == "Подключения · БС")
                    }
                    motion = true; selected = 1; bind()
                }
                Thread.sleep(70)
                main {
                    val before = circle(nav).first
                    selected = count - 1; bind()
                    check(abs(before - circle(nav).first) < 2) { "Retarget jumped" }
                }
                Thread.sleep(900)
                main {
                    check(circle(nav).second < 2)
                    // Each reference style must render a distinct silhouette without changing tabs.
                    motion = false
                }
                val signatures = mutableSetOf<Int>()
                for (variant in BottomNavigationStyle.choices.keys) {
                    main { style = variant; bind() }
                    main {
                        val image = bitmap(nav)
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        signatures.add(pixels.contentHashCode())
                        File(targetContext.getExternalFilesDir(null), "navbar-${if (dark) "dark" else "light"}-$count-$variant.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                        check(nav.childCount == count)
                        if (variant == BottomNavigationStyle.EXPANDING)
                            check(nav.getChildAt(selected).width > nav.getChildAt((selected + 1) % count).width)
                    }
                    // Exercise real frames for every style, including a host detach/reattach
                    // and a second tap while the first transition is still running.
                    var startHash = 0
                    var middleHash = 0
                    fun signature():Int = bitmap(nav).let { image ->
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                        pixels.contentHashCode().also {image.recycle()}
                    }
                    main {
                        motion = true; selected = (selected + 1) % count; bind()
                        // A synchronous page rebuild after submit used to spend the
                        // animation before the user could see it. No paint => no time.
                        Thread.sleep(600)
                        bind() // same selection, as during a full MainActivity render
                        val state = BottomNavigationView::class.java.getDeclaredField("playback").apply { isAccessible = true }
                        check((state.get(nav) as BottomNavigationPlayback).progress == 0f) {
                            "$variant consumed animation during page rebuild"
                        }
                        startHash = signature()
                        root.removeView(nav)
                        root.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    }
                    Thread.sleep(90)
                    main {
                        middleHash = signature()
                        check(startHash != middleHash) { "$variant did not animate after reattachment" }
                        bitmap(nav).let { image ->
                            File(targetContext.getExternalFilesDir(null), "navbar-motion-${if (dark) "dark" else "light"}-$count-$variant.png")
                                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            image.recycle()
                        }
                        selected = (selected + 1) % count; bind()
                        check(middleHash != 0)
                    }
                    Thread.sleep(900)
                    main {
                        check(middleHash != signature()) { "$variant never reached the retargeted tab" }
                        // Zero motion settles synchronously, with no late animation frame.
                        motion = false; selected = (selected + 1) % count; bind()
                        startHash = signature()
                    }
                    Thread.sleep(90)
                    main { check(startHash == signature()) { "$variant moved with animations disabled" } }
                }
                check(signatures.size == 4) { "Reference styles must be visually distinct" }
                report.append("PASS ${if (dark) "dark" else "light"}/$count: four styles, rendered circle, accessible tabs, large font, insets, instant motion, retarget\n")
            }
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED; report.append("FAIL ").append(error.stackTraceToString())
        } finally {
            main { Loft.apply(wasDark); activity?.finish() }
            finish(code, Bundle().apply { putString("stream", report.toString()) })
        }
    }
}
