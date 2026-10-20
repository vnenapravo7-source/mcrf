package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.function.Consumer

object BackupDeviceChecks {
    @JvmStatic fun run(base: Context, temporary: File, passed: Consumer<String>) {
        val preferenceNames = mutableListOf<String>()
        fun context(name: String): Context {
            val root = File(temporary, name).also { check(it.mkdir()) }
            val prefix = "mcrf-backup-fixture-${UUID.randomUUID()}-"
            return object : ContextWrapper(base) {
                override fun getFilesDir() = root
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                    val key = prefix+name; if (key !in preferenceNames) preferenceNames.add(key)
                    return base.getSharedPreferences(key, mode)
                }
            }
        }
        fun verify(condition: Boolean, message: String) { check(condition) { message }; passed.accept(message) }
        val password = "Fixture-only-password".toCharArray()
        try {
            val actual=ProfileStore(base,"preferences.enc").read()
            AndroidBackup.validatePreferences(if(actual.isEmpty())JSONObject()else JSONObject(actual))
            passed.accept("Actual encrypted user settings, including any pending WARP account, pass backup validation without changes")
            // Production restore validates through the separately installed primary
            // module. The base APK intentionally no longer carries go.Seq/libbox.
            val source = context("source"); val destination = context("destination")
            check(destination.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString(ClientPrimarySelection.KEY,"singbox").commit())
            AndroidBackup.registerSection("backupFixtureSection") { require(it.getInt("mtu") in 1280..9000) }
            AndroidBackup.registerSection("backupFixtureGeneral",BackupDefaults.Group.GENERAL) { require(it.get("reconnect") is Boolean) }
            AndroidBackup.registerSection("backupFixtureProtected",BackupDefaults.Group.PRESERVE) { require(it.getString("token") == "fixture-only-preserved-token") }
            AndroidBackup.registerPreference("backupFixtureToggle",false)
            val proxyBefore = LocalProxySettings.value(source).toString()
            val extensions = JSONObject(ProfileStore(source,"preferences.enc").read())
                .put("backupFixtureSection",JSONObject().put("mtu",1400).put("secret","fixture-only-secret"))
                .put("backupFixtureGeneral",JSONObject().put("reconnect",true))
                .put("backupFixtureProtected",JSONObject().put("token","fixture-only-preserved-token"))
            // Exercise the completed production validators as well as the generic registry.
            // These are inert fixture values: no VPN, API listener, login or deployment is started.
            val productionSections = linkedMapOf(
                "clientTun" to JSONObject().put("mtu",1450).put("mixedPort",18180),
                "coreTuning" to JSONObject().put("clashSecret","fixture-only-clash-secret").put("apiSecret","fixture-only-api-secret").put("muxConcurrency",16),
                "subscriptionOptions" to JSONObject().put("userAgent","MCRF-Fixture/1.0").put("intervalMinutes",45).put("clearOnSuccess",false).put("sendDeviceHeaders",false),
                "generalOptions" to JSONObject().put("metered",true).put("resetOnWake",false),
                "androidDeploy" to JSONObject().put("vk",JSONObject().put("token","fixture-only-vk-token").put("user","fixture-user").put("expires",0))
                    .put("sshTrust",JSONObject().put("fixture.invalid:22","SHA256:"+"A".repeat(43))),
                "warpOptions" to JSONObject().put("excludeDme",false).put("extended",true).put("termsAccepted",false),
                "optionalCores" to JSONObject().put("xrayMuxConcurrency",16).put("fragmentSize","13-91")
            )
            check(productionSections.keys.all { it in AndroidBackup.sectionKeys() }) { "Production backup settings registry is incomplete." }
            productionSections.forEach { (key,value) -> extensions.put(key,value) }
            extensions.put("webDavBackup",JSONObject().put("url","retired-opaque-value").put("username","fixture-user").put("password","fixture-dav-secret").put("future",JSONObject().put("kept",true)))
            ProfileStore(source,"preferences.enc").write(extensions.toString())
            ProfileStore(source).write("{\"outbounds\":[{\"tag\":\"fixture-direct\",\"type\":\"direct\"}],\"route\":{\"final\":\"fixture-direct\"}}")
            val nodes = Connections(source); nodes.load(); nodes.add("socks://127.0.0.1:18080#Backup%20fixture")
            val id = nodes.entries().single().id; nodes.setAuto(id, true); nodes.edit(id, JSONObject().put("name", "Edited fixture"))
            val routes = RoutingStore(source); routes.load()
            routes.save(routes.snapshot().put("advancedDns",AdvancedDnsConfig.defaults().put("cache",false).put("timeout","20s").put("answers","192.0.2.77 backup-fixture.invalid")))
            verify(routes.snapshot().getJSONArray("rules").getJSONObject(0).getString("name") == "Всё кроме РФ", "Fresh routes include editable outside-Russia rule")
            val sourcePrefs = source.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
            check(sourcePrefs.edit().putString(ClientPrimarySelection.KEY,"singbox").commit())
            check(sourcePrefs.edit().putString("theme", "dark").putBoolean("tun", false).putBoolean("backupFixtureToggle",true).putString("installationHwid", "fixture-private-hwid").commit())
            // Change a complete preference batch immediately after its first snapshot.
            // Export must contain batch A, not the first value of A and later values of B.
            check(sourcePrefs.edit().putInt("port",18180).commit())
            var switched = false
            val changingPreferences = object : android.content.SharedPreferences by sourcePrefs {
                override fun getAll(): MutableMap<String, *> {
                    val before = sourcePrefs.all.toMutableMap()
                    if(!switched) {
                        switched = true
                        check(sourcePrefs.edit().putBoolean("tun",true).putString("theme","light").putInt("port",19180).commit())
                    }
                    return before
                }
            }
            val changingContext = object : ContextWrapper(source) {
                override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences =
                    if(name == "MainActivity") changingPreferences else source.getSharedPreferences(name,mode)
            }
            val changingBackup = AndroidBackup.export(changingContext,password)
            val changingPlain = BackupCodec.decrypt(changingBackup,password)
            try {
                val captured = JSONObject(JSONObject(String(changingPlain,Charsets.UTF_8)).getJSONObject("stores").getString("preferences.enc"))
                verify(!captured.getBoolean("tun") && captured.getString("theme") == "dark" && captured.getInt("port") == 18180,
                    "Export captures one coherent preference batch while fixture preferences change")
            } finally { changingPlain.fill(0); changingBackup.fill(0) }
            check(sourcePrefs.edit().putBoolean("tun",false).putString("theme","dark").putInt("port",18180).commit())
            val existing = ProfileStore(destination, "connections.enc"); existing.write(ConnectionCatalogue("").serialize())
            val oldFile = File(destination.filesDir, "connections.enc"); val oldCiphertext = oldFile.readBytes()
            val encrypted = AndroidBackup.export(source, password)
            verify(!String(encrypted, Charsets.ISO_8859_1).contains("Backup fixture"), "Portable backup does not expose server names")
            AndroidBackup.restore(destination, encrypted, password)
            verify(ProfileStore.generation(destination).isNotEmpty(), "Restore commits an encrypted generation")
            verify(ProfileStore(destination).read().contains("fixture-direct"), "Saved profile passes real native config validation before restore commit")
            verify(oldFile.readBytes().contentEquals(oldCiphertext), "Original encrypted stores remain recoverable and unchanged")
            verify(existing.read().contains("Edited fixture"), "Existing store instance follows the committed generation")
            val restored = Connections(destination); restored.load()
            verify(restored.entries().single().name == "Edited fixture" && restored.autoIds() == setOf(id), "Local connection edits and explicit auto pool survive portable restore")
            verify(AndroidBackup.applyRestoredPreferences(destination), "Preferences apply after durable restore commit")
            val targetPrefs = destination.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
            verify(targetPrefs.getString("theme", "") == "dark" && !targetPrefs.getBoolean("tun", true) && !targetPrefs.contains("installationHwid"), "Theme and mode restored without copying HWID")
            verify(!AndroidBackup.applyRestoredPreferences(destination), "Startup does not overwrite preferences twice")
            verify(targetPrefs.getBoolean("backupFixtureToggle",false), "Registered new preference survives restore")
            verify(JSONObject(ProfileStore(destination,"preferences.enc").read()).getJSONObject("backupFixtureSection").getString("secret") == "fixture-only-secret", "Registered extension settings and credentials survive encrypted restore")
            verify(JSONObject(ProfileStore(destination,"preferences.enc").read()).getJSONObject("webDavBackup").getString("password") == "fixture-dav-secret", "Opaque retired encrypted settings survive local restore")
            verify(LocalProxySettings.value(destination).toString() == proxyBefore, "Local proxy authentication survives restore")
            verify(ProfileStore(destination,"routing.enc").read() == ProfileStore(source,"routing.enc").read(), "Routing rules and nondefault advanced DNS settings survive encrypted restore exactly")
            val restoredSettings = JSONObject(ProfileStore(destination,"preferences.enc").read())
            productionSections.forEach { (key, expected) ->
                verify(restoredSettings.getJSONObject(key).toString() == expected.toString(), "Production $key settings/credentials round-trip through encrypted restore")
            }
            val generation = ProfileStore.generation(destination)
            try { AndroidBackup.restore(destination, encrypted, "Wrong-fixture-password".toCharArray()); error("Incorrect password accepted") } catch (_: IllegalArgumentException) { }
            verify(ProfileStore.generation(destination) == generation, "Wrong password cannot switch storage generation")
            val corrupted = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            try { AndroidBackup.restore(destination, corrupted, password); error("Corrupt backup accepted") } catch (_: IllegalArgumentException) { }
            verify(ProfileStore.generation(destination) == generation, "Corruption cannot switch storage generation")
            val values = ProfileStore.snapshot(destination).toMutableMap(); values["routing.enc"] = "x".repeat(ServerImport.MAX_BYTES+1)
            try { ProfileStore.replaceAll(destination, values); error("Oversized generation accepted") } catch (_: java.io.IOException) { }
            verify(ProfileStore.generation(destination) == generation && existing.read().contains("Edited fixture"), "Failure writing a new generation preserves the active stores")
            val connectionBeforeReset = ProfileStore(destination,"connections.enc").read()
            val routingBeforeReset = ProfileStore(destination,"routing.enc").read()
            val settingsBeforeBadReset = ProfileStore(destination,"preferences.enc").read()
            val badResetSettings = JSONObject(settingsBeforeBadReset).put("backupFixtureSection",JSONObject().put("mtu",1))
            ProfileStore(destination,"preferences.enc").write(badResetSettings.toString())
            try { BackupDefaults.reset(destination,BackupDefaults.Scope.GENERAL); error("Invalid retained section accepted during reset") } catch(_: IllegalArgumentException) {}
            verify(ProfileStore.generation(destination) == generation && targetPrefs.getString("theme","") == "dark", "Reset validation fails before generation or preferences change")
            ProfileStore(destination,"preferences.enc").write(settingsBeforeBadReset)
            BackupDefaults.reset(destination,BackupDefaults.Scope.GENERAL)
            verify(!targetPrefs.contains("theme") && !targetPrefs.contains("backupFixtureToggle"), "Scoped general reset removes registered preferences")
            verify(JSONObject(ProfileStore(destination,"preferences.enc").read()).has("backupFixtureSection") && JSONObject(ProfileStore(destination,"preferences.enc").read()).has("webDavBackup"), "General reset preserves advanced settings and opaque retired encrypted settings")
            verify(!JSONObject(ProfileStore(destination,"preferences.enc").read()).has("backupFixtureGeneral") && JSONObject(ProfileStore(destination,"preferences.enc").read()).has("backupFixtureProtected"), "General reset selects general encrypted settings and preserves account data")
            val generalResetSettings = JSONObject(ProfileStore(destination,"preferences.enc").read())
            verify(!generalResetSettings.has("generalOptions") && (productionSections.keys-"generalOptions").all(generalResetSettings::has), "GENERAL reset selects actual generalOptions and retains advanced/deploy production sections")
            BackupDefaults.reset(destination,BackupDefaults.Scope.ADVANCED)
            verify(!JSONObject(ProfileStore(destination,"preferences.enc").read()).has("backupFixtureSection") && JSONObject(ProfileStore(destination,"preferences.enc").read()).has("webDavBackup"), "Advanced reset preserves opaque retired encrypted settings")
            val advancedResetSettings = JSONObject(ProfileStore(destination,"preferences.enc").read())
            verify((productionSections.keys-setOf("generalOptions","androidDeploy")).none(advancedResetSettings::has) && advancedResetSettings.getJSONObject("androidDeploy").toString() == productionSections.getValue("androidDeploy").toString(), "ADVANCED reset removes actual network/core/subscription/WARP settings but preserves VK token and SSH trust")
            val fullResetSource = JSONObject(ProfileStore(destination,"preferences.enc").read()).put("backupFixtureSection",JSONObject().put("mtu",1400)).put("backupFixtureGeneral",JSONObject().put("reconnect",true))
            ProfileStore(destination,"preferences.enc").write(fullResetSource.toString())
            check(targetPrefs.edit().putString("theme","dark").putInt("port",18080).commit())
            BackupDefaults.reset(destination,BackupDefaults.Scope.ALL_SETTINGS)
            val fullResetSettings = JSONObject(ProfileStore(destination,"preferences.enc").read())
            verify(!targetPrefs.contains("theme") && !targetPrefs.contains("port") && !fullResetSettings.has("backupFixtureSection") && !fullResetSettings.has("backupFixtureGeneral") && fullResetSettings.has("backupFixtureProtected") && JSONObject(ProfileStore(destination,"preferences.enc").read()).has("webDavBackup"), "Full settings reset clears both groups but preserves encrypted accounts and opaque retired encrypted settings")
            verify(ProfileStore(destination,"connections.enc").read() == connectionBeforeReset && ProfileStore(destination,"routing.enc").read() == routingBeforeReset && LocalProxySettings.value(destination).toString() == proxyBefore, "All reset scopes preserve profiles, routes and local proxy credentials")
            verify(JSONObject(ProfileStore(destination,"preferences.enc").read()).getJSONObject("androidDeploy").toString() == productionSections.getValue("androidDeploy").toString(), "All reset scopes preserve actual VK credentials and SSH host trust")
            val legacy = ProfileStore.snapshot(source).toMutableMap().apply { this["preferences.enc"] = JSONObject().put("tun",false).toString() }
            val legacyEncrypted = BackupCodec.encrypt(JSONObject().put("version",1).put("stores",JSONObject(legacy as Map<*, *>)).toString().toByteArray(),password)
            check(targetPrefs.edit().putString(ClientPrimarySelection.KEY,"singbox").commit())
            try { AndroidBackup.restore(destination,legacyEncrypted,password) } finally { legacyEncrypted.fill(0) }
            verify(LocalProxySettings.value(destination).toString() == proxyBefore, "Legacy v1 backups without localProxy preserve destination proxy authentication")
            encrypted.fill(0); corrupted.fill(0); oldCiphertext.fill(0)
        } finally {
            password.fill('\u0000')
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
        }
    }
}

