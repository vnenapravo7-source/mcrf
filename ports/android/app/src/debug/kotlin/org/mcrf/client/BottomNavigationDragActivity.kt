package org.mcrf.client

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only input host. Does not construct stores, randomize appearance or start services. */
class BottomNavigationDragActivity : Activity() {
    lateinit var navigation: BottomNavigationView
    var chosen = 0
    var commits = 0
    var variant = BottomNavigationStyle.FLOATING
    var motion = true
    var count = 4
    var beforeNavigate: (() -> Unit)? = null
    lateinit var pageContent: FrameLayout
        private set
    private lateinit var title: TextView
    override fun onCreate(state: Bundle?) {
        check(android.app.Application.getProcessName() == "org.mcrf.client:navigationFixture") {
            "Navigation fixture must run only in its dedicated process"
        }
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Loft.BACKGROUND); clipChildren = false; clipToPadding = false
        }
        pageContent = FrameLayout(this).apply { setBackgroundColor(Loft.BACKGROUND) }
        root.addView(pageContent, LinearLayout.LayoutParams(-1, 0, 1f))
        title = TextView(this).apply {
            textSize = 24f; setTextColor(Loft.INK); gravity = Gravity.CENTER
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        pageContent.addView(title, FrameLayout.LayoutParams(-1, -1))
        // Actual page pixels reach the base bar, including underneath its raised
        // circle. The screenshot must show this stripe behind the protrusion.
        val pageStripe = TextView(this).apply {
            text = "PAGE CONTENT · circle overlays this row"; textSize = 12f
            gravity = Gravity.CENTER; setBackgroundColor(0xff8bbac3.toInt()); setTextColor(0xff102b30.toInt())
        }
        pageContent.addView(pageStripe, FrameLayout.LayoutParams(-1, (40 * resources.displayMetrics.density).toInt(), Gravity.BOTTOM))
        navigation = BottomNavigationView(this, { _, color ->
            getDrawable(android.R.drawable.ic_menu_view)!!.mutate().apply { setTint(color) }
        }) { motion }
        root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        setContentView(root); bind()
    }
    fun bind() {
        val names = listOf("Главная — подключение", "Белые списки VPN", "Маршрутизация соединений", "Журнал подключений").take(count)
        title.text = "${BottomNavigationStyle.choices[variant]} · ${names[chosen]}"
        navigation.submit(names.mapIndexed { index, name ->
            NavigationTab("tab-$index", name, "home", name, chosen == index)
        }, variant) { id -> beforeNavigate?.invoke(); chosen = id.removePrefix("tab-").toInt(); commits++; bind() }
    }
}
