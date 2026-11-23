package org.mcrf.client

import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray

/** Isolated encrypted settings; never authorizes VK or starts a connection. */
object VkSettingsDeferredDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,temporary:File):String {
        check(temporary.mkdir())
        val base=test.targetContext
        val context=object:ContextWrapper(base) {
            override fun getFilesDir()=temporary
            override fun getApplicationContext():Context=this
        }
        val store=AndroidDeployStore(context)
        val hashes=listOf("A".repeat(43),"B".repeat(43))
        store.update("vkSettings"){it.put("manual",JSONArray(hashes))}
        val before=store.exportForEncryptedBackup().toString()
        val backend=AndroidDeployBackend(store)
        val activity=test.startActivitySync(Intent(base,MainActivity::class.java)
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun onMain(block:()->Unit) {
            val failure=AtomicReference<Throwable?>()
            test.runOnMainSync {try {block()}catch(error:Throwable){failure.set(error)}}
            failure.get()?.let {throw AssertionError("VK UI check failed",it)}
        }
        val startupDeadline=android.os.SystemClock.elapsedRealtime()+8000
        var ready=false
        while(!ready&&android.os.SystemClock.elapsedRealtime()<startupDeadline) {
            onMain {ready=!activity.working()}
            if(!ready)android.os.SystemClock.sleep(50)
        }
        check(ready){"Activity startup did not finish"}
        test.waitForIdleSync()
        fun fields(view:View):Int=if(view is EditText)1 else if(view is ViewGroup)
            (0 until view.childCount).sumOf {fields(view.getChildAt(it))}else 0
        fun slots(view:View):VkCallSlots? {
            if(view is VkCallSlots)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)slots(view.getChildAt(i))?.let {return it}
            return null
        }
        val locked=CountDownLatch(1)
        val release=CountDownLatch(1)
        val lockOwner=Thread {
            synchronized(ProfileStore.storageLock){locked.countDown();release.await(10,TimeUnit.SECONDS)}
        }
        var ui:AndroidDeployUi?=null
        var dialog:Dialog?=null
        lateinit var shell:View
        var deliveries=0
        try {
            // A busy encrypted store must not delay the first VK frame.
            lockOwner.start();check(locked.await(3,TimeUnit.SECONDS))
            val firstFrame=CountDownLatch(1)
            onMain {
                ui=AndroidDeployUi(activity,store,backend){}
                shell=ui!!.vkSettings {deliveries++}
                check(shell.findViewWithTag<LinearLayout>("vk-account-content").childCount==0)
                shell.viewTreeObserver.addOnDrawListener {firstFrame.countDown()}
                dialog=Dialog(activity).apply {setContentView(shell);show()}
            }
            check(firstFrame.await(3,TimeUnit.SECONDS)){"Storage blocked the first VK frame"}
            onMain {
                check(deliveries==0)
                check(shell.findViewWithTag<LinearLayout>("vk-account-content").childCount==0)
                dialog!!.dismiss();ui!!.detach()
            }
            release.countDown();lockOwner.join(3000)
            val drained=CountDownLatch(1)
            AndroidDeployIo.submit({Unit}){drained.countDown()}
            check(drained.await(5,TimeUnit.SECONDS))
            onMain {
                check(deliveries==0){"Detached page accepted a late callback"}
                check(shell.findViewWithTag<LinearLayout>("vk-calls-content").childCount==0)
            }
            val loaded=CountDownLatch(1)
            onMain {
                ui=AndroidDeployUi(activity,store,backend){}
                shell=ui!!.vkSettings {loaded.countDown()}
                dialog=Dialog(activity).apply {setContentView(shell);show()}
            }
            check(loaded.await(5,TimeUnit.SECONDS)){"VK settings did not finish loading"}
            onMain {
                check(shell.findViewWithTag<View>("vk-account-link")!=null)
                val editor=checkNotNull(slots(shell))
                check(editor.values==hashes);check(fields(editor)==0)
                editor.findViewWithTag<View>("manual-hashes-fold").performClick()
                check(fields(editor)==2);check(editor.values==hashes)
                editor.findViewWithTag<View>("manual-hashes-fold").performClick()
                check(editor.values==hashes)
                dialog!!.dismiss();ui!!.detach()

                var changes=0
                val lazy=VkCallSlots(activity,hashes,Int.MAX_VALUE){changes++}
                lazy.append(listOf("C".repeat(43)))
                check(fields(lazy)==0);check(lazy.values.size==3);check(changes==1)
                lazy.findViewWithTag<View>("manual-hashes-fold").performClick()
                check(fields(lazy)==3);check(changes==1)
                val bulk=VkCallSlots(activity,(1..600).map {it.toString().padStart(43,'A')},Int.MAX_VALUE){}
                check(fields(bulk)==0);check(bulk.values.size==600)
            }
            check(before==store.exportForEncryptedBackup().toString()){"Opening VK modified persisted settings"}
            return "PASS: first frame while storage locked; stale callback rejected; same shell filled; lazy rows preserve calls; opening performs no writes.\n"
        }finally {
            release.countDown();lockOwner.join(3000)
            onMain {dialog?.dismiss();ui?.detach();activity.finish()}
            backend.close()
        }
    }
}
