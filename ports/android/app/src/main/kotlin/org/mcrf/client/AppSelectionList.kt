package org.mcrf.client

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Recycled, fixed-height rows. Package-manager icon I/O never runs in getView. */
class AppSelectionList(private val activity: MainActivity,private val selected: MutableSet<String>,private val changed: () -> Unit): ListView(activity) {
    data class Item(val id: String,val title: String,val detail: String,val installed: Boolean=true)
    private var items=emptyList<Item>()
    val displayedItems: List<Item> get()=items
    fun isSelected(id: String)=id in selected
    private val stopped=AtomicBoolean(false)
    private val iconWorker=Executors.newFixedThreadPool(2)
    private val main=Handler(Looper.getMainLooper())
    private val icons=LruCache<String,Drawable.ConstantState>(64)
    private val iconLoads=AppIconLoadGate()
    private val failedIcons=HashSet<String>()
    private val pm=activity.applicationContext.packageManager
    private fun dp(value: Int)=activity.dp(value)
    private class Row(val root: LinearLayout,val icon: ImageView,val title: TextView,val detail: TextView,val check: CheckBox,var installed:Boolean=true)
    private fun loadIcon(id:String) {
        if(stopped.get()||id in failedIcons||!iconLoads.reserve(id))return
        iconWorker.execute {
            val icon=runCatching {pm.getApplicationIcon(id).constantState}.getOrNull()
            main.post {
                iconLoads.release(id)
                if(!stopped.get()) {
                    if(icon!=null)icons.put(id,icon)else failedIcons.add(id)
                    // Refresh the current binding, never a captured recycled row.
                    // Retry visible rows skipped while the bounded queue was full.
                    for(index in 0 until childCount) {
                        val visible=getChildAt(index).tag as? Row ?: continue
                        if(!visible.installed)continue
                        val key=visible.icon.tag as? String ?: continue
                        val cached=icons.get(key)
                        if(cached!=null)visible.icon.setImageDrawable(cached.newDrawable(resources))
                        else loadIcon(key)
                    }
                }
            }
        }
    }
    private val rows=object: BaseAdapter() {
        override fun getCount()=items.size
        override fun getItem(position: Int)=items[position]
        override fun getItemId(position: Int)=position.toLong()
        override fun getView(position: Int,recycled: View?,parent: ViewGroup?): View {
            val row=(recycled?.tag as? Row) ?: createRow()
            val item=items[position]
            row.installed=item.installed
            row.title.text=item.title;row.detail.text=item.detail
            row.check.setOnCheckedChangeListener(null)
            row.check.isChecked=item.id in selected;row.check.contentDescription=item.title
            row.root.background=Loft.rowBackground(activity,row.check.isChecked)
            row.check.setOnCheckedChangeListener { _,checked ->
                if(checked) selected.add(item.id) else selected.remove(item.id)
                row.root.background=Loft.rowBackground(activity,checked)
                changed()
            }
            row.root.setOnClickListener {row.check.performClick()}
            row.icon.tag=item.id
            row.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            if(item.installed) {
                val cached=icons.get(item.id)
                if(cached!=null) row.icon.setImageDrawable(cached.newDrawable(resources))
                else loadIcon(item.id)
            }
            return row.root
        }
    }
    init {
        divider=ColorDrawable(Loft.LINE);dividerHeight=dp(1).coerceAtLeast(1)
        setBackgroundColor(Color.TRANSPARENT);isHorizontalScrollBarEnabled=false
        adapter=rows
    }
    private fun createRow(): Row {
        val root=LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(6),dp(4),dp(6))
            layoutParams=AbsListView.LayoutParams(-1,dp(68))
        }
        val icon=ImageView(activity).apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        root.addView(icon,LinearLayout.LayoutParams(dp(36),dp(36)).apply {rightMargin=dp(12)})
        val labels=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL}
        val title=TextView(activity).apply {textSize=14f;setTextColor(Loft.INK);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=1;ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false}
        val detail=TextView(activity).apply {textSize=11f;setTextColor(Loft.MUTED);maxLines=1;ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false;setPadding(0,dp(4),0,0)}
        labels.addView(title);labels.addView(detail)
        root.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val check=CheckBox(activity).apply {buttonTintList=ColorStateList.valueOf(Loft.ACCENT);minimumWidth=dp(48);minimumHeight=dp(48);setPadding(dp(8),0,0,0)}
        root.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)))
        return Row(root,icon,title,detail,check).also {root.tag=it}
    }
    fun submit(next: List<Item>) {items=next.toList();rows.notifyDataSetChanged()}
    fun close() {stopped.set(true);iconWorker.shutdownNow();iconLoads.clear();failedIcons.clear();icons.evictAll()}
}
