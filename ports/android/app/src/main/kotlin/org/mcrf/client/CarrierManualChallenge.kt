package org.mcrf.client

import android.content.Context
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded user-driven REST challenge. Never waits on UI or replays a network operation. */
object CarrierManualChallenge {
    fun await(context:Context,url:String,cancelled:()->Boolean):String {
        check(Looper.myLooper()!=Looper.getMainLooper()) {"Подтверждение VK должно ожидаться в фоне"}
        val completed=CountDownLatch(1)
        val result=AtomicReference<String?>()
        val id=CarrierChallenge.registerBounded("vk-api",url,{value->result.set(value);completed.countDown()},{retired->CarrierChallengeNotifications.cancel(context,retired)})
            ?:error("Слишком много ожидающих проверок VK. Заверши или отмени открытую проверку.")
        try {
            CarrierChallengeNotifications.show(context,id)
            val deadline=System.nanoTime()+150_000_000_000L
            while(System.nanoTime()<deadline){
                check(!cancelled()&&!Thread.currentThread().isInterrupted) {"Подтверждение VK отменено"}
                if(completed.await(200,TimeUnit.MILLISECONDS)){
                    val proof=requireNotNull(result.get())
                    check(!proof.startsWith("error:")) {"Подтверждение VK отменено или истекло"}
                    return proof
                }
                check(CarrierChallenge.get(id)!=null) {"Время подтверждения VK истекло"}
            }
            error("Время подтверждения VK истекло")
        }finally{CarrierChallenge.remove(id)}
    }
}
