package org.mcrf.client

import android.app.AlertDialog
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import org.json.JSONObject

class ConnectionEditor(private val activity: MainActivity, private val entry: ServerImport.Server) {
    private val form = EditorLayout.body(activity).apply {tag="connection-editor-form"}
    private val options = ConnectionEdits.snapshot(entry)
    private val name = input(entry.name)
    private val readers = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private val pendingWrites = java.util.concurrent.atomic.AtomicInteger()
    private val status = activity.text("Изменения сохраняются автоматически после проверки. Применятся при следующем подключении.", 12, Loft.MUTED)
    private var watching = false
    private var dirty = false
    private var onCommitted: Runnable? = null
    private var captchaControl: View? = null
    @Volatile private var lastSaved = options.toString()
    private val saveLater = Runnable { save() }
    private fun input(value: String, secret: Boolean = false) = activity.editor("").apply {
        setSingleLine(true); setText(value)
        if (secret) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; transformationMethod = PasswordTransformationMethod.getInstance() }
        typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
    }
    private fun field(label: String, control: View, explanation: String, parent: LinearLayout = form) {
        if(control is CarrierOption){control.describe(label,explanation);parent.addView(control,activity.space(0,4));return}
        EditorLayout.field(activity,parent,label,control,explanation)
    }
    private fun picker(labels:Array<String>,selected:Int,help:String)=CarrierOption(activity,labels.map {CarrierOption.Value(it,optionHelp(it,help))},selected,::changed)
    private fun optionHelp(label:String,fallback:String):String=when {
        label.contains("Ручн",true)->"Используются звонки, указанные в полях этого сервера."
        label.contains("Auto JS",true)||label.contains("Авто VK",true)->"Звонки создаются через авторизованный аккаунт VK. Нужен соответствующий режим и токен."
        label.contains("legacy",true)||label.contains("Капча",true)->"Совместимый способ получения TURN-данных. VK может запросить проверку капчи."
        label.contains("UDP",true)->"Транспорт UDP. Обычно даёт меньшую задержку; сеть должна пропускать UDP до TURN."
        label.contains("TCP",true)->"Транспорт TCP для сетей с ограничениями UDP. Меняет только участок до TURN."
        label.contains("Аудио",true)->"Маскировка трафика под аудиопоток звонка. Должна поддерживаться сервером."
        label.contains("Видео",true)->"Маскировка трафика под видеопоток звонка. Должна поддерживаться сервером."
        else->"$label. $fallback"
    }
    private fun carrierAddress(node:JSONObject) {
        val panel=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="carrier-address-fields"}
        val toggle=activity.button("Адрес  ▾",false).apply {tag="carrier-address";setOnClickListener {panel.visibility=if(panel.visibility==View.VISIBLE)View.GONE else View.VISIBLE;text=if(panel.visibility==View.VISIBLE)"Адрес  ▴"else"Адрес  ▾"}}
        form.addView(toggle,activity.space(0,8));form.addView(panel,activity.space(0,12))
        scalar(node,"server","Адрес сервера","Домен или IP без схемы и порта.",parent=panel)
        scalar(node,"server_port","Порт","Порт подключения на сервере.",parent=panel)
        val password=input(node.optString("password"),true)
        field("Пароль",password,"Пароль подключения. Пробелы сохраняются.",panel)
        readers.add {node.put("password",password.text.toString())}
    }
    private fun calls(node:JSONObject):VkCallSlots {
        val old=node.optJSONArray("hashes")?:org.json.JSONArray()
        return VkCallSlots(activity,(0 until old.length()).map {old.getString(it)},Int.MAX_VALUE,::changed).also {control->
            field("VK-звонки",control,"По одной ссылке или хешу в поле. Несколько звонков можно вставить сразу в первое поле.")
            readers.add {node.put("hashes",org.json.JSONArray(control.values))}
            form.addView(VkManualCallsUi.generator(activity,VkRelayAccount(AndroidDeployStore(activity)),{form.isAttachedToWindow}){control.append(it)},activity.space(0,12))
            EditorLayout.heading(activity,form,"Параметры подключения","settings")
        }
    }
    private fun switch(parent: LinearLayout, title: String, explanation: String, checked: Boolean) = Switch(activity).apply {
        text = title; activity.tune(this); isChecked = checked
        parent.addView(this); parent.addView(activity.text(explanation, 11, Loft.MUTED), activity.space(0, 12))
    }
    init {
        field("Название", name, "Название только в MCRF. Локальные правки не меняют исходную подписку.")
        if (entry.outbound != null && ClientPrimarySelection.selected(activity) == "singbox") {
            val familyKey = ConnectionAddressFamily.key(entry.id)
            switch(form, "Совместимость IPv4 для этого сервера",
                "Если IPv6 этого подключения не работает: разрешать имена только в IPv4. IPv6-защита TUN остаётся; остальные серверы, ссылка и подписка не меняются. Применится при следующем подключении.",
                activity.prefs.getBoolean(familyKey, false)).apply {
                setOnCheckedChangeListener { _, enabled -> activity.prefs.edit().putBoolean(familyKey, enabled).apply() }
            }
        }
        when {
            entry.outbound != null -> ordinary(JSONObject(entry.outbound.toString()))
            entry.endpoint != null -> endpoint(JSONObject(entry.endpoint.toString()))
            entry.carrier != null -> carrier(JSONObject(entry.carrier.toString()))
            entry.configuration != null -> json("Конфигурация sing-box", JSONObject(entry.configuration.toString()), "configuration")
            else -> form.addView(activity.text("Этот движок ещё переносится. Исходную ссылку можно скопировать из меню сервера.", 13, Loft.MUTED))
        }
        form.addView(status, activity.space(0, 12))
    }
    private fun ordinary(node: JSONObject) {
        val host = input(entry.host); val port = activity.number(entry.port)
        field("Адрес сервера", host, "Домен или IP без https://, пути и номера порта.")
        field("Порт", port, "Порт подключения, указанный провайдером. От 1 до 65535.")
        readers.add { node.put("server", host.text.toString().trim()).put("server_port", port.text.toString().toInt()); options.put("outbound", node) }
        val type = node.optString("type")
        val credentials = when (type) {
            "vless", "vmess" -> listOf("uuid" to "UUID")
            "socks", "http", "ssh", "naive" -> listOf((if (type == "ssh") "user" else "username") to "Логин", "password" to "Пароль")
            else -> if (node.has("password")) listOf("password" to "Пароль подключения") else emptyList()
        }
        credentials.forEach { (key, label) ->
            val control = input(node.optString(key), key == "password")
            field(label, control, if (key == "uuid") "Идентификатор доступа из конфигурации сервера. Не меняй на случайный UUID." else "Данные доступа сервера. Сохраняются в зашифрованном каталоге.")
            readers.add { node.put(key, control.text.toString()) }
        }
        if (node.has("method")) scalar(node, "method", "Шифр Shadowsocks", "Точный шифр из конфигурации сервера. Произвольная замена нарушит соединение.")
        if (node.has("flow")) scalar(node, "flow", "Режим VLESS", "Например xtls-rprx-vision. Vision нельзя сочетать с MUX; пусто — без специального режима.")
        node.optJSONObject("tls")?.let { tls ->
            val sni = input(tls.optString("server_name", entry.host))
            field("Домен TLS / SNI", sni, "Имя для TLS-соединения и проверки сертификата. Должно подходить серверу.")
            readers.add { tls.put("server_name", sni.text.toString().trim()) }
            tls.optJSONObject("utls")?.let { scalar(it, "fingerprint", "Отпечаток uTLS", "Имитирует TLS-рукопожатие выбранного клиента, например chrome или firefox. Поддержку проверяет установленное ядро.") }
            tls.optJSONObject("reality")?.let { reality ->
                val publicKey = input(reality.optString("public_key")); val shortId = input(reality.optString("short_id"))
                field("Публичный ключ REALITY", publicKey, "Публичный ключ сервера из ссылки. Это не приватный ключ.")
                field("Short ID", shortId, "Короткий идентификатор REALITY; должен совпадать с разрешённым на сервере.")
                readers.add { reality.put("public_key", publicKey.text.toString().trim()).put("short_id", shortId.text.toString().trim()) }
            }
        }
        node.optJSONObject("transport")?.let { transport ->
            val key = if (transport.optString("type") == "grpc") "service_name" else "path"
            val value = input(transport.optString(key))
            field(if (key == "service_name") "Имя gRPC" else "Путь транспорта", value, "Значение должно совпадать с сервером. Меняет транспорт, а не список маршрутов.")
            readers.add { transport.put(key, value.text.toString()) }
        }
        advanced(node)
    }
    private fun advanced(node: JSONObject) {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        form.addView(activity.button("Расширенные настройки", false).apply { setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if(panel.visibility==View.VISIBLE)panel.post {
                var ancestor=panel.parent
                while(ancestor!=null&&ancestor !is ScrollView)ancestor=ancestor.parent
                (ancestor as? ScrollView)?.let {scroll->
                    val rect=android.graphics.Rect();panel.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(panel,rect)
                    scroll.smoothScrollTo(0,(rect.top-activity.dp(12)).coerceAtLeast(0))
                }
            }
        } }, activity.space(0, 12))
        form.addView(panel)
        val supported = node.optString("type") in setOf("vless", "vmess", "trojan", "shadowsocks") && node.optString("flow").isEmpty()
        if (supported) {
            val old = node.optJSONObject("multiplex") ?: JSONObject()
            val mux = switch(panel, "MUX · объединять соединения", "Несколько потоков идут по общему соединению. Сервер тоже должен поддерживать MUX; это не всегда быстрее.", old.optBoolean("enabled"))
            val muxFields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = if (mux.isChecked) View.VISIBLE else View.GONE }; panel.addView(muxFields)
            mux.setOnCheckedChangeListener { _, enabled -> muxFields.visibility = if (enabled) View.VISIBLE else View.GONE }
            val protocols = arrayOf("h2mux", "smux", "yamux"); val protocol = activity.picker(protocols, protocols.indexOf(old.optString("protocol", "h2mux")))
            muxFields.addView(activity.field("Протокол MUX", protocol)); muxFields.addView(activity.text("Выбери вариант, поддерживаемый сервером. h2mux — стандартный вариант ядра.", 11, Loft.MUTED), activity.space(0, 12))
            val policy = activity.picker(arrayOf("Ограничивать соединения", "Ограничивать потоки"), if (old.optInt("max_streams") > 0) 1 else 0)
            muxFields.addView(activity.field("Режим ограничения", policy))
            val count = activity.number(old.optInt("max_connections", 4).coerceAtLeast(1)); val minimum = activity.number(old.optInt("min_streams", 4).coerceAtLeast(1)); val maximum = activity.number(old.optInt("max_streams", 8).coerceAtLeast(1))
            val connections = activity.field("Соединений · 1–64", count); val streams = activity.field("Минимум потоков · 1–256", minimum); val limit = activity.field("Потоков на соединение · 1–256", maximum)
            muxFields.addView(connections); muxFields.addView(streams); muxFields.addView(limit)
            fun showPolicy() { connections.visibility = if (policy.selectedItemPosition == 0) View.VISIBLE else View.GONE; streams.visibility = connections.visibility; limit.visibility = if (connections.visibility == View.GONE) View.VISIBLE else View.GONE }
            policy.onItemSelectedListener = activity.selectedListener { showPolicy() }; showPolicy()
            muxFields.addView(activity.text("Первый режим задаёт число соединений и порог открытия нового. Второй ограничивает потоки на одно соединение. Эти ограничения не смешиваются.", 11, Loft.MUTED), activity.space(0, 12))
            val padding = switch(muxFields, "Дополнять MUX-пакеты", "Добавляет служебные данные к пакетам. Нужно совместимое ядро сервера; расход трафика увеличится.", old.optBoolean("padding"))
            readers.add {
                node.put("multiplex", JSONObject(old.toString()).put("enabled", mux.isChecked).apply {
                    if (mux.isChecked) { put("protocol", protocols[protocol.selectedItemPosition]).put("padding", padding.isChecked)
                        listOf("max_connections", "min_streams", "max_streams").forEach(::remove)
                        if (policy.selectedItemPosition == 0) put("max_connections", count.text.toString().toInt()).put("min_streams", minimum.text.toString().toInt()) else put("max_streams", maximum.text.toString().toInt()) }
                })
            }
        } else panel.addView(activity.text("MUX недоступен для этого протокола или режима Vision.", 11, Loft.MUTED), activity.space(0, 12))
        val tls = node.optJSONObject("tls")
        if (tls != null && node.optString("type") !in setOf("hysteria", "hysteria2", "tuic")) {
            val fragment = activity.picker(arrayOf("Как в исходной ссылке", "Выключена", "Разбивать TLS-записи", "Разбивать TCP-пакеты"), 0)
            panel.addView(activity.field("Фрагментация TLS", fragment)); panel.addView(activity.text("Меняет разбиение начального TLS-рукопожатия. Для TCP/TLS, не QUIC. Разбиение записей обычно менее затратное; TCP-вариант может повысить задержку.", 11, Loft.MUTED), activity.space(0, 12))
            val delay = activity.number(500); val delayField = activity.field("Пауза TCP-фрагментов, мс", delay); delayField.visibility = View.GONE; panel.addView(delayField)
            panel.addView(activity.text("Пауза используется, когда ядро не определило её автоматически. На Android при TCP-фрагментации действует заданное значение. От 10 до 5000 мс.", 11, Loft.MUTED), activity.space(0, 12))
            fragment.onItemSelectedListener = activity.selectedListener { i -> delayField.visibility = if (i == 3) View.VISIBLE else View.GONE }
            val insecure = switch(panel, "Не проверять TLS-сертификат", "Разрешает неподтверждённый сертификат сервера. Оставь выключенным, если провайдер не требует этого.", tls.optBoolean("insecure"))
            readers.add { tls.put("insecure", insecure.isChecked); when (fragment.selectedItemPosition) {
                1 -> { tls.put("fragment", false).put("record_fragment", false); tls.remove("fragment_fallback_delay") }
                2 -> { tls.put("fragment", false).put("record_fragment", true); tls.remove("fragment_fallback_delay") }
                3 -> { val ms = delay.text.toString().toInt(); require(ms in 10..5000) { "Пауза фрагментов должна быть 10–5000 мс." }; tls.put("fragment", true).put("record_fragment", false).put("fragment_fallback_delay", "${ms}ms") }
            } }
        }
        val timeout = input(node.optString("connect_timeout"))
        panel.addView(activity.field("Таймаут соединения", timeout)); panel.addView(activity.text("Сколько ждать подключения: например 10s или 1m. Пусто — настройка ядра; не влияет на интервал замеров.", 11, Loft.MUTED), activity.space(0, 12))
        val fastOpen = switch(panel, "TCP Fast Open", "Позволяет начать передачу раньше при повторном TCP-подключении. Нужна поддержка сети и сервера.", node.optBoolean("tcp_fast_open"))
        readers.add { val value = timeout.text.toString().trim(); require(value.isEmpty() || value.matches(Regex("(?:[1-9]|[1-9][0-9]|[12][0-9]{2}|300)s|[1-5]m"))) { "Таймаут: 1–300s или 1–5m." }; if (value.isEmpty()) node.remove("connect_timeout") else node.put("connect_timeout", value); node.put("tcp_fast_open", fastOpen.isChecked) }
    }
    private fun endpoint(node: JSONObject) {
        val privateKey = input(node.optString("private_key"), true); val address = input((node.optJSONArray("address") ?: org.json.JSONArray()).let { (0 until it.length()).joinToString(", ") { i -> it.getString(i) } })
        field("Приватный ключ WireGuard", privateKey, "Ключ клиента. Не публикуй его отдельно от подключения.")
        field("Адреса внутри туннеля", address, "IP/префикс клиента через запятую, как в конфигурации провайдера.")
        readers.add { node.put("private_key", privateKey.text.toString()).put("address", org.json.JSONArray(address.text.toString().split(',').map(String::trim))); options.put("endpoint", node) }
        val dns = input(entry.profileDns.joinToString(", "))
        field("DNS профиля", dns, "IP-адреса DNS через запятую. Подключение использует первый адрес по UDP; остальные сохраняются для экспорта, автоматического fallback нет. Пусто — общие настройки MCRF.")
        readers.add { options.put("profileDns", org.json.JSONArray(dns.text.toString().split(',').map(String::trim).filter(String::isNotEmpty))) }
        val peers = node.getJSONArray("peers")
        repeat(peers.length()) { i -> val peer = peers.getJSONObject(i); val host = input(peer.optString("address")); val port = activity.number(peer.getInt("port")); val key = input(peer.getString("public_key"))
            field("Адрес пира ${i+1}", host, "Домен или IP сервера WireGuard."); field("Порт пира ${i+1}", port, "UDP-порт сервера."); field("Публичный ключ пира ${i+1}", key, "Публичный ключ сервера, не приватный ключ клиента.")
            readers.add { peer.put("address", host.text.toString().trim()).put("port", port.text.toString().toInt()).put("public_key", key.text.toString().trim()) }
            scalar(peer, "pre_shared_key", "Общий ключ пира", "Необязательный дополнительный ключ, 32 байта в Base64. Пусто — без общего ключа.", true)
            val allowed = input(peer.getJSONArray("allowed_ips").let { array -> (0 until array.length()).joinToString(", ") { array.getString(it) } })
            field("Маршруты пира", allowed, "IP/префиксы через запятую: например 0.0.0.0/0 и ::/0. Должны соответствовать серверу.")
            readers.add { peer.put("allowed_ips", org.json.JSONArray(allowed.text.toString().split(',').map(String::trim))) }
            scalar(peer, "persistent_keepalive_interval", "Keepalive, с", "0 выключает пакеты поддержания соединения; допустимо 0–65535.")
        }
        scalar(node, "mtu", "MTU профиля", "Размер пакета 576–9000. Пусто — значение ядра.")
        val awg = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "header_protection_key", "content_padding_addition", "rekey_after_time", "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts", "random_trailers", "disable_cookies")
        awg.filter(node::has).forEach { key -> scalar(node, key, "AWG · $key", "Параметр обфускации из конфигурации сервера. Версия должна поддерживаться установленным ядром; произвольная замена нарушит соединение.", key == "header_protection_key") }
    }
    private fun scalar(node: JSONObject, key: String, label: String, explanation: String, secret: Boolean = false, parent: LinearLayout = form) {
        val old = node.opt(key)
        if(key=="fingerprint"&&old !is Boolean){
            val values=(listOf("chrome","firefox","safari","ios","android","random","randomized")+node.optString(key)).distinct()
            val control=picker(values.map {it.ifEmpty {"Выбор движка"}}.toTypedArray(),values.indexOf(node.optString(key)),explanation)
            field("Отпечаток TLS",control,explanation,parent)
            readers.add {node.put(key,values[control.selectedItemPosition])};return
        }
        if (old is Boolean) {
            if(entry.carrier!=null){
                val labels=if(key=="turn_tcp")arrayOf("TURN · UDP","TURN · TCP")else arrayOf("Выключено","Включено")
                val control=picker(labels,if(old)1 else 0,explanation)
                field(label,control,explanation,parent);readers.add {node.put(key,control.selectedItemPosition==1)};return
            }
            val control = switch(parent, label, explanation, old)
            readers.add { node.put(key, control.isChecked) }
        } else {
            val control = input(if (old == null || old === JSONObject.NULL) "" else old.toString(), secret)
            field(label, control, explanation, parent)
            readers.add { val value = control.text.toString().trim(); if (value.isEmpty()) node.remove(key) else node.put(key, if (old is Number || key in setOf("mtu", "persistent_keepalive_interval")) value.toLong() else value) }
        }
    }
    private fun carrier(node: JSONObject) {
        if(Csqtt25Profile.isProfile(node)){csqtt25(node);return}
        if(node.optString("type")=="csqtt"){
            val families=CsqttGenerations.legacy
            val generation=picker(families.map {it.label}.toTypedArray(),families.indexOf(CsqttGenerations.family(node)),"Выберите поколение сервера. Для каждого несовместимого протокола скачивается отдельный модуль; автоматической подмены нет.")
            field("Поколение CSQTT",generation,"Старые сохранённые подключения остаются на прежнем движке, пока вы сами не измените этот выбор.")
            readers.add {node.put("csqtt_generation",families[generation.selectedItemPosition].id)}
        }
        if(node.optString("type") in setOf("csqtt","qwdtt")&&!node.has("fingerprint"))node.put("fingerprint","chrome")
        val swg=node.optString("type") in SwgProfile.TYPES
        if(swg)carrierAddress(node)
        if (node.optString("type") == "olcrtc") {
            if (!node.has("token")) scalar(node, "token", "Токен сервиса", "Необязательный токен WBStream; для datachannel обязателен.", true)
            if (!node.has("channel")) scalar(node, "channel", "Канал комнаты", "Необязательный ID канала сервиса. Пусто — значение движка.")
            val extra = node.optJSONObject("options") ?: JSONObject().also { node.put("options", it) }
            val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            form.addView(activity.button("Параметры транспорта OLC", false).apply { setOnClickListener { panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE } }, activity.space(0, 12))
            form.addView(panel)
            listOf("vp8-fps", "vp8-batch", "fps", "batch", "frag", "ack-ms", "video-w", "video-h", "video-fps", "video-qr-size", "video-tile-module", "video-tile-rs", "video-codec", "video-qr-recovery").forEach { key ->
                val spec = CompatibilityImportFields.describe(key)
                scalar(extra, key, spec.first, spec.second + " Пусто — значение движка.", parent = panel)
            }
        }
        if (node.optString("type") == "dnstt") {
            val keys = arrayOf("doh", "dot", "udp")
            val selected = keys.indexOfFirst { node.has(it) && node.optString(it).isNotEmpty() }.coerceAtLeast(0)
            val kind = activity.picker(arrayOf("HTTPS / DoH", "TLS / DoT", "UDP"), selected)
            val resolver = input(node.optString(keys[selected]))
            field("Транспорт DNS", kind, "Выбери один способ доставки зашифрованного DNSTT-туннеля к DNS-резолверу.")
            field("Адрес резолвера", resolver, "DoH: https://домен/dns-query. DoT: домен:853. UDP: IP:53. При смене транспорта укажи соответствующий адрес.")
            readers.add { keys.forEach(node::remove); node.put(keys[kind.selectedItemPosition], resolver.text.toString().trim()) }
            scalar(node, "username", "Логин прокси", "Необязательный логин SOCKS за туннелем; задаётся вместе с паролем.")
            scalar(node, "password", "Пароль прокси", "Необязательный пароль SOCKS за туннелем; сохраняется зашифрованно.", true)
        }
        fun visit(obj: JSONObject, prefix: String = "") {
            obj.keys().asSequence().toList().forEach { key ->
                val value = obj.get(key); val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when {
                    key in setOf("type", "name", "outbound_tag", "hash_source", "vk_call_count", "vk_hash_mode", "vk_runtime_token", "extra_streams", "csqtt_generation",CarrierStreamDefaults.OVERRIDE) -> Unit
                    prefix.isEmpty()&&swg&&key in setOf("server","server_port","password","workers")->Unit
                    prefix.isEmpty()&&key=="turn_tcp"&&node.optString("type")=="csqtt"&&CsqttGenerations.family(node).engine=="csqtt20"->{
                        field("Транспорт TURN",activity.text("UDP",14,Loft.INK),"В CSQTT 2.0 доступен только UDP до TURN. TCP требует другого поколения сервера.")
                    }
                    prefix.isEmpty()&&node.optString("type") in SwgProfile.TYPES&&key in setOf("obfs","vk_anon_path","mode","go_dns","fingerprint")->{
                        val choices=when(key){
                            "obfs"->listOf("audio" to "Аудио","video" to "Видео")
                            "vk_anon_path"->listOf("vkcalls" to "Авто · гостевые VK Calls","legacy" to "Капча · совместимый режим") + if(node.optString("type")=="csqtt"&&CsqttGenerations.family(node).engine=="csqtt")listOf("auto_js" to "Авто VK · привязанный аккаунт")else emptyList()
                            "mode"->listOf("wg" to "WireGuard","raw" to "RAW")
                            "go_dns"->listOf("yandex" to "Яндекс · обычный DNS","google" to "Google · обычный DNS","cloudflare" to "Cloudflare · обычный DNS","doh-yandex" to "Яндекс · DoH","doh-google" to "Google · DoH","doh-cloudflare" to "Cloudflare · DoH")
                            else->if(node.optString("type") in setOf("csqtt","qwdtt"))listOf("firefox" to "Firefox","chrome" to "Chrome","safari" to "Safari")else listOf("android" to "Android","chrome" to "Chrome","firefox" to "Firefox","safari" to "Safari","ios" to "iOS")
                        }
                        val current=obj.getString(key)
                        val index=choices.indexOfFirst {it.first==current}
                        if(index<0)throw IllegalArgumentException("Неизвестный параметр транспорта: $key")
                        val spec=CompatibilityImportFields.describe(key)
                        val control=picker(choices.map {it.second}.toTypedArray(),index,spec.second)
                        field(spec.first,control,spec.second)
                        readers.add {obj.put(key,choices[control.selectedItemPosition].first)}
                    }
                    key == "captcha_mode" && prefix.isEmpty() && node.optString("type") in SwgProfile.TYPES -> {
                        val wire=obj.optString(key,"auto")
                        val control=picker(arrayOf("Авто","Вручную"),if(CarrierCaptchaMode.automatic(wire))0 else 1,"Авто сначала использует автоматические способы, затем ручную проверку при необходимости. Вручную — проверка по уведомлению.")
                        control.tag="connection-captcha-mode";captchaControl=control
                        field("Капча VK",control,"Авто — автоматические способы движка; если они не помогут, появится запрос на ручную проверку. Вручную — окно VK по уведомлению. Изменение применяется при следующем подключении.")
                        readers.add {obj.put(key,CarrierCaptchaMode.select(wire,control.selectedItemPosition==0))}
                    }
                    node.optString("type") == "olcrtc" && key == "options" -> Unit
                    node.optString("type") == "dnstt" && prefix.isEmpty() && key in setOf("doh", "dot", "udp", "username", "password") -> Unit
                    value is JSONObject -> visit(value, path)
                    value is org.json.JSONArray && key == "transports" -> repeat(value.length()) { i -> visit(value.getJSONObject(i), "Транспорт ${i + 1}") }
                    value is org.json.JSONArray && key == "hashes" -> {
                        calls(obj)
                    }
                    value !is org.json.JSONArray -> {
                        val spec = if (key == "mode" && node.optString("type") == "openflux") "Режим OpenFlux" to "Пусто — согласование профиля; stream — один потоковый транспорт без секрета и согласования. Должен соответствовать серверу." else CompatibilityImportFields.describe(key)
                        scalar(obj, key, if (prefix.isEmpty()) spec.first else "$prefix · ${spec.first}", spec.second, key in setOf("password", "secret", "key", "token", "custom_vk_client_secret"))
                    }
                }
            }
        }
        visit(node)
        if(node.optString("type")=="qwdtt")readers.add {node.put("hash_source","manual");node.remove("vk_call_count")}
        if(node.optString("type")=="csqtt")readers.add {node.put("vk_hash_mode",if(node.optString("vk_anon_path")=="auto_js")"auto_js"else"manual")}
        readers.add { options.put("carrier", if(swg)CarrierStreamDefaults.apply(node,JSONObject()).apply {if(node.has(CarrierStreamDefaults.OVERRIDE))put(CarrierStreamDefaults.OVERRIDE,node.getInt(CarrierStreamDefaults.OVERRIDE))}else node) }
    }
    private fun csqtt25(node:JSONObject) {
        val implementations=CsqttGenerations.modernImplementations
        val keys=implementations.keys.toList()
        val implementation=picker(implementations.values.toTypedArray(),keys.indexOf(CsqttGenerations.implementation(node)),"Оригинальный CSQTT 2.5 и Rebirth используют один протокол. SWG Panel 2.5.1 совместим с ним; отдельные копии одной совместимой библиотеки не скачиваются.")
        field("Сервер CSQTT 2.5",implementation,"Выбор сохраняет происхождение профиля; не переключает на старое несовместимое поколение.")
        readers.add {node.put("csqtt_implementation",keys[implementation.selectedItemPosition])}
        form.addView(activity.text("CSQTT 2.5 · отдельный движок. Старые подключения CSQTT остаются без изменений.",12,Loft.MUTED),activity.space(0,14))
        carrierAddress(node)
        val wire=node.optString("captcha_mode","auto")
        val captcha=picker(arrayOf("Авто","Вручную"),if(CarrierCaptchaMode.automatic(wire))0 else 1,"Авто — автоматические способы с ручной проверкой при необходимости. Вручную — проверка VK по уведомлению.")
        captcha.tag="connection-captcha-mode";captchaControl=captcha
        field("Капча VK",captcha,"Авто — цепочка способов CSQTT 2.5 с ручной проверкой по уведомлению, если потребуется. Вручную — сразу ручной WebView. При активном подключении ничего не перезапускается.")
        readers.add {node.put("captcha_mode",CarrierCaptchaMode.select(wire,captcha.selectedItemPosition==0))}
        val modes=arrayOf("manual","auto_js")
        val source=picker(arrayOf("Ручные звонки","Auto JS · токен из профиля"),modes.indexOf(node.getString("vk_hash_mode")).coerceAtLeast(0),"Ручные звонки не требуют аккаунта. Auto JS использует токен авторизованного VK из профиля CSQTT 2.5.")
        field("Источник звонков CSQTT 2.5",source,"Отдельная схема оригинального CSQTT 2.5. Для Auto JS очисти ручные звонки и укажи токен; для ручных звонков очисти токен. Аккаунт QWDTT не используется.")
        calls(node)
        val token=input(node.optString("vk_token"),true)
        field("Токен Auto JS",token,"Секрет из профиля CSQTT 2.5. Хранится локально зашифрованно; не нужен для ручных звонков. Фоновые замеры не создают Auto JS-звонки.")
        readers.add {
            node.put("vk_hash_mode",modes[source.selectedItemPosition])
            if(token.text.isEmpty())node.remove("vk_token")else node.put("vk_token",token.text.toString())
        }
        scalar(node,"allow_hash_redistribution","Разрешить повторное использование хешей","Разрешает выбрать больше 10 потоков на один ручной хеш; распределением управляет сам движок swg-panel. Не отменяет ограничения VK.")
        val masks=listOf("unified","audio","video")
        val masking=picker(arrayOf("Unified · по умолчанию","Аудио","Видео"),masks.indexOf(node.getString("obfs")).coerceAtLeast(0),"Маскировка оригинального CSQTT 2.5. Должна поддерживаться сервером.")
        field("Маскировка",masking,"Unified — штатный режим swg-panel. Audio и video — соответствующие виды медиапотока.")
        readers.add {node.put("obfs",masks[masking.selectedItemPosition])}
        val fingerprints=(listOf("","chrome","firefox","safari")+node.getString("fingerprint")).distinct()
        val fingerprint=picker(fingerprints.map {it.ifEmpty {"Выбор движка"}}.toTypedArray(),fingerprints.indexOf(node.getString("fingerprint")),"Отпечаток TLS-клиента. Пусто — штатный выбор движка.")
        field("Отпечаток TLS",fingerprint,"По умолчанию Chrome. Выбор не меняет приложение браузера на телефоне.")
        readers.add {node.put("fingerprint",fingerprints[fingerprint.selectedItemPosition])}
        val authModes=arrayOf("vkcalls","legacy","auto_js")
        val auth=picker(arrayOf("VK Calls","Совместимый · legacy","Auto JS"),authModes.indexOf(node.getString("vk_auth_mode")).coerceAtLeast(0),"VK Calls — гостевой способ. Legacy — совместимый способ. Auto JS — авторизованный VK из профиля.")
        field("Авторизация VK в CSQTT 2.5",auth,"Auto JS требует соответствующий источник звонков и токен. Это не переключатель автоматического решения капчи.")
        readers.add {node.put("vk_auth_mode",authModes[auth.selectedItemPosition])}
        val transports=arrayOf("udp","tcp")
        val turn=picker(arrayOf("TURN · UDP","TURN · TCP"),transports.indexOf(node.getString("turn_transport")).coerceAtLeast(0),"Меняет транспорт от устройства до TURN. От TURN до сервера используется UDP.")
        field("Транспорт TURN",turn,"Отдельная настройка CSQTT 2.5; TCP не включается автоматически.")
        readers.add {node.put("turn_transport",transports[turn.selectedItemPosition])}
        val ips=activity.editor("").apply {minLines=2;setText(node.getJSONArray("custom_turn_ips").let {a->(0 until a.length()).joinToString("\n"){a.getString(it)}})}
        field("Собственные IP TURN",ips,"Необязательно: до 256 IP-адресов через пробел, запятую или новую строку. Не домены и не правила «Напрямую». Пусто — выбор движка.")
        readers.add {node.put("custom_turn_ips",org.json.JSONArray(ips.text.toString().split(Regex("[,;\\s]+")).filter(String::isNotEmpty)))}
        for((key,label) in listOf("client_ids" to "Client IDs","generation" to "Поколение серверного пароля"))
            scalar(node,key,label,"Необязательное значение оригинального сервера CSQTT 2.5; не придумывается и не меняется автоматически.")
        val salt=input(node.optString("salt"),true)
        field("Соль серверного пароля",salt,"Необязательное точное значение из оригинального профиля. Пробелы не удаляются автоматически.")
        readers.add {if(salt.text.isEmpty())node.remove("salt")else node.put("salt",salt.text.toString())}
        if(node.has("device_id"))form.addView(activity.text("Локальный идентификатор профиля сохранён. В реализации swg-panel 2.5.1 адрес клиента определяется сервером по паролю, а не отдельной привязкой устройства.",12,Loft.MUTED),activity.space(0,14))
        readers.add {options.put("carrier",CarrierStreamDefaults.apply(node,JSONObject()).apply {if(node.has(CarrierStreamDefaults.OVERRIDE))put(CarrierStreamDefaults.OVERRIDE,node.getInt(CarrierStreamDefaults.OVERRIDE))})}
    }
    private fun json(label: String, value: JSONObject, key: String) {
        val editor = activity.editor(label).apply { minLines = 8; maxLines = 14; setText(value.toString(2)); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        field(label, editor, "Полная конфигурация этого подключения. Перед сохранением проверяется формат; слушающие порты основного ядра задаёт MCRF.")
        readers.add { options.put(key, JSONObject(editor.text.toString())) }
    }
    private fun changed() { if (watching) { dirty = true; revision.incrementAndGet(); handler.removeCallbacks(saveLater); handler.postDelayed(saveLater, 700) } }
    private fun watch(view: View) {
        if(view is VkCallSlots||view is CarrierOption)return // These controls own dynamic listeners.
        when (view) {
            is EditText -> view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            is Switch -> { var previous = view.isChecked; view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (view.isChecked != previous) { previous = view.isChecked; changed() } }; view.setOnClickListener { changed() } }
            is Spinner -> { val prior = view.onItemSelectedListener; var previous = view.selectedItemPosition; view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) { prior?.onNothingSelected(parent) }
                override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) { prior?.onItemSelected(parent, item, position, id); if (previous != position) { previous = position; changed() } }
            } }
        }
        if (view is android.view.ViewGroup) repeat(view.childCount) { watch(view.getChildAt(it)) }
    }
    private fun save() {
        if (worker.isShutdown || !dirty) return
        val candidate: ServerImport.Server; val captured: JSONObject
        try { options.put("name", name.text.toString()); readers.forEach { it() }; captured = JSONObject(options.toString()); candidate = ConnectionEdits.apply(entry, captured) }
        catch (error: Exception) { status.text = if (error is IllegalArgumentException && !error.message.isNullOrBlank()) error.message else "Проверь поля. Последние корректные настройки сохранены."; status.setTextColor(Loft.ERROR); return }
        val current = revision.get(); val serialized = captured.toString()
        if (serialized == lastSaved && pendingWrites.get() == 0) { dirty = false; return }
        status.text = "Проверка настроек…"; status.setTextColor(Loft.MUTED)
        pendingWrites.incrementAndGet()
        worker.execute {
            try {
                CompatibilityImportValidation.editor.validateAndCommit(candidate, CompatibilityImportValidation.Validator { checked ->
                    if (!checked.needsEngine()) {
                        val profile = ServerImport.build(listOf(checked), checked.id, false, 60, 50, "Cloudflare")
                        ClientPrimarySelection.validateNative(activity.applicationContext, ProfileConfig.build(profile.toString(), true, 10808).toString(), true)
                    }
                }) { if (current == revision.get()) {
                    activity.catalogue.edit(entry.id, captured)
                    lastSaved = serialized
                    handler.post { if (current == revision.get()) { dirty = false; status.text = "Сохранено. Применится при следующем подключении."; status.setTextColor(Loft.MUTED) }; if (!activity.isDestroyed && !activity.isFinishing) onCommitted?.run() }
                } }
            } catch (_: Exception) { handler.post { if (current == revision.get()) { status.text = "Ядро отклонило настройки или сохранение не удалось. Последний корректный вариант сохранён."; status.setTextColor(Loft.ERROR) } } }
            finally { pendingWrites.decrementAndGet() }
        }
    }
    @JvmOverloads fun show(onCommitted: Runnable? = null, focusCaptcha: Boolean = false): AlertDialog {
        this.onCommitted = onCommitted
        val scroll = ScrollView(activity).apply { addView(form) }
        val dialog = ThemedDialogBuilder(activity).setTitle("Настройки подключения").setView(scroll).setNeutralButton("Исходные", null).setPositiveButton("Готово", null).create()
        dialog.setOnShowListener {
            watch(form); watching = true
            if(focusCaptcha)captchaControl?.let {control->scroll.post {
                val rect=android.graphics.Rect();control.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(control,rect)
                scroll.smoothScrollTo(0,(rect.top-activity.dp(48)).coerceAtLeast(0));control.requestFocus()
            }}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(); dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                watching = false; revision.incrementAndGet(); handler.removeCallbacks(saveLater)
                worker.execute { try { activity.catalogue.edit(entry.id, null); handler.post { if (!activity.isDestroyed && !activity.isFinishing) onCommitted?.run(); dialog.dismiss() } } catch (_: Exception) { handler.post { status.text = "Не удалось восстановить исходные настройки."; watching = true } } }
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(saveLater); if (watching) save(); watching = false; worker.shutdown() }
        dialog.show(); return dialog
    }
}
