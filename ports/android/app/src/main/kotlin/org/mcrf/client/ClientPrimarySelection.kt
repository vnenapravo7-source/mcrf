package org.mcrf.client

import android.content.Context
import android.net.VpnService
import org.json.JSONObject
import org.json.JSONArray
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.steerprep.SteerMcrfSnapshotAdapter

/** A missing choice is deliberate. Never fall back to a bundled or another installed core. */
object ClientPrimarySelection {
    const val KEY="primaryCore"
    const val DUAL_CONFIRMED="primaryTrafficChoiceConfirmed"
    const val DNS_MODEL_V2_ACCEPTED="steerDnsModelV2Accepted"
    class DnsModelConsentRequired:IllegalArgumentException("Нужно отдельное подтверждение новой модели DNS Steer.")
    /** Off UI. Inspect only the verified installed module's bounded asset;
     * the worker still independently validates the complete immutable input. */
    fun steerContract(context:Context):SteerSnapshotContract {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Проверка движка выполняется в фоне."}
        val installed=EngineUpdatePrimary.installation(context,"steer")
        return java.util.zip.ZipFile(installed.sourceDir).use {zip->
            val entry=requireNotNull(zip.getEntry("assets/steer-capabilities.json")) {"Нет описания возможностей Steer."}
            require(entry.size in 1L..65536L) {"Некорректное описание возможностей Steer."}
            val bytes=zip.getInputStream(entry).use {input->
                val out=java.io.ByteArrayOutputStream();val block=ByteArray(4096)
                while(true){val count=input.read(block);if(count<0)break;require(out.size()+count<=65536);out.write(block,0,count)}
                out.toByteArray()
            }
            SteerSnapshotContract.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
        }
    }
    fun acceptSteerDnsModel(context:Context) {
        require(steerContract(context).model==2) {"Установленная модель Steer изменилась. Повтори выбор движка."}
        check(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean(DNS_MODEL_V2_ACCEPTED,true).commit())
    }
    /** Package/native verification is deliberately performed off the UI thread. */
    fun needsTrafficChoice(context:Context):Boolean {
        if(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getBoolean(DUAL_CONFIRMED,false))return false
        return EngineUpdatePrimary.kinds.all {kind->
            try {EngineUpdatePrimary.installation(context,kind);true}
            catch(_:Exception){false}
        }
    }
    fun selected(context:Context):String? {
        val saved=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all[KEY] as? String
        return saved?.takeIf {id->ClientPrimaryCorePolicy.Core.values().any {it.id==id}}
    }
    fun requireSelected(context:Context):String = selected(context)
        ?: throw IllegalArgumentException("Сначала выбери и установи основной движок: sing-box или Steer.")
    /** Explicit human protocol precedence, never persisted as the primary choice. Off UI. */
    fun effectiveWireGuardChoice(context:Context,preferred:String,wireGuard:Boolean):String {
        require(preferred in setOf("singbox","steer"))
        if(!wireGuard)return preferred
        val present=try {EngineUpdatePrimary.installation(context,"sing-box");true}
            catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
        // A present but invalid sing-box throws above; do not silently try the compact backend.
        return if(WgAddonPolicy.backend(preferred,present,false)==WgAddonPolicy.Backend.SING_BOX)"singbox" else preferred
    }
    /** Denial-only presence check: even an invalid/disabled installed sing-box must not enable an addon fallback. */
    fun requireCompactDownloadAllowed(context:Context) {
        val present=try {
            context.packageManager.getApplicationInfo(EngineModules.packageName("sing-box"),0);true
        }catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
        WgAddonPolicy.requireDownloadAllowed(selected(context),present)
    }
    fun select(context:Context,choice:String) {
        val core=ClientPrimaryCorePolicy.Core.parse(choice)
        EngineUpdatePrimary.installation(context,core.kind)
        val model=if(choice=="steer")steerContract(context).model else 0
        if(model==2&&!context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getBoolean(DNS_MODEL_V2_ACCEPTED,false))throw DnsModelConsentRequired()
        val confirmsBoth=needsTrafficChoice(context)
        check(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString(KEY,choice).apply {
            if(choice=="steer"&&model==1)putBoolean("steerIpv4DnsAccepted",true)
            if(confirmsBoth)putBoolean(DUAL_CONFIRMED,true)
        }.commit())
    }
    fun envelope(config:JSONObject,vpn:Boolean,metered:Boolean)=JSONObject().put("schema",1)
        .put("mode",if(vpn)"vpn"else"proxy").put("metered",metered).put("config",JSONObject(config.toString())).toString()
    fun steerSnapshot(context:Context,routing:JSONObject,vpn:Boolean,accepted:Boolean?=null):SteerMcrfSnapshotAdapter.Result {
        RouteServerPlan.requireSingleServerCore(routing)
        val captured=JSONObject(routing.toString())
        val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val additional=JSONArray()
        val contract=steerContract(context)
        if(prefs.getBoolean("automatic",false)&&!contract.independentProbe)additional.put("automatic-selection")
        val extras=OptionalCoreStore(context).load()
        if(extras.xrayVlessDefault||extras.xrayMuxDefault||extras.customFragmentDefault||extras.mixedCaseSniDefault)additional.put("optional-core-defaults")
        val geoPlan=if(contract.model==2)org.mcrf.steerprep.SteerGeoProjection.plan(captured)else null
        val store=ClientTunStore(context);val loadedTun=store.load();val tun=loadedTun.json()
        // DNS address-family policy is NOT TUN capture/routing policy. Keep the
        // original IPv6 switch/routes even when compatibility is explicitly ON.
        tun.remove("ipv4Compatibility")
        require(!loadedTun.ipv4Compatibility||contract.model!=2||contract.ipv4Compatibility){
            "Установленный Steer не заявляет общую IPv4-совместимость DNS. Настройки не изменены."
        }
        if(contract.model==2){
            if(!prefs.getBoolean(DNS_MODEL_V2_ACCEPTED,false))throw DnsModelConsentRequired()
            require(!tun.getBoolean("ipv6")||contract.ipv6) {"Установленный Steer не заявляет обработку IPv6. Настройки не изменены."}
            require(!tun.getBoolean("mixedEnabled")||contract.authenticatedProxy) {"Установленный Steer не заявляет локальный прокси. Настройки не изменены."}
            val rules=geoPlan?.rules()?:captured.optJSONArray("rules")
            val nativeRules=rules!=null&&(0 until rules.length()).any {index ->rules.getJSONObject(index).let {it.optBoolean("enabled",true)&&it.has("nativeMatch")}}
            require(!nativeRules||contract.nativeAddressRules){"Установленный Steer не заявляет точные адресные правила. Настройки не изменены."}
            var flowRules=false;var nativeOwnerRules=false
            if(rules!=null)for(index in 0 until rules.length()){
                val rule=rules.getJSONObject(index)
                if(rule.optBoolean("enabled",true)&&rule.has("nativeMatch")){
                    val required=org.mcrf.steerprep.json.SteerNativeFlowShape.inspect(rule.getJSONObject("nativeMatch"))
                    flowRules=flowRules||required.flow;nativeOwnerRules=nativeOwnerRules||required.owner
                }
            }
            require(!flowRules||contract.perFlowRules){"Установленный Steer не заявляет правила исходного соединения: адреса, порты и сеть. Настройки не изменены."}
            require(!nativeOwnerRules||contract.perAppRules){"Установленный Steer не заявляет определение исходного приложения. Настройки не изменены."}
            require(!flowRules||!tun.getBoolean("mixedEnabled")||contract.supportsProxyFlow()){
                "Установленный Steer не заявляет проверку исходного клиента и запроса локального прокси для точных правил. Настройки не изменены."
            }
            require(geoPlan?.neededSelectors?.isNotEmpty()!=true||contract.geoRules){"Установленный Steer не заявляет подготовленные геоданные. Настройки не изменены."}
            val presets=rules!=null&&(0 until rules.length()).any { index -> rules.getJSONObject(index).let {it.optBoolean("enabled",true)&&it.has("presetIds")} }
            require(!presets||contract.presetRules) {"Установленный Steer не заявляет готовые списки SRS. Настройки не изменены."}
            val ruleDns=rules!=null&&(0 until rules.length()).any {index->rules.getJSONObject(index).let {rule->
                rule.optBoolean("enabled",true)&&rule.optString("target")!="block"&&rule.optJSONObject("dns")!=null
            }}
            require(!ruleDns||contract.perRuleDns) {"Установленный Steer не заявляет отдельный DNS правил. Настройки не изменены."}
            val appRules=(captured.optJSONArray("vpnApps")?.length()?:0)>0||rules!=null&&(0 until rules.length()).any {index->rules.getJSONObject(index).let {rule->
                rule.optBoolean("enabled",true)&&rule.optString("mode","any")!="addresses"&&
                    RouteRules.parse(rule.getString("text")).getValue("package_name").isNotEmpty()
            }}
            require(!appRules||contract.perAppRules) {"Установленный Steer не заявляет определение исходного приложения для правил. Настройки не изменены."}
        }
        val snapshot=JSONObject().put("schema",contract.model).put("mode",if(vpn)"vpn"else"proxy")
            .put("routing",captured).put("tun",tun)
            .put("coreTuning",CoreTuningStore(context).load().json()).put("additionalRequiredFeatures",additional)
        if(contract.model==1)snapshot.put("acceptSteerIpv4DnsModel",accepted?:prefs.getBoolean("steerIpv4DnsAccepted",false))
        else {
            snapshot.put("acceptSteerDnsModel",true)
            snapshot.put("ipv4Compatibility",loadedTun.ipv4Compatibility)
            if(captured.optString("appMode","all")=="include")snapshot.put("hostPackage",context.packageName)
            if(tun.getBoolean("mixedEnabled"))snapshot.put("localProxyAuth",store.auth())
            if(geoPlan!=null&&geoPlan.neededSelectors.isNotEmpty())snapshot.put("geoManifest",SteerGeoPreparation.capture(context,captured,geoPlan))
        }
        return SteerMcrfSnapshotAdapter.convert(snapshot.toString())
    }
    fun steerFailure(result:SteerMcrfSnapshotAdapter.Result):String = "Steer не поддерживает текущий набор параметров. Ничего не изменено.\n"+
        result.unsupported.map {SteerCompatibilityHints.describe(it.code,it.field)}.distinct().take(10).joinToString("\n")
    fun prepared(context:Context,raw:String,vpn:Boolean,port:Int):JSONObject {
        val store=ClientTunStore(context)
        var config=LocalProxyConfig.apply(ProfileConfig.build(raw,vpn,port),store.auth())
        val routing=RoutingStore(context);routing.load();val snapshot=routing.snapshot()
        val catalogue=Connections(context);catalogue.load();val routeServers=catalogue.entries()
        config=RouteRules.apply(config,snapshot,vpn,PresetAssets.resolve(context,snapshot),PresetAssets.ipRuleSetTags(context,snapshot),DirectDnsNetwork.ipv4Only(context),routeServers)
        RouteServerSession.check(context,RouteServerPlan(snapshot,routeServers),java.util.function.BooleanSupplier{false})
        val tuning=CoreTuningStore(context).load()
        val plan=OptionalCorePlan(config,OptionalCoreStore(context).load(),tuning)
        val bridges=linkedMapOf<String,JSONObject>()
        plan.preflight(java.util.function.BooleanSupplier{false}) {entry->
            OptionalCoreProcess.check(context,entry.request,java.util.function.BooleanSupplier{false})
            bridges[entry.tag]=OptionalCoreConfig.bridgeOutbound(entry.tag,10900+bridges.size,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),entry.request.kind)
        }
        return ClientTunConfig.apply(CoreTuningConfig.apply(plan.replace(bridges),tuning),store.load(),store.auth(),port).config
    }
    /** Strict installed-native validation, off UI; backup and editor use the same gate. */
    fun validateNative(context:Context,config:String,vpn:Boolean) {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Проверка движка выполняется в фоне."}
        val choice=effectiveWireGuardChoice(context,requireSelected(context),WgAddonPolicy.usesWireGuard(JSONObject(config)))
        require(choice=="singbox") {"Для Steer нужна проверка полного снимка настроек. Этот формат пока доступен через sing-box."}
        val worker=ClientPrimaryWorker.connect(context,choice,System.nanoTime().coerceAtLeast(1),null)
        try {
            val result=worker.validate("",envelope(JSONObject(config),vpn,ClientBehaviorStore.load(context).metered),if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY)
            require(result.supported) {"Выбранный движок не поддерживает профиль (${result.code}). Исходные настройки сохранены."}
        } finally {worker.close()}
    }
}
