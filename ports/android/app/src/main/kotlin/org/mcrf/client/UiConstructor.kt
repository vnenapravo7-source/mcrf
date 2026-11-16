package org.mcrf.client

import android.content.ClipData
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** A draft changes presentation only. Save commits one complete document. */
internal class UiConstructor(private val host:MainActivity) {
    companion object {const val KEY="appearanceUiConstructor";const val RECOVER="layout-constructor"}
    private var draft:JSONObject?=null
    private data class Focus(val root:String,val scope:String,val title:String)
    private val focus=mutableListOf<Focus>()
    private val hiddenViews=linkedSetOf<View>()
    private val bound=linkedMapOf<LinearLayout,String>()
    private val stableIds=java.util.WeakHashMap<View,String>()
    private val captions=java.util.WeakHashMap<TextView,String>()
    val editing get()=draft!=null
    fun beginRender(){hiddenViews.clear()}
    fun enforceHidden(){hiddenViews.forEach {it.visibility=View.GONE}}
    fun snapshot()=draft?.toString()
    fun restore(value:String?){draft=value?.takeIf {host.canEditInterface()}?.let {runCatching {JSONObject(it)}.getOrNull()}}
    private fun saved()=runCatching {JSONObject(host.prefs.getString(KEY,"{}").orEmpty())}.getOrDefault(JSONObject())
    private fun state()=draft?:saved()

    fun start(){
        if(editing||!host.canEditInterface())return
        focus.clear()
        draft=saved()
        host.render()
    }

    fun finish(save:Boolean){
        val changes=draft?:return
        if(save&&!host.prefs.edit().putString(KEY,changes.toString()).commit()){
            host.alert("Конструктор","Не удалось сохранить оформление.")
            return
        }
        draft=null
        focus.clear()
        host.render()
    }

