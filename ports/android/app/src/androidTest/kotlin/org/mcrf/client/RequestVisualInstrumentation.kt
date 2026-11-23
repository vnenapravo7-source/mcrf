package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator UI only. Opens editors; never starts traffic or installs/deletes packages. */
class RequestVisualInstrumentation:Instrumentation() {
    private var theme="dark"
    private var allowPhone=false
    private var serverOrbitOnly=false
    private var displayFontOnly=false
    private var anubisLayout=false
    private var pinnedAudit=false
    private var indicatorOnly=false
    private var logAppearance=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);theme=arguments?.getString("theme")?:"dark";allowPhone=arguments?.getString("allowPhoneUi")=="true";serverOrbitOnly=arguments?.getString("serverOrbit")=="true";displayFontOnly=arguments?.getString("displayFont")=="true";pinnedAudit=arguments?.getString("pinnedAudit")=="true";anubisLayout=arguments?.getString("anubisLayout")=="true";indicatorOnly=arguments?.getString("indicator")=="true";logAppearance=arguments?.getString("logAppearance")=="true";start()}
    override fun onStart() {
        val report=Bundle();var activity:MainActivity?=null
        val prefs=targetContext.getSharedPreferences("MainActivity",0)
        val previous=prefs.getString("theme",null)
        val previousThemeMode=prefs.getString(HomeUiTheme.MODE_KEY,null)
        val previousWallpaper=prefs.getString(PatternWallpaper.KEY,null)
        val previousStyle=prefs.getString(ConnectionAppearance.STYLE,null)
        val compactPresent=prefs.contains(ConnectionAppearance.COMPACT)
        val previousCompact=prefs.getBoolean(ConnectionAppearance.COMPACT,true)
        val previousPosition=prefs.getString(BottomNavigationStyle.POSITION,null)
        val previousNavStyle=prefs.getString(BottomNavigationStyle.KEY,null)
        val previousFeatures=prefs.getString(UsageFeatures.KEY,null)
        val askedPresent=prefs.contains(UsageFeatures.ASKED)
        val previousAsked=prefs.getBoolean(UsageFeatures.ASKED,false)
        val migrationKeys=listOf(ConnectionAppearance.MERGED,ConnectionAppearance.MERGED_DEFAULT_APPLIED)
        val previousMigration=prefs.all.filterKeys {it in migrationKeys}
        fun main(action:()->Unit) {val error=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(e:Throwable){error.set(e)}};error.get()?.let {throw it}}
        fun deferStartupPrompt():Boolean {
            var dismissed=false
            main {
                fun later(view:android.view.View):android.view.View? {
                    if(view is android.widget.Button&&view.text.toString()=="Позже")return view
                    if(view is android.view.ViewGroup)repeat(view.childCount){later(view.getChildAt(it))?.let {found->return found}}
                    return null
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::later)?.let {it.performClick();dismissed=true}
            }
            return dismissed
        }
        fun capture(name:String) {
            Thread.sleep(600)
            // Catalogue replies can arrive after the first screen was opened.
            // Dismiss only the real startup "Later" prompt, never a test dialog.
            if(deferStartupPrompt())Thread.sleep(700)
            val root=File(targetContext.getExternalFilesDir(null),"request-visual/${BuildConfig.VERSION_CODE}");check(root.isDirectory||root.mkdirs())
            val bitmap=requireNotNull(uiAutomation.takeScreenshot())
            File(root,"$theme-$name.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        }
        fun dismiss(){sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(200)}
        try {
            check(BuildConfig.DEBUG&&(android.os.Build.MODEL.contains("sdk",true)||allowPhone)){"Explicit phone UI opt-in required"}
            check(theme in setOf("dark","light")&&!McrfVpnService.hasActiveSession())
            check(prefs.edit().putString("theme",theme).putString(HomeUiTheme.MODE_KEY,theme).putString(ConnectionAppearance.STYLE,ConnectionAppearance.TOGGLE).putBoolean(ConnectionAppearance.COMPACT,true).commit())
            if(serverOrbitOnly)prefs.edit().putBoolean(ConnectionAppearance.MERGED,false)
                .remove(ConnectionAppearance.MERGED_DEFAULT_APPLIED).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host=activity!!
            val deadline=System.nanoTime()+15_000_000_000L
            while(host.working()&&System.nanoTime()<deadline)Thread.sleep(50)
            check(!host.working())
            deferStartupPrompt()
            if(logAppearance){
                ClientEvents.record(host,"Система","Обычное событие.")
                ClientEvents.record(host,"Shizuku","Shizuku подключён. Управление приложениями доступно.")
                ClientEvents.record(host,"Anubis","Предупреждение Anubis.")
                ClientEvents.record(host,"Anubis","Ошибка заморозки приложения.")
                main {host.navigate("Логи")}
                val deadline=android.os.SystemClock.uptimeMillis()+7000
                var checked=false
                do {
                    waitForIdleSync()
                    main {
                        val decor=host.window.decorView
                        val anubis=decor.findViewWithTag<android.widget.TextView>("log-kind-ANUBIS")
                        val success=decor.findViewWithTag<android.widget.TextView>("log-level-SUCCESS")
                        val warning=decor.findViewWithTag<android.widget.TextView>("log-level-WARNING")
                        val error=decor.findViewWithTag<android.widget.TextView>("log-level-ERROR")
                        val info=decor.findViewWithTag<android.widget.TextView>("log-level-INFO")
                        checked=anubis?.background!=null&&success?.background!=null&&
                            warning?.background!=null&&error?.background!=null&&info!=null&&info.background==null
                    }
                    if(!checked)android.os.SystemClock.sleep(120)
                }while(!checked&&android.os.SystemClock.uptimeMillis()<deadline)
                check(checked) {"Log heading tones or fills are missing"}
                capture("log-headings")
                main {
                    val picker=host.window.decorView.findViewWithTag<android.widget.Spinner>("logs-filter")
                    picker.setSelection(LogEntries.Group.ANUBIS.ordinal)
                }
                waitForIdleSync()
                capture("log-anubis")
                report.putString("logs","severity colors and Anubis filter")
                finish(Activity.RESULT_OK,report)
                return
            }
            if(indicatorOnly){
                val prior=prefs.getString(ConnectionIndicator.KEY,null)
                fun view(tag:String)=android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .firstNotNullOfOrNull {it.findViewWithTag<android.view.View>(tag)}
                try {
                    main {
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        requireNotNull(view("appearance-connection-indicator")).performClick()
                    }
                    waitForIdleSync()
                    main {
                        ConnectionIndicator.entries.forEach { check(view("indicator-option-${it.key}") != null) }
                        check(ConnectionIndicator.parse(null) == ConnectionIndicator.ORB_CONNECTING)
                        for (retired in listOf("spiral", "beacon", "orbWorking")) {
                            check(view("indicator-option-$retired") == null)
                            prefs.edit().putString(ConnectionIndicator.KEY, retired).apply()
                            check(ConnectionIndicator.selected(host) == ConnectionIndicator.ORB_CONNECTING)
                            check(prefs.getString(ConnectionIndicator.KEY, null) == ConnectionIndicator.ORB_CONNECTING.key)
                        }
                    }
                    capture("indicator-options")
                    main {
                        requireNotNull(view("indicator-option-none")).performClick()
                        host.navigate("Главная")
                        check(prefs.getString(ConnectionIndicator.KEY,null)=="none")
                        check(view("connection-status-indicator")==null)
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        requireNotNull(view("appearance-connection-indicator")).performClick()
                    }
                    waitForIdleSync()
                    main {
                        requireNotNull(view("indicator-option-gyro")).performClick()
                        host.navigate("Главная")
                        check(prefs.getString(ConnectionIndicator.KEY,null)=="gyro")
                        check(view("connection-status-indicator")?.visibility==android.view.View.GONE)
                        val busy=MainActivity::class.java.getDeclaredField("busy").apply {isAccessible=true}
                        val refresh=MainActivity::class.java.getDeclaredMethod("updateHomeControl").apply {isAccessible=true}
                        busy.setBoolean(host,true)
                        try {
                            refresh.invoke(host)
                            check(view("connection-status-indicator")?.visibility==android.view.View.VISIBLE)
                            val badge=requireNotNull(view("connection-status-indicator"))
                            val row=badge.parent as android.widget.LinearLayout
                            check(row.indexOfChild(badge)==0&&row.getChildAt(1) is ConnectionCaptionView)
                        }finally {
                            busy.setBoolean(host,false)
                            refresh.invoke(host)
                        }
                    }
                    report.putString("indicator","connecting default, retired choices migrated, saved off or gyro, gyro shown with pending status")
                    finish(Activity.RESULT_OK,report)
                    return
                } finally {
                    prefs.edit().apply {if(prior==null)remove(ConnectionIndicator.KEY)else putString(ConnectionIndicator.KEY,prior)}.commit()
                }
            }
            if(anubisLayout){
                val mode=ExperienceModeStore(host)
                val prior=mode.load()
                val pinnedBefore=prefs.contains(EdgePanelAppearance.PINNED)
                val previousPinned=prefs.getBoolean(EdgePanelAppearance.PINNED,false)
                val previousUsage=prefs.getString(UsageFeatures.KEY,null)
                fun view(tag:String)=host.window.decorView.findViewWithTag<android.view.View>(tag)
                try {
                    main {
                        mode.save(ExperienceMode.EXPERT)
                        host.navigate(ClientSettingsNavigation.ROUTES)
                        requireNotNull(view("routing-anubis")).performClick()
                    }
                    if(AnubisLaunchObserver.enabled(host)&&!ManagedAppFreezeUi.providerChosen(host))dismiss()
                    capture("anubis-rules")
                    main {
                        check(view("anubis-settings")!=null)
                        check(view("anubis-group-only_vpn")!=null)
                        check(view("anubis-freeze")==null&&view("anubis-network-check")==null)
                        check((view("anubis-accessibility-setup")!=null)==!AnubisLaunchObserver.enabled(host))
                        requireNotNull(view("anubis-group-only_vpn")).performClick()
                    }
                    capture("anubis-app-picker")
                    dismiss()
                    main {
                        prefs.edit().putBoolean(EdgePanelAppearance.PINNED,true)
                            .putString(UsageFeatures.KEY,UsageFeatures.encode(UsageFeatures.Feature.entries.toSet())).commit()
                        host.render()
                    }
                    waitForIdleSync()
                    var oldScroll=0
                    main {
                        val edge=requireNotNull(view("edge-pinned-scroll")) as android.widget.ScrollView
                        edge.scrollTo(0,host.dp(160))
                        oldScroll=edge.scrollY
                        check(oldScroll>0)
                        requireNotNull(view("edge-panel-settings")).performClick()
                    }
                    waitForIdleSync()
                    main {
                        val edge=requireNotNull(view("edge-pinned-scroll")) as android.widget.ScrollView
                        check(edge.scrollY==oldScroll) {"Sidebar scrolled from $oldScroll to ${edge.scrollY}"}
                        mode.save(ExperienceMode.SIMPLE)
                        host.render()
                        host.navigate(ClientSettingsNavigation.ANUBIS)
                        check(view("anubis-settings")!=null)
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        check(view("open-layout-constructor")==null)
                    }
                    report.putString("anubis","Routes entry, grouped rules, app picker, and preserved sidebar scroll")
                    finish(Activity.RESULT_OK,report)
                    return
                }finally {main {
                    mode.save(prior)
                    prefs.edit().apply {
                        if(pinnedBefore)putBoolean(EdgePanelAppearance.PINNED,previousPinned)else remove(EdgePanelAppearance.PINNED)
                        if(previousUsage!=null)putString(UsageFeatures.KEY,previousUsage)else remove(UsageFeatures.KEY)
                    }.commit()
                }}
            }
            if(pinnedAudit){
                val keys=listOf(HomeUiTheme.MODE_KEY,ConnectionAppearance.SERVER_ORBIT,HomeUiTheme.GRADIENT_KEY,EdgePanelAppearance.PINNED,
                    ConnectionAppearance.MERGED,UsageFeatures.KEY,"edgeLeft",AppTypography.INTERFACE,AppTypography.DISPLAY)
                val previousValues=prefs.all.filterKeys {it in keys}
                val mode=ExperienceModeStore(host)
                val previousMode=mode.load()
                val pages=listOf("Главная","Белые списки","Маршрутизация","Расписание","Логи","WARP","Настройки",
                    "Оформление","Beta-функции","Автодеплой","Подключение и работа в фоне","VPN и прокси",
                    "Настройки DNS","Аккаунт и звонки VK","Обновления и движки","Расширенные настройки","Диагностика подключения","Минимализм")
                try {
                    main {
                        mode.save(ExperienceMode.EXPERT)
                        prefs.edit().putString(HomeUiTheme.MODE_KEY,HomeUiTheme.Mode.AMOLED_BACKGROUND.key)
                            .putBoolean(EdgePanelAppearance.PINNED,true).putBoolean(ConnectionAppearance.MERGED,false)
                            .putString(AppTypography.INTERFACE,"Jura")
                            .putString(UsageFeatures.KEY,UsageFeatures.encode(UsageFeatures.Feature.entries.toSet())).commit()
                        host.applyUserThemeColors()
                    }
                    for(left in listOf(true,false)){
                        main {prefs.edit().putBoolean("edgeLeft",left).commit();host.render()}
                        for((index,page) in pages.withIndex()){
                            main {host.navigate(page)}
                            waitForIdleSync()
                            main {
                                val root=host.window.decorView
                                val screen=requireNotNull(root.findViewWithTag<android.view.View>("screen-$page")) {page}
                                val edge=requireNotNull(root.findViewWithTag<android.view.View>("connection-edge"))
                                val contentBounds=android.graphics.Rect()
                                val edgeBounds=android.graphics.Rect()
                                check(screen.getGlobalVisibleRect(contentBounds)&&edge.getGlobalVisibleRect(edgeBounds)) {page}
                                check(if(left)edgeBounds.right<=contentBounds.left else contentBounds.right<=edgeBounds.left) {page}
                                fun validate(view:android.view.View){
                                    if(view.visibility!=android.view.View.VISIBLE)return
                                    if(view is android.widget.TextView&&view.isAccessibilityHeading&&view.text.isNotBlank()){
                                        val layout=view.layout
                                        check(view.width>0&&layout!=null&&layout.lineCount>0) {"Clipped heading on $page: ${view.text}"}
                                    }
                                    if(view is android.view.ViewGroup)repeat(view.childCount){validate(view.getChildAt(it))}
                                }
                                validate(screen)
                                if(page==ClientSettingsNavigation.APPEARANCE){
                                    check(screen.findViewWithTag<android.view.View>("random-appearance-now")==null)
                                    check(HomeUiTheme.amoledGradient)
                                }
                                if(page==ClientSettingsNavigation.UPDATES&&host.contentWidthDp()<370){
                                    check(requireNotNull(screen.findViewWithTag<android.widget.LinearLayout>("primary-core-summary"))
                                        .orientation==android.widget.LinearLayout.VERTICAL)
                                }
                            }
                            if(left||index in listOf(0,2,4,6,7))capture("pinned-${if(left)"left"else"right"}-${index.toString().padStart(2,'0')}")
                        }
                    }
                    val matrix=AppTypography.choices(AppTypography.Scope.INTERFACE).keys.map {it to AppTypography.DEFAULT}+
                        AppTypography.displayChoices.keys.filter {it!=AppTypography.DEFAULT}.map {AppTypography.DEFAULT to it}
                    val typographyPages=listOf("Главная","Маршрутизация","Настройки","Оформление")
                    for((body,accent) in matrix){
                        main {
                            prefs.edit().putString(AppTypography.INTERFACE,body).putString(AppTypography.DISPLAY,accent).commit()
                            host.applyUserThemeColors()
                        }
                        for(page in typographyPages){
                            main {host.navigate(page)}
                            waitForIdleSync()
                            main {
                                val screen=requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("screen-$page"))
                                check(screen.isShown) {"$page with $body/$accent"}
                                if(page=="Маршрутизация"){
                                    for(tag in listOf("routes-section-rules","routes-section-schedule")){
                                        val label=requireNotNull(screen.findViewWithTag<android.widget.TextView>(tag))
                                        val layout=requireNotNull(label.layout)
                                        check(layout.lineCount==1&&layout.getLineEnd(0)==label.text.length&&layout.getEllipsisCount(0)==0&&
                                            layout.getLineWidth(0)<=label.width-label.totalPaddingLeft-label.totalPaddingRight+1f) {
                                            "Clipped ${label.text} with $body/$accent"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    report.putString("pinnedAudit","${pages.size} screens, both sides; ${matrix.size} font choices across ${typographyPages.size} screens")
                    finish(Activity.RESULT_OK,report)
                    return
                }finally {
                    main {
                        mode.save(previousMode)
                        prefs.edit().apply {
                            keys.forEach(::remove)
                            previousValues.forEach { (key,value)->when(value){is String->putString(key,value);is Boolean->putBoolean(key,value)} }
                        }.commit()
                        host.applyUserThemeColors()
                    }
                }
            }
            if(displayFontOnly){
                check(android.os.Build.MODEL.contains("sdk",true))
                val previousFont=prefs.getString(AppTypography.DISPLAY,null)
                val previousInterface=prefs.getString(AppTypography.INTERFACE,null)
                val imports=AppTypography.Scope.entries.associateWith {scope->
                    File(targetContext.noBackupFilesDir,scope.file).takeIf(File::isFile)?.readBytes()
                }
                fun fontView(tag:String)=android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .firstNotNullOfOrNull {it.findViewWithTag<android.widget.TextView>(tag)}
                try {
                    main {
                        prefs.edit().putString(AppTypography.DISPLAY,"").putString(AppTypography.INTERFACE,"").commit()
                        host.applyUserThemeColors()
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                    }
                    waitForIdleSync()
                    var initialSample:android.graphics.Typeface?=null
                    var initialBody:android.graphics.Typeface?=null
                    main {initialSample=fontView("display-font-sample")?.typeface;initialBody=fontView("appearance-display-font")?.typeface}
                    for((index,key) in AppTypography.displayChoices.keys.withIndex().filter {it.value.isNotBlank()}){
                        main {
                            val anchor=requireNotNull(fontView("appearance-display-font"))
                            val sample=requireNotNull(fontView("display-font-sample"))
                            anchor.requestRectangleOnScreen(android.graphics.Rect(0,0,anchor.width,anchor.height),true)
                        }
                        waitForIdleSync()
                        main {check(requireNotNull(fontView("appearance-display-font")).performClick())}
                        if(index==1)capture("display-font-options")
                        main {check(requireNotNull(fontView("dropdown-option-$index")).performClick())}
                        waitForIdleSync()
                        main {
                            check(prefs.getString(AppTypography.DISPLAY,null)==key)
                            val sample=requireNotNull(fontView("display-font-sample"))
                            check(sample.typeface!=initialSample)
                            check(requireNotNull(fontView("appearance-display-font")).typeface==initialBody)
                            val test=android.widget.LinearLayout(host)
                            val status=ConnectionCaptionView(host)
                            val ping=android.widget.TextView(host).apply {tag="server-ping"}
                            val title=android.widget.TextView(host).apply {isAccessibilityHeading=true}
                            listOf(status,ping,title).forEach {test.addView(it)}
                            AppTypography.applyTree(test)
                            check(listOf(status,ping,title).all {it.typeface==sample.typeface})
                        }
                        if(key=="Jura")capture("display-font-jura")
                    }
                    main {
                        host.navigate("Главная")
                        host.applyUserThemeColors()
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        check(requireNotNull(fontView("appearance-display-font")).text.toString().startsWith("Balsamiq Sans"))
                    }
                    for(key in AppTypography.choices(AppTypography.Scope.INTERFACE).keys.filter {it.isNotBlank()}){
                        main {
                            val field=requireNotNull(fontView("appearance-interface-font"))
                            field.requestRectangleOnScreen(android.graphics.Rect(0,0,field.width,field.height),true)
                        }
                        waitForIdleSync()
                        main {check(requireNotNull(fontView("appearance-interface-font")).performClick())}
                        val index=AppTypography.choices(AppTypography.Scope.INTERFACE).keys.indexOf(key)
                        main {check(requireNotNull(fontView("dropdown-option-$index")).performClick())}
                        waitForIdleSync()
                        main {
                            check(AppTypography.selected(host,AppTypography.Scope.INTERFACE)==key)
                            check(requireNotNull(fontView("appearance-interface-font")).typeface!=initialBody)
                        }
                    }
                    for((scope,name) in AppTypography.Scope.entries.zip(listOf("Jura","Tektur"))){
                        val fixture=File.createTempFile("test-font-",".ttf",targetContext.cacheDir)
                        try{
                            targetContext.assets.open("fonts/$name.ttf").use {source->fixture.outputStream().use {source.copyTo(it)}}
                            AppTypography.importFont(targetContext,scope,AppTypography.CUSTOM,android.net.Uri.fromFile(fixture))
                        }finally {fixture.delete()}
                    }
                    main {
                        prefs.edit().putString(AppTypography.INTERFACE,AppTypography.CUSTOM)
                            .putString(AppTypography.DISPLAY,AppTypography.CUSTOM).commit()
                        host.applyUserThemeColors()
                        check(requireNotNull(fontView("appearance-display-font")).typeface!=
                            requireNotNull(fontView("display-font-sample")).typeface)
                        for(scope in AppTypography.Scope.entries)check(AppTypography.selected(host,scope)==AppTypography.CUSTOM)
                    }
                    main {
                        val editor=requireNotNull(RouteRuleUi(host,object:RouteRuleUi.Session {
                            override val scope=RouteRuleUi.Scope.LIVE
                            override fun snapshot()=RouteRules.defaults()
                            override fun revision()="fixture"
                            override fun generation()="fixture"
                            override fun editable(rule:org.json.JSONObject)=rule
                            override fun commit(revision:String,generation:String,edit:(org.json.JSONObject)->Unit,saved:()->Unit,failed:(Exception)->Unit) =
                                error("Fixture must never save a rule")
                        }).edit(null))
                        try{
                            val apps=requireNotNull(editor.window?.decorView?.findViewWithTag<android.widget.Button>("route-apps"))
                            check(apps.performClick())
                            check(apps.text.toString()=="Загружаем\nприложения…"&&apps.maxLines==2)
                        }finally{editor.dismiss()}
                    }
                    report.putString("displayFont","all accent families, independent imports, accent scopes, persisted selection")
                    finish(Activity.RESULT_OK,report)
                    return
                }finally {
                    main {
                        imports.forEach { (scope,previous)->
                            val file=File(targetContext.noBackupFilesDir,scope.file)
                            if(previous==null)file.delete()else file.writeBytes(previous)
                        }
                        prefs.edit().putString(AppTypography.DISPLAY,previousFont)
                            .putString(AppTypography.INTERFACE,previousInterface).commit()
                        host.applyUserThemeColors()
                    }
                }
            }
            if(serverOrbitOnly){
                check(android.os.Build.MODEL.contains("sdk",true))
                check(host.mergedConnections()&&prefs.getBoolean(ConnectionAppearance.MERGED_DEFAULT_APPLIED,false))
                val reality=org.json.JSONObject().put("tls",org.json.JSONObject()
                    .put("reality",org.json.JSONObject().put("enabled",true)))
                val latvia=ServerImport.Server("owned-fixture","Латвия VLESS TCP Reality","vless","192.0.2.70",443,reality,null,null)
                check(ServerDisplay.name(latvia)=="Латвия"&&ServerDisplay.protocol(latvia)=="VLESS · Reality")
                val custom=ServerImport.Server("owned-custom-name","Латвия Reality","socks5","192.0.2.71",1080,null,null,null)
                check(ServerDisplay.name(custom)=="Латвия Reality")
                val keys=listOf(HomeUiTheme.MODE_KEY,ConnectionAppearance.SERVER_ORBIT,ConnectionAppearance.MERGED,EdgePanelAppearance.HIDDEN,
                    EdgePanelAppearance.PINNED,ConnectionControlsDock.HIDDEN,BottomNavigationStyle.POSITION,UsageFeatures.KEY,
                    BsAutoSelectionPolicy.KEY,BsAutoSelectionPolicy.LAST_MODE,AppTypography.INTERFACE,UiConstructor.KEY,
                    "edgeLeft","selected","automatic","tun")
                val prior=prefs.all.filterKeys {it in keys}
                val snapshots=Connections::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host.catalogue) as CatalogueSnapshots
                val old=snapshots.readable()
                val fixture=CatalogueSnapshot(ConnectionCatalogue("").apply {
                    listOf("Франкфурт","Амстердам","Хельсинки","Париж","Стокгольм","Варшава","Вена").forEachIndexed {index,name->
                        add("socks5://192.0.2.${index+21}:1080#$name")
                    }
                    val bs="csqtt://connect?v=2&host=192.0.2.50&peer=46000&password=fixture-only&hashes=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    add(bs)
                    replaceSubscription("https://fixture.invalid/sub",ServerImport.read("socks5://192.0.2.44:1080#Подписка"))
                    recordExitCountry(entries().first {it.name=="Париж"},"FR",System.currentTimeMillis())
                })
                try {
                    main {
                        prefs.edit().remove(ConnectionAppearance.SERVER_ORBIT).remove(ConnectionAppearance.MERGED)
                            .putBoolean(EdgePanelAppearance.HIDDEN,true).putBoolean(ConnectionControlsDock.HIDDEN,true)
                            .putBoolean(EdgePanelAppearance.PINNED,false)
                            .putBoolean("tun",true)
                            .putBoolean("edgeLeft",false)
                            .putString(BottomNavigationStyle.POSITION,BottomNavigationStyle.TOP)
                            .putString(UsageFeatures.KEY,UsageFeatures.encode(UsageFeatures.Feature.entries.toSet())).commit()
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        check(host.mergedConnections())
                        prefs.edit().putString(HomeUiTheme.MODE_KEY,HomeUiTheme.Mode.AMOLED.key).commit()
                        host.applyUserThemeColors()
                        snapshots.replace {fixture};host.navigate("Главная")
                    }
                    Thread.sleep(450)
                    main {
                        snapshots.replace {fixture};host.render()
                        host.select(fixture.entries[3].id)
                        host.render()
                        check(HomeUiTheme.amoled&&Loft.dark)
                        check(host.window.decorView.findViewWithTag<android.view.View>("orbit-connection-status")!=null)
                        check(host.window.decorView.findViewWithTag<android.view.View>("connection-primary-toggle")==null)
                        check(host.window.decorView.findViewWithTag<android.view.View>("connection-status-progress")==null)
                        check(host.window.decorView.findViewWithTag<android.view.View>("home-connection-target")==null)
                        check(host.window.decorView.findViewWithTag<android.view.View>("server-management-toggle")==null)
                        check(host.window.decorView.findViewWithTag<BottomNavigationView>("top-section-navigation").atTop)
                        check(host.window.decorView.findViewWithTag<android.view.View>("connection-edge").isShown)
                        check(host.window.decorView.findViewWithTag<android.view.View>("edge-whitelists").visibility==android.view.View.GONE)
                        check(host.window.decorView.findViewWithTag<android.view.View>("edge-hide").visibility==android.view.View.VISIBLE)
                        requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("edge-hide")).performClick()
                        check(host.window.decorView.findViewWithTag<android.view.View>("edge-restore").visibility==android.view.View.VISIBLE)
                        requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("edge-restore")).performClick()
                    }
                    waitForIdleSync()
                    val point=IntArray(2)
                    var initialScroll=0
                    fun orbitView(tag:String)=android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .firstNotNullOfOrNull {it.findViewWithTag<android.view.View>(tag)}
                    fun swipe(x:Int,y:Int,dx:Float,dy:Float,hold:Boolean=false) {
                        val down=android.os.SystemClock.uptimeMillis()
                        for(step in 0..8){
                            val action=when(step){0->android.view.MotionEvent.ACTION_DOWN;8->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE}
                            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x+dx*step/8,y+dy*step/8,0)
                            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                            try {
                                if(hold)check(uiAutomation.injectInputEvent(event,true)) else sendPointerSync(event)
                            }finally {event.recycle()}
                            Thread.sleep(if(step==0&&hold)android.view.ViewConfiguration.getLongPressTimeout()+200L else 25L)
                        }
                    }
                    main {
                        val track=requireNotNull(orbitView("server-orbit-track"))
                        val bounds=android.graphics.Rect()
                        check(track.getGlobalVisibleRect(bounds)&&bounds.height()>host.dp(240))
                        check((orbitView("orbit-connection-status")?.height?:Int.MAX_VALUE)<host.dp(34))
                        val screen=requireNotNull(orbitView("screen-Главная"))
                        check(screen.paddingRight==0)
                        val viewportBounds=android.graphics.Rect()
                        val labelBounds=android.graphics.Rect()
                        check(screen.getGlobalVisibleRect(viewportBounds))
                        check(requireNotNull(orbitView("server-orbit-group-label")).getGlobalVisibleRect(labelBounds))
                        check(kotlin.math.abs(viewportBounds.bottom-bounds.bottom)<=host.dp(2))
                        check(viewportBounds.bottom-labelBounds.bottom in 0..host.dp(14))
                        val location=IntArray(2)
                        track.getLocationOnScreen(location)
                        point[0]=location[0]+track.width/2
                        point[1]=location[1]+host.dp(210)
                        initialScroll=track.scrollY
                        check(orbitView("server-orbit-actions")==null)
                        check(orbitView("server-orbit-group-label")!!.isShown)
                        check(orbitView("server-orbit-marker-${fixture.entries.single {it.whitelists}.id}")!=null)
                        check(orbitView("server-orbit-marker-${fixture.entries.single {it.name=="Подписка"}.id}")!=null)
                    }
                    capture("server-orbit")
                    swipe(point[0],point[1],0f,-240f)
                    Thread.sleep(650)
                    main {
                        val track=requireNotNull(orbitView("server-orbit-track")) as android.widget.ScrollView
                        check(track.scrollY>initialScroll) {"Orbit did not scroll: ${track.scrollY} from $initialScroll, content ${track.getChildAt(0).height}, viewport ${track.height}"}
                        requireNotNull(orbitView("server-orbit-marker-${fixture.entries[4].id}")).performLongClick()
                    }
                    capture("server-orbit-details")
                    main {
                        val overlay=requireNotNull(orbitView("server-orbit-dismiss-area"))
                        val track=requireNotNull(orbitView("server-orbit-track"))
                        check(overlay.height==track.height&&overlay.background==null)
                        val details=requireNotNull(orbitView("server-orbit-details"))
                        val card=IntArray(2).also(details::getLocationOnScreen)
                        val orbitBounds=IntArray(2).also(track::getLocationOnScreen)
                        val edge=requireNotNull(orbitView("connection-edge"))
                        val edgeLocation=IntArray(2).also(edge::getLocationOnScreen)
                        check(card[0]>=orbitBounds[0]&&card[0]+details.width<=edgeLocation[0])
                        check(overlay.performClick()&&orbitView("server-orbit-details")==null)
                        requireNotNull(orbitView("server-orbit-marker-${fixture.entries[4].id}")).performLongClick()
                    }
                    Thread.sleep(350)
                    main {
                        val confirm=orbitView("server-orbit-confirm")
                        val entry=fixture.entries[4]
                        requireNotNull(confirm).performClick()
                        check(host.selected==entry.id)
                        val bs=fixture.entries.single {it.whitelists}
                        requireNotNull(orbitView("server-orbit-marker-${bs.id}")).performLongClick()
                    }
                    Thread.sleep(450)
                    main {
                        val bs=fixture.entries.single {it.whitelists}
                        val address=orbitView("orbit-address") as android.widget.TextView
                        check(address.text=="192.0.2.50")
                        val updated=CatalogueSnapshot(ConnectionCatalogue(fixture.catalogue.serialize()).apply {
                            recordProbe(bs.id,42,System.currentTimeMillis())
                        })
                        snapshots.replace {updated};host.pageRefresh?.invoke()
                        check((orbitView("orbit-ping-${bs.id}") as android.widget.TextView).text=="42 мс")
                        check((orbitView("orbit-details-ping") as android.widget.TextView).text=="42 мс")
                        requireNotNull(orbitView("server-orbit-confirm")).performClick()
                        check(host.selected==bs.id&&host.connectionSectionWhitelists())
                        check((orbitView("server-orbit-group-label") as android.widget.TextView).text=="БС")
                    }
                    capture("server-orbit-bs")
                    main {
                        requireNotNull(orbitView("edge-open-controls")).performClick()
                    }
                    waitForIdleSync()
                    val popupStart=IntArray(2)
                    main {
                        requireNotNull(orbitView("connection-controls-popup-grip")).getLocationOnScreen(popupStart)
                        check(orbitView("connection-controls-block")?.isShown==true)
                    }
                    swipe(popupStart[0]+host.dp(45),popupStart[1]+host.dp(20),0f,-host.dp(70).toFloat())
                    capture("server-orbit-controls")
                    main {
                        val after=IntArray(2)
                        requireNotNull(orbitView("connection-controls-popup-grip")).getLocationOnScreen(after)
                        check(after[1]<popupStart[1]-host.dp(20)) {"Popup did not move"}
                        requireNotNull(orbitView("connection-controls-popup-close")).performClick()
                    }
                    main {
                        check(orbitView("edge-auto-bs-interval")?.isShown==true)
                        val auto=requireNotNull(orbitView("edge-auto-selection")) as android.widget.Switch
                        val previous=auto.isChecked
                        auto.performClick()
                        check(auto.isChecked!=previous)
                        auto.performClick()
                        check(auto.isChecked==previous)
                        requireNotNull(orbitView("edge-auto-selection-settings")).performClick()
                    }
                    waitForIdleSync()
                    main {
                        val settings=requireNotNull(orbitView("edge-auto-selection-settings-popup"))
                        for(tag in listOf("auto-selection-interval","auto-bs-selection-policy","auto-tolerance"))
                            check(settings.findViewWithTag<android.view.View>(tag)!=null)
                        settings.findViewWithTag<android.view.View>("auto-bs-selection-policy").performClick()
                    }
                    waitForIdleSync()
                    main {
                        requireNotNull(orbitView("dropdown-option-2")).performClick()
                        check(BsAutoSelectionPolicy.selected(prefs)==BsAutoSelectionPolicy.RECENT)
                    }
                    capture("server-orbit-auto")
                    main {
                        val settings=requireNotNull(orbitView("edge-auto-selection-settings-popup"))
                        val policy=settings.findViewWithTag<android.widget.TextView>("auto-bs-selection-policy")
                        check(policy.text=="Последний выбранный")
                        check((0 until policy.layout.lineCount).all {policy.layout.getEllipsisCount(it)==0})
                    }
                    dismiss()
                    main {
                        prefs.edit().putBoolean(EdgePanelAppearance.PINNED,true).commit()
                        host.render()
                        val screen=requireNotNull(orbitView("screen-Главная"))
                        check(screen.paddingRight==0)
                        check(orbitView("connection-edge")?.isShown==true)
                        check(orbitView("edge-open-controls")?.visibility==android.view.View.GONE)
                        for(tag in listOf("edge-vpn-proxy-mode","edge-action-clipboard","edge-action-ping","edge-action-refresh","edge-action-vk"))
                            check(orbitView(tag)!=null)
                        check(orbitView("edge-action-qr")==null)
                        check(orbitView("edge-action-clipboard")?.isLongClickable==true)
                    }
                    waitForIdleSync()
                    val edge=IntArray(2)
                    main {requireNotNull(orbitView("connection-edge")).getLocationOnScreen(edge)}
                    swipe(edge[0]+host.dp(32),edge[1]+host.dp(36),-host.dp(140).toFloat(),0f)
                    waitForIdleSync()
                    main {
                        check(prefs.getBoolean("edgeLeft",false))
                        val left=requireNotNull(orbitView("screen-Главная"))
                        check(left.paddingLeft==0&&left.paddingRight==0)
                    }
                    capture("server-orbit-pinned")
                    main {
                        val mode=requireNotNull(orbitView("edge-vpn-proxy-mode")) as ModeSwitch
                        val vpn=requireNotNull(mode.findViewWithTag<android.view.View>("mode-rendered:VPN"))
                        val proxy=requireNotNull(mode.findViewWithTag<android.view.View>("mode-rendered:Прокси"))
                        check(vpn.height>=host.dp(48)&&proxy.height>=host.dp(48))
                        check(proxy.performClick())
                        check(!prefs.getBoolean("tun",true))
                        check(!vpn.isSelected&&proxy.isSelected)
                    }
                    capture("server-orbit-pinned-proxy")
                    main {
                        val mode=requireNotNull(orbitView("edge-vpn-proxy-mode"))
                        check(requireNotNull(mode.findViewWithTag<android.view.View>("mode-rendered:VPN")).performClick())
                        check(prefs.getBoolean("tun",false))
                    }
                    main {requireNotNull(orbitView("edge-panel-settings")).performClick()}
                    waitForIdleSync()
                    main {
                        check(orbitView("edge-appearance-settings")?.isShown==true)
                        prefs.edit().putString(AppTypography.INTERFACE,"Jura").commit()
                        host.applyUserThemeColors()
                        check(AppTypography.active!=null)
                        prefs.edit().putString(AppTypography.INTERFACE,"").commit()
                        host.applyUserThemeColors()
                        check(AppTypography.active==null)
                        host.navigate(ClientSettingsNavigation.ROOT)
                    }
                    for(page in listOf("Маршрутизация","Логи",ClientSettingsNavigation.ROOT)) {
                        main {host.navigate(page)}
                        waitForIdleSync()
                        main {
                            val content=requireNotNull(orbitView("screen-$page"))
                            val sidebar=requireNotNull(orbitView("connection-edge"))
                            val leftBounds=android.graphics.Rect()
                            val rightBounds=android.graphics.Rect()
                            check(content.getGlobalVisibleRect(rightBounds)&&sidebar.getGlobalVisibleRect(leftBounds))
                            check(leftBounds.right<=rightBounds.left)
                        }
                    }
                    main {
                        prefs.edit().remove(UiConstructor.KEY).commit()
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        requireNotNull(orbitView("open-layout-constructor")).performClick()
                        check(orbitView("constructor-header")?.isShown==true)
                        requireNotNull(orbitView("tab:Маршрутизация")).performClick()
                        check(orbitView("constructor-header")?.isShown==true)
                    }
                    waitForIdleSync()
                    capture("constructor")
                    main {host.navigate("Настройки")}
                    waitForIdleSync()
                    val settingsDrag=IntArray(4)
                    main {
                        val beta=requireNotNull(orbitView("settings-page-${ClientSettingsNavigation.BETA}"))
                        val appearance=requireNotNull(orbitView("settings-page-${ClientSettingsNavigation.APPEARANCE}"))
                        val grip=(beta.parent as android.view.View).findViewWithTag<android.view.View>("constructor-grip")
                        val start=IntArray(2);val end=IntArray(2)
                        grip.getLocationOnScreen(start);appearance.getLocationOnScreen(end)
                        settingsDrag[0]=start[0]+grip.width/2;settingsDrag[1]=start[1]+grip.height/2
                        settingsDrag[2]=end[0]+appearance.width/2;settingsDrag[3]=end[1]+appearance.height/2
                    }
                    swipe(settingsDrag[0],settingsDrag[1],(settingsDrag[2]-settingsDrag[0]).toFloat(),
                        (settingsDrag[3]-settingsDrag[1]).toFloat(),hold=true)
                    waitForIdleSync()
                    main {
                        val beta=requireNotNull(orbitView("settings-page-${ClientSettingsNavigation.BETA}"))
                        val appearance=requireNotNull(orbitView("settings-page-${ClientSettingsNavigation.APPEARANCE}"))
                        check((beta.parent as android.view.View).top>(appearance.parent as android.view.View).top)
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        val section=requireNotNull(orbitView("appearance-settings"))
                        (section.parent as android.view.View).findViewWithTag<android.view.View>("constructor-grip").performClick()
                    }
                    waitForIdleSync()
                    main {
                        val dialog=android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
                            it.findViewById<android.widget.Button>(android.R.id.button3)?.takeIf {button->button.text=="Править содержимое"}
                        }
                        check(requireNotNull(dialog).performClick())
                    }
                    waitForIdleSync()
                    main {
                        val connection=requireNotNull(orbitView("appearance-connection"))
                        check((connection.parent as android.view.View).tag=="constructor-item")
                        check(connection.findViewWithTag<android.view.View>("appearance-connection-indicator")!=null)
                        host.navigate(ClientSettingsNavigation.DEPLOY)
                    }
                    val deadline=android.os.SystemClock.uptimeMillis()+8000
                    var deployRows:List<android.view.View> = emptyList()
                    do {
                        waitForIdleSync()
                        main {
                            val content=orbitView("androidDeploy.destination.home.") as? android.view.ViewGroup
                            deployRows=if(content==null)emptyList() else
                                (0 until content.childCount).map(content::getChildAt).filter {it.tag=="constructor-item"}
                        }
                        if(deployRows.size<2)android.os.SystemClock.sleep(100)
                    }while(deployRows.size<2&&android.os.SystemClock.uptimeMillis()<deadline)
                    check(deployRows.size>=2) {"Deployment items loaded without constructor grips"}
                    val deployDrag=IntArray(4)
                    main {
                        val first=deployRows[0];val second=deployRows[1]
                        val grip=first.findViewWithTag<android.view.View>("constructor-grip")
                        val start=IntArray(2);val end=IntArray(2)
                        grip.getLocationOnScreen(start);second.getLocationOnScreen(end)
                        deployDrag[0]=start[0]+grip.width/2;deployDrag[1]=start[1]+grip.height/2
                        deployDrag[2]=end[0]+second.width/2;deployDrag[3]=end[1]+second.height/2
                    }
                    swipe(deployDrag[0],deployDrag[1],(deployDrag[2]-deployDrag[0]).toFloat(),
                        (deployDrag[3]-deployDrag[1]).toFloat(),hold=true)
                    waitForIdleSync()
                    main {
                        check(deployRows[0].top>deployRows[1].top) {"Deployment reordering reset its view"}
                        host.navigate("Маршрутизация")
                    }
                    waitForIdleSync()
                    fun tabGrip(id:String):android.view.View=
                        (requireNotNull(orbitView("tab:$id")).parent as android.view.View)
                            .findViewWithTag("constructor-grip")
                    fun confirmEdit(){
                        val done=android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
                            it.findViewById<android.widget.Button>(android.R.id.button1)?.takeIf {button->button.text=="Готово"}
                        }
                        check(requireNotNull(done).performClick())
                    }
                    val start=IntArray(2)
                    val destination=IntArray(2)
                    main {
                        val grip=tabGrip("Маршрутизация")
                        grip.getLocationOnScreen(start)
                        start[0]+=grip.width/2;start[1]+=grip.height/2
                        val target=requireNotNull(orbitView("tab:Логи"))
                        target.getLocationOnScreen(destination)
                        destination[0]+=target.width/2
                    }
                    swipe(start[0],start[1],(destination[0]-start[0]).toFloat(),0f,hold=true)
                    waitForIdleSync()
                    main {
                        val routes=requireNotNull(orbitView("tab:Маршрутизация")).parent as android.view.View
                        val logs=requireNotNull(orbitView("tab:Логи")).parent as android.view.View
                        check(routes.left>logs.left) {"Tab drag did not change the draft order"}
                        tabGrip("Маршрутизация").performClick()
                    }
                    waitForIdleSync()
                    main {
                        (orbitView("constructor-name") as android.widget.EditText).setText("Мои маршруты")
                        confirmEdit()
                    }
                    waitForIdleSync()
                    main {
                        check((orbitView("tab:Маршрутизация") as android.widget.TextView).text=="Мои маршруты")
                        requireNotNull(orbitView("constructor-cancel")).performClick()
                        check(!prefs.contains(UiConstructor.KEY))
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        requireNotNull(orbitView("open-layout-constructor")).performClick()
                        tabGrip("Логи").performClick()
                    }
                    waitForIdleSync()
                    main {
                        (orbitView("constructor-hide") as android.widget.Switch).isChecked=true
                        confirmEdit()
                    }
                    waitForIdleSync()
                    main {
                        requireNotNull(orbitView("constructor-save")).performClick()
                        check(prefs.getString(UiConstructor.KEY,"").orEmpty().contains("\"hidden\":true"))
                        check(orbitView("nav-Логи")==null)
                        host.render()
                        check(orbitView("nav-Логи")==null)
                    }
                    main {
                        host.navigate(ClientSettingsNavigation.APPEARANCE)
                        requireNotNull(orbitView("appearance-theme")).performClick()
                    }
                    waitForIdleSync()
                    main {
                        requireNotNull(orbitView("dropdown-option-0")).performClick()
                        check(HomeUiTheme.mode(prefs)==HomeUiTheme.Mode.DARK)
                        snapshots.replace {fixture};host.navigate("Главная")
                        check(host.window.decorView.findViewWithTag<android.view.View>("server-orbit-inline")==null)
                        check(!HomeUiTheme.amoled)
                        check(fixture.entries.all {host.window.decorView.findViewWithTag<ServerRow>("server-row-${it.id}")!=null})
                    }
                }finally{main {
                    snapshots.replace {old}
                    prefs.edit().apply {
                        keys.forEach(::remove)
                        prior.forEach { (key,value)-> when(value) {is String->putString(key,value);is Boolean->putBoolean(key,value)} }
                    }.commit()
                }}
                report.putString("serverOrbit","appearance toggle, selection, restore classic layout")
                finish(Activity.RESULT_OK,report)
                return
            }
            capture("home")
            for(pattern in listOf("it-01","cat-1")) {
                main {check(prefs.edit().putString(PatternWallpaper.KEY,pattern).commit());UserThemeColorsUi.configure(host);host.render()}
                capture("wallpaper-$pattern")
            }
            main {prefs.edit().apply {if(previousWallpaper==null)remove(PatternWallpaper.KEY)else putString(PatternWallpaper.KEY,previousWallpaper)}.commit();UserThemeColorsUi.configure(host);host.render()}
            main {
                val bar=requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("connection-status-progress"))
                check(!bar.isClickable&&!bar.isFocusable&&bar.background==null)
                val toggle=requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("connection-primary-toggle"))
                check(toggle.parent===bar.parent)
                check((bar.parent as android.widget.LinearLayout).orientation==android.widget.LinearLayout.HORIZONTAL)
                check(kotlin.math.abs((toggle.top+toggle.bottom)-(bar.top+bar.bottom))<=2)
                val row=bar.parent as android.view.View
                val caption=requireNotNull(host.window.decorView.findViewWithTag<android.widget.TextView>("connection-progress-caption"))
                check(caption.parent===row.parent&&caption.top>=row.bottom)
                check(caption.width==row.width)
                val title=requireNotNull(host.window.decorView.findViewWithTag<android.widget.TextView>("auto-selection-title"))
                check(title.text=="Автовыбор"&&title.paint.measureText(title.text.toString())<=title.width-title.paddingLeft-title.paddingRight+1)
                val detection=requireNotNull(host.window.decorView.findViewWithTag<android.widget.LinearLayout>("auto-bs-control"))
                val detectionTitle=detection.getChildAt(0) as android.widget.TextView
                check(detectionTitle.text.toString().replace('\n',' ')=="Автоопределение БС")
                check(detectionTitle.layout.lineCount<=detectionTitle.maxLines)
                check(host.window.decorView.findViewWithTag<android.view.View>("home-connection-status").visibility==android.view.View.GONE)
            }
            check(ConnectionAppearance.CLASSIC !in ConnectionAppearance.choices)
            fun connectedLayoutFixture(style:String) {
                if(!android.os.Build.MODEL.contains("sdk",true))return
                val snapshots=Connections::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host.catalogue) as CatalogueSnapshots
                val previousCatalogue=snapshots.readable()
                val previousStatus=McrfVpnService.status;val previousId=McrfVpnService.selectedId
                val fixture=CatalogueSnapshot(ConnectionCatalogue("").apply {add("socks5://192.0.2.123:1080#Owned-long-fixture-server-name-for-layout-only")})
                val entry=fixture.entries.single()
                fun refresh(){MainActivity::class.java.getDeclaredMethod("updateHomeControl").apply {isAccessible=true}.invoke(host)}
                try {
                    main {snapshots.replace {fixture};McrfVpnService.status="VPN подключён";McrfVpnService.selectedId=entry.id;refresh()}
                    capture("fixture-connected-layout-$style")
                    main {
                        if(style==ConnectionAppearance.TOGGLE) {
                            val label=host.window.decorView.findViewWithTag<android.widget.TextView>("connection-progress-caption")
                            check(label.layout.lineCount<=2&&label.text.toString().contains("192.0.2.123")){"IP lost in compact caption"}
                        }else if(prefs.getBoolean(ConnectionAppearance.COMPACT,true)) {
                            val name=host.window.decorView.findViewWithTag<android.widget.TextView>("home-connection-target")
                            val ip=host.window.decorView.findViewWithTag<android.widget.TextView>("home-connection-ip")
                            check(name.isShown&&name.layout.lineCount==1&&ip.isShown&&ip.layout.lineCount==1&&ip.text.toString()=="192.0.2.123")
                            val status=host.window.decorView.findViewWithTag<android.widget.TextView>("home-connection-status")
                            check(listOf(status,name,ip).map {it.paddingLeft}.distinct().size==1)
                        }else {
                            val status=host.window.decorView.findViewWithTag<android.widget.TextView>("home-connection-status")
                            val hero=host.window.decorView.findViewWithTag<android.view.View>("connection-primary")
                            check(status.layout.lineCount<=2&&status.text.toString().contains("192.0.2.123"))
                            val h=IntArray(2);val s=IntArray(2);hero.getLocationOnScreen(h);status.getLocationOnScreen(s)
                            check(s[1]>=h[1]+hero.height)
                            check(kotlin.math.abs(hero.height-host.dp(ConnectionAppearance.EXPANDED_SCENE_DP))<=1)
                        }
                    }
                }finally {main {snapshots.replace {previousCatalogue};McrfVpnService.status=previousStatus;McrfVpnService.selectedId=previousId;refresh()}}
            }
            for(style in ConnectionAppearance.choices.keys) {
                main {check(prefs.edit().putString(ConnectionAppearance.STYLE,style).commit());host.render()}
                if(style!=ConnectionAppearance.TOGGLE) {
                    val rendererDeadline=System.nanoTime()+5_000_000_000L
                    var loaded=false
                    while(!loaded&&System.nanoTime()<rendererDeadline) {
                        main {loaded=(host.window.decorView.findViewWithTag<android.view.View>("connection-primary") as ForgeConnectionButton).scene.isRendererLoaded}
                        if(!loaded)Thread.sleep(40)
                    }
                    check(loaded){"Scene failed to load: $style"}
                }
                capture("compact-$style")
                main {
                    if(style!=ConnectionAppearance.TOGGLE) {
                        val hero=requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("connection-primary"))
                        check((hero as ForgeConnectionButton).scene.rendererError==null){"Scene renderer failed: $style"}
                        val status=requireNotNull(host.window.decorView.findViewWithTag<android.widget.TextView>("home-connection-status"))
                        val auto=requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("unified-auto-selection"))
                        val h=IntArray(2);val s=IntArray(2);val a=IntArray(2)
                        hero.getLocationOnScreen(h);status.getLocationOnScreen(s);auto.getLocationOnScreen(a)
                        check(hero.height<=host.dp(160)&&status.textSize/host.resources.displayMetrics.scaledDensity<=12.1f)
                        check(status.visibility==android.view.View.VISIBLE&&s[0]>=h[0]+hero.width&&s[1]>=h[1]&&a[1]>=h[1]+hero.height)
                    }
                }
            }
            for(style in ConnectionAppearance.choices.keys) {
                main {check(prefs.edit().putString(ConnectionAppearance.STYLE,style).commit());host.render()}
                Thread.sleep(600);connectedLayoutFixture(style)
            }
            for(style in ConnectionAppearance.choices.keys.filter {it!=ConnectionAppearance.TOGGLE}) {
                main {check(prefs.edit().putBoolean(ConnectionAppearance.COMPACT,false).putString(ConnectionAppearance.STYLE,style).commit());host.render()}
                Thread.sleep(800);connectedLayoutFixture("expanded-$style")
            }
            main {check(prefs.edit().putBoolean(ConnectionAppearance.COMPACT,true).commit())}
            main {check(prefs.edit().putString(ConnectionAppearance.STYLE,ConnectionAppearance.TOGGLE).putString(BottomNavigationStyle.POSITION,BottomNavigationStyle.TOP).commit());host.render()}
            capture("navigation-top")
            for(navStyle in BottomNavigationStyle.choices.keys) {
                main {check(prefs.edit().putString(BottomNavigationStyle.KEY,navStyle).commit());host.render()}
                capture("navigation-top-$navStyle")
                main {check(host.window.decorView.findViewWithTag<BottomNavigationView>("top-section-navigation").atTop)}
            }
            main {
                val nav=requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("top-section-navigation"))
                check((nav.parent as android.view.ViewGroup).indexOfChild(nav)==0)
                host.navigate("Логи")
            }
            capture("navigation-top-logs")
            main {
                check(host.window.decorView.findViewWithTag<android.view.View>("top-section-navigation")!=null)
                host.navigate("Главная")
                check(prefs.edit().putString(BottomNavigationStyle.POSITION,BottomNavigationStyle.BOTTOM).commit());host.render()
            }
            main {
                val bar=requireNotNull(host.window.decorView.findViewWithTag<ConnectionStatusProgress>("connection-status-progress"))
                bar.state(ConnectionProgressState.Phase.FAILED);bar.performClick()
                check(host.window.decorView.findViewWithTag<android.view.View>("logs-filter")!=null)
            }
            capture("progress-error-logs")
            main {host.navigate("Главная")}
            var progress:ConnectionStatusProgress?=null
            var preview:android.app.AlertDialog?=null
            var clicks=0
            main {
                progress=ConnectionStatusProgress(host){clicks++}
                preview=ThemedDialogBuilder(host).setTitle("Состояние подключения").setView(progress).setNegativeButton("Закрыть",null).show()
            }
            main {progress!!.state(ConnectionProgressState.Phase.PREPARING)}
            var lastFill=0f
            repeat(24) {
                Thread.sleep(40)
                main {
                    val rail=ConnectionStatusProgress::class.java.getDeclaredField("rail").apply {isAccessible=true}.get(progress)
                    val fill=rail.javaClass.getDeclaredField("travel").apply {isAccessible=true}.getFloat(rail)
                    check(fill>=lastFill&&fill<=.2501f){"Progress cycled or regressed: $lastFill -> $fill"};lastFill=fill
                }
            }
            check(kotlin.math.abs(lastFill-.25f)<.001f)
            for(phase in ConnectionProgressState.Phase.entries) {
                main {
                    val bar=progress!!;bar.state(phase)
                    val failed=phase==ConnectionProgressState.Phase.FAILED
                    check(bar.contentDescription.toString().contains(phase.label)&&bar.background==null)
                    check(bar.isClickable==failed&&bar.isFocusable==failed)
                    val before=clicks;bar.performClick();check(clicks==before+if(failed)1 else 0)
                }
                capture("progress-${phase.name.lowercase()}")
            }
            main {check(clicks==1);preview!!.dismiss()}
            main {ClientBehaviorUi.showEnergy(host)};capture("energy");dismiss()
            main {host.window.decorView.findViewWithTag<android.view.View>("auto-bs-selection-policy").performClick()}
            capture("auto-selection-menu");dismiss()
            main {AutoExclusionsUi.show(host)};capture("auto-filter")
            main {
                fun headings(view:android.view.View):Int {
                    var count=if(view is android.widget.TextView&&view.text.toString() in setOf("Исключения","Исключения автовыбора"))1 else 0
                    if(view is android.view.ViewGroup)repeat(view.childCount){count+=headings(view.getChildAt(it))}
                    return count
                }
                check(android.view.inspector.WindowInspector.getGlobalWindowViews().sumOf(::headings)==1)
            };dismiss()
            main {host.resourceMonitor.showSettings()};capture("monitor")
            main {
                val footer=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOf {it.findViewWithTag<android.widget.LinearLayout>("resource-monitor-footer")}
                val close=footer.getChildAt(footer.childCount-1)
                check(footer.width-close.right>=host.dp(16)&&footer.height-close.bottom>=host.dp(16))
            };dismiss()
            for(style in ConnectionAppearance.choices.keys.filter {it!=ConnectionAppearance.TOGGLE}) {
                var scene:ConnectionScene?=null
                var dialog:android.app.AlertDialog?=null
                main {
                    scene=ConnectionScene(host)
                    val container=android.widget.FrameLayout(host).apply {addView(scene,android.widget.FrameLayout.LayoutParams(-1,host.dp(156)))}
                    dialog=ThemedDialogBuilder(host).setTitle("Анимация · ${ConnectionAppearance.choices[style]}").setView(container).setNegativeButton("Закрыть",null).show()
                }
                Thread.sleep(800)
                main {check(scene!!.isRendererLoaded);scene!!.present(style,ConnectionAppearance.MONOLITH,ConnectionMotion.Signal.CONNECTING,0.0,true,true)}
                capture("scene-connecting-$style")
                main {scene!!.present(style,ConnectionAppearance.LOCK,ConnectionMotion.Signal.CONNECTED,0.0,true,true)}
                capture("scene-connected-$style")
                main {check(scene!!.rendererError==null);dialog!!.dismiss();scene!!.dispose()}
            }
            main {
                host.navigate("Настройки")
                check(host.window.decorView.findViewWithTag<android.view.View>("change-history")!=null)
                check(host.window.decorView.findViewWithTag<android.view.View>("version-history")==null)
            }
            capture("settings-change-history")
            main {host.navigate(ClientSettingsNavigation.UPDATES);check(host.window.decorView.findViewWithTag<android.view.View>("version-history")!=null)}
            capture("updates-version-history")
            main {EngineUpdateUi.show(host)}
            waitForIdleSync()
            main {
                val action=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {it.findViewWithTag<android.widget.Button>("openflux-update-check")}
                check(action!=null&&action.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK==android.view.Gravity.CENTER_HORIZONTAL)
            }
            capture("openflux-centered-update");dismiss()
            main {host.navigate(ClientSettingsNavigation.APPEARANCE)}
            waitForIdleSync()
            main {
                val compact=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOf {it.findViewWithTag<android.widget.CheckBox>("connection-window-compact")}
                check(compact.isChecked);compact.isChecked=false;check(!prefs.getBoolean(ConnectionAppearance.COMPACT,true))
            }
            waitForIdleSync()
            capture("appearance-compact-option")
            main {
                val compact=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOf {it.findViewWithTag<android.widget.CheckBox>("connection-window-compact")}
                compact.isChecked=true;check(prefs.getBoolean(ConnectionAppearance.COMPACT,false))
            }
            var featuresBefore=""
            main {
                host.navigate(ClientSettingsNavigation.APPEARANCE)
                check(host.window.decorView.findViewWithTag<android.view.View>("usage-features-save")==null)
                featuresBefore=UsageFeatures.encode(UsageFeaturesUi.selected(host))
                val selected=UsageFeaturesUi.selected(host)
                val feature=UsageFeatures.Feature.values().firstOrNull {it !in selected}?:UsageFeatures.Feature.VPN
                val box=host.window.decorView.findViewWithTag<android.widget.CheckBox>("usage-feature-${feature.id}")
                box.isChecked=!box.isChecked
            }
            val saveDeadline=System.nanoTime()+10_000_000_000L
            while(host.working()&&System.nanoTime()<saveDeadline)Thread.sleep(30)
            main {
                check(!host.working()&&UsageFeatures.encode(UsageFeaturesUi.selected(host))!=featuresBefore)
                check(host.window.decorView.findViewWithTag<android.view.View>("usage-feature-vpn").isShown)
                check(host.window.decorView.findViewWithTag<android.view.View>("usage-features-save")==null)
                val form=host.window.decorView.findViewWithTag<android.view.View>("usage-features-settings")
                form.requestRectangleOnScreen(android.graphics.Rect(0,0,form.width,minOf(form.height,host.dp(360))),true)
            }
            capture("appearance-autosave")
            val history=startActivitySync(Intent(targetContext,VersionHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1200);capture("version-history");main {history.finish()}
            report.putString("result","PASS: six screens captured; no traffic tests started")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){report.putString("failure",e.javaClass.name+": "+e.message+" at "+e.stackTrace.firstOrNull());finish(Activity.RESULT_CANCELED,report)}
        finally {
            activity?.let {host->main {host.finish()}}
            prefs.edit().apply {
                if(previousThemeMode==null)remove(HomeUiTheme.MODE_KEY)else putString(HomeUiTheme.MODE_KEY,previousThemeMode)
                if(previous==null)remove("theme")else putString("theme",previous)
                if(previousWallpaper==null)remove(PatternWallpaper.KEY)else putString(PatternWallpaper.KEY,previousWallpaper)
                if(previousStyle==null)remove(ConnectionAppearance.STYLE)else putString(ConnectionAppearance.STYLE,previousStyle)
                if(compactPresent)putBoolean(ConnectionAppearance.COMPACT,previousCompact)else remove(ConnectionAppearance.COMPACT)
                if(previousPosition==null)remove(BottomNavigationStyle.POSITION)else putString(BottomNavigationStyle.POSITION,previousPosition)
                if(previousNavStyle==null)remove(BottomNavigationStyle.KEY)else putString(BottomNavigationStyle.KEY,previousNavStyle)
                if(previousFeatures==null)remove(UsageFeatures.KEY)else putString(UsageFeatures.KEY,previousFeatures)
                if(askedPresent)putBoolean(UsageFeatures.ASKED,previousAsked)else remove(UsageFeatures.ASKED)
                if(serverOrbitOnly)migrationKeys.forEach {key->
                    val value=previousMigration[key] as? Boolean
                    if(value==null)remove(key)else putBoolean(key,value)
                }
            }.commit()
        }
    }
}
