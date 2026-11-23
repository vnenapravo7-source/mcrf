package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Source-only inventory: no Activity/service launch, network, logs/env, mutating
 * store APIs, files written, or engine commands. Execution is separately gated:
 * ordinary instrumentation may restart its target and must NOT be used on a VPN.
 * PID mode needs fresh appPid/hostPid; mode=catalogue needs no process inventory.
 * Negotiated values are deliberately unknown: the current Go bridge does not
 * expose WORKERS as retained telemetry. Never substitute configured or active.
 */
class CsqttEffectiveConfigReadOnlyInstrumentation : Instrumentation() {
    private var input = Bundle()
    private enum class CatalogueStage {
        DEBUG_CHECK, STORAGE_BEFORE, ARGUMENTS, STORE_LOCATION, EXISTING_KEY,
        PREFERENCES_READ, CONNECTIONS_READ, SELECTION_READ, ENTRY_LOOKUP,
        PROFILE_TYPE, SETTINGS_READ, PURE_MERGE, NUMERIC_FIELDS, PURE_CONFIG,
        DEADLINE_CHECK, COMPLETE, STORAGE_AFTER
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        input = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        if (input.getString("mode") == "catalogue") {
            catalogueSnapshot()
            return
        }
        val result = JSONObject().put("status", "UNAVAILABLE")
            .put("implementation", JSONObject.NULL)
            .put("configured_workers", JSONObject.NULL).put("exact_workers", JSONObject.NULL)
            .put("effective_workers", JSONObject.NULL).put("server_limit", JSONObject.NULL)
            .put("active_workers", JSONObject.NULL)
        var code = Activity.RESULT_CANCELED
        try {
            check(BuildConfig.DEBUG)
            val appPid = input.getString("appPid")?.toIntOrNull() ?: error("INPUT")
            val hostPid = input.getString("hostPid")?.toIntOrNull() ?: error("INPUT")
            check(appPid == Process.myPid() && hostPid > 0)
            val processDirectory = File("/proc/$hostPid")
            check(Os.stat(processDirectory.path).st_uid == Process.myUid())
            val stamp = processStamp(hostPid)
            val cache = File(targetContext.applicationInfo.dataDir, "cache").canonicalFile
            val directory = File(Os.readlink("/proc/$hostPid/cwd")).canonicalFile
            check(directory.parentFile == cache && directory.name.matches(
                Regex("mcrf-carrier-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            check(!Files.isSymbolicLink(File(cache, directory.name).toPath()))
            val file = File(directory, "config.json")
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1L..65536L)
            val bytes = bounded(file, 65536)
            val profile = JSONObject(String(bytes, Charsets.UTF_8))
            check(profile.optString("csqtt_generation") == "2.5")
            val implementation = profile.optString("csqtt_implementation").takeIf { it.isNotEmpty() }
            check(implementation == null || implementation in
                setOf("swg-panel-2.5.1", "swg-panel-2.5.2", "swg-panel-2.5.3"))
            val configured = profile.getInt("workers")
            check(configured in 3..100 && profile.opt("exact_workers") is Boolean)

            // Only bind in-memory counters to this exact runtime directory.
            // A selected catalogue entry alone would not prove active ownership.
            val service = try {
                McrfVpnService::class.java.getDeclaredField("currentService")
                    .apply { isAccessible = true }.get(null)
            } catch (_: Exception) { null }
            val carrier = service?.let { field(it, "carrier") } as? NativeCarrier
            val ownedDirectory = carrier?.let { field(it, "directory") } as? File
            val selected = if (ownedDirectory?.canonicalFile == directory && service != null)
                field(service, "selectedId") as? String else null
            val active = if (selected != null) carrier?.actualStreams()?.takeIf { it in 0..65535 } else null
            check(processStamp(hostPid) == stamp &&
                File(Os.readlink("/proc/$hostPid/cwd")).canonicalFile == directory &&
                bytes.contentEquals(bounded(file, 65536)))
            if (selected != null) {
                val owner = requireNotNull(service)
                check(field(owner, "carrier") === carrier && field(owner, "selectedId") == selected)
            }
            result.put("status", "CONFIG_OBSERVED_NEGOTIATION_UNAVAILABLE")
                .put("implementation", implementation ?: JSONObject.NULL).put("configured_workers", configured)
                .put("exact_workers", if (profile.getBoolean("exact_workers")) 1 else 0)
                .put("active_workers", active ?: JSONObject.NULL)
            code = Activity.RESULT_OK
        } catch (_: Exception) {
            // Never render exception messages, a JSON payload, paths or secrets.
        }
        finish(code, Bundle().apply { putString("csqtt_numeric_inventory", result.toString()) })
    }

    /** Detached saved configuration, not proof of a running or negotiated session.
     * mode=catalogue optionally accepts implementation=swg-panel-2.5.1/2/3.
     * This selects a read target only; it never changes the user's selection. */
    private fun catalogueSnapshot() {
        val output = emptyCatalogueResult()
        var code = Activity.RESULT_CANCELED
        var before: Map<String, String>? = null
        var stage = CatalogueStage.DEBUG_CHECK
        var failureStatus = "UNAVAILABLE"
        val deadline = SystemClock.elapsedRealtime() + 15_000
        try {
            check(BuildConfig.DEBUG)
            stage = CatalogueStage.STORAGE_BEFORE
            before = protectedHashes(deadline)
            stage = CatalogueStage.ARGUMENTS
            failureStatus = "INVALID_ARGUMENTS"
            check(input.keySet().all { it in setOf("mode", "implementation") })
            check(input.get("mode") == "catalogue")
            val requestedImplementation = if (input.containsKey("implementation")) {
                (input.get("implementation") as? String).also {
                    check(it in setOf("swg-panel-2.5.1", "swg-panel-2.5.2", "swg-panel-2.5.3"))
                }
            } else null
            failureStatus = "UNAVAILABLE"
            output.put("catalogue_scope", if (requestedImplementation == null)
                "SELECTED_CATALOGUE_NOT_NEGOTIATED" else "MATCHING_CATALOGUE_NOT_NEGOTIATED")
            stage = CatalogueStage.STORE_LOCATION
            val root = File(targetContext.applicationInfo.dataDir, "files").canonicalFile
            check(root.isDirectory)
            val pointer = File(root, "active-profile.txt")
            refuseSidecars(pointer)
            val stores = if (pointer.exists()) {
                val name = String(bounded(pointer, 128), Charsets.US_ASCII)
                check(name.matches(Regex("mcrf-store-[0-9a-f-]{36}")))
                File(root, name).also {
                    check(it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root)
                }
            } else root
            // Never call ProfileStore.read(): AtomicFile can restore a backup,
            // and ProfileStore.key() can generate a key. Use an EXISTING key only.
            stage = CatalogueStage.EXISTING_KEY
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(keyStore.containsAlias("mcrf-profile-v1"))
            val key = keyStore.getKey("mcrf-profile-v1", null) as? SecretKey ?: error("KEY_UNAVAILABLE")
            stage = CatalogueStage.PREFERENCES_READ
            val preferences = encryptedJson(File(stores, "preferences.enc"), key)
            stage = CatalogueStage.CONNECTIONS_READ
            val connections = encryptedJson(File(stores, "connections.enc"), key)
            val entries = ConnectionCatalogue(connections.toString()).entries()
            val entry = if (requestedImplementation == null) {
                stage = CatalogueStage.SELECTION_READ
                val plainPreferences = File(targetContext.applicationInfo.dataDir, "shared_prefs/MainActivity.xml")
                refuseSidecars(plainPreferences)
                val selected = selectedFromXml(plainPreferences, deadline)
                check(selected.matches(Regex("[0-9a-f]{64}")))
                stage = CatalogueStage.ENTRY_LOOKUP
                entries.single { it.id == selected }
            } else {
                stage = CatalogueStage.ENTRY_LOOKUP
                val matching = entries.firstOrNull { candidate -> candidate.carrier?.let {
                    Csqtt25Profile.isProfile(it) && it.optString("csqtt_implementation") == requestedImplementation
                } == true }
                if (matching == null) {
                    failureStatus = "NO_MATCHING_SAVED_PROFILE"
                    error("NO_MATCH")
                }
                matching
            }
            stage = CatalogueStage.PROFILE_TYPE
            val carrier = entry.carrier
            if (carrier == null || !Csqtt25Profile.isProfile(carrier)) {
                failureStatus = "NOT_CSQTT25_SELECTION"
                error("NOT_MODERN")
            }
            val base = JSONObject(carrier.toString())
            stage = CatalogueStage.SETTINGS_READ
            val settings = JSONObject((preferences.optJSONObject(CarrierStreamDefaults.SECTION) ?: JSONObject()).toString())
            val deploy = preferences.optJSONObject(AndroidDeployBackupSettings.SECTION) ?: JSONObject()
            val vkSettings = deploy.optJSONObject("vkSettings") ?: JSONObject()
            val memory = object : VkRelayPersistence {
                override fun get(section: String): JSONObject =
                    JSONObject((deploy.optJSONObject(section) ?: JSONObject()).toString())
                override fun update(section: String, change: (JSONObject) -> Unit) { error("WRITE_DISABLED") }
                override fun remove(section: String) { error("WRITE_DISABLED") }
                override fun verifyCaptcha(url: String, cancelled: () -> Boolean): String = error("NETWORK_DISABLED")
            }
            val account = VkRelayAccount(memory, { error("NETWORK_DISABLED") }, { error("WAIT_DISABLED") })
            // EXACT NativeCarrier order; never prepare/resolve/create VK calls.
            stage = CatalogueStage.PURE_MERGE
            val merged = VkRelayRuntime.withManualSupplements(JSONObject(base.toString()), account)
            stage = CatalogueStage.NUMERIC_FIELDS
            val global = settings.optInt("csqtt25", CarrierStreamDefaults.default("csqtt25"))
            val overrideWorkers = optionalCount(base, CarrierStreamDefaults.OVERRIDE)
            val implementation = base.optString("csqtt_implementation").takeIf { it in
                setOf("compatible-2.5", "swg-panel-2.5.1", "swg-panel-2.5.2", "swg-panel-2.5.3") }
            // Import normalization may insert false. Report stored presence
            // separately; do not call normalized false an explicitly saved flag.
            val storedCarrier = connections.optJSONObject("edits")?.optJSONObject(entry.id)?.optJSONObject("carrier")
                ?: entry.original.trim().takeIf { it.startsWith('{') }?.let {
                    runCatching { JSONObject(it) }.getOrNull()?.takeIf { raw -> Csqtt25Profile.isProfile(raw) }
                }
            val storedRedistribution = storedCarrier?.let { optionalFlag(it, "allow_hash_redistribution") }
            output.put("desired_global_workers", optionalCount(settings, "csqtt25") ?: JSONObject.NULL)
                .put("global_workers", global).put("desired_workers", overrideWorkers ?: global)
                .put("link_workers", optionalCount(base, "link_workers") ?: JSONObject.NULL)
                .put("override_workers", overrideWorkers ?: JSONObject.NULL)
                .put("manual_base_hash_count", base.optJSONArray("hashes")?.length() ?: JSONObject.NULL)
                .put("merged_hash_count", merged.optJSONArray("hashes")?.length() ?: JSONObject.NULL)
                .put("redistribution_flag", storedRedistribution ?: JSONObject.NULL)
                .put("effective_redistribution_flag", if (merged.optBoolean("allow_hash_redistribution", false)) 1 else 0)
                .put("current_selection_implementation", implementation ?: JSONObject.NULL)
            // Reuse the now-present shared selection schema, not endpoint count.
            // Missing is null; explicit Automatic is zero; one chosen pool is one.
            if (vkSettings.has(VkTurnSubnets.SELECTED) && !vkSettings.isNull(VkTurnSubnets.SELECTED)) {
                output.put("subnet_count", if (VkTurnSubnets.read(vkSettings).subnetId.isEmpty()) 0 else 1)
            }
            // Preserve safe inputs if the actual pure admission rule rejects an
            // explicit override; never pretend the result was clamped anyway.
            stage = CatalogueStage.PURE_CONFIG
            val effective = try { CarrierStreamDefaults.apply(merged, settings) }
                catch (_: IllegalArgumentException) { null }
            if (effective == null) {
                output.put("status", "PURE_CONFIG_REJECTED")
            } else {
                output.put("effective_workers", effective.getInt("workers"))
                    .put("effective_link_workers", optionalCount(effective, "link_workers") ?: JSONObject.NULL)
                    .put("status", "CATALOGUE_CONFIG_ONLY_NOT_NEGOTIATED")
                code = Activity.RESULT_OK
            }
            stage = CatalogueStage.DEADLINE_CHECK
            check(SystemClock.elapsedRealtime() <= deadline)
            stage = CatalogueStage.COMPLETE
        } catch (_: Exception) {
            clearCatalogueResult(output, failureStatus)
            code = Activity.RESULT_CANCELED
        } finally {
            val after = if (before == null) null else try {
                protectedHashes(SystemClock.elapsedRealtime() + 15_000)
            } catch (_: Exception) { null }
            if (before == null || after == null || before != after) {
                clearCatalogueResult(output, if (before == null || after == null) "STORAGE_UNVERIFIED" else "STORAGE_CHANGED")
                output.put("stores_unchanged", if (before != null && after != null) 0 else JSONObject.NULL)
                stage = CatalogueStage.STORAGE_AFTER
                code = Activity.RESULT_CANCELED
            } else output.put("stores_unchanged", 1)
            output.put("stage", stage.name)
        }
        finish(code, Bundle().apply { putString("csqtt_numeric_inventory", output.toString()) })
    }

    private fun emptyCatalogueResult(): JSONObject = JSONObject().put("status", "UNAVAILABLE").apply {
        for (name in listOf("desired_global_workers", "global_workers", "desired_workers", "link_workers",
            "override_workers", "effective_workers", "effective_link_workers", "manual_base_hash_count",
            "merged_hash_count", "redistribution_flag", "effective_redistribution_flag",
            "current_selection_implementation", "subnet_count", "stores_unchanged")) put(name, JSONObject.NULL)
    }

    private fun clearCatalogueResult(output: JSONObject, status: String) {
        val empty = emptyCatalogueResult()
        empty.keys().forEach { key -> output.put(key, empty.get(key)) }
        output.put("status", status)
    }

    private fun optionalCount(value: JSONObject, name: String): Int? {
        if (!value.has(name) || value.isNull(name)) return null
        check(value.get(name) is Int)
        return value.getInt(name).also { check(it in 0..100) }
    }

    private fun optionalFlag(value: JSONObject, name: String): Int? {
        if (!value.has(name) || value.isNull(name)) return null
        check(value.get(name) is Boolean)
        return if (value.getBoolean(name)) 1 else 0
    }

    private fun refuseSidecars(file: File) {
        check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        check(!Files.isSymbolicLink(file.toPath()))
    }

    private fun encryptedJson(file: File, key: SecretKey): JSONObject {
        refuseSidecars(file)
        if (!file.exists()) return JSONObject()
        val bytes = bounded(file, 8 * 1024 * 1024)
        check(bytes.size >= 32 && java.nio.ByteBuffer.wrap(bytes, 0, 4).int == 0x4d435246)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        }
        return JSONObject(String(cipher.doFinal(bytes, 16, bytes.size - 16), Charsets.UTF_8))
    }