    fun header(pageScope:String):View=with(host) {
        LinearLayout(this).apply {
            tag="constructor-header";gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(2),0,dp(2),0)
            addView(iconButton("close","Отменить изменения").apply {
                tag="constructor-cancel";setOnClickListener {finish(false)}
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(levelControl(pageScope),LinearLayout.LayoutParams(0,dp(48),1f))
            addView(iconButton("check","Сохранить изменения").apply {
                tag="constructor-save";setOnClickListener {finish(true)}
            },LinearLayout.LayoutParams(dp(48),dp(48)))
        }
    }

    fun panel(vararg controls:View):View=with(host){
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(4),dp(2),dp(4),dp(2))
            background=GradientDrawable().apply {
                setColor(Loft.mix(Loft.SURFACE,Loft.selection(),.18f))
                cornerRadius=dp(16).toFloat()
                setStroke(1,Loft.mix(Loft.SURFACE,Loft.LINE,.65f))
            }
            controls.forEach {addView(it,LinearLayout.LayoutParams(-1,-2))}
        }
    }

    fun levelControl(pageScope:String)=with(host){
        text("",15,Loft.INK).apply {
            gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            captions[this]=pageScope
            setOnClickListener {
                focus.lastOrNull {it.root==pageScope||it.root=="edge"}?.let {
                    focus.remove(it)
                    refreshBindings()
                }
            }
            updateCaption(this,pageScope)
        }
    }

    private fun updateCaption(caption:TextView,pageScope:String){
        val level=focus.lastOrNull {it.root==pageScope||it.root=="edge"}
        caption.text=level?.let {"‹  ${it.title}"}?:"Тяните за ручку ⠿"
        caption.contentDescription=level?.let {"На уровень выше. ${it.title}"}
        caption.isClickable=level!=null
    }

    private fun refreshBindings(){
        bound.toList().forEach {(root,scope)->apply(root,scope)}
        captions.toMap().forEach { (view,scope)->updateCaption(view,scope) }
    }

    fun bind(root:LinearLayout,scope:String){
        if(!editing&&state().length()==0)return
        if(bound.put(root,scope)==null){
            val observer=android.view.ViewTreeObserver.OnGlobalLayoutListener {bound[root]?.let {apply(root,it)}}
            root.viewTreeObserver.addOnGlobalLayoutListener(observer)
            root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view:View)=Unit
                override fun onViewDetachedFromWindow(view:View){
                    root.viewTreeObserver.removeOnGlobalLayoutListener(observer)
                    root.removeOnAttachStateChangeListener(this)
                    bound.remove(root)
                }
            })
        }
        apply(root,scope)
    }

    private fun apply(root:LinearLayout,scope:String){
        val target=focus.lastOrNull {it.root==scope}?.scope
        val automatic=if(editing&&target==null)editableChildren(root,scope,skipStatic=true) else null
        visit(root,scope,scope,target?:automatic?:scope,state())
    }

    fun tabs(items:List<NavigationTab>):List<NavigationTab>{
        val rules=state().optJSONObject("navigation")?:return items
        val ordered=ordered(items,{ "tab:${it.id}#0" },rules)
        return ordered.filterNot {hidden(rules,"tab:${it.id}#0")}.map {item->
            val text=label(rules,"tab:${item.id}#0")?:return@map item
            item.copy(title=text,description=text)
        }.ifEmpty {listOf(NavigationTab(RECOVER,"Оформление","edit","Открыть оформление",false))}
    }

    fun tabEditor(items:List<NavigationTab>,navigate:(String)->Unit):View=with(host){
        LinearLayout(this).apply {
            tag="constructor-navigation";gravity=Gravity.CENTER_VERTICAL
            items.forEach {item->
                addView(text(item.title,12,if(item.chosen)Loft.INK else Loft.MUTED).apply {
                    tag="tab:${item.id}"
                    gravity=Gravity.CENTER;maxLines=2
                    isSelected=item.chosen;isFocusable=true
                    setPadding(dp(4),dp(6),dp(4),0)
                    val shape=GradientDrawable().apply {
                        cornerRadius=dp(10).toFloat()
                        setColor(if(item.chosen)Loft.mix(Loft.SURFACE,Loft.selection(),.78f) else Color.TRANSPARENT)
                    }
                    val mask=GradientDrawable().apply {
                        cornerRadius=dp(10).toFloat()
                        setColor(Color.WHITE)
                    }
                    background=android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Loft.ripple()),shape,mask)
                    setOnClickListener {navigate(item.id)}
                },LinearLayout.LayoutParams(0,dp(48),1f).apply {
                    marginStart=dp(2);marginEnd=dp(2)
                })
            }
            bind(this,"navigation")
        }
    }

    private data class Item(val id:String,val view:View,val title:String)
    private class ConstructorFrame(context:android.content.Context,val item:Item):FrameLayout(context)
    private data class Move(val scope:String,val id:String)

    private fun items(root:ViewGroup):List<Item>{
        val counts=mutableMapOf<String,Int>()
        val children=(0 until root.childCount).map(root::getChildAt)
        val used=children.mapNotNull {child->
            val view=(child as? ConstructorFrame)?.item?.view?:child
            stableIds[view]
        }.toMutableSet()
        return children.map {child->
            if(child is ConstructorFrame)return@map child.item
            val text=title(child)
            val tag=(child.tag as? String)?.takeIf {it.isNotBlank()}
            val key=tag?:text.take(96).ifBlank {child.javaClass.simpleName}
            val id=stableIds.getOrPut(child){
                var occurrence=counts.getOrDefault(key,0)
                while("$key#$occurrence" in used)occurrence++
                counts[key]=occurrence+1
                "$key#$occurrence".also(used::add)
            }
            Item(id,child,text.ifBlank {tag.orEmpty()})
        }
    }

    private fun orderViews(root:LinearLayout,items:List<Item>,rules:JSONObject){
        val sorted=ordered(items,Item::id,rules)
        if(sorted==items)return
        for(item in sorted){
            val wrapper=item.view.parent as? ConstructorFrame
            root.bringChildToFront(wrapper?:item.view)
        }
    }

    private fun visit(root:ViewGroup,rootScope:String,scope:String,editableScope:String,state:JSONObject){
        if(root.tag=="server-orbit-track"||root is android.widget.AdapterView<*>)return
        val items=items(root)
        val rules=state.optJSONObject(scope)?:JSONObject()
        if(root is LinearLayout)orderViews(root,items,rules)
        items.forEach {item->
            val node=item.view
            val childScope="$scope/${item.id}"
            if(editing&&(editableScope==childScope||editableScope.startsWith("$childScope/"))&&node.visibility==View.GONE)
                CompactUi.expandFold(node)
            if(node is ViewGroup)visit(node,rootScope,childScope,editableScope,state)
            label(rules,item.id)?.let {applyLabel(node,it)}
            if(root !is LinearLayout)return@forEach
            val wrapper=node.parent as? ConstructorFrame
            val showGrip=editing&&scope==editableScope&&item.title.isNotBlank()&&node.visibility!=View.GONE
            if(!showGrip&&wrapper!=null)unwrap(root,wrapper)
            val hidden=hidden(rules,item.id)
            if(showGrip){
                if(wrapper==null)wrap(root,rootScope,scope,item,hidden)
                else node.alpha=if(hidden).4f else 1f
            }else if(!editing&&hidden&&node.visibility!=View.GONE){
                node.visibility=View.GONE
                hiddenViews.add(node)
            }
        }
    }

    private fun unwrap(parent:LinearLayout,frame:ConstructorFrame){
        val index=parent.indexOfChild(frame)
        val params=frame.layoutParams
        val node=frame.item.view
        frame.removeView(node)
        parent.removeViewAt(index)
        node.alpha=1f
        parent.addView(node,index,params)
    }

    private fun title(view:View):String=when(view){
        is EditText->view.hint?.toString().orEmpty()
        is TextView->view.text?.toString().orEmpty().ifBlank {view.contentDescription?.toString().orEmpty()}
        is ViewGroup->(0 until view.childCount).firstNotNullOfOrNull {title(view.getChildAt(it)).takeIf(String::isNotBlank)}.orEmpty()
        else->view.contentDescription?.toString().orEmpty()
    }
    private fun applyLabel(view:View,label:String){
        when(view){
            is EditText->if(view.hint?.toString()!=label)view.hint=label
            is TextView->if(view.text.isNullOrBlank())view.contentDescription=label else if(view.text.toString()!=label)view.text=label
            is ViewGroup->(0 until view.childCount).firstOrNull {title(view.getChildAt(it)).isNotBlank()}
                ?.let {applyLabel(view.getChildAt(it),label)}
            else->view.contentDescription=label
        }
    }

    private fun wrap(parent:LinearLayout,rootScope:String,scope:String,item:Item,hidden:Boolean)=with(host){
        val index=parent.indexOfChild(item.view)
        val params=item.view.layoutParams
        val frame=ConstructorFrame(this,item).apply {tag="constructor-item"}
        val targetOutline=GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius=dp(6).toFloat()
            setStroke(dp(1),Loft.ACCENT)
        }
        parent.removeView(item.view)
        frame.addView(item.view,FrameLayout.LayoutParams(-1,if(params.height==ViewGroup.LayoutParams.WRAP_CONTENT)-2 else -1))
        item.view.alpha=if(hidden).4f else 1f
        val handle=FrameLayout(this).apply {
            tag="constructor-grip"
            addView(text("⠿",12,Loft.INK).apply {
                gravity=Gravity.CENTER
                background=GradientDrawable().apply {
                    setColor(Loft.SURFACE)
                    cornerRadius=dp(4).toFloat()
                }
            },FrameLayout.LayoutParams(dp(14),dp(16),Gravity.TOP or Gravity.END).apply {
                topMargin=dp(2);marginEnd=dp(2)
            })
            contentDescription="Править ${item.title}. Удерживайте для переноса."
            setOnClickListener {options(rootScope,scope,item)}
            fun drag()=startDragAndDrop(ClipData.newPlainText("",""),View.DragShadowBuilder(this),Move(scope,item.id),0)
            setOnLongClickListener {drag()}
            val slop=ViewConfiguration.get(context).scaledTouchSlop
            var downX=0f
            var downY=0f
            var moved=false
            setOnTouchListener {view,event->
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{
                        downX=event.rawX;downY=event.rawY;moved=false
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE->if(!moved&&maxOf(kotlin.math.abs(event.rawX-downX),kotlin.math.abs(event.rawY-downY))>slop){
                        moved=drag()
                        if(moved)view.cancelLongPress()
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                moved
            }
        }
        frame.addView(handle,FrameLayout.LayoutParams(dp(40),dp(40),Gravity.TOP or Gravity.END))
        frame.setOnDragListener {_,event->
            val move=event.localState as? Move
            if(move?.scope!=scope)return@setOnDragListener false
            when(event.action){
                DragEvent.ACTION_DRAG_ENTERED->if(move.id!=item.id)frame.foreground=targetOutline
                DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED->frame.foreground=null
                DragEvent.ACTION_DROP->if(move.id!=item.id){
                    val rules=rules(scope)
                    val current=items(parent)
                    val order=current.map(Item::id).toMutableList()
                    val source=order.indexOf(move.id)
                    val target=order.indexOf(item.id)
                    if(source>=0&&target>=0){
                        java.util.Collections.swap(order,source,target)
                        rules.put("order",JSONArray(order))
                        orderViews(parent,current,rules)
                    }
                }
            }
            true
        }
        parent.addView(frame,index,params)
    }

    private fun editableChildren(node:View,scope:String,skipStatic:Boolean=false):String?{
        if(node !is ViewGroup||node.tag=="server-orbit-track"||node is android.widget.AdapterView<*>)return null
        val candidates=items(node).filter {
            val view=it.view
            val static=skipStatic&&(view is TextView&&view.isAccessibilityHeading&&!view.isClickable||
                view.tag=="androidDeploy.status")
            !static&&(view.visibility!=View.GONE&&it.title.isNotBlank()||CompactUi.isFoldBody(view))
        }
        val fold=candidates.firstOrNull {CompactUi.isFoldBody(it.view)}
        if(fold!=null)return editableChildren(fold.view,"$scope/${fold.id}",skipStatic)
        if(node is LinearLayout&&candidates.size>1)return scope
        val single=candidates.singleOrNull()?:return null
        return editableChildren(single.view,"$scope/${single.id}",skipStatic)?:scope.takeIf {node is LinearLayout}
    }

    private fun options(rootScope:String,scope:String,item:Item)=with(host){
        val rules=rules(scope)
        val field=editor("Название").apply {tag="constructor-name";setSingleLine(true);setText(label(rules,item.id)?:item.title)}
        val hidden=Switch(this).apply {tag="constructor-hide";text="Скрыть";tune(this);isChecked=hidden(rules,item.id)}
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0)
            addView(field);addView(hidden)
            if(editableChildren(item.view,"$scope/${item.id}")!=null)
                addView(text("«Править содержимое» открывает элементы внутри этого блока.",12,Loft.MUTED))
        }
        val children=editableChildren(item.view,"$scope/${item.id}")
        ThemedDialogBuilder(this).setTitle("Элемент оформления").setView(body).apply {
            if(children!=null)setNeutralButton("Править содержимое"){_,_->
                focus.add(Focus(rootScope,children,item.title));refreshBindings()
            }
        }
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Готово"){_,_->
                val entries=rules.optJSONObject("items")?:JSONObject().also {rules.put("items",it)}
                val text=field.text.toString().trim().take(160)
                entries.put(item.id,JSONObject().put("hidden",hidden.isChecked)
                    .put("label",text.takeUnless {it.isBlank()||it==item.title}.orEmpty()))
                refreshBindings()
            }.show()
    }

    private fun rules(scope:String):JSONObject{
        val active=requireNotNull(draft)
        return active.optJSONObject(scope)?:JSONObject().also {active.put(scope,it)}
    }
    private fun hidden(rules:JSONObject,id:String)=rules.optJSONObject("items")?.optJSONObject(id)?.optBoolean("hidden")==true
    private fun label(rules:JSONObject,id:String)=rules.optJSONObject("items")?.optJSONObject(id)
        ?.optString("label")?.takeIf(String::isNotBlank)
    private fun <T> ordered(items:List<T>,key:(T)->String,rules:JSONObject):List<T>{
        val order=rules.optJSONArray("order")?:return items
        val ranks=(0 until order.length()).associateBy {order.optString(it)}
        return items.sortedBy {ranks[key(it)]?:Int.MAX_VALUE}
    }
}
