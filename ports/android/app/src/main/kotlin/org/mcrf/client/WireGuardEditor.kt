package org.mcrf.client

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Debounced, validated autosave. Call close() when the containing screen is dismissed. */
class WireGuardEditor(
    private val activity: MainActivity,
    source: String,
    private val validateRuntime: (WireGuardProfile.Profile) -> Unit,
    private val persist: (WireGuardProfile.Profile) -> Unit,
    private val onSaved: () -> Unit = {}
) : AutoCloseable {
    private val original = WireGuardProfile.parse(source)
    private val controls = linkedMapOf<String, EditText>()
    private val generation = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    @Volatile private var closed = false
    private val status = activity.text("Изменения сохраняются автоматически после проверки.", 12, Loft.MUTED)
    val view: View
    init {
        val form = activity.card()
        form.addView(status, activity.space(0, 12))
        val fields = original.fields()
        val basic = listOf("Name", "Endpoint", "Address", "PrivateKey", "PublicKey", "PresharedKey", "AllowedIPs", "DNS", "MTU", "ListenPort", "PersistentKeepalive", "Reserved", "Workers", "UDPTimeout")
        basic.forEach { addField(form, it, fields[it].orEmpty()) }
        val advanced = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        form.addView(activity.button("Параметры AmneziaWG", false).apply { setOnClickListener { advanced.visibility = if(advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }, activity.space(0, 12))
        form.addView(advanced)
        (listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies")).forEach { addField(advanced, it, fields[it].orEmpty()) }
        view = ScrollView(activity).apply { addView(form) }
    }
    private fun addField(form: LinearLayout, key: String, value: String) {
        val editor = activity.editor("").apply {
            setSingleLine(true); setText(value)
            if(key in setOf("PrivateKey", "PresharedKey", "HeaderProtectionKey")) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        controls[key] = editor
        form.addView(activity.field(key, editor))
        form.addView(activity.text(explanation(key), 11, Loft.MUTED), activity.space(0, 14))
        editor.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { schedule() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun schedule() {
        if(closed) return
        val revision = generation.incrementAndGet()
        pending?.let(handler::removeCallbacks)
        status.text = "Проверка изменений…"
        val task = Runnable {
            val changes = controls.mapValues { it.value.text.toString().trim().takeIf(String::isNotEmpty) }
            val profile = try { original.edited(changes) } catch(_: Exception) {
                status.text = "Проверь поля. Последние корректные настройки сохранены."; return@Runnable
            }
            worker.execute {
                try {
                    validateRuntime(profile)
                    if(closed || revision != generation.get()) return@execute
                    persist(profile)
                    handler.post { if(!closed && revision == generation.get()) { status.text = "Сохранено · ${profile.version.name.replace('_', '.')}"; onSaved() } }
                } catch(_: Exception) {
                    handler.post { if(!closed && revision == generation.get()) status.text = "Движок не принял настройки или сохранение не удалось. Последние корректные настройки сохранены." }
                }
            }
        }
        pending = task; handler.postDelayed(task, 500)
    }
    override fun close() {
        closed = true; generation.incrementAndGet(); pending?.let(handler::removeCallbacks); worker.shutdownNow()
    }
    companion object {
        fun explanation(key: String): String = when(key) {
            "Name" -> "Название только в списке MCRF."
            "Endpoint" -> "Домен или IP сервера и UDP-порт. IPv6 записывается как [адрес]:порт."
            "Address" -> "Адреса клиента внутри туннеля через запятую, с маской /32 или /128."
            "PrivateKey" -> "Секретный ключ клиента из конфигурации. Сохраняется в зашифрованном каталоге."
            "PublicKey" -> "Публичный ключ пира; должен принадлежать серверу."
            "PresharedKey" -> "Дополнительный общий секрет, если его требует сервер. Пустое поле отключает PSK."
            "AllowedIPs" -> "Разрешённые сети пира. Глобальные правила маршрутизации MCRF применяются отдельно."
            "DNS" -> "IP-адреса DNS из профиля. Применяются через общую политику DNS VPN."
            "MTU" -> "Размер пакета внутри туннеля: 576–9000. Должен подходить сети и серверу."
            "ListenPort" -> "Локальный UDP-порт; 0 или пустое поле выбирает свободный порт."
            "Workers" -> "Количество обработчиков WireGuard: 0 — автоматически, максимум 256."
            "UDPTimeout" -> "Время жизни неактивного UDP-соединения внутри туннеля, например 30s или 5m."
            "PersistentKeepalive" -> "Интервал поддерживающих пакетов в секундах; 0 отключает. Диапазон требует AWG 3.x."
            "Reserved" -> "Три reserved-байта заголовка через запятую, каждый 0–255. Нужны только по настройкам провайдера."
            "Jc" -> "Количество мусорных пакетов перед handshake. Значение должно соответствовать политике AWG."
            "Jmin", "Jmax" -> "Минимальный и максимальный размер мусорных пакетов. Jmin не больше Jmax."
            "S1", "S2", "S3", "S4" -> "Дополнение пакетов handshake/response/cookie/data. При защите заголовка каждый S1–S4 не меньше 12 байт."
            "H1", "H2", "H3", "H4" -> "Идентификатор типа пакета: uint32. Диапазон min-max требует AWG 2.0; должен совпадать с сервером."
            "I1", "I2", "I3", "I4", "I5" -> "Цепочка CPS для маскирующих пакетов AWG 2.0. Регистр значим; точный формат проверяет установленный движок."
            "HeaderProtectionKey" -> "Секрет защиты заголовка AWG 3.x: 32 байта Base64. Должен совпадать с сервером."
            "ContentPaddingAddition" -> "Дополнительное заполнение AWG 3.x: число или диапазон min-max."
            "RandomTrailers" -> "AWG 3.1: случайные окончания пакетов; true/false. Требует поддержки сервера и ядра."
            "DisableCookies" -> "AWG 3.1: отключение cookie-проверок; true/false. Менять только по настройкам сервера."
            else -> "Параметр таймеров AWG 3.x: число или диапазон min-max. Требует совместимого сервера и ядра."
        }
    }
}
