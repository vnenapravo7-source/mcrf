package org.mcrf.client

/** Pure presentation of one immutable session snapshot. */
internal object ConnectionNotification {
    data class Content(val title:String,val text:String,val expanded:String)
    fun content(state:TrafficState.Snapshot,message:String,proxyMode:Boolean,counters:Boolean):Content {
        val server=state.server.ifBlank {"Подключение"}
        val status=if(proxyMode)message else message.split(" · ").filterNot {
            it.contains("прокси",ignoreCase=true)||it.contains("127.0.0.1:")||it.contains("[::1]:")
        }.joinToString(" · ").ifBlank {"VPN подключён"}
        val details=buildList {
            if(state.turn){add("IP: ${state.serverIp.ifBlank {"—"}}");add(TrafficState.streamLabel(state))}
            if(counters)add(TrafficState.notificationTraffic(state))
        }
        return Content("MCRF · $server",details.joinToString(" · ").ifBlank {status},
            (listOf(status,server)+details).joinToString("\n"))
    }
}