    private fun selectedFromXml(file: File, deadline: Long): String {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false)
        parser.setInput(String(bounded(file, 4 * 1024 * 1024), Charsets.UTF_8).reader())
        var selected: String? = null
        while (true) {
            check(SystemClock.elapsedRealtime() <= deadline)
            when (parser.nextToken()) {
                XmlPullParser.DOCDECL -> error("XML_DECLARATION_DISABLED")
                XmlPullParser.END_DOCUMENT -> return requireNotNull(selected)
                XmlPullParser.START_TAG -> if (parser.name == "string" && parser.getAttributeValue(null, "name") == "selected") {
                    check(selected == null)
                    selected = parser.nextText().also { check(it.length <= 128) }
                }
            }
        }
    }

    /** Private digest map; never export paths, hashes or bytes. Only the exact
     * credential-protected shared_prefs/AndroidIDS.xml is excluded, not its
     * backups, other preferences, device-protected copy or similarly named files.
     */
    private fun protectedHashes(deadline: Long): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var count = 0
        var total = 0L
        val app = targetContext.applicationInfo
        val credentialRoot = File(app.dataDir)
        val ignored = File(credentialRoot, "shared_prefs/AndroidIDS.xml").absoluteFile
        fun visit(label: String, file: File, depth: Int) {
            check(SystemClock.elapsedRealtime() <= deadline && depth <= 5)
            check(!Files.isSymbolicLink(file.toPath()))
            if (!file.exists()) return
            if (file.absoluteFile == ignored) {
                check(file.isFile)
                return
            }
            check(++count <= 4096)
            if (file.isDirectory) {
                result["$label/"] = "DIRECTORY"
                val children = requireNotNull(file.listFiles())
                check(children.size <= 4096)
                children.sortedBy { it.name }.forEach { visit("$label/${it.name}", it, depth + 1) }
            } else {
                check(file.isFile && file.length() <= 128L * 1024 * 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(SystemClock.elapsedRealtime() <= deadline)
                        val size = stream.read(buffer)
                        if (size < 0) break
                        check(size > 0)
                        total += size
                        // Three retained app/module APK versions legitimately
                        // exceed 128 MiB; keep the same full-content proof and
                        // deadline with a bounded allowance for that archive.
                        check(total <= 512L * 1024 * 1024)
                        digest.update(buffer, 0, size)
                    }
                }
                result[label] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
        // Context.getFilesDir/getNoBackupFilesDir can mkdir lazily: construct
        // paths from ApplicationInfo instead, and never create missing roots.
        visit("credential-files", File(credentialRoot, "files"), 0)
        visit("credential-preferences", File(credentialRoot, "shared_prefs"), 0)
        visit("credential-databases", File(credentialRoot, "databases"), 0)
        visit("credential-no-backup", File(credentialRoot, "no_backup"), 0)
        val deviceRoot = File(requireNotNull(app.deviceProtectedDataDir))
        visit("device-files", File(deviceRoot, "files"), 0)
        visit("device-preferences", File(deviceRoot, "shared_prefs"), 0)
        visit("device-databases", File(deviceRoot, "databases"), 0)
        visit("device-no-backup", File(deviceRoot, "no_backup"), 0)
        return result
    }

    private fun bounded(file: File, maximum: Int): ByteArray = file.inputStream().use { stream ->
        val bytes = ByteArray(maximum + 1)
        var size = 0
        while (size < bytes.size) {
            val count = stream.read(bytes, size, bytes.size - size)
            if (count < 0) break
            check(count > 0)
            size += count
        }
        check(size in 1..maximum)
        bytes.copyOf(size)
    }

    private fun processStamp(pid: Int): String {
        val stat = String(bounded(File("/proc/$pid/stat"), 4096), Charsets.US_ASCII)
        val fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
        return fields[19].also { check(it.matches(Regex("[0-9]+"))) }
    }

    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val current = type
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            } catch (_: NoSuchFieldException) {
                type = current.superclass
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }
}
