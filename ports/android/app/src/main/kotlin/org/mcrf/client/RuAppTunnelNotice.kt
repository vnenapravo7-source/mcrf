package org.mcrf.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.zip.GZIPInputStream

internal object RuAppTunnelNotice {
    private const val CHANNEL="ru-app-tunnel-risk-important"
    private const val PREFS="ru-app-tunnel-risk"
    private const val ID=319
    private const val SETUP="org.mcrf.client.RU_APP_TUNNEL_SETUP"
    private const val TICKET="ticket"
    const val ENABLED="ruAppTunnelNoticeEnabled"
    const val PERMISSION_REQUEST=3197
    private const val SILENCED="silenced"
    private const val BODY="Сейчас трафик российских приложений идёт через VPN. Вы рискуете раскрыть IP-адрес сервера. Впоследствии сервер может быть заблокирован РКН. Рекомендуется настроить маршрутизацию."

    private fun preferences(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun settings(context:Context)=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)

    fun enabled(context:Context)=settings(context).getBoolean(ENABLED,!preferences(context).getBoolean(SILENCED,false))
    fun notificationsAllowed(context:Context)=context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun requestNotificationAccess(activity:MainActivity){
        val prefs=preferences(activity)
        if(android.os.Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&
            (!prefs.getBoolean("permissionRequested",false)||activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))){
            prefs.edit().putBoolean("permissionRequested",true).apply()
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),PERMISSION_REQUEST)
        }else activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,activity.packageName))
    }

    @Synchronized fun setEnabled(context:Context,enabled:Boolean){
        settings(context).edit().putBoolean(ENABLED,enabled).apply()
        preferences(context).edit().remove("shownGeneration").remove(TICKET).remove(SILENCED).apply()
        if(!enabled)context.getSystemService(NotificationManager::class.java).cancel(ID)
        else prepareCountryIndex(context)
    }

    private data class DnsKey(val generation:Long,val uid:Int,val address:String)
    private data class DnsMatch(val domain:String?,val expires:Long)
    private val domains=object:LinkedHashMap<DnsKey,DnsMatch>(64,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<DnsKey,DnsMatch>?)=size>256
    }

    private fun addressKey(address:String)=runCatching {
        android.net.InetAddresses.parseNumericAddress(address).hostAddress
    }.getOrNull()

    @Synchronized fun observeDns(generation:Long,uid:Int,host:String,ip:String,ttl:Long){
        val name=TrafficDomainAttribution.canonicalHost(host)?:return
        val address=addressKey(ip)?:return
        val key=DnsKey(generation,uid,address)
        val now=android.os.SystemClock.elapsedRealtime()
        val old=domains[key]?.takeIf {it.expires>now}
        domains[key]=DnsMatch(if(old==null||old.domain==name)name else null,now+ttl.coerceIn(1,5_000))
    }

    @Synchronized fun observedHost(generation:Long,uid:Int,ip:String):String? {
        val address=addressKey(ip)?:return null
        return domains[DnsKey(generation,uid,address)]?.takeIf {it.expires>android.os.SystemClock.elapsedRealtime()}?.domain
    }

    fun russianDomain(host:String?)=host!=null&&
        (host.endsWith(".ru")||host.endsWith(".xn--p1ai"))&&host.contains('.')

    private val geoLoader=java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var geo:Map<Int,java.util.NavigableMap<java.math.BigInteger,java.math.BigInteger>>?=null
    private var geoLoading=false

    @Synchronized fun prepareCountryIndex(context:Context){
        if(geo!=null||geoLoading)return
        geoLoading=true
        val app=context.applicationContext
        geoLoader.execute {
            try {
                val text=GZIPInputStream(app.assets.open("russia-routes.bin")).use {
                    String(ImportIO.read(it),Charsets.UTF_8)
                }
                val rows=RouteRules.parse(text).getValue("ip_cidr").map {ClientTunCidr.parse(it)}
                val groups=rows.groupBy {it.bits}.mapValues {(_,ranges)->
                    val merged=java.util.TreeMap<java.math.BigInteger,java.math.BigInteger>()
                    for(range in ranges.sortedBy {it.number}){
                        val last=merged.lastEntry()
                        if(last!=null&&range.number<=last.value+java.math.BigInteger.ONE)
                            merged[last.key]=maxOf(last.value,range.end)
                        else merged[range.number]=range.end
                    }
                    merged
                }
                geo=groups
            }catch(_:Exception){
                ClientEvents.record(app,"Маршруты","Не удалось загрузить IP-диапазоны для предупреждения RuApp.")
            }finally{
                synchronized(this){geoLoading=false}
            }
        }
    }

    fun russianAddress(address:String):Boolean {
        val index=geo?:return false
        val host=runCatching {android.net.InetAddresses.parseNumericAddress(address)}.getOrNull()?:return false
        if(host.isAnyLocalAddress||host.isLoopbackAddress||host.isLinkLocalAddress||host.isSiteLocalAddress||host.isMulticastAddress)return false
        val number=java.math.BigInteger(1,host.address)
        val range=index[host.address.size*8]?.floorEntry(number)?:return false
        return number<=range.value
    }

    data class Flow(val packages:Set<String>,val host:String?,val ip:String,val port:Int,val protocol:Int)

    // The TUN proves ingress. Only notify when every possible matching route forwards remotely.
    fun unambiguousProxy(config:JSONObject?,flow:Flow?=null):Boolean {
        val route=config?.optJSONObject("route")?:return false
        val rules=route.optJSONArray("rules")?:JSONArray()
        val outbounds=config.optJSONArray("outbounds")?:return false
        val nodes=(0 until outbounds.length()).mapNotNull(outbounds::optJSONObject)
            .groupBy {it.optString("tag")}
        for(index in 0 until rules.length()){
            val rule=rules.optJSONObject(index)?:return false
            val action=rule.optString("action",if(rule.has("outbound"))"route" else "")
            if(action in setOf("sniff","resolve"))continue
            if(action=="hijack-dns"&&!rule.optBoolean("invert")) {
                if(flow==null&&rule.optInt("port")==53)continue
                // Web flows may lack a DNS correlation (cached answers or encrypted DNS).
                if(rule.optString("protocol")=="dns"&&flow?.port in setOf(80,443))continue
            }
            val match=flow?.let {matches(rule,it)}
            if(match==false)continue
            if(action!="route"||!remoteOnly(rule.optString("outbound"),nodes,emptySet()))return false
            if(match==true)return true
        }
        return remoteOnly(route.optString("final"),nodes,emptySet())
    }

    private fun matches(rule:JSONObject,flow:Flow):Boolean? {
        fun values(key:String):List<String>{
            val array=rule.optJSONArray(key)
            return if(array!=null)(0 until array.length()).map(array::optString) else listOf(rule.optString(key))
        }
        fun cidr(list:List<String>,address:String):Boolean?=runCatching {
            val ip=ClientTunCidr.parse("$address/${if(':' in address)128 else 32}")
            list.any {ClientTunCidr.parse(it).overlaps(ip)}
        }.getOrNull()
        val keys=rule.keys().asSequence().filterNot {it in setOf("outbound","action","invert")}.toList()
        if(keys.size>1)return null
        var unknown=false
        for(key in keys){
            val value=when(key){
                "package_name"->values(key).any {it in flow.packages}
                "domain"->flow.host?.let {it in values(key)}
                "domain_suffix"->flow.host?.let {host->values(key).any {suffix->
                    val base=suffix.removePrefix(".")
                    host==base||host.endsWith(".$base")
                }}
                "port"->values(key).any {it.toIntOrNull()==flow.port}
                "network"->values(key).any {it==when(flow.protocol){6->"tcp";17->"udp";else->""}}
                "ip_cidr"->cidr(values(key),flow.ip)
                "ip_is_private"->cidr(privateRanges,flow.ip)?.let {it==rule.optBoolean(key)}
                else->null
            }
            if(value==false)return rule.optBoolean("invert")
            if(value==null)unknown=true
        }
        return if(unknown)null else !rule.optBoolean("invert")
    }

    private val privateRanges=listOf("0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16",
        "172.16.0.0/12","192.168.0.0/16","224.0.0.0/4","240.0.0.0/4","::/128","::1/128","fc00::/7","fe80::/10","ff00::/8")

    private fun remoteOnly(tag:String,nodes:Map<String,List<JSONObject>>,visited:Set<String>):Boolean {
        if(tag in visited||visited.size>=16)return false
        val node=nodes[tag]?.singleOrNull()?:return false
        if(node.has("detour"))return false
        return when(node.optString("type")){
            "selector","urltest"->{
                val children=node.optJSONArray("outbounds")?:return false
                children.length()>0&&(0 until children.length()).all {
                    remoteOnly(children.optString(it),nodes,visited+tag)
                }
            }
            "vless","vmess","trojan","shadowsocks","hysteria2","tuic","anytls","ssh","naive"->node.optString("server").isNotBlank()
            else->false
        }
    }

    @Synchronized fun routeRemoved(context:Context) {
        if (!enabled(context)) return
        post(context, "Правило маршрутизации удалено",
            "Трафик российских приложений может пойти через VPN. Проверьте маршрутизацию.")
    }

    @Synchronized fun report(context:Context) {
        if (!enabled(context)) return
        post(context, "Трафик российских приложений через VPN", BODY)
    }

    private fun post(context:Context,title:String,body:String):Boolean {
        val prefs = preferences(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() ||
            manager.activeNotifications.any { it.id == ID && it.tag == null }) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Маршрутизация RU-app",NotificationManager.IMPORTANCE_HIGH))
        if(manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE)return false
        val ticket=UUID.randomUUID().toString()
        if(!prefs.edit().putString(TICKET,ticket).commit())return false
        val setup=PendingIntent.getActivity(context,ID,Intent(context,MainActivity::class.java).setAction(SETUP)
            .putExtra(TICKET,ticket).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ignore=PendingIntent.getBroadcast(context,ID,Intent(context,RuAppTunnelNoticeDismiss::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID,Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.status_icon)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(setup).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null,"Настроить в 1 клик",setup).build())
            .addAction(Notification.Action.Builder(null,"Мне всё равно",ignore).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        return true
    }

    fun dismiss(context:Context)=setEnabled(context,false)

    @Synchronized fun handle(activity:MainActivity):Boolean {
        val intent=activity.intent
        if(intent.action!=SETUP)return false
        val prefs=preferences(activity)
        val expected=prefs.getString(TICKET,null)?:return false
        if(intent.getStringExtra(TICKET)!=expected||activity.working())return false
        if(!prefs.edit().remove(TICKET).commit())return false
        intent.removeExtra(TICKET)
        activity.navigate(ClientSettingsNavigation.ROUTES)
        activity.runAfterSuccess(MainActivity.Operation {
            val installed=activity.packageManager.getInstalledApplications(0).map {it.packageName}.toSet()
            val packages=RoutingDefaultsRussianApps.installed(installed).map {it.packageName}.toSet()
            val country=GZIPInputStream(activity.assets.open("russia-routes.bin")).use {
                String(ImportIO.read(it),Charsets.UTF_8)
            }
            val generation=activity.routing.generation()
            activity.routing.editInGeneration(generation){state->
                val ru=RoutingDefaultsRussianAppsPolicy.apply(state,packages,packages,true)
                val rules=ru.getJSONArray("rules")
                val existing=(0 until rules.length()).map(rules::getJSONObject)
                    .firstOrNull {RouteRules.countryPreset(it)}
                if(existing==null){
                    require((0 until rules.length()).none {rules.getJSONObject(it).optString("id")==RouteRules.COUNTRY_DIRECT}) {
                        "Идентификатор пресета «РФ напрямую» уже занят."
                    }
                    RouteRules.insert(ru,RouteRules.russiaDirect(country))
                }else existing.put("enabled",true).put("target","direct").put("invert",false)
                    .put("name",RouteRules.COUNTRY_NAME).put("builtinPreset",RouteRules.COUNTRY_DIRECT)
                val include=state.optString("appMode","all")=="include"
                val remaining=ru.getJSONArray("vpnApps")
                require(!include||remaining.length()>0) {
                    "В текущем списке VPN нет приложений за пределами RU-app. Проверьте список вручную."
                }
                state.put("rules",ru.getJSONArray("rules")).put("vpnApps",remaining)
                    .put("defaultTarget","proxy")
            }
            "Маршруты RU-app и «РФ напрямую» сохранены"
        },true,Runnable {
            activity.getSystemService(NotificationManager::class.java).cancel(ID)
            McrfVpnService.reloadActiveRoutes(activity)
        })
        return true
    }
}

class RuAppTunnelNoticeDismiss:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){RuAppTunnelNotice.dismiss(context)}
}
