package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural contracts only; these do not replace device layout acceptance. */
class TrafficCaptureUiTest {
    private fun source() = listOf(
        File("src/main/kotlin/org/mcrf/client/TrafficCaptureActivity.kt"),
        File("app/src/main/kotlin/org/mcrf/client/TrafficCaptureActivity.kt")
    ).first { it.isFile }.readText()

    @Test fun themeIsAppliedBeforeInflationAndAllSurfacesUseLoft() {
        val source = source()
        val creation = source.substringAfter("override fun onCreate").substringBefore("private fun updateContext")
        val steps = listOf("UserThemeColorsUi.configure", "getSharedPreferences(\"MainActivity\"",
            "Loft.apply", "setTheme(", "super.onCreate", "setContentView(root)")
        val positions = steps.map { creation.indexOf(it).also { index -> assertTrue(it, index >= 0) } }
        assertEquals(positions.sorted(), positions)
        for (surface in listOf("Loft.rootBackground", "Loft.glass", "Loft.fieldBackground",
            "StandardButtons.style", "Loft.popupBackground", "Loft.INK", "Loft.MUTED"))
            assertTrue(surface, source.contains(surface))
    }

    @Test fun optionalContentStartsCollapsedAndSelectionControlsAreConditional() {
        val source = source()
        assertTrue(source.contains("getBoolean(\"helpExpanded\") ?: false"))
        assertTrue(source.contains("getBoolean(\"eventsExpanded\") ?: false"))
        assertTrue(source.contains("privacy.visibility = if (helpExpanded) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("rows.visibility = if (eventsExpanded) View.VISIBLE else View.GONE"))
        val scope = source.substringAfter("scope.changed").substringBefore("label(\"Таймер\")")
        assertTrue(scope.contains("apps.visibility = if (position == 1) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("picker(TrafficCapturePolicy.presets.map { it.label })"))
        assertFalse(source.contains("Свой интервал"))
        assertFalse(source.contains("private lateinit var custom"))
        for (key in listOf("helpExpanded", "eventsExpanded"))
            assertTrue(source.contains("state.putBoolean(\"$key\", $key)"))
    }

    @Test fun singleScrollContainsHeaderStatusControlsAndHistoryInOrder() {
        val creation = source().substringAfter("override fun onCreate").substringBefore("private fun updateContext")
        assertEquals(1, Regex("ScrollView\\(this\\)").findAll(creation).count())
        assertEquals(1, Regex("root\\.addView\\(").findAll(creation).count())
        assertTrue(creation.contains("addView(content)"))
        val steps = listOf("content.addView(copy(title.toString(), 18f", "content.addView(status)",
            "val controls = card(content)", "controls.addView(start", "val history = card(content)")
        val positions = steps.map { creation.indexOf(it).also { index -> assertTrue(it, index >= 0) } }
        assertEquals(positions.sorted(), positions)
        assertTrue(creation.contains("status = copy(TrafficCaptureService.status, 12f)"))
        assertTrue(creation.contains("setPadding(dp(16), dp(16), dp(16), dp(16))"))
    }

    @Test fun singlePrimaryActionFollowsControlsAndSupportsStopWithoutConsent() {
        val source = source()
        val action = source.substringAfter("start = button(").substringBefore("val history = card(content)")
        assertTrue(action.contains("TrafficCaptureService.busy()"))
        assertTrue(action.contains("setAction(TrafficCaptureService.STOP)"))
        assertTrue(action.contains("else consent()"))
        assertTrue(action.contains("controls.addView(start"))
        assertTrue(action.contains("topMargin = dp(16)"))
        assertFalse(source.contains("root.addView(start"))
        assertEquals(1, Regex("start = button\\(").findAll(source).count())
        assertTrue(source.substringAfter("private fun button(").contains("StandardButtons.style(this, Button(this), label, primary)"))
        assertTrue(source.contains("start.isEnabled = !transitioning"))
    }

    @Test fun layoutUsesConsistentSpacingAndLightDisclosureRows() {
        val source = source()
        val card = source.substringAfter("private fun card(").substringBefore("private fun disclosure()")
        assertTrue(card.contains("setPadding(dp(16), dp(16), dp(16), dp(16))"))
        assertTrue(card.contains("topMargin = dp(16)"))
        val label = source.substringAfter("fun label(value: String)").substringBefore("label(\"Режим\")")
        assertTrue(label.contains("copy(value, 12f, Loft.INK)"))
        assertTrue(label.contains("topMargin = if (controls.childCount == 0) 0 else dp(12)"))
        assertTrue(label.contains("bottomMargin = dp(4)"))
        val disclosure = source.substringAfter("private fun disclosure()").substringBefore("private fun button(")
        for (token in listOf("copy(\"\", 12f, Loft.INK)", "minHeight = dp(48)",
            "gravity = Gravity.CENTER_VERTICAL", "isClickable = true", "isFocusable = true", "selectableItemBackground", "setBackgroundResource(feedback.resourceId)"))
            assertTrue(token, disclosure.contains(token))
        assertTrue(source.contains("private lateinit var help: TextView"))
        assertTrue(source.contains("private lateinit var events: TextView"))
        assertTrue(source.contains("help = disclosure()"))
        assertTrue(source.contains("events = disclosure()"))
        val history = source.substringAfter("val history = card(content)").substringBefore("private fun updateContext")
        assertTrue(history.indexOf("copy(\"Сохранённая запись\", 18f") < history.indexOf("history.addView(actions)"))
        assertTrue(history.indexOf("history.addView(actions)") < history.indexOf("history.addView(events)"))
        assertTrue(history.contains("marginEnd = dp(8)"))
    }

    @Test fun fieldsHaveTouchHeightAndSpinnerLabelsDoNotWrapOrAppendArrows() {
        val source = source()
        val picker = source.substringAfter("private fun picker(").substringBefore("private fun dp(")
        for (token in listOf("minimumHeight = dp(48)", "LinearLayout.LayoutParams(-1, -2)",
            "LayerDrawable", "canvas.drawPath", "setPaddingRelative(dp(12), dp(10), dp(40), dp(10))",
            "setSingleLine(true)", "ellipsize = TextUtils.TruncateAt.END"))
            assertTrue(token, picker.contains(token))
        assertFalse(picker.contains("LinearLayout.LayoutParams(-1, dp(48))"))
        assertTrue(source.contains("controls.addView(apps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })"))
        assertTrue(source.contains("gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; includeFontPadding = false"))
    }

    @Test fun privacyConsentGatesLifecycleAndDataActionsRemainPresent() {
        val source = source()
        val consent = source.substringAfter("private fun consent()").substringBefore("private fun waitRetirement")
        assertTrue(consent.contains("Сохранить время, приложения, адреса и доступные домены"))
        assertFalse(consent.contains("setMessage(TrafficCapturePolicy.PRIVACY"))
        assertTrue(source.contains("privacy = copy(TrafficCapturePolicy.PRIVACY"))
        assertTrue(consent.contains("setPositiveButton(\"Разрешаю запись\")"))
        for (token in listOf("FLAG_SECURE", "TrafficCapturePolicy.admit", "Admission.FOREIGN_VPN",
            "McrfVpnService.cleanupPending()", "VpnService.prepare(this)", "POST_NOTIFICATIONS",
            "Intent.ACTION_CREATE_DOCUMENT", "TrafficCaptureService.DELETE", "TrafficJournalStore.local(this).export()",
            "onSaveInstanceState", "onActivityResult", "main.removeCallbacksAndMessages(null)", "io.shutdown()"))
            assertTrue(token, source.contains(token))
    }

    @Test fun journalShowsLabelsSeparateAddressesAndExplicitBulkActions() {
        val source = source()
        for (label in listOf("В правило отмеченное", "Все в правило", "Очистить")) assertTrue(source.contains("button(\"$label\")"))
        assertTrue(source.contains("packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))"))
        val row = source.substringAfter("private fun captureRow(").substringBefore("private fun showEntry(")
        assertTrue(row.contains("displayedLabels[it]"))
        assertTrue(row.contains("copy(entry.domain ?: \"Домен неизвестен\""))
        assertTrue(row.contains("copy(entry.ip, 12f)"))
        assertTrue(row.contains("CheckBox("))
        assertTrue(row.contains("checkedEvents"))
        assertTrue(source.contains("store.editAtRevision(revision, generation)"))
        assertTrue(source.contains("StyleSpan(android.graphics.Typeface.BOLD)"))
    }
}
