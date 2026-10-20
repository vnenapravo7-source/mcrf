package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock

/** Synthetic registry/prompt lifecycle only. Never opens the WebView or sends a provider request. */
object CarrierPromptDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        check(CarrierChallenge.unopened()==null) { "Existing challenge; fixture must not interfere" }
        var replies=0
        val first=requireNotNull(CarrierChallenge.registerBounded("owned-first","https://vk.com/captcha",{replies++}))
        val second=requireNotNull(CarrierChallenge.registerBounded("owned-second","https://vk.com/captcha",{replies++}))
        val activity=test.startActivitySync(Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity")
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(label:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(!condition()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(40)
            check(condition()){label}
        }
        try {
            await("First synthetic prompt not offered"){CarrierChallenge.get(first)?.opened==true}
            check(CarrierChallenge.get(second)?.opened==false){"Two prompts offered concurrently"}
            CarrierChallenge.remove(first) // Models native retirement/expiry, not user cancellation.
            await("Stale prompt blocked the next challenge"){CarrierChallenge.get(second)?.opened==true}
            test.runOnMainSync {activity.finish()}
            await("Destroyed owner retained the second prompt"){CarrierChallenge.get(second)?.opened==false}
            check(replies==0){"Lifecycle emitted a proof or cancellation without user choice"}
            return "PASS retired prompt admits next FIFO request; destroyed owner reoffers live request; no provider/WebView/replies"
        } finally {
            CarrierChallenge.remove(first);CarrierChallenge.remove(second)
            test.runOnMainSync {if(!activity.isFinishing)activity.finish()}
        }
    }
}
