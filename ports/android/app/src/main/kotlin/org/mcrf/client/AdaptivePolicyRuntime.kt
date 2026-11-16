package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/** Existing service workers call this adapter. It owns no VPN, timer, payload or Android permission. */
class AdaptivePolicyRuntime(private val context: Context) {
    private val meter = UsageQuotaPolicy.Meter()
    private val guard = AdaptivePolicy.SwitchGuard()
    private var configurationAt = Long.MIN_VALUE
    private var settings = ClientBehaviorSettings()
    private var membersAt = Long.MIN_VALUE
    private var memberships = emptyMap<String, String>()
    @Volatile var notice = ""
        private set
    @Synchronized fun settings(): ClientBehaviorSettings {
        val now = SystemClock.elapsedRealtime()
        if (configurationAt == Long.MIN_VALUE || now - configurationAt >= 5_000 || now < configurationAt) {
            settings = runCatching {ClientBehaviorStore.load(context)}.getOrDefault(settings); configurationAt = now
        }
        // Old keys remain readable for backups; retired policies never affect a live connection.
        return settings.copy(adaptivePolicyEnabled=false,conserveBattery=false,conserveMobile=false,
            conserveRoaming=false,prioritizeRealtime=false,foregroundPolicyEnabled=false)
    }
    @Synchronized fun probeInterval(base: Long): Long = runCatching {
        val current=settings()
        if(current.unstableNetwork)maxOf(base,current.unstableIntervalSeconds*1000L)else base
    }.getOrDefault(base)
    fun managed():Boolean=settings().unstableNetwork||runCatching {UsageQuotaStore.local(context).enabled()}.getOrDefault(false)
    fun observe(server: String, healthy: Boolean): Boolean {
        guard.observe(server, healthy)
        return guard.mayReconsider(settings(), healthy)
    }
    fun admit(current: String, target: String, manual: Boolean): Boolean = runCatching {
        guard.admit(current, target, SystemClock.elapsedRealtime(), manual) && UsageQuotaStore.local(context).admitSwitch(current,target,manual)
    }.getOrDefault(false)
    fun permitsTarget(server: String): Boolean = runCatching {
        val limits=UsageQuotaStore.local(context).hits()
        if(limits.none {it.rule.action!=UsageQuotaPolicy.Action.WARN&&it.rule.scope in setOf(UsageQuotaPolicy.Scope.SERVER,UsageQuotaPolicy.Scope.SUBSCRIPTION)})return@runCatching true
        val subscription=subscription(server)
        AutoSelection.allowedByQuota(server,subscription,limits)
    }.getOrDefault(false)
    @Synchronized fun counters(owner: String, tx: Long, rx: Long, server: String) {
        val store = UsageQuotaStore.local(context)
        val (enabled,epoch)=store.accountingState()
        val bytes = meter.sampleAccounting(owner,tx,rx,enabled,epoch)
        if(!enabled)return
        store.record(UsageQuotaPolicy.Delta(System.currentTimeMillis(), bytes,
            server = server.ifBlank { UsageQuotaPolicy.UNKNOWN }, subscription = runCatching {subscription(server)}.getOrDefault(UsageQuotaPolicy.UNKNOWN)),epoch)
    }
    fun accountingEnabled():Boolean {
        return UsageQuotaStore.local(context).enabled()
    }
    @Synchronized fun subscription(server: String): String {
        if(server.isBlank()||server==UsageQuotaPolicy.UNKNOWN)return UsageQuotaPolicy.UNKNOWN
        val now = SystemClock.elapsedRealtime()
        if (membersAt == Long.MIN_VALUE || now - membersAt >= 30_000 || now < membersAt) {
            val catalogue = Connections(context).apply { load() }
            memberships = AutoSelection.subscriptionMembership(catalogue.subscriptions()); membersAt = now
        }
        return memberships[server] ?: UsageQuotaPolicy.UNKNOWN
    }
    @Synchronized fun quotaCandidates(entries: List<ServerImport.Server>, probes: JSONObject, current: String, hit: UsageQuotaStore.Hit,
                                     subscriptions:List<JSONObject>): List<ServerImport.Server> {
        memberships=AutoSelection.subscriptionMembership(subscriptions);membersAt=SystemClock.elapsedRealtime()
        val store = UsageQuotaStore.local(context)
        val blocked = store.hits()
        val admitted=Connections(context).apply {load()}.autoCandidates(entries)
        return AutoSelection.policyCandidates(admitted, probes, System.currentTimeMillis(), current,
            if (hit.rule.action == UsageQuotaPolicy.Action.SWITCH_SUBSCRIPTION) subscription(current) else null, memberships)
            .filter { candidate -> AutoSelection.allowedByQuota(candidate.id,memberships[candidate.id]?:UsageQuotaPolicy.UNKNOWN,blocked) }
    }
    fun warn(message: String) { notice = message; ClientEvents.record(context, "Политика трафика", message) }
    fun clearNotice(){notice=""}
    companion object {
        fun environment(context: Context): AdaptivePolicy.Environment {
            val power = context.getSystemService(PowerManager::class.java)
            val battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val physical = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val mobile = physical.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } && physical.none { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            return AdaptivePolicy.Environment(power.isPowerSaveMode || battery in 0..20, mobile,
                mobile && physical.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) })
        }
        fun <T> measured(context: Context, action: BatteryCostReport.Action, work: () -> T): T {
            val wall = SystemClock.elapsedRealtime(); val cpu = SystemClock.currentThreadTimeMillis()
            try { return work() } finally { runCatching { UsageQuotaStore.local(context).measured(action,
                SystemClock.elapsedRealtime() - wall, SystemClock.currentThreadTimeMillis() - cpu) } }
        }
        fun backgroundUpdatesAllowed(context: Context): Boolean = true
    }
}
