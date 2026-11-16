package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cancels only the exact pending challenge, never another connection or system VPN. */
class CarrierChallengeCancelReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val id=intent.getStringExtra("request")?:return
        if(CarrierChallenge.respondAsync(id,"error:cancelled"))CarrierChallengeNotifications.cancel(context,id)
        else CarrierChallengeNotifications.show(context,id)
    }
}
