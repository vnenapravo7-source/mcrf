package org.mcrf.client

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.json.JSONObject

/** Independent settings surface; writes only the encrypted routing snapshot. */
class AdvancedDnsEditor(private val activity: MainActivity, private val store: RoutingStore,private val features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity)) {
    fun buildPage():LinearLayout=build()
    fun advancedView():LinearLayout=build(true)
    fun build():LinearLayout=build(false)
    private fun build(advanced:Boolean): LinearLayout = with(activity) {
        val editing=store.snapshotWithGeneration()
        val state = AdvancedDnsConfig.value(editing.first)
        val opened=JSONObject(state.toString())
        var committed=JSONObject(opened.toString()) // Only the ordered writer accesses this baseline.
        val body = if(advanced)card()else LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        if(advanced)body.addView(text("Дополнительные параметры DNS",18,Loft.INK))
        val status = text("Автосохранение · применяется при новом подключении", 12, Loft.MUTED)
        val generation=editing.second
        val debounce=RoutingDefaultsDebounce(body) {
                val captured=JSONObject(state.toString())
                RoutingDefaultsWrites.submit {
                    try { if(RoutingDefaultsEdits.equivalent(captured,committed))return@submit;AdvancedDnsConfig.validate(captured); store.editInGeneration(generation){it.put("advancedDns",RoutingDefaultsEdits.merge(AdvancedDnsConfig.value(it),committed,captured))};committed=JSONObject(captured.toString()); runOnUiThread {status.text = "Сохранено · новое подключение применит настройки"; status.setTextColor(Loft.MUTED)} }
                    catch (e: Exception) { runOnUiThread {status.text = e.message ?: "Проверь DNS-настройки"; status.setTextColor(Loft.ERROR)} }
                }
        }
        // Legacy disabled snapshots stay untouched on opening. A real edit applies the
        // visible policy without a second, hidden opt-in checkbox.
        fun save() {state.put("enabled",true);debounce.schedule()}
        val groupKeys=linkedSetOf<String>();var groupTitle=""
        fun finishGroup() {
            if(!advanced||groupKeys.isEmpty())return
            val keys=groupKeys.toSet();val title=groupTitle;groupKeys.clear()
            body.addView(button("Сбросить настройки группы",false).apply {
                tag="reset-group:dns:${keys.sorted().joinToString(",")}";contentDescription="Сбросить группу DNS: $title"
                setOnClickListener {ThemedDialogBuilder(this@with).setTitle("Сбросить «$title»?")
                    .setMessage("Изменятся только параметры этой группы DNS. DNS серверов и списков, маршруты и остальные группы сохранятся. Применится при новом подключении.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->
                        debounce.flush();RoutingDefaultsWrites.submit {
                            try {store.editInGeneration(generation){root->val next=SettingsGroupReset.apply(AdvancedDnsConfig.value(root),AdvancedDnsConfig.defaults(),keys);AdvancedDnsConfig.validate(next);root.put("advancedDns",next)};runOnUiThread {if(!isDestroyed)render()}}
                            catch(_:Exception){runOnUiThread {status.text="Не удалось сбросить группу. Предыдущие настройки сохранены.";status.setTextColor(Loft.ERROR)}}
                        }
                    }.show()}
            },space(0,12))
        }
        fun group(title: String) {finishGroup();groupTitle=title;divider(body,12,10);body.addView(text(title,15,Loft.INK).apply {setTypeface(null,android.graphics.Typeface.BOLD);setAccessibilityHeading(true)},space(0,8)) }
        fun toggle(key: String, title: String, hint: String) {
            groupKeys.add(key)
            body.addView(Switch(this).apply { tag="dns-setting.$key";text = title; tune(this); isChecked = state.optBoolean(key); setOnCheckedChangeListener { _, v -> state.put(key, v); save() } }, space(8, 0))
            body.addView(text(hint, 12, Loft.MUTED), space(0, 8))
        }
        fun input(key: String, title: String, hint: String, number: Boolean = false, parent:LinearLayout=body) {
            groupKeys.add(key)
            val edit = editor(hint).apply { tag="dns-setting.$key";setText(state.optString(key)); if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            parent.addView(field(title, edit)); parent.addView(text(hint, 12, Loft.MUTED), space(0, 8))
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { state.put(key, if (number) s.toString().toIntOrNull() ?: -1 else s.toString()); save() }
            })
        }
        if(!advanced) {
        for ((key, title) in listOf("direct" to "DNS прямого выхода", "local" to "DNS для подключения к серверам")) {
            val resolver=card().apply {tag=if(key=="direct")"dns-direct"else"dns-bootstrap";addView(text(title,18,Loft.INK),space(0,12))}
            val editor = DnsEditor(this, state.getJSONObject(key)); resolver.addView(editor.view)
            editor.onChange { try {AdvancedDnsEditorChanges.resolver(state.getJSONObject(key),editor.value())?.let {state.put(key,it);save()}} catch (e: Exception) { status.text = e.message } }
            if(key=="local")resolver.addView(text("Нужен до запуска VPN: узнаёт IP-адрес сервера по его имени, например vpn.example.com, чтобы приложение могло подключиться. Также определяет адреса DNS-сервисов. Это не DNS локальной сети и не выбор DNS для трафика внутри VPN. Обычно оставьте «Системный». Если он не находит адрес сервера, можно указать другой DNS по IP; имя вместо IP здесь может создать замкнутый круг.",12,Loft.MUTED).apply {tag="dns-bootstrap-explanation"},space(12,0))
            body.addView(resolver,space(0,16))
        }
        val finals = arrayOf("remote", "direct")
        val defaultChoice=picker(arrayOf("Через VPN", "Напрямую"), finals.indexOf(state.getString("final"))).apply {
            tag="dns-default-choice"
            onItemSelectedListener = selectedListener { i -> if (state.optString("final") != finals[i]) { state.put("final", finals[i]); save() } }
        }
        val defaultCard=card().apply {tag="dns-default"}
        defaultCard.addView(LinearLayout(this).apply {
            tag="dns-default-row";orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            addView(text("DNS по умолчанию",12,Loft.INK).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL;setSingleLine(true);setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            },LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(8)})
            addView(defaultChoice,LinearLayout.LayoutParams(0,dp(48),1f))
        })
        body.addView(defaultCard,space(0,16))
        body.addView(card().apply {tag="dns-advanced-link";addView(button("Расширенные настройки",false).apply {setOnClickListener {openAdvanced("advanced-dns")}})},space(0,8))
        } else {
        group("Фильтрация IPv6-ответов DNS")
        toggle("remoteNoAAAA", "Не выдавать IPv6-адреса через DNS VPN", "Возвращает пустой ответ на запрос IPv6-адреса (AAAA). Не выключает IPv6 в самом VPN и не переключает запрос на другой DNS.")
        toggle("directNoAAAA", "Не выдавать IPv6-адреса через прямой DNS", "Тот же фильтр только для доменов, разрешаемых напрямую. Не заменяет общую настройку IPv6 в VPN.")
        body.addView(text("Изменения этой страницы применяют DNS-настройки приложения при следующем подключении. Явный DNS отдельных списков сохраняет приоритет.",12,Loft.MUTED),space(0,10))
        if(UsageFeatures.showFakeIp(features,state.optBoolean("fakeIp"))) {
            group("Виртуальные адреса · FakeIP")
            if(!UsageFeatures.usesVpn(features))body.addView(text("FakeIP сохранён из режима VPN и требует TUN. Для подключения в режиме «Только прокси» выключи его здесь либо верни VPN в «Скрыть разделы». Значение не сбрасывается автоматически.",12,Loft.MUTED),space(0,8))
            toggle("fakeIp", "Выдавать виртуальные IP-адреса", "FakeIP в системном VPN. Явные доменные правила и hosts остаются выше.")
            toggle("fakeNoAAAA", "FakeIP без IPv6", "Отключает IPv6-диапазон виртуальных адресов.")
        }
        group("Собственные ответы DNS")
        toggle("hosts", "Учитывать системный hosts-файл", "Читает /system/etc/hosts; записи приложения добавляются ниже.")
        input("answers", "Предопределённые ответы", "По строке: 192.0.2.1 example.com; IPv6 тоже поддерживается.")
        group("Кэш DNS")
        toggle("cache", "Кэш DNS", "Повторные запросы используют сохранённые ответы до истечения TTL.")
        input("capacity", "Ёмкость кэша", "0–65536 записей. Больше записей требует больше памяти. Для полного отключения кэша используйте переключатель «Кэш DNS».", true)
        toggle("optimistic", "Оптимистичный кэш", "Устаревший ответ возвращается сразу и обновляется в фоне. Требует кэша.")
        input("optimisticTimeout", "Срок устаревшего ответа", "Как долго разрешён старый ответ при фоновом обновлении. Длительность с единицей, например 30s; действует только с оптимистичным кэшем.")
        group("Время ожидания DNS")
        input("timeout", "Таймаут DNS", "Как долго ждать ответ DNS, например 10s. Слишком малое время даёт ложные ошибки в медленной сети; это не таймаут подключения VPN.")
        group("Порядок IP-адресов в ответах")
        groupKeys.add("strategy")
        val strategies = arrayOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
        body.addView(field("Разрешение доменов", picker(arrayOf("По умолчанию", "Сначала IPv4", "Сначала IPv6", "Только IPv4", "Только IPv6"), strategies.indexOf(state.getString("strategy"))).apply { onItemSelectedListener = selectedListener { i -> if (state.optString("strategy") != strategies[i]) { state.put("strategy", strategies[i]); save() } } }))
        body.addView(text("Определяет предпочтение IP-адресов при разрешении доменных имён. «Только IPv4/IPv6» ограничивает ответы, а не включает или выключает интерфейс IPv6 в VPN. Настройка самого интерфейса находится в «VPN и прокси».",12,Loft.MUTED),space(0,10))
        }
        finishGroup();body.addView(status, space(10, 0)); body
    }
}
