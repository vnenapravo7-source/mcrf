package org.mcrf.client

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Actual attached routing views with isolated encrypted rules; no user route/theme writes. */
object RoutingWarmDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,temporary:File,passed:Consumer<String>) {
        val context=test.targetContext;val before=ProfileStore.snapshot(context)
        val scoped=object:ContextWrapper(context){override fun getFilesDir()=temporary}
        val store=RoutingStore(scoped).apply {load();save(RouteRules.defaults().apply {
            getJSONArray("rules").put(JSONObject().put("id","warm-fixture").put("name","Warm fixture")
                .put("text","example.invalid").put("mode","addresses").put("target","direct").put("enabled",true))
        })}
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val dark=Loft.dark
        fun main(block:()->Unit) {
            val failure=AtomicReference<Throwable?>();val done=CountDownLatch(1)
            check(Handler(Looper.getMainLooper()).post {try{block()}catch(error:Throwable){failure.set(error)}finally{done.countDown()}})
            check(done.await(15,TimeUnit.SECONDS)){"Routing UI queue timeout"};failure.get()?.let {throw it}
        }
        fun attach():LinearLayout {
            val result=AtomicReference<LinearLayout>()
            main {
                val host=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                activity.setContentView(host);RoutingPage(activity,store).build(host);result.set(host)
            }
            return result.get()
        }
        fun ready(host:LinearLayout):View {
            val result=AtomicReference<View?>();val until=SystemClock.elapsedRealtime()+10000
            while(result.get()==null&&SystemClock.elapsedRealtime()<until) {
                main {
                    val body=host.findViewWithTag<LinearLayout>("routing-content")
                    if(body!=null&&body.childCount>2&&body.findViewWithTag<View>("rules-heading")!=null)result.set(body)
                }
                if(result.get()==null)Thread.sleep(25)
            }
            return requireNotNull(result.get()){"Attached routes did not become ready"}
        }
        try {
            val until=SystemClock.elapsedRealtime()+15000
            while(activity.working()&&SystemClock.elapsedRealtime()<until)Thread.sleep(25)
            check(!activity.working()){"Activity startup timeout"}
            val firstHost=attach();val first=ready(firstHost)
            val started=SystemClock.elapsedRealtime();val secondHost=attach();val second=ready(secondHost)
            check(first===second){"Unchanged routing content was recreated"}
            main {check(firstHost.childCount==0&&secondHost.childCount==1);check(second.parent===secondHost)}
            passed.accept("Warm routes reuse the same attached content without duplicate parents; ready in ${SystemClock.elapsedRealtime()-started}ms")
            store.save(store.snapshot().put("defaultTarget","block"))
            val edited=ready(attach());check(edited!==second){"Edited routes reused stale controls"}
            passed.accept("Encrypted fixture revision invalidates the rendered routing screen")
            main {Loft.apply(!dark)}
            val themedHost=attach();val themed=ready(themedHost);check(themed!==edited){"Theme change reused old colors"}
            main {
                val width=activity.dp(320)
                themedHost.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                themedHost.layout(0,0,width,themedHost.measuredHeight)
                check(themed.width==width&&themedHost.childCount==1)
            }
            passed.accept("Theme change rebuilds routing controls and the content fits a 320dp viewport")
        } finally {
            main {Loft.apply(dark);activity.finish()}
            check(ProfileStore.snapshot(context)==before){"Warm routing fixture changed real user files"}
        }
        passed.accept("Warm routing checks preserve real encrypted settings and do not start VPN or network requests")
    }
}
