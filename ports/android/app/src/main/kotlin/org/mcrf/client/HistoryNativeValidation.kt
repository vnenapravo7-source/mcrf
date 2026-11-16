package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.steerprep.SteerGeoProjection
import org.mcrf.steerprep.SteerMcrfSnapshotAdapter

/** Validate the captured version, including its core choice; never apply it to live stores. */
internal object HistoryNativeValidation {
    fun validate(context:Context,values:Map<String,String>,preferences:JSONObject,vpn:Boolean) {
        val choice=preferences.optString(ClientPrimarySelection.KEY).ifBlank {ClientPrimarySelection.requireSelected(context)}
        require(choice in setOf("singbox","steer")){"Неизвестный основной движок в снимке."}
        val raw=values.getValue("profile.enc")
        var profile=""
        val settings=if(choice=="singbox") {
            val config=ProfileConfig.build(raw,vpn,preferences.optInt("port",10808)).apply {remove("mcrf_carrier")}
            ClientPrimarySelection.envelope(config,vpn,preferences.optJSONObject(ClientBehaviorSettings.KEY)?.optBoolean("metered",true)?:true)
        } else {
            val source=values.getValue("routing.enc")
            val routing=if(source.isBlank())RouteRules.defaults()else ExperienceRouting.project(JSONObject(source),ExperienceModeStore.parse(preferences.optString(ExperienceModeStore.KEY,"expert")))
            TextGeoRules.stripSources(routing)
            RouteServerPlan.requireSingleServerCore(routing)
            val contract=ClientPrimarySelection.steerContract(context)
            val tun=preferences.optJSONObject(ClientTunSettings.KEY)?.let(ClientTunSettings::fromJson)
                ?:ClientTunSettings(mixedPort=preferences.optInt("port",10808))
            val extras=preferences.optJSONObject(OptionalCoreSettings.KEY)?.let(OptionalCoreSettings::fromJson)?:OptionalCoreSettings()
            val additional=JSONArray()
            if(preferences.optBoolean("automatic")&&!contract.independentProbe)additional.put("automatic-selection")
            if(extras.xrayVlessDefault||extras.xrayMuxDefault||extras.customFragmentDefault||extras.mixedCaseSniDefault)additional.put("optional-core-defaults")
            val captured=RoutingGroups.runtime(routing)
            val snapshot=JSONObject().put("schema",contract.model).put("mode",if(vpn)"vpn"else"proxy")
                .put("routing",captured).put("tun",tun.json().apply {remove("ipv4Compatibility")})
                .put("coreTuning",preferences.optJSONObject(CoreTuningSettings.KEY)?:CoreTuningSettings().json()).put("additionalRequiredFeatures",additional)
            if(contract.model==2) {
                require(preferences.optBoolean(ClientPrimarySelection.DNS_MODEL_V2_ACCEPTED)){"В снимке не подтверждена модель DNS Steer."}
                snapshot.put("acceptSteerDnsModel",true).put("ipv4Compatibility",tun.ipv4Compatibility)
                if(captured.optString("appMode","all")=="include")snapshot.put("hostPackage",context.packageName)
                if(tun.mixedEnabled)snapshot.put("localProxyAuth",preferences.optJSONObject("localProxy")?:error("В снимке нет настроек локального прокси."))
                val plan=SteerGeoProjection.plan(captured)
                if(plan.neededSelectors.isNotEmpty())snapshot.put("geoManifest",SteerGeoPreparation.capture(context,captured,plan))
            } else snapshot.put("acceptSteerIpv4DnsModel",preferences.optBoolean("steerIpv4DnsAccepted"))
            val converted=SteerMcrfSnapshotAdapter.convert(snapshot.toString())
            require(converted.supported){ClientPrimarySelection.steerFailure(converted)}
            val entries=ConnectionCatalogue(values.getValue("connections.enc")).entries()
            profile=entries.firstOrNull {it.id==preferences.optString("selected")}?.original
                ?:error("В снимке не найден выбранный сервер Steer.")
            converted.settingsJson
        }
        val worker=ClientPrimaryWorker.connect(context,choice,System.nanoTime().coerceAtLeast(1),null,
            ClientPrimaryValidationWorkerService::class.java)
        try {
            val result=worker.validate(profile,settings,if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY)
            require(result.supported){"Движок не поддерживает снимок (${result.code}). Настройки не изменены."}
        } finally {worker.close()}
    }
}
