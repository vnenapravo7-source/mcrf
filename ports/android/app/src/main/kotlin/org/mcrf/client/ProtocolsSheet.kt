package org.mcrf.client

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import java.util.Locale

object ProtocolsSheet {
    val engines=EngineModules.allKinds.toList()
    fun installed(activity:MainActivity,kinds:Collection<String> = engines)=kinds.count {EnginePackages.missing(activity,setOf(it)).isEmpty()}
    fun visibleAdditional(activity:MainActivity,features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity))=
        (EngineModules.kinds+if(ClientPrimarySelection.selected(activity)=="steer")EngineModules.packetCores else emptySet()).filter {UsageFeatures.engine(it,features)}
    /** Shared module inventory/actions: no duplicate engine management implementation. */
    fun engineView(activity:MainActivity,primaryHost:EngineUpdatePrimaryUi.Host?=null,
        features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity),query:String="",
        beforeInstall:()->Unit={}):LinearLayout=with(activity) {
        val rows=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
                rows.tag="engine-table"
                rows.addView(EngineUpdatePrimaryUi.view(this@with,primaryHost))
                val installedSizes=engines.mapNotNull {EngineStorage.size(this@with,it)}
                val mainBytes=EngineStorage.mainApplicationBytes(this@with)
                rows.addView(text(buildString {
                    if(mainBytes!=null)append("MCRF с данными: ${EngineStorage.format(this@with,mainBytes)}\n")
                    append("Движки отдельно: ${EngineStorage.format(this@with,installedSizes.distinctBy{it.packageId}.sumOf{it.bytes})}")
                },12,Loft.MUTED),space(4,12))
                val headings=LinearLayout(this).apply {setPadding(dp(8),dp(10),dp(8),dp(10));setBackgroundColor(Loft.FIELD)}
                headings.addView(text("Движок",11,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1f));headings.addView(text("Состояние",11,Loft.MUTED),LinearLayout.LayoutParams(dp(80),-2));headings.addView(android.view.View(this),LinearLayout.LayoutParams(dp(48),1));rows.addView(headings)
                visibleAdditional(this@with,features).filter {it.contains(query)||it=="tgws"&&"telegram".contains(query)}.forEach {kind->
                    val available=EnginePackages.missing(this@with,setOf(kind)).isEmpty()
                    val label=when(kind){S3BypassProfile.KIND->"S3 Bypass";WgAddonPolicy.KIND->"WG / AmneziaWG для Steer";"sing-box-lx"->"sing-box-lx";"tgws"->"Telegram WS";"olcrtc"->"OLC RTC";"openflux"->"OpenFlux";"warpscout"->"WARP Scanner";else->kind.uppercase(Locale.ROOT)}
                    val size=if(kind in engines)EngineStorage.size(this@with,kind)else null
                    val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(12),dp(8),dp(12))}
                    row.addView(LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(text(label,14,Loft.INK));addView(text(when{size==null&&kind in engines->"Отдельная загрузка";size==null->"В приложении";size.packageId==EngineModules.LEGACY->"Старый общий пакет";else->"${EngineStorage.format(this@with,size.bytes)} на устройстве"},10,Loft.MUTED),space(4,0))
                        if(size!=null&&size.packageId!=EngineModules.LEGACY)addView(text("APK: ${EngineStorage.format(this@with,size.apk)}",10,Loft.MUTED),space(3,0))
                        if(kind in EngineModules.optionalCores)addView(text(if(kind=="xray")"Отдельное ядро VLESS / REALITY / XHTTP / MUX"else"Отдельное ядро TLS: uTLS и фрагментация ClientHello",10,Loft.MUTED),space(3,0))
                    },LinearLayout.LayoutParams(0,-2,1f))
                    val tone=if(available){if(Loft.dark)0xff79dba5.toInt()else 0xff18733f.toInt()}else{if(Loft.dark)0xffe5be6c.toInt()else 0xff84580a.toInt()}
                    row.addView(text(if(available)"Скачан" else "Доступен",11,tone).apply {gravity=Gravity.CENTER_VERTICAL;contentDescription="$label · ${if(available)"Скачан"else"Доступен для установки"}"},LinearLayout.LayoutParams(dp(80),-2))
                    if(kind in engines){
                        val separate=size?.packageId==EngineModules.packageName(kind)
                        row.addView(iconButton(if(separate)"trash"else"download",if(separate)"Удалить $label"else"Скачать отдельный $label").apply {tag="engine-action-$kind";setOnClickListener {if(separate)EngineStorage.remove(this@with,kind)else{beforeInstall();installEngine(kind)}}},LinearLayout.LayoutParams(dp(48),dp(48)))
                    }else row.addView(android.view.View(this),LinearLayout.LayoutParams(dp(48),1))
                    rows.addView(row);divider(rows,0,0)
                }
                rows.addView(text("В системной карточке MCRF учтено только основное приложение. Движки — отдельные приложения Android. «На устройстве» — занятые блоки APK и библиотек, без данных и системного кэша; «APK» — размер файла для загрузки.",11,Loft.MUTED),space(12,0))

        rows
    }
    fun show(activity:MainActivity,initialEngines:Boolean=false,primaryHost:EngineUpdatePrimaryUi.Host?=null,features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity)):AlertDialog=with(activity) {
        val catalogue=assets.open("protocol-catalogue.json").use {JSONArray(String(ImportIO.read(it),Charsets.UTF_8))}
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(6),dp(16),dp(8))}
        val tabs=LinearLayout(this);val protocolTab=button("Протоколы",false).apply {tag="protocol-tab"};val engineTab=button("Движки",false).apply {tag="engine-tab"}
        tabs.addView(protocolTab,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(4)});tabs.addView(engineTab,LinearLayout.LayoutParams(0,dp(48),1f).apply {leftMargin=dp(4)})
        val search=editor("Поиск протокола или движка").apply {setSingleLine(true)}
        val rows=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="protocol-rows"}
        var engineMode=initialEngines
        var window:AlertDialog?=null
        fun render(){
            rows.removeAllViews();val query=search.text.toString().trim().lowercase(Locale.ROOT)
            protocolTab.setTextColor(if(!engineMode)Loft.ACCENT else Loft.MUTED);engineTab.setTextColor(if(engineMode)Loft.ACCENT else Loft.MUTED)
            if(engineMode){
                rows.tag="engine-table"
                rows.addView(engineView(this@with,primaryHost,features,query){window?.dismiss()})
            } else {
                rows.tag="protocol-rows"
                rows.addView(text("Основные протоколы работают через выбранный отдельно установленный движок. Дополнительные транспорты загружаются отдельно.",11,Loft.MUTED),space(8,12))
                repeat(catalogue.length()){index->val item=catalogue.getJSONObject(index);val name=item.getString("name");val transport=item.getString("transport")
                    if(UsageFeatures.protocol(name,item.getString("engine"),features)&&(query.isEmpty()||"$name $transport".lowercase(Locale.ROOT).contains(query))){
                        val extra=item.getString("engine")!="sing-box-lx"&&name!="Telegram WS"
                        val row=LinearLayout(this@with).apply {tag="protocol-row-$index";gravity=Gravity.TOP;setPadding(dp(2),dp(12),dp(2),dp(12))}
                        val label=LinearLayout(this@with).apply {orientation=LinearLayout.VERTICAL;addView(text(name,14,Loft.INK));addView(text(if(extra||name=="Telegram WS")"ДОП. ПАКЕТ" else"ОСНОВНОЙ ДВИЖОК",8,if(extra)Loft.MUTED else Loft.ACCENT),space(5,0))}
                        row.addView(label,LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(12)});row.addView(text(transport,12,Loft.MUTED),LinearLayout.LayoutParams(0,-2,1.2f));rows.addView(row);divider(rows,0,0)
                    }
                }
            }
        }
        protocolTab.setOnClickListener {engineMode=false;render()};engineTab.setOnClickListener {engineMode=true;render()}
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=render();override fun afterTextChanged(s:android.text.Editable?)=Unit})
        body.addView(tabs);body.addView(search,space(10,10));body.addView(ScrollView(this).apply {isVerticalScrollBarEnabled=false;addView(rows)},LinearLayout.LayoutParams(-1,dp((resources.configuration.screenHeightDp-340).coerceIn(48,400))));render()
        window=ThemedDialogBuilder(this).setTitle("Протоколы и движки").setView(body).setPositiveButton("Готово",null).show()
        val receiver=object:android.content.BroadcastReceiver(){override fun onReceive(context:android.content.Context?,intent:android.content.Intent?){if(intent?.data?.schemeSpecificPart?.startsWith(EngineModules.LEGACY)==true)render()}}
        val filter=android.content.IntentFilter().apply {addAction(android.content.Intent.ACTION_PACKAGE_ADDED);addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);addDataScheme("package")}
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,android.content.Context.RECEIVER_NOT_EXPORTED)else registerReceiver(receiver,filter)
        window!!.setOnDismissListener {try{unregisterReceiver(receiver)}catch(_:Exception){}}
        window!!
    }
}
