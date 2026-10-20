package org.mcrf.client

import android.content.Context
import org.json.JSONObject

object AndroidBackup {
    /** Installed-primary integration must perform real strict validation or throw. */
    fun interface RuntimeValidator {
        fun validate(context: Context, config: String)
    }
    @Volatile private var runtimeValidator: RuntimeValidator? = null
    @JvmStatic fun setRuntimeValidator(validator: RuntimeValidator?) { runtimeValidator = validator }
    /** Shared strict gate: validation failure must never reach the durable generation commit. */
    internal fun runtimeValidatedCommit(config: String?, validate: ((String) -> Unit)?, commit: () -> Unit) {
        if(config != null) {
            requireNotNull(validate) { "Для восстановления профиля сначала установите и выберите основное ядро в разделе «Движки». Затем повторите восстановление." }(config)
        }
        commit()
    }
    private data class Section(val group: BackupDefaults.Group, val validate: (JSONObject) -> Unit)
    private val sections = java.util.concurrent.ConcurrentHashMap<String, Section>()
    private val additionalPreferences = java.util.concurrent.ConcurrentHashMap<String, Any>()
    /** Original hook remains source/binary compatible; new settings default to the advanced group. */
    fun registerSection(key: String, validate: (JSONObject) -> Unit) = registerSection(key, BackupDefaults.Group.ADVANCED, validate)
    fun registerSection(key: String, group: BackupDefaults.Group, validate: (JSONObject) -> Unit) {
        require(key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && key !in booleans+integers+strings+additionalPreferences.keys+setOf("localProxy","webDavBackup","wantedRunning","restoreAppliedGeneration"))
        // This mixed account/trust/deployment section is always data, never an implicit settings reset.
        sections[key] = Section(if(key == "androidDeploy") BackupDefaults.Group.PRESERVE else group, validate)
    }
    fun registerPreference(key: String, defaultValue: Any) { require(defaultValue is Boolean || defaultValue is Int || defaultValue is String); require(key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && key !in booleans+integers+strings+sections.keys+setOf("localProxy","webDavBackup","wantedRunning","restoreAppliedGeneration")); additionalPreferences[key] = defaultValue }
    internal fun preferenceKeys() = booleans+integers+strings+additionalPreferences.keys
    internal fun preferenceKeys(group: BackupDefaults.Group): Set<String> = when(group) {
        BackupDefaults.Group.GENERAL -> preferenceKeys()-setOf("port","dns","subscriptionHours")
        BackupDefaults.Group.ADVANCED -> setOf("port","dns","subscriptionHours")
        BackupDefaults.Group.PRESERVE -> emptySet()
    }
    internal fun sectionKeys(group: BackupDefaults.Group) = sections.filterValues { it.group == group }.keys.toSet()
    internal fun sectionKeys() = sections.keys.toSet()+"webDavBackup"
    private val booleans = setOf("tun", "automatic", "applyRoutes", "catalogueProfile")
    private val integers = setOf("port", "interval", "tolerance", "subscriptionHours")
    private val strings = setOf("theme", "selected", "dns", "autoPolicy")
    @JvmStatic fun export(context: Context, password: CharArray): ByteArray {
        val source = synchronized(ProfileStore.storageLock) {
            val proxyAuth=LocalProxySettings.value(context)
            val stores = ProfileStore.snapshot(context).toMutableMap()
            if (stores["connections.enc"].isNullOrEmpty()) stores["connections.enc"] = ConnectionCatalogue("").serialize()
            if (stores["routing.enc"].isNullOrEmpty()) { val routes = RoutingStore(context); routes.load(); stores["routing.enc"] = routes.snapshot().toString() }
            val preferences = stores["preferences.enc"].orEmpty().let { if(it.isEmpty()) JSONObject() else JSONObject(it) }; val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
            val preferenceSnapshot = prefs.all
            preferenceKeys().forEach { key -> preferences.remove(key); preferenceSnapshot[key]?.let { preferences.put(key, it) } }
            // A last-started runtime target is not portable authorization to auto-connect elsewhere.
            preferences.remove(AutoVpnReturn.KEY)
            preferences.put("localProxy",proxyAuth)
            validatePreferences(preferences)
            stores["preferences.enc"] = preferences.toString()
            JSONObject().put("version", 1).put("createdAt", System.currentTimeMillis()).put("stores", JSONObject(stores as Map<*, *>)).toString().toByteArray(Charsets.UTF_8)
        }
        return try { BackupCodec.encrypt(source, password) } finally { source.fill(0) }
    }
    @JvmStatic fun validate(source: JSONObject): Map<String, String> {
        require(source.getInt("version") == 1) { "Неизвестная версия бэкапа." }
        val stores = source.getJSONObject("stores")
        require(stores.keys().asSequence().toSet() == setOf("profile.enc", "connections.enc", "routing.enc", "preferences.enc")) { "Неполный или неизвестный набор настроек." }
        val values = stores.keys().asSequence().associateWith { stores.getString(it).also { value ->
            require(value.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES)
            CompatibilityImportFormats.validateJsonDepth(value)
        } }
        ConnectionCatalogue(values.getValue("connections.enc")).serialize()
        RoutingStore.validate(JSONObject(values.getValue("routing.enc")))
        val preferences = JSONObject(values.getValue("preferences.enc"))
        val hadRuntimeTarget=preferences.has(AutoVpnReturn.KEY)
        preferences.remove(AutoVpnReturn.KEY)
        validatePreferences(preferences)
        values.getValue("profile.enc").takeIf(String::isNotEmpty)?.let { ProfileConfig.build(it, preferences.optBoolean("tun", true), preferences.optInt("port", 10808)) }
        return if(hadRuntimeTarget)values+("preferences.enc" to preferences.toString())else values
    }
    @JvmStatic fun restore(context: Context, file: ByteArray, password: CharArray) {
        val plain = BackupCodec.decrypt(file, password)
        val values = try { validate(decodeDocument(plain)).toMutableMap() } finally { plain.fill(0) }
        PresetAssets.validate(context,JSONObject(values.getValue("routing.enc")))
        val validator = runtimeValidator
        val config = values.getValue("profile.enc").takeIf(String::isNotEmpty)?.let { source ->
            val preferences = JSONObject(values.getValue("preferences.enc"))
            ProfileConfig.build(source, preferences.optBoolean("tun", true), preferences.optInt("port", 10808)).apply { remove("mcrf_carrier") }.toString()
        }
        runtimeValidatedCommit(config, validator?.let { installed -> { built -> installed.validate(context,built) } }) {
          synchronized(ProfileStore.storageLock) {
            // Backups created before authenticated local sharing have no credentials.
            // Keep the destination credentials instead of silently regenerating them.
            val preferences = JSONObject(values.getValue("preferences.enc"))
            if(!preferences.has("localProxy")) {
                preferences.put("localProxy",LocalProxySettings.value(context))
                values["preferences.enc"] = preferences.toString()
            }
            ProfileStore.replaceAll(context, values)
          }
        }
        // The activity is recreated by the restore UI. Preferences are applied from the
        // committed generation on startup, also after a crash at this exact point.
    }
    /** Untrusted encrypted documents must not replace malformed UTF-8 or overflow recursive JSON parsing. */
    internal fun decodeDocument(bytes:ByteArray):JSONObject {
        require(bytes.size<=BackupCodec.MAX_PLAIN)
        val text=try{Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()}
            catch(_:java.nio.charset.CharacterCodingException){throw IllegalArgumentException("Повреждённая UTF-8 кодировка бэкапа.")}
        CompatibilityImportFormats.validateJsonDepth(text)
        val token=org.json.JSONTokener(text)
        val value=token.nextValue() as? JSONObject?:throw IllegalArgumentException("В бэкапе нужен один JSON-объект.")
        require(token.nextClean()=='\u0000') {"Лишние данные после документа бэкапа."}
        return value
    }
    /** Atomic generation carries settings; apply once, including after a crash between commit and preferences. */
    @JvmStatic fun applyRestoredPreferences(context: Context): Boolean = synchronized(ProfileStore.storageLock) {
        val generation = ProfileStore.generation(context); if (generation.isEmpty()) return@synchronized false
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        if (prefs.getString("restoreAppliedGeneration", "") == generation) return@synchronized false
        val values = JSONObject(ProfileStore(context, "preferences.enc").read()); validatePreferences(values)
        val editor = prefs.edit(); preferenceKeys().forEach(editor::remove)
        values.keys().forEach { key -> when (key) { in booleans -> editor.putBoolean(key, values.getBoolean(key)); in integers -> editor.putInt(key, values.getInt(key)); in strings -> editor.putString(key, values.getString(key)) } }
        additionalPreferences.forEach { (key, default) -> if(values.has(key)) when(default) { is Boolean -> editor.putBoolean(key,values.getBoolean(key)); is Int -> editor.putInt(key,values.getInt(key)); is String -> editor.putString(key,values.getString(key)) } }
        check(editor.putString("restoreAppliedGeneration", generation).putBoolean("wantedRunning", false).commit()) { "Восстановленные данные сохранены; настройки будут применены при следующем запуске." }
        true
    }
    internal fun validatePreferences(values: JSONObject) {
        if(values.has(ConnectionAppearance.STYLE))require(values.getString(ConnectionAppearance.STYLE) in ConnectionAppearance.choices){"Неизвестное оформление подключения."}
        if(values.has(ConnectionAppearance.EXIT))require(values.getString(ConnectionAppearance.EXIT) in ConnectionAppearance.exits){"Неизвестное оформление отключения."}
        require(values.optString("autoPolicy",AutoSelection.LATENCY) in setOf(AutoSelection.LATENCY,AutoSelection.BS_ENGINES))
        require(values.keys().asSequence().all { it in preferenceKeys()+sectionKeys()+"localProxy" }) { "Неизвестные параметры бэкапа: зарегистрируйте новые секции до восстановления." }
        sections.forEach { (key, section) -> if(values.has(key)) section.validate(values.getJSONObject(key)) }
        // Retired encrypted section is opaque data: never activate, normalize or migrate its access values.
        if(values.has("webDavBackup")) values.getJSONObject("webDavBackup")
        additionalPreferences.forEach { (key, default) -> if(values.has(key)) require(when(default) { is Boolean -> values.get(key) is Boolean; is Int -> values.get(key) is Int; else -> values.get(key) is String }) { "Неверный тип параметра $key" } }
        if(values.has("localProxy"))LocalProxyConfig.validate(values.getJSONObject("localProxy"))
        booleans.filter(values::has).forEach(values::getBoolean); integers.filter(values::has).forEach(values::getInt); strings.filter(values::has).forEach(values::getString)
        require(values.optInt("port", 10808) in 1024..65535 && values.optInt("interval", 60) in 5..86400 && values.optInt("tolerance", 50) in 0..10000)
        require(values.optInt("subscriptionHours", 0) in SubscriptionUpdates.HOURS && values.optString("theme", "system") in setOf("system", "light", "dark") && values.optString("selected").length <= 64)
    }
}

