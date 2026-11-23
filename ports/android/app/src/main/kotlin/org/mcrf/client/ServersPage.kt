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

class ServersPage @JvmOverloads constructor(private val activity: MainActivity, private val callbacks: ServerPageActions = ServerPageActions()) {
    private val catalogue get() = activity.catalogue
    private fun sectionEntries(whitelistOnly:Boolean)=with(activity){
        val features=UsageFeaturesUi.selected(this)
        catalogue.entries().filter {
            val sectionVisible=if(it.whitelists)UsageFeatures.whitelists(features) else UsageFeatures.visible("Главная",features)
            (if(mergedConnections())sectionVisible else ConnectionSections.belongs(it,whitelistOnly))&&UsageFeatures.connection(it,features)
        }
    }
    internal fun measureVisible(whitelistOnly:Boolean){
        val entries=sectionEntries(whitelistOnly)
        measure(ServerVisibility.visible(entries,ServerVisibility.hidden(activity)+SubscriptionGrouping.hiddenEntryIds(entries,catalogue.subscriptions())))
    }
    @JvmOverloads fun build(page: LinearLayout, includeControls:Boolean=true, whitelistOnly:Boolean=false, controlsHost:LinearLayout=page) = with(activity) {
        val sectionEntries=sectionEntries(whitelistOnly)
        val subscriptions=catalogue.subscriptions()
        val individualHiddenIds=ServerVisibility.hidden(this)
        val grouping=runCatching {SubscriptionGrouping.groups(sectionEntries,subscriptions)}
        val membership=grouping.getOrElse {listOf(SubscriptionGrouping.Group(null,"Подключения",null,sectionEntries.distinctBy {it.id}))}
        val hiddenIds=individualHiddenIds+membership.filter {it.subscription?.optBoolean("hidden")==true}.flatMap {it.entries}.map {it.id}
        val entries=ServerVisibility.visible(sectionEntries,hiddenIds)
        if(includeControls) {
            FallbackChainUi.entry(activity,controlsHost,whitelistOnly)
        }
        val actions = LinearLayout(this).apply {tag="server-actions";gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(4))}
        val showTelegram=UsageFeatures.telegram(UsageFeaturesUi.selected(this))
        val actionCount=ServerToolbarActions.order(whitelistOnly,showWarpTools()).count {showTelegram||it!=ServerToolbarActions.Action.TELEGRAM}
        val tv=TvUi.enabled(this)
        val narrow=tv||contentWidthDp()-64<actionCount*48
        fun action(view:View){view.minimumWidth=dp(48);view.minimumHeight=dp(48);actions.addView(view,if(narrow)LinearLayout.LayoutParams(dp(48),dp(48))else LinearLayout.LayoutParams(0,dp(48),1f))}
        val clipboard = iconButton("clipboard", "Вставить из буфера").apply {
            setOnClickListener {importFromClipboard()} }
        action(clipboard)
        if(!tv)action(iconButton("qr", "QR камерой или из изображения").apply { setOnClickListener {scanServerQr()} })
        val measureSlot=FrameLayout(this).apply {tag="probe-action"};action(measureSlot)
        var showingStop:Boolean?=null
        fun updateMeasureAction(running:Boolean){if(showingStop==running)return;showingStop=running;measureSlot.removeAllViews()
            measureSlot.addView(iconButton(if(running)"stop" else "ping",if(running)"Остановить замер" else if(whitelistOnly)"Замерить серверы" else "Замерить серверы · удерживайте для A/B транспортов").apply {setOnClickListener {
                if(probing()||showingStop==true)ProbeCoordinator.cancel(this@with)
                else if(!working()){measure(entries);updateMeasureAction(probing())}
            }
                if(!whitelistOnly)setOnLongClickListener {
                    if(probing()||working())toast("Дождитесь завершения замера")
                    else callbacks.compareTransports?.invoke(false) ?: TransportComparisonUi.show(this@with,false)
                    true
                }
            },FrameLayout.LayoutParams(-1,-1))
        }
        updateMeasureAction(ProbeCoordinator.measurements!=null)
        val refresh=FrameLayout(this).apply {tag="subscription-refresh";clipChildren=false;clipToPadding=false}
        val refreshIcon=iconButton("refresh","Обновить подписки · удерживайте для выбора интервала")
        val intervalLabel=text("",10,Loft.MUTED).apply {gravity=Gravity.CENTER;includeFontPadding=false}
        fun intervalText(){try { intervalLabel.text=SubscriptionSchedule.badgeMinutes(SubscriptionOptionsStore.load(this@with).intervalMinutes) }
            catch(_:Exception){intervalLabel.text="?";refresh.contentDescription="Обновить подписки · расписание не удалось прочитать"}}
        fun chooseInterval(anchor:View):Boolean = SubscriptionGroupingRefreshUi.chooseInterval(this@with,anchor) {intervalText()}
        refresh.contentDescription="Обновить подписки · удерживайте для выбора интервала";refresh.isFocusable=true
        refresh.setOnClickListener {refreshSubscriptions()};refresh.setOnLongClickListener {chooseInterval(it)}
        refreshIcon.isClickable=false;refreshIcon.isLongClickable=false;refreshIcon.isFocusable=false;refreshIcon.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        intervalLabel.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        refresh.addView(refreshIcon,FrameLayout.LayoutParams(-1,-1));refresh.addView(intervalLabel,FrameLayout.LayoutParams(-1,dp(14),Gravity.TOP).apply {if(tv)topMargin=dp(3)});intervalText();action(refresh)
        val showWarp=showWarpTools()
        ServerToolbarActions.order(whitelistOnly,showWarp).drop(4).forEach {item->
            when(item){
                ServerToolbarActions.Action.VK -> if(!tv||TvUi.showVkTurn(this@with))action(iconButton("vk","Настройки VK Turn",26).apply {
                    tag="server-action-vk";contentDescription="Настройки VK Turn"
                    setOnClickListener {callbacks.vkSettings?.invoke() ?: navigate(ClientSettingsNavigation.VK)}
                })
                ServerToolbarActions.Action.TELEGRAM -> if(!tv&&showTelegram)action(iconButton("telegram","Добавить в TG Proxy").apply {
                    tag="server-action-telegram";setOnClickListener {
                        try {callbacks.telegramProxy?.invoke() ?: startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(TelegramBridge.setupLink(TelegramBridge.DEFAULT_PORT))))}
                        catch(_:ActivityNotFoundException){alert("Telegram","На телефоне не найден Telegram.")}
                    }
                })
                ServerToolbarActions.Action.WARP -> action(iconButton("warp","WARP · добавить сервер").apply {
                    tag="server-action-warp";setOnClickListener {callbacks.warp?.invoke() ?: navigate("WARP")}
                })
                else -> Unit
            }
        }
        if(includeControls&&tv)controlsHost.addView(button("Добавить ссылку",false).apply {
            tag="tv-server-import"
            setOnClickListener {importDialog()}
        },space(0,6))
        if(includeControls&&tv)controlsHost.addView(button("Принять с Android",false).apply {
            tag="tv-server-receive"
            setOnClickListener { TvPairingUi.receive(this@with) }
        },space(0,6))
        if(includeControls)controlsHost.addView(if(narrow)HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;addView(actions,ViewGroup.LayoutParams(-2,-2))}else actions, space(0, 0))
        val progressLabel=text("",12,Loft.MUTED).apply { visibility=View.GONE;contentDescription="Ход проверки серверов" }
        controlsHost.addView(progressLabel,space(0,if(controlsHost===page)12 else 0))
        if(includeControls && sectionEntries.any {it.id in individualHiddenIds})controlsHost.addView(button("Показать скрытые серверы",false).apply {
            tag="server-hidden-restore";setOnClickListener {TransportComparisonUi.showHidden(this@with,whitelistOnly){render()}}
        },space(8,8))
        if (entries.isEmpty()) page.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="servers-empty"
            // Orbit pages intentionally have no outer padding; their empty text still needs a safe inset.
            if(orbitEnabled())setPadding(dp(20),0,dp(20),0)
            addView(text(if(sectionEntries.isEmpty())"Пока нет серверов" else "Серверы скрыты",18,Loft.INK))
            addView(text(if(tv)"Добавьте ссылку на сервер или подписку." else "Вставьте ссылку или подписку, либо отсканируйте QR-код.",13,Loft.MUTED),space(8,0))
        },space(0,20))
        val pool = try { catalogue.autoIds() } catch (_: Exception) { emptySet() }
        val results = try { catalogue.presentationProbes() } catch (_: Exception) { JSONObject() }
        val delays=entries.associate {it.id to results.optJSONObject(it.id)?.optLong("delay",-1)}
        var orbitDelays=delays
        var orbitPanel:ServerOrbitPicker.Panel?=null
        val goodLimit=PingStatus.goodLimit(delays.values)
        val rows=mutableMapOf<String,ServerRow>()
        val orbit=orbitEnabled()
        val ordered=PingStatus.sorted(entries) { results.optJSONObject(it.id)?.optLong("delay", -1) }
        if(grouping.isFailure){
            page.addView(text("Не удалось прочитать группы подписок. Все подключения доступны ниже.",12,Loft.ERROR),space(0,8))
        }
        val order=ordered.mapIndexed {index,entry->entry.id to index}.toMap()
        val grouped=membership.map {group->group.copy(entries=group.entries.filter {it.id in order}.sortedBy {order.getValue(it.id)})}
        val groups=grouped.flatMap {group->
            if(!mergedConnections()||group.subscription!=null)listOf(group)
            else group.entries.groupBy {it.whitelists}.map {(bs,items)->
                group.copy(id=if(bs)"manual-bs" else null,title=if(bs)"БС · вручную" else "Вручную",entries=items)
            }
        }
        if(orbit&&ordered.isNotEmpty()) {
            val orbitGroups=buildList {
                ordered.filter {it.whitelists}.takeIf {it.isNotEmpty()}?.let {add(ServerOrbitPicker.Group("БС",it))}
                grouped.filter {it.subscription!=null}.forEach {group->
                    group.entries.filterNot {it.whitelists}.takeIf {it.isNotEmpty()}?.let {add(ServerOrbitPicker.Group(group.title,it))}
                }
                grouped.filter {it.subscription==null}.flatMap {it.entries}.filterNot {it.whitelists}
                    .takeIf {it.isNotEmpty()}?.let {add(ServerOrbitPicker.Group("",it))}
            }
            val countries=try {catalogue.exitTests()}catch(_:Exception){JSONObject()}
            val owners=grouped.filter {it.subscription!=null}.flatMap {group->group.entries.map {it.id to group}}.toMap()
            orbitPanel=ServerOrbitPicker.build(this,orbitGroups,selected,
                {id->orbitDelays[id]},
                {id->countries.optJSONObject(id)?.optString("country").orEmpty()},
                ::select,{entry->ConnectionEditor(this,entry).show(Runnable {activity.render()})},
                {entry->measure(listOf(entry))},{entry->shareConnection(entry)},::deleteServer,
                subscription={entry->owners[entry.id]?.let {group->
                    val sub=requireNotNull(group.subscription)
                    ServerOrbitPicker.Subscription(group.title,{toggleSubscription(sub)},{editSubscription(sub)},{removeSubscription(sub)})
                }},openSubscriptions=if(subscriptions.isEmpty())null else ::showSubscriptions)
            page.addView(orbitPanel.view,LinearLayout.LayoutParams(-1,0,1f))
        }
        if(orbit&&ordered.isEmpty()&&subscriptions.isNotEmpty())attachOrbitFooter(OrbitSubscriptionsUi.footer(this,null,::showSubscriptions))
        if(!orbit)groups.filter { it.entries.isNotEmpty() }.forEach { group ->
            val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="server-group-${group.id ?: "manual"}";setPadding(0,0,0,0)}
            val subscription=group.subscription
            val title=if(subscription==null&&group.id==null) "Вручную" else group.title
            val header=if(subscription!=null) subscriptionHeader(subscription,group.title,group.entries)
                else LinearLayout(this).apply {
                    gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48);setPadding(dp(8),0,dp(8),0)
                    addView(text("$title · ${group.entries.size}",14,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
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
            if(subscription==null)list.addView(header)else {
                val id=subscription.getString("id")
                list.addView(ServerSwipeRow(this,header,"subscription-$id",Runnable {removeSubscription(subscription)},emptyList(),
                    Runnable {refreshSubscription(subscription)},subscriptionShare={shareSubscription(subscription)},editSubscription={editSubscription(subscription)}))
            }
            list.addView(body);displayDisclosure()
            group.entries.forEachIndexed { entryIndex,entry ->
                divider(body,0,0)
                val result=results.optJSONObject(entry.id)
                lateinit var row:ServerRow
                row=ServerRow(this,entry,entry.id==selected,entry.id in pool,
                    result?.optLong("delay",-1),Runnable {select(entry.id)},Consumer {value ->
                        if(!saveAutoMark(entry.id,value,Runnable {pageRefresh?.invoke()}))row.restoreAuto()
                    },Runnable {ConnectionEditor(this,entry).show(Runnable {activity.render()})},automatic,ConnectionSections.policy(entry.whitelists),goodLimit)
                rows[entry.id]=row
                body.addView(ServerSwipeRow(this,row,entry.id,Runnable {deleteServer(entry)},
                    listOf(
                        ServerSwipeRow.ShareAction("share","Поделиться ${entry.name}",Runnable {shareConnection(entry)}),
                        ServerSwipeRow.ShareAction("qr","QR-код ${entry.name}",Runnable {showQr(entry.name,catalogue.share(entry))}),
                        ServerSwipeRow.ShareAction("share-shield","Поделиться шифрованной ссылкой ${entry.name}",Runnable {encryptedShare(entry.name,catalogue.share(entry),false)}),
                        ServerSwipeRow.ShareAction("qr-shield","Шифрованный QR-код ${entry.name}",Runnable {encryptedShare(entry.name,catalogue.share(entry),true)})
                    ),Runnable {measure(listOf(entry))},entry.configuration==null&&(!entry.needsEngine()||entry.carrier!=null),entryIndex==group.entries.lastIndex),LinearLayout.LayoutParams(-1,-2))
            }
            if(group.entries.isEmpty()) body.addView(text("Серверы этой подписки уже показаны выше либо пока отсутствуют.",11,Loft.MUTED).apply {setPadding(dp(12),0,dp(12),dp(12))})
            page.addView(list,space(0,4))
        }
        pageRefresh={
            val current=ProbeCoordinator.measurements
            updateMeasureAction(current!=null)
            progressLabel.text=current?.label().orEmpty();progressLabel.visibility=if(current==null)View.GONE else View.VISIBLE
            val saved=try {catalogue.presentationProbes()}catch(_:Exception){results}
            val latest=entries.associate {it.id to saved.optJSONObject(it.id)?.optLong("delay",-1)}+(current?.results ?: emptyMap())
            orbitDelays=latest
            orbitPanel?.refreshDelays?.invoke(latest)
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
    private fun editSubscription(sub:JSONObject)=with(activity) {
        val id=sub.getString("id")
        val current=catalogue.subscriptions().firstOrNull {it.getString("id")==id}?:return@with toast("Подписка уже удалена")
        val url=current.getString("url")
        val name=editor("Название подписки").apply {tag="subscription-title";setSingleLine(true);setText(SubscriptionGrouping.title(current))}
        val input=editor("HTTPS-ссылка подписки").apply {tag="subscription-url";setSingleLine(true);setText(url)}
        val form=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(8))
            addView(text("Название",13,Loft.MUTED));addView(name,space(4,12))
            addView(text("Ссылка подписки",13,Loft.MUTED));addView(input,space(4,0))
        }
        val dialog=ThemedDialogBuilder(this).setTitle("Подписка").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val address=input.text.toString();val title=name.text.toString().trim()
            runAfterSuccess(Operation {
                require(title.length in 1..256&&title.none(Char::isISOControl)) {"Название подписки: от 1 до 256 символов без управляющих знаков."}
                if(SubscriptionClient.address(address).toString()==url){catalogue.renameSubscription(id,title);"Подписка сохранена"}
                else {val fetched=SubscriptionUpdates.fetch(this,address);val changes=catalogue.editFetched(id,address,fetched,title);reconcileSelection(false);changes.summary()}
            },true,Runnable {dialog.dismiss()})
        }};dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show()
    }
    private fun toggleSubscription(sub:JSONObject)=with(activity){
        val id=sub.getString("id")
        run(Operation {
            val current=catalogue.subscriptions().firstOrNull {it.getString("id")==id}?:throw IllegalArgumentException("Подписка уже удалена.")
            val hidden=!current.optBoolean("hidden");catalogue.setSubscriptionHidden(id,hidden)
            if(hidden)"Подписка скрыта"else"Подписка показана"
        })
    }
    private fun showSubscriptions(anchor:View)=OrbitSubscriptionsUi.show(activity,anchor,catalogue.subscriptions(),::toggleSubscription,::editSubscription,::removeSubscription)
    private fun refreshSubscription(sub:JSONObject)=with(activity) {
        val id=sub.getString("id");val url=sub.getString("url")
        run(Operation {val fetched=SubscriptionUpdates.fetch(this,url);val changes=catalogue.editFetched(id,url,fetched);reconcileSelection(false);changes.summary()})
    }
    private fun removeSubscription(sub:JSONObject)=with(activity) {
        val id=sub.getString("id");val host=SubscriptionClient.address(sub.getString("url")).host
        ThemedDialogBuilder(this).setTitle("Удалить подписку?").setMessage("$host · вручную добавленные серверы останутся.")
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->run(Operation {catalogue.removeSubscription(id);reconcileSelection(false);"Подписка удалена"})}.show()
    }
    private fun shareSubscription(sub:JSONObject)=with(activity) {
        val id=sub.getString("id")
        val options=ServerShareOptions.values().filter {it!=ServerShareOptions.SETTINGS_LINK}
        DropdownPopup.show(this,window.decorView.findViewWithTag("server-group-toggle-$id")?:window.decorView,"Поделиться подпиской",
            options.map {DropdownPopup.Item(if(it==ServerShareOptions.LINK)"Копировать ссылку подписки"else it.title,selectable=false)},-1) {index->
            // Resolve committed data again: editing a subscription invalidates captured links.
            val current=catalogue.subscriptions().firstOrNull {it.optString("id")==id}
            if(current==null){toast("Подписка уже удалена");return@show}
            val url=current.getString("url");val host=SubscriptionClient.address(url).host;val option=options[index]
            fun copy(value:String) {
                try {getSystemService(ClipboardManager::class.java).setPrimaryClip(AndroidDeployClipboard.sensitive(value,"Подписка MCRF"));toast("Ссылка скопирована")}
                catch(_:Exception){alert("Поделиться","Не удалось скопировать ссылку.")}
            }
            if(option.encrypted)CompatibilityImportUi.shareEncrypted(this,url){value->if(option.qr)showQr(host,value)else copy(value)}
            else if(option.qr)showQr(host,url)else copy(url)
        }
    }
    private fun subscriptionHeader(sub:JSONObject,title:String,entries:List<ServerImport.Server>):LinearLayout=with(activity) {
        val host=SubscriptionClient.address(sub.getString("url")).host
        LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(4),dp(2),dp(4),dp(2));minimumHeight=dp(52)
            addView(LinearLayout(this@with).apply {
                orientation=LinearLayout.VERTICAL
                addView(text(title,14,Loft.INK).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END})
                addView(text("Серверов: ${entries.size}",11,Loft.MUTED))
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(iconButton("ping","Проверить серверы подписки $host").apply {setOnClickListener {measure(entries)}},LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(iconButton("more","Действия с подпиской $host").apply {setOnClickListener {anchor->
                DropdownPopup.show(this@with,anchor,null,listOf(DropdownPopup.Item("Данные подписки",selectable=false),
                    DropdownPopup.Item("Изменить название и ссылку",selectable=false),
                    DropdownPopup.Item(if(sub.optBoolean("hidden"))"Показать серверы подписки"else"Скрыть серверы подписки",selectable=false)),-1) {index->when(index){0->subscriptionDetails(sub);1->editSubscription(sub);else->toggleSubscription(sub)}}
            }},LinearLayout.LayoutParams(dp(48),dp(48)))
        }
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
        val entries=only ?: ConnectionSections.entries(catalogue.entries(),connectionSectionWhitelists())
        val eligible=ProbePlan.all(entries,ClientPrimarySelection.selected(applicationContext),explicit=only!=null)
        val results = ProbeCoordinator.measure(applicationContext, eligible, expectedCancellation = token); catalogue.recordProbes(results)
        val passed = results.values.count { it >= 0 }; ClientEvents.record(this, "Замеры", "Проверено серверов: ${results.size}, отвечают: $passed")
        "Проверено: ${results.size} из ${entries.size} · отвечают: $passed" + if(eligible.size<entries.size) ". Отдельные JSON-профили: ${entries.size-eligible.size}" else ""
    }) }
}
