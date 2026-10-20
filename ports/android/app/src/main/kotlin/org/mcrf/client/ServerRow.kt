package org.mcrf.client

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.util.function.Consumer

/** One shared list row, not a card and toolbar for every connection. */
class ServerRow @JvmOverloads constructor(
    activity: MainActivity, private val entry: ServerImport.Server, private var chosen: Boolean, marked: Boolean,
    delay: Long?, onSelect: Runnable, onAuto: Consumer<Boolean>, onEdit: Runnable, autoEnabled: Boolean = true, autoPolicy: String = AutoSelection.LATENCY, goodLimit: Long = PingStatus.GOOD_LIMIT_MS
) : LinearLayout(activity) {
    private lateinit var ping: TextView
    private lateinit var check: CheckBox
    private lateinit var title:TextView
    fun showChosen(value:Boolean) {
        if(chosen==value)return
        chosen=value;isSelected=value
        val wrapped=parent?.parent is ServerSwipeRow
        ((background as? RippleDrawable)?.getDrawable(0) as? android.graphics.drawable.ColorDrawable)?.color=
            if(value&&!wrapped)Loft.translucent(Loft.selection())else Color.TRANSPARENT
        title.setTextColor(if(value)Loft.ACCENT else Loft.INK)
        updateDescription()
    }
    private fun updateDescription() {
        contentDescription="${entry.name}, ${entry.label()}, ${entry.host}, ${PingStatus.description(shownDelay,shownGoodLimit)}" + if(chosen) ", выбран" else ""
        (parent?.parent as? ServerSwipeRow)?.refreshPresentation()
    }
    private var automaticMode=autoEnabled
    private var confirmedMark=marked
    private var restoringMark=false
    fun showAuto(marked:Boolean,enabled:Boolean) {
        confirmedMark=marked;automaticMode=enabled;restoringMark=true
        try {check.isChecked=marked;check.visibility=if(enabled)View.VISIBLE else View.GONE}
        finally {restoringMark=false}
    }
    fun restoreAuto()=showAuto(confirmedMark,automaticMode)
    private var shownDelay=delay
    private var shownGoodLimit=goodLimit
    fun showDelay(delay: Long?,goodLimit: Long=shownGoodLimit) {
        if(shownDelay==delay&&shownGoodLimit==goodLimit)return
        shownDelay=delay;shownGoodLimit=goodLimit
        ping.text=PingStatus.label(delay)
        ping.setTextColor(Loft.pingColor(delay,goodLimit))
        ping.contentDescription=PingStatus.description(delay,goodLimit)
        updateDescription()
        ping.invalidate()
    }
    init {
        orientation = HORIZONTAL
        tag="server-row-${entry.id}"
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = activity.dp(72)
        setPadding(activity.dp(4), activity.dp(8), activity.dp(4), activity.dp(8))
        // Rows share the group's outer rounded card. A second rounded rectangle on
        // the selected row clashes with the straight dividers and looks like a crooked frame.
        // Selection changes tint/text only, never row geometry or its padding.
        background = Loft.rowBackground(activity,chosen)
        isSelected = chosen
        contentDescription = "${entry.name}, ${entry.label()}, ${entry.host}, ${PingStatus.description(delay,goodLimit)}" + if (chosen) ", выбран" else ""
        check = CheckBox(activity).apply {
            buttonTintList = ColorStateList.valueOf(Loft.ACCENT)
            isChecked = marked
            visibility = if (autoEnabled) View.VISIBLE else View.GONE
            jumpDrawablesToCurrentState()
            isEnabled = AutoSelection.eligible(entry,autoPolicy)
            contentDescription = "Использовать ${entry.name} в автовыборе"
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, checked -> if(!restoringMark)onAuto.accept(checked) }
        }
        addView(check, LayoutParams(activity.dp(48), activity.dp(48)))
        setOnClickListener {onSelect.run()}
        val labels = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(activity.dp(4), 0, activity.dp(8), 0)
            title=activity.text(entry.name, 15, if(chosen)Loft.ACCENT else Loft.INK).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            }
            addView(title)
            val engine = if (entry.needsEngine()) " · доп. движок" else ""
            val endpoint=when {
                entry.protocol in SwgProfile.TYPES&&entry.carrier!=null->entry.host
                entry.protocol=="openflux"&&entry.carrier!=null->{
                    val transports=entry.carrier.optJSONArray("transports")
                    (0 until (transports?.length()?:0)).mapNotNull {transports?.optJSONObject(it)?.optString("type")?.takeIf(String::isNotBlank)}.distinct().joinToString("/")
                }
                else->""
            }
            val listLabel=entry.label()+(if(endpoint.isNotBlank())" · $endpoint"else "")
            addView(activity.text("$listLabel$engine", 11, Loft.MUTED).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
                setPadding(0, activity.dp(5), 0, 0)
            })
        }
        addView(labels, LayoutParams(0, -2, 1f))
        ping=TextView(activity).apply {
            text = PingStatus.label(delay); textSize = 12f; setTextColor(Loft.pingColor(delay,goodLimit)); includeFontPadding = false
            contentDescription = PingStatus.description(delay,goodLimit)
            gravity = Gravity.CENTER; maxLines=Int.MAX_VALUE; ellipsize = null
            setHorizontallyScrolling(false)
            minimumHeight=activity.dp(32)
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
            // Colour the value itself, not the row or a coloured badge.
            background = null
        }
        // The pencil stays visible; ping and deletion are in the swipe action tray.
        labels.addView(ping,LayoutParams(-1,-2))
        ping.gravity=Gravity.LEFT or Gravity.CENTER_VERTICAL;ping.minimumHeight=0;ping.textSize=11f;ping.setPadding(0,activity.dp(3),0,0)
        addView(activity.iconButton("edit", "Редактировать ${entry.name}",18).apply {
            tag="server-edit-${entry.id}";setOnClickListener {onEdit.run()}
        }, LayoutParams(activity.dp(48), activity.dp(48)))
    }
}
