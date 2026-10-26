package org.mcrf.client

import android.app.Instrumentation
import android.content.res.Configuration
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.function.Consumer
import kotlin.math.roundToInt

/** Real Android layout measurement of the shared chrome with a plain synthetic page.
 * NOT a protected Activity/WebView screenshot or a substitute for visual acceptance.
 * No window/provider/cookie/native process, theme preferences or VPN is changed. */
object Post55OpenFluxLayoutDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        data class Case(val width: Int, val height: Int, val scale: Float, val pageVisible: Boolean)
        fun measure(case: Case, resize: Boolean): Boolean {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = case.scale })
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
            val root = OpenFluxVerificationLayout(context).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
            root.details.addView(TextView(context).apply {
                text = "Проверка OpenFlux\n\nСайт: https://captcha.invalid\n\n" +
                    "Пояснение ручной проверки и отдельного хранилища. ".repeat(24)
            })
            root.actions.addView(TextView(context).apply { text = "Страница не загрузилась. Можно повторить открытие сайта." })
            fun button(label: String) = StandardButtons.style(context, Button(context), label).apply { maxLines = 3; ellipsize = null }
            root.actions.addView(button("Открыть сайт"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            val row = LinearLayout(context).apply { isBaselineAligned = false }
            val cancel = button("Отмена"); val send = button("Передать ответ")
            row.addView(cancel, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
            row.addView(send, LinearLayout.LayoutParams(0, -1, 1f))
            root.actions.addView(row)
            val page = TextView(context).apply { text = "Synthetic page viewport"; visibility = if (case.pageVisible) View.VISIBLE else View.GONE }
            root.setPage(page)
            fun layout(height: Int): Boolean {
                root.measure(View.MeasureSpec.makeMeasureSpec(dp(case.width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val footer = root.actionScroll
                if (root.measuredWidth != dp(case.width) || root.measuredHeight != dp(height) ||
                    footer.height <= 0 || footer.bottom > root.height - root.paddingBottom ||
                    root.detailScroll.height <= 0 || (case.pageVisible && page.height <= 0) ||
                    cancel.top != send.top || cancel.bottom != send.bottom || send.width <= 0) return false
                val textLayout = send.layout ?: return false
                if (textLayout.lineCount !in 1..send.maxLines ||
                    textLayout.getLineEnd(textLayout.lineCount - 1) != send.text.length) return false
                footer.scrollTo(0, root.actions.height)
                // Every part of the control content is reachable by scrolling; this
                // does not claim every large-font button fits in one tiny viewport.
                return root.actions.bottom - footer.scrollY <= footer.height
            }
            return layout(case.height) && (!resize || layout(240))
        }
        val cases = listOf(Case(360, 640, 1f, false), Case(360, 640, 1f, true),
            Case(240, 320, 2f, true), Case(640, 240, 2f, true), Case(360, 640, 1.3f, true))
        instrumentation.runOnMainSync {
            cases.forEachIndexed { index, case ->
                check(measure(case, index == cases.lastIndex)) { "OpenFlux synthetic layout bounds failed case$index" }
                passed.accept("Synthetic OpenFlux chrome case$index: measured bounds, action row and scrolling; not visual/WebView acceptance")
            }
        }
    }
}
