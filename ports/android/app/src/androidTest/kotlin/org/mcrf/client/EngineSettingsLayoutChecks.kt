package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

/** Read-only, real-framework layout checks at phone widths; no installs or settings edits. */
object EngineSettingsLayoutChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        test.waitForIdleSync()
        var failure:Throwable?=null
        var checks=0
        test.runOnMainSync {
            try {
                val host=object:EngineUpdatePrimaryUi.Host {
                    override fun selected()="singbox"
                    override fun validateFullSnapshot(choiceId:String)=emptyList<String>()
                    override fun selectValidated(choiceId:String)=error("Read-only test")
                }
                fun measure(view:View,width:Int) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(width),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                }
                fun preview(view:View,name:String) {
                    measure(view,343)
                    val image=Bitmap.createBitmap(view.width,view.height.coerceIn(1,1800),Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(image);canvas.drawColor(Loft.BACKGROUND);view.draw(canvas)
                    File(activity.getExternalFilesDir(null),"engine-ui-$name.png").outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                    image.recycle()
                }
                val summary=EngineUpdatePrimaryUi.updatesView(activity,host)
                for(width in listOf(288,343,500)) {
                    measure(summary,width)
                    val row=summary.findViewWithTag<LinearLayout>("primary-core-summary")
                    check(row.getChildAt(0).height==row.getChildAt(1).height){"Unequal primary cards at $width dp"};checks++
                    for(index in 0..1)check(row.getChildAt(index).width>0&&row.getChildAt(index).height>0)
                }
                preview(summary,"summary")
                val table=EngineUpdateInventoryUi.view(activity,host,simple=false)
                measure(table,288)
                val grouped=table.findViewWithTag<LinearLayout>("engine-update-table")
                check(grouped!=null&&grouped.childCount>2);checks++
                fun inspect(node:View) {
                    if(node.tag?.toString()?.startsWith("additional-engine-")==true||node.tag?.toString()?.startsWith("openflux-script-")==true) {
                        check(node.background==null){"A table cell paints an individual card"}
                        var ancestor=node.parent
                        while(ancestor is View&&ancestor!==grouped)ancestor=ancestor.parent
                        check(ancestor===grouped){"A row is outside the shared table"}
                        val parent=node.parent as View
                        check(node.left>=0&&node.right<=parent.width&&node.bottom<=parent.height){"Engine details overflow their row"};checks++
                    }
                    if(node is ViewGroup)repeat(node.childCount){inspect(node.getChildAt(it))}
                }
                inspect(grouped)
                preview(table,"table")
                val scripts=EngineUpdateOpenFluxScriptUi.view(activity)
                preview(scripts,"scripts")
                val store=RoutingStore(activity).apply {load()}
                val before=store.snapshot().toString()
                val dns=AdvancedDnsEditor(activity,store).advancedView()
                val timeout=dns.findViewWithTag<View>("dns-setting.timeout")
                ServerEditorLayout.reveal(timeout,dns);measure(dns,288)
                check(timeout.width==activity.dp(88)&&timeout.height==activity.dp(48));checks++
                preview(dns,"dns")
                val geo=GeoDataEditor(activity,store).build();measure(geo,288)
                val card=geo.findViewWithTag<LinearLayout>("geo-databases-card")
                val last=(0 until card.childCount).map(card::getChildAt).last {it.visibility==View.VISIBLE}
                check(card.height-last.bottom<=activity.dp(32)){"Excess empty Geo footer"};checks++
                preview(geo,"geo")
                val editor=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                for(title in listOf("Сервер","Подключение","Доступ","Защита TLS")) {
                    val section=ServerEditorLayout.section(activity,editor,title)
                    ServerEditorLayout.field(activity,section,"Адрес",activity.editor("").apply {setText("example.com")})
                }
                preview(editor,"editor")
                check(store.snapshot().toString()==before){"Opening editors changed routing settings"};checks++
            }catch(error:Throwable){failure=error}
        }
        failure?.let {throw it}
        return "PASS $checks engine/settings layout checks"
    }
}
