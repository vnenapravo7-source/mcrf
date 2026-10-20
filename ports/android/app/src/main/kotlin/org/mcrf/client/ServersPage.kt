package org.mcrf.client

import android.app.AlertDialog
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.util.function.Consumer
import org.mcrf.client.MainActivity.Operation

class ServersPage(private val activity: MainActivity) {
    private val catalogue get() = activity.catalogue
    @JvmOverloads fun build(page: LinearLayout, includeControls:Boolean=true, whitelistOnly:Boolean=false) = with(activity) {
        val entries=ConnectionSections.entries(catalogue.entries(),whitelistOnly).filter {UsageFeatures.connection(it,UsageFeaturesUi.selected(this))}
        val actions = LinearLayout(this).apply {tag="server-actions"}
        fun action(view:View){actions.addView(view,LinearLayout.LayoutParams(0,dp(48),1f))}
        val clipboard = iconButton("clipboard", "Вставить из буфера").apply {
            setOnClickListener { try {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                val value = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this@with)?.toString().orEmpty() else ""
                if(value.isBlank()) toast("В буфере нет ссылки или конфигурации") else importDialog(value)
            } catch(_:Exception) {alert("Импорт","Не удалось прочитать буфер обмена.")} } }
        action(clipboard)
        action(iconButton("qr", "QR камерой или из изображения").apply { setOnClickListener { startActivityForResult(Intent(this@with, QrScanActivity::class.java), 6) } })
        action(iconButton("folder", "Импортировать из файла").apply { setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 3) } })
        val measureSlot=FrameLayout(this).apply {tag="probe-action"};action(measureSlot)
        var showingStop:Boolean?=null
        fun updateMeasureAction(running:Boolean){if(showingStop==running)return;showingStop=running;measureSlot.removeAllViews()
            measureSlot.addView(iconButton(if(running)"stop" else "ping",if(running)"Остановить замер" else "Замерить серверы").apply {setOnClickListener {
                if(probing()||showingStop==true)ProbeCoordinator.cancel(this@with)
                else if(!working()){measure(entries);updateMeasureAction(probing())}
            }},FrameLayout.LayoutParams(-1,-1))
        }
        updateMeasureAction(ProbeCoordinator.measurements!=null)
        val refresh=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;tag="subscription-refresh"}
        val refreshIcon=iconButton("refresh","Обновить подписки · удерживайте для выбора интервала")
        val intervalLabel=text("",10,Loft.MUTED).apply {gravity=Gravity.CENTER}
        fun intervalText(){try { intervalLabel.text=SubscriptionSchedule.badgeMinutes(SubscriptionOptionsStore.load(this@with).intervalMinutes) }
            catch(_:Exception){intervalLabel.text="?";refresh.contentDescription="Обновить подписки · расписание не удалось прочитать"}}
        fun chooseInterval(anchor:View):Boolean = SubscriptionGroupingRefreshUi.chooseInterval(this@with,anchor) {intervalText()}
        refresh.contentDescription="Обновить подписки · удерживайте для выбора интервала";refresh.isFocusable=true
        refresh.setOnClickListener {refreshSubscriptions()};refresh.setOnLongClickListener {chooseInterval(it)}
        refreshIcon.isClickable=false;refreshIcon.isLongClickable=false;refreshIcon.isFocusable=false;refreshIcon.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        intervalLabel.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        refresh.addView(refreshIcon,LinearLayout.LayoutParams(-1,dp(34)));refresh.addView(intervalLabel,LinearLayout.LayoutParams(-1,dp(14)));intervalText();action(refresh)
        page.addView(actions, space(0, 8))
        val progressLabel=text("",12,Loft.MUTED).apply { visibility=View.GONE;contentDescription="Ход проверки серверов" }
        page.addView(progressLabel,space(0,12))
        if(includeControls) page.addView(buildControls(whitelistPolicy=whitelistOnly),space(0,12))
        section(page, "ПОДКЛЮЧЕНИЯ · ${entries.size}")
        if (entries.isEmpty()) page.addView(card().apply { addView(text("Пока нет серверов", 18, Loft.INK)); addView(text("Вставьте ссылку или подписку, либо выберите файл конфигурации.", 13, Loft.MUTED), space(8, 0)) }, space(0, 20))
        val pool = try { catalogue.autoIds() } catch (_: Exception) { emptySet() }
        val results = try { catalogue.probes() } catch (_: Exception) { JSONObject() }
        val delays=entries.associate {it.id to results.optJSONObject(it.id)?.optLong("delay",-1)}
        val goodLimit=PingStatus.goodLimit(delays.values)
        val rows=mutableMapOf<String,ServerRow>()
        val ordered=PingStatus.sorted(entries) { results.optJSONObject(it.id)?.optLong("delay", -1) }
        val groups=try { SubscriptionGrouping.groups(ordered,catalogue.subscriptions()) } catch(_:Exception) {
            page.addView(text("Не удалось прочитать группы подписок. Все подключения доступны ниже.",12,Loft.ERROR),space(0,8))
            listOf(SubscriptionGrouping.Group(null,"Подключения",null,ordered.distinctBy {it.id}))
        }
        groups.filter { it.entries.isNotEmpty() }.forEach { group ->
            val list=card().apply {orientation=LinearLayout.VERTICAL;tag="server-group-${group.id ?: "manual"}";setPadding(dp(4),dp(4),dp(4),dp(4))}
            val subscription=group.subscription
            val title=if(subscription==null) "Вручную" else group.title
            val header=if(subscription!=null) subscriptionHeader(subscription,group.title,group.entries.size)
                else LinearLayout(this).apply {
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(56);setPadding(dp(8),dp(4),dp(8),dp(4))
                    addView(text("Вручную · ${group.entries.size}",14,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                }
            val key=ServerGroupDisclosure.key(group.id)
            var collapsed=prefs.getBoolean(key,false)
            val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="server-group-body-${group.id ?: "manual"}"}
            // The header owns the touch target; a Button here leaks its pressed outline into the label.
            val arrow=ImageView(this).apply {
                setImageDrawable(icon("arrow",Loft.MUTED))
                scaleType=ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(15),dp(15),dp(15),dp(15))
                background=null
                isClickable=false;isLongClickable=false;isFocusable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            header.addView(arrow,0,LinearLayout.LayoutParams(dp(48),dp(48)))
            fun displayDisclosure() {
                body.visibility=if(collapsed) View.GONE else View.VISIBLE
                arrow.rotation=if(collapsed) 0f else 90f
                header.contentDescription=ServerGroupDisclosure.description(title,group.entries.size,collapsed)
            }
            header.tag="server-group-toggle-${group.id ?: "manual"}";header.isFocusable=true
            header.setOnClickListener { collapsed=!collapsed;prefs.edit().putBoolean(key,collapsed).apply();displayDisclosure() }
            list.addView(header);list.addView(body);displayDisclosure()
            group.entries.forEachIndexed { entryIndex,entry ->
                divider(body,0,0)
                val result=results.optJSONObject(entry.id)
                lateinit var row:ServerRow
                row=ServerRow(this,entry,entry.id==selected,entry.id in pool,
                    result?.optLong("delay",-1),Runnable {select(entry.id)},Consumer {value ->
                        if(!saveAutoMark(entry.id,value,Runnable {pageRefresh?.invoke()}))row.restoreAuto()
                    },Runnable {ConnectionEditor(this,entry).show(Runnable {activity.render()})},automatic,autoPolicy(),goodLimit)
                rows[entry.id]=row
                body.addView(ServerSwipeRow(this,row,entry.id,Runnable {deleteServer(entry)},
                    listOf(
                        ServerSwipeRow.ShareAction("share","Поделиться ${entry.name}",Runnable {share(entry.name,catalogue.share(entry))}),
                        ServerSwipeRow.ShareAction("qr","QR-код ${entry.name}",Runnable {showQr(entry.name,catalogue.share(entry))}),
                        ServerSwipeRow.ShareAction("share-shield","Поделиться шифрованной ссылкой ${entry.name}",Runnable {encryptedShare(entry.name,catalogue.share(entry),false)}),
                        ServerSwipeRow.ShareAction("qr-shield","Шифрованный QR-код ${entry.name}",Runnable {encryptedShare(entry.name,catalogue.share(entry),true)})
                    ),Runnable {measure(listOf(entry))},entry.configuration==null&&(!entry.needsEngine()||entry.carrier!=null),entryIndex==group.entries.lastIndex),LinearLayout.LayoutParams(-1,-2))
            }
            if(group.entries.isEmpty()) body.addView(text("Серверы этой подписки уже показаны выше либо пока отсутствуют.",11,Loft.MUTED).apply {setPadding(dp(12),0,dp(12),dp(12))})
            page.addView(list,space(0,12))
        }
        pageRefresh={
            val current=ProbeCoordinator.measurements
            updateMeasureAction(current!=null)
            progressLabel.text=current?.label().orEmpty();progressLabel.visibility=if(current==null)View.GONE else View.VISIBLE
            val saved=try {catalogue.probes()}catch(_:Exception){results}
            val latest=entries.associate {it.id to saved.optJSONObject(it.id)?.optLong("delay",-1)}+(current?.results ?: emptyMap())
            val limit=PingStatus.goodLimit(latest.values)
            latest.forEach { (id,delay) ->rows[id]?.showDelay(delay,limit) }
            rows.forEach {(id,row)->row.showChosen(id==selected)}
            if(!working()) {
                val marked=try {catalogue.autoIds()}catch(_:Exception){null}
                if(marked!=null)rows.forEach {(id,row)->row.showAuto(id in marked,automatic)}
            }
        }
        pageRefresh?.invoke()
    }
    /** Host the existing mode pill and automation in one surface; no overlay gutter. */
    @JvmOverloads fun buildControls(mode:View?=null,whitelistPolicy:Boolean=false):LinearLayout = with(activity) {
        val options = card().apply {orientation=LinearLayout.VERTICAL;tag="auto-options";setPadding(dp(14),dp(6),dp(10),dp(6))}
        mode?.let { options.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        lateinit var auto:AutoSelectSwitch
        var syncAutoControls:()->Unit={}
        auto = AutoSelectSwitch(this, automatic, { working() }) { value ->
            val applied=setAutomatic(value);auto.showChecked(automatic);syncAutoControls();pageRefresh?.invoke()
            if(applied&&value)measureAllOnEnable()
        }
        val optionsRow=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;addView(auto,LinearLayout.LayoutParams(0,dp(48),1f))}
        options.addView(optionsRow,LinearLayout.LayoutParams(-1,dp(48)))
        val probe = LinearLayout(this).apply {tag="auto-details";orientation = LinearLayout.VERTICAL; visibility = if(automatic&&prefs.getBoolean("autoExpanded",false))View.VISIBLE else View.GONE;setPadding(0,dp(8),dp(4),dp(8)) }
        val expand=iconButton("arrow","Параметры автовыбора").apply {tag="auto-expand";isEnabled=automatic;rotation=if(probe.visibility==View.VISIBLE)270f else 90f
            setOnClickListener {val expanded=probe.visibility!=View.VISIBLE;probe.visibility=if(expanded)View.VISIBLE else View.GONE;prefs.edit().putBoolean("autoExpanded",expanded).apply();animate().rotation(if(expanded)270f else 90f).setDuration(160).start()}
        }
        optionsRow.addView(expand,LinearLayout.LayoutParams(dp(48),dp(48)))
        syncAutoControls={
            probe.visibility=if(automatic&&prefs.getBoolean("autoExpanded",false))View.VISIBLE else View.GONE
            expand.isEnabled=automatic;expand.animate().cancel();expand.rotation=if(probe.visibility==View.VISIBLE)270f else 90f
        }
        autoControlsRefresh={auto.showChecked(automatic);syncAutoControls()}
        if(whitelistPolicy)probe.addView(text("Автовыбор проверяет отмеченные подключения белых списков при запуске: сначала приоритет движка, затем свежий пинг. Устаревший замер не снимает отметку. VPN-серверы в этот пул не входят.",12,Loft.MUTED).apply {tag="whitelist-auto-hint"},space(0,8))
        val seconds = number(prefs.getInt("interval", 60)); val tolerance = number(prefs.getInt("tolerance", 50))
        seconds.tag="auto-interval";tolerance.tag="auto-tolerance"
        seconds.contentDescription="Интервал проверки серверов, секунды"
        tolerance.contentDescription="Допустимое отклонение задержки, миллисекунды"
        // Each section has one unambiguous pool; no hidden BS choice on VPN.
        fun autoField(label:String,unit:String,control:EditText)=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(2));background=flat(Loft.FIELD,14,0)
            addView(text(label,10,Loft.MUTED).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
            addView(LinearLayout(this@with).apply {gravity=Gravity.CENTER
                addView(control.apply {textSize=15f;includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL or Gravity.END;background=null;setPadding(0,0,0,0);minimumHeight=dp(40);minHeight=dp(40);minimumWidth=dp(32);minWidth=dp(32)},LinearLayout.LayoutParams(-2,dp(40)))
                addView(text(unit,12,Loft.MUTED),LinearLayout.LayoutParams(-2,-2).apply {leftMargin=dp(4)})
            },LinearLayout.LayoutParams(-1,dp(40)))
        }
        val timing=LinearLayout(this).apply {gravity=Gravity.TOP}
        timing.addView(autoField("Замер раз в","с",seconds),LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(8)})
        timing.addView(autoField("Отклонение","мс",tolerance),LinearLayout.LayoutParams(0,-2,1f))
        probe.addView(timing,space(10,0))
        val description=if(autoPolicy()==AutoSelection.BS_ENGINES)"CSQTT → QWDTT → WDTT → OpenFlux" else "Отмеченные серверы · доступность проверяется при подключении; старый пинг не отменяет выбор"
        probe.addView(text(description,11,Loft.MUTED).apply {gravity=Gravity.CENTER;setLineSpacing(dp(2).toFloat(),1f)},space(10,2))
        fun persist(input:EditText,key:String,range:IntRange){input.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(s:android.text.Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){val value=s.toString().toIntOrNull();if(value!=null&&value in range){prefs.edit().putInt(key,value).apply();input.error=null}}
        });input.setOnFocusChangeListener {_,focus->if(!focus&&input.text.toString().toIntOrNull() !in range)input.error="${range.first}–${range.last}"}}
        persist(seconds,"interval",5..86400);persist(tolerance,"tolerance",0..10000)
        options.addView(probe)
        options
    }
    private fun subscriptionHeader(sub: JSONObject,title:String,count:Int):LinearLayout = with(activity) {
        val url = sub.getString("url"); val host = SubscriptionClient.address(url).host; val id = sub.getString("id")
        fun edit() {
            val input = editor("HTTPS-ссылка подписки").apply { setSingleLine(true); setText(url) }
            val form = LinearLayout(this@with).apply { setPadding(dp(20), dp(12), dp(20), dp(8)); addView(input, LinearLayout.LayoutParams(-1, -2)) }
            val dialog = ThemedDialogBuilder(this@with).setTitle("Ссылка подписки").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text.toString()
                run(Operation { val fetched = SubscriptionUpdates.fetch(this@with, address); val changes=catalogue.editFetched(id, address, fetched); reconcileSelection(false); changes.summary() }, true, Runnable { dialog.dismiss() })
            } }; dialog.show()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(4), dp(8)); minimumHeight = dp(72) }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(text(title, 15, Loft.INK).apply { maxLines=2; ellipsize = TextUtils.TruncateAt.END })
            addView(text("$count серверов", 11, Loft.MUTED), space(5, 0))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(iconButton("edit", "Изменить ссылку подписки $host").apply { setOnClickListener { edit() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(iconButton("more", "Действия с подпиской $host").apply { setOnClickListener { anchor ->
            ThemedActionMenu(this@with, anchor).apply {
                listOf("Данные подписки", "Обновить", "Поделиться", "Удалить", "QR-код", "Поделиться шифрованной ссылкой", "Шифрованный QR-код").forEachIndexed { index, title -> menu.add(0, index, index, title) }
                setOnMenuItemClickListener { item -> when (item.itemId) {
                    0 -> subscriptionDetails(sub)
                    1 -> run(Operation { val fetched = SubscriptionUpdates.fetch(this@with, url); val changes=catalogue.editFetched(id, url, fetched); reconcileSelection(false); changes.summary() })
                    2 -> share(host, url)
                    3 -> ThemedDialogBuilder(this@with).setTitle("Удалить подписку?").setMessage("$host · вручную добавленные серверы останутся.").setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ -> run(Operation { catalogue.removeSubscription(id); reconcileSelection(false); "Подписка удалена" }) }.show()
                    4 -> showQr(host, url)
                    5 -> encryptedShare(host, url, false)
                    6 -> encryptedShare(host, url, true)
                }; true }; show()
            }
        } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row
    }
    private fun subscriptionDetails(sub: JSONObject) = with(activity) {
        val meta = sub.optJSONObject("metadata") ?: JSONObject()
        val used = meta.optLong("upload", 0).toDouble() + meta.optLong("download", 0).toDouble()
        fun bytes(value: Double) = if (value >= 1_073_741_824) "%.2f ГБ".format(value / 1_073_741_824) else "%.1f МБ".format(value / 1_048_576)
        val expiry = meta.optLong("expire", 0)
        val info = buildString {
            append("Серверов: ${sub.getJSONArray("servers").length()}\n")
            append("Трафик: ${if (meta.has("upload") || meta.has("download")) bytes(used) else "не указан сервером"}\n")
            append("Лимит: ${if (meta.optLong("total", 0) > 0) bytes(meta.getLong("total").toDouble()) else "не указан сервером"}\n")
            append("Действует до: ${if (expiry > 0 && expiry <= Long.MAX_VALUE / 1000) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(expiry * 1000)) else "не указано сервером"}\n")
            append("Лимит устройств: ${if (meta.has("deviceLimit")) meta.getInt("deviceLimit").toString() else "не указан сервером"}\n\n")
            append("HWID установки: ${SubscriptionUpdates.identity(this@with)}")
            append("\nПередача HWID, модели и версии Android: ${if(SubscriptionOptionsStore.load(this@with).sendDeviceHeaders) "разрешена пользователем" else "отключена"}")
        }
        alert("Данные подписки", info)
    }
    private fun deleteServer(entry:ServerImport.Server) = with(activity) {
        if(working()){toast("Дождись завершения операции");return@with}
        ThemedDialogBuilder(this).setTitle("Удалить подключение?").setMessage(entry.name)
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->
                run(Operation {catalogue.remove(entry.id);reconcileSelection(false);"Подключение удалено"})
            }.show()
    }
    private fun encryptedShare(label:String, source:String, qr:Boolean) {
        CompatibilityImportUi.shareEncrypted(activity, source) { encrypted ->
            if(qr) activity.showQr(label, encrypted) else activity.share(label, encrypted)
        }
    }
    private fun autoPolicy()=activity.connectionSectionPolicy()
    private fun measureAllOnEnable() = measure(ProbePlan.automatic(ConnectionSections.entries(activity.catalogue.entries(),activity.connectionSectionWhitelists()),autoPolicy()))
    private fun measure(only: List<ServerImport.Server>? = null) = with(activity) {
        val token = ProbeCoordinator.cancellationToken()
        measureServers(Operation {
        val entries=only ?: ConnectionSections.entries(catalogue.entries(),connectionSectionWhitelists());val eligible=ProbePlan.all(entries)
        val results = ProbeCoordinator.measure(applicationContext, eligible, expectedCancellation = token); catalogue.recordProbes(results)
        val passed = results.values.count { it >= 0 }; ClientEvents.record(this, "Замеры", "Проверено серверов: ${results.size}, отвечают: $passed")
        "Проверено: ${results.size} из ${entries.size} · отвечают: $passed" + if(eligible.size<entries.size) ". Отдельные JSON-профили: ${entries.size-eligible.size}" else ""
    }) }
}
