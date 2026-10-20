package org.mcrf.client

import java.util.Locale

/** Immutable session snapshots; speeds are derived from the core's monotonic byte counters. */
object TrafficState {
    class Snapshot @JvmOverloads constructor(
        @JvmField val sent: Long, @JvmField val received: Long,
        @JvmField val upload: Long, @JvmField val download: Long,
        @JvmField val server: String, @JvmField val available: Boolean,
        @JvmField val countersSupported:Boolean = true
    )

    @JvmField @Volatile var current = Snapshot(0, 0, 0, 0, "", false)
    private var lastSent = 0L
    private var lastReceived = 0L
    private var lastAt = 0L
    private var sentOffset=0L
    private var receivedOffset=0L

    @JvmStatic @JvmOverloads @Synchronized fun reset(name: String, countersSupported:Boolean=true) {
        lastSent = 0; lastReceived = 0; lastAt = 0
        sentOffset=0;receivedOffset=0
        current = Snapshot(0, 0, 0, 0, name, false,countersSupported)
    }
    @JvmStatic @Synchronized fun handover(name:String){
        sentOffset=current.sent;receivedOffset=current.received;lastSent=0;lastReceived=0;lastAt=0
        current=Snapshot(sentOffset,receivedOffset,0,0,name,current.available)
    }
    @JvmStatic @Synchronized fun server(name: String) {
        val previous = current
        current = Snapshot(previous.sent, previous.received, previous.upload, previous.download, name, previous.available,previous.countersSupported)
    }
    @JvmStatic @Synchronized fun stop() {
        val previous = current
        current = Snapshot(previous.sent, previous.received, 0, 0, previous.server, previous.available,previous.countersSupported)
        lastAt = 0
    }
    @JvmStatic @Synchronized fun update(sent: Long, received: Long, elapsed: Long) {
        if(!current.countersSupported)return
        val outgoing = sent.coerceAtLeast(0)
        val incoming = received.coerceAtLeast(0)
        val duration = elapsed - lastAt
        fun rate(value: Long, previous: Long) = if (lastAt == 0L || duration <= 0) 0L
            else ((value - previous).coerceAtLeast(0) * 1000.0 / duration).toLong()
        val up = rate(outgoing, lastSent)
        val down = rate(incoming, lastReceived)
        lastSent = outgoing; lastReceived = incoming; lastAt = elapsed
        fun sum(one:Long,two:Long)=if(Long.MAX_VALUE-one<two)Long.MAX_VALUE else one+two
        current = Snapshot(sum(sentOffset,outgoing),sum(receivedOffset,incoming), up, down, current.server, true)
    }
    @JvmStatic fun bytes(value: Long): String = when {
        value < 1024 -> "$value Б"
        value < 1_048_576 -> String.format(Locale.ROOT, "%.1f КБ", value / 1024.0)
        value < 1_073_741_824 -> String.format(Locale.ROOT, "%.1f МБ", value / 1_048_576.0)
        else -> String.format(Locale.ROOT, "%.2f ГБ", value / 1_073_741_824.0)
    }
    @JvmStatic fun speed(state: Snapshot) = "↓ ${bytes(state.download)}/с  ↑ ${bytes(state.upload)}/с"
    @JvmStatic fun total(state: Snapshot) = "↓ ${bytes(state.received)}  ↑ ${bytes(state.sent)}"
    @JvmStatic fun homeSpeed(state:Snapshot,connected:Boolean):String = if(connected&&!state.countersSupported) "↓ —\n↑ —" else if(connected&&state.available)
        "↓ ${bytes(state.download)}/с\n↑ ${bytes(state.upload)}/с" else "↓ 0 Б/с\n↑ 0 Б/с"
    @JvmStatic fun homeTraffic(state:Snapshot,connected:Boolean):String = if(connected&&!state.countersSupported) "↓ —\n↑ —" else if(connected&&state.available)
        "↓ ${bytes(state.received)}\n↑ ${bytes(state.sent)}" else "↓ 0 Б\n↑ 0 Б"
    @JvmStatic fun notificationTraffic(state: Snapshot): String {
        if (!state.countersSupported) return "Счётчики трафика недоступны"
        if (!state.available) return "Трафик за подключение: —"
        val sum = if (Long.MAX_VALUE-state.sent < state.received) Long.MAX_VALUE else state.sent+state.received
        return "Трафик за подключение: ${bytes(sum)}"
    }
}
