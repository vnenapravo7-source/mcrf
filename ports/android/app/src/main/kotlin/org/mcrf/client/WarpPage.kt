package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.*

/** Glass settings section. Coordinator owns async operation lifecycle and the isolated trace hook. */
class WarpPage(
    private val activity: MainActivity,
    entries: List<ServerImport.Server>,
    private val register: () -> Unit,
    private val scan: (String) -> Unit,
    private val remove: (String) -> Unit,
    private val cancel: () -> Unit,
    private val retry: () -> Unit = {},
    private val tryRussia: () -> Unit = {},
    selectedId: String? = null
) {
    companion object {
        /** Header entry only; the host owns navigation and keeps the controller across page changes. */
        @JvmStatic fun homeButton(activity: MainActivity, open: Runnable): View = activity.button("☁ WARP",false).apply {
            contentDescription="Открыть Cloudflare WARP"
            setTextColor(WarpAppearance.TEXT)
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(WarpAppearance.SURFACE);cornerRadius=activity.resources.displayMetrics.density*12
            }
            setOnClickListener { open.run() }
        }
    }
    val view = activity.card()
    private val actions = mutableListOf<View>()
    private val status = activity.text("Готов к подбору выхода.",12,Loft.MUTED)
    private val stop = activity.button("Отменить подбор",false).apply { visibility=View.GONE; setOnClickListener { cancel() } }
    private val retryButton = activity.button("Повторить поиск",false).apply { visibility=View.GONE;setOnClickListener { retry() } }
    private val russiaButton = activity.button("Попробовать РФ",false).apply { visibility=View.GONE;setOnClickListener { tryRussia() } }
    private var busy = false
    private var retryAvailable = false
    private var russiaAvailable = false
    private var settings = WarpSettingsStore.load(activity)
    init {
        status.setTextColor(WarpAppearance.TEXT)
        status.background=android.graphics.drawable.GradientDrawable().apply { setColor(WarpAppearance.SURFACE);cornerRadius=activity.resources.displayMetrics.density*12 }
        val inset=(12*activity.resources.displayMetrics.density).toInt();status.setPadding(inset,inset,inset,inset)
        choice("Протокол",listOf("Автоматически" to "auto","WireGuard" to "wg","AmneziaWG" to "awg"),settings.protocol,"Авто: сначала AWG, затем WireGuard.") { value -> change { it.copy(protocol=value) } }
        countryChoice()
        flag("Расширенный подбор сразу",settings.extended,"Больше адресов и портов с первой попытки; поиск дольше.") { value -> change { it.copy(extended=value) } }
        action("Создать WARP / найти выход") {
            if(settings.termsAccepted) register() else ThemedDialogBuilder(activity).setTitle("Условия Cloudflare WARP")
                .setMessage("Регистрация отправляет новый публичный ключ в Cloudflare. Условия сервиса доступны по кнопке ниже.")
                .setNeutralButton("Условия") { _,_ -> activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(WarpSettings.TERMS_URL))) }
                .setNegativeButton("Отмена",null).setPositiveButton("Принять и зарегистрировать") { _,_ -> if(change { it.copy(termsAccepted=true) }) register() }.show()
        }
        val profiles=entries.filter(WarpProfile::isWarp)
        if(profiles.isEmpty()) view.addView(activity.text("После проверки WARP появится в «Серверах».",12,Loft.MUTED),activity.space(0,12))
        profiles.forEach { entry ->
            val label=activity.text(if(entry.id==selectedId) WarpAppearance.selectedText(entry.name) else entry.name,14,WarpAppearance.TEXT)
            label.setBackgroundColor(WarpAppearance.SURFACE);label.setPadding(inset,inset,inset,inset)
            view.addView(label,activity.space(8,8))
            action("Подобрать / переподобрать выход") { scan(entry.id) }
            action("Удалить этот профиль WARP") { remove(entry.id) }
        }
        if(profiles.isNotEmpty())view.addView(activity.text("Удаляется локальный профиль, не регистрация Cloudflare.",11,Loft.MUTED),activity.space(0,12))
        view.addView(status,activity.space(0,12));view.addView(stop,activity.space(0,8))
        view.addView(retryButton,activity.space(0,8));view.addView(russiaButton,activity.space(0,8))
    }
    fun setBusy(busy: Boolean, message: String) {
        this.busy=busy
        actions.forEach { it.isEnabled=!busy };stop.visibility=if(busy) View.VISIBLE else View.GONE;status.text=message
        setRecovery(retryAvailable,russiaAvailable)
    }
    fun setRecovery(retry: Boolean, russia: Boolean) {
        retryAvailable=retry;russiaAvailable=russia
        retryButton.visibility=if(!busy&&retry) View.VISIBLE else View.GONE
        russiaButton.visibility=if(!busy&&russia) View.VISIBLE else View.GONE
    }
    private fun change(edit: (WarpSettings)->WarpSettings): Boolean = try { settings=WarpSettingsStore.change(activity,edit);true } catch(_: Exception) { status.text="Настройку не удалось сохранить. Предыдущее значение действует.";false }
    private fun countryChoice() {
        val spinner=activity.picker(WarpCountryChoices.values(WarpCountryChoices.showAll(settings.country)).map {it.first}.toTypedArray(),0).apply {tag="warp-country"}
        val all=CheckBox(activity).apply {text="Все страны";textSize=13f;setTextColor(Loft.INK);tag="warp-all-countries";isChecked=WarpCountryChoices.showAll(settings.country);minimumHeight=activity.dp(48)}
        view.addView(LinearLayout(activity).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL
            addView(activity.text("Страна выхода",14,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
            addView(all,LinearLayout.LayoutParams(-2,-2))
        },LinearLayout.LayoutParams(-1,-2))
        view.addView(spinner,LinearLayout.LayoutParams(-1,-2))
        fun populate() {
            val values=WarpCountryChoices.values(all.isChecked)
            val selected=settings.country.takeUnless {it=="any"} ?: "foreign"
            var last=selected
            spinner.onItemSelectedListener=null
            spinner.adapter=activity.spinnerAdapter(values.map {it.first}.toTypedArray())
            spinner.setSelection(values.indexOfFirst {it.second==selected}.coerceAtLeast(0))
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?) {}
                override fun onItemSelected(parent:AdapterView<*>?,row:View?,position:Int,id:Long) {
                    val value=values.getOrNull(position)?.second ?: return
                    if(value!=last){if(change {it.copy(country=value,excludeDme=true)})last=value else populate()}
                }
            }
        }
        all.setOnCheckedChangeListener {_,checked ->
            if(!checked&&settings.country !in setOf("foreign","RU")) {
                if(!change {it.copy(country="foreign",excludeDme=true)}) {all.isChecked=true;return@setOnCheckedChangeListener}
            }
            populate()
        }
        actions.add(all);actions.add(spinner);populate()
    }
    private fun action(title: String, click: () -> Unit) {
        val button=activity.button(title,false).apply { setOnClickListener { click() } };actions.add(button);view.addView(button,activity.space(0,10))
    }
    private fun flag(title: String, value: Boolean, explanation: String, changed: (Boolean)->Boolean) {
        var last=value;var reverting=false
        val control=Switch(activity).apply { text=title;activity.tune(this);isChecked=value;setOnCheckedChangeListener { button,checked ->
            if(!reverting) { if(changed(checked)) last=checked else { reverting=true;button.isChecked=last;reverting=false } }
        } }
        actions.add(control);view.addView(control);view.addView(activity.text(explanation,11,Loft.MUTED),activity.space(0,12))
    }
    private fun choice(title: String, values: List<Pair<String,String>>, selected: String, explanation: String, changed: (String)->Boolean) {
        val control=activity.picker(values.map {it.first}.toTypedArray(),values.indexOfFirst {it.second==selected}.coerceAtLeast(0))
        var last=selected
        control.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { val value=values[position].second;if(value!=last){if(changed(value))last=value else control.setSelection(values.indexOfFirst { it.second==last }.coerceAtLeast(0))} }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        actions.add(control);view.addView(activity.field(title,control));view.addView(activity.text(explanation,11,Loft.MUTED),activity.space(0,12))
    }
}
