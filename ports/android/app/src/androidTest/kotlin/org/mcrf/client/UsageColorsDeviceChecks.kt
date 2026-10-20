package org.mcrf.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import java.util.concurrent.atomic.AtomicReference

/** Only fresh disposable UID. Never writes preferences of the human's MCRF package. */
object UsageColorsDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val context=test.targetContext
        check(context.packageName=="org.mcrf.client.onboardingfixture") {"Disposable onboarding package required"}
        check(context.getSharedPreferences("MainActivity",0).all.isEmpty()) {"Fresh fixture preferences required; never reset an existing installation"}
        fun onMain(block:()->Unit) {
            val failure=AtomicReference<Throwable?>()
            test.runOnMainSync {try{block()}catch(error:Throwable){failure.set(error)}}
            failure.get()?.let {throw it}
        }
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var usage:AlertDialog?=null
        val field=MainActivity::class.java.getDeclaredField("usageChooser").apply {isAccessible=true}
        val primary=MainActivity::class.java.getDeclaredField("primaryChooser").apply {isAccessible=true}
        try {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(SystemClock.elapsedRealtime()<deadline) {
                onMain {usage=field.get(activity) as? AlertDialog}
                if(usage?.isShowing==true)break
                SystemClock.sleep(50)
            }
            test.waitForIdleSync()
            onMain {
                val root=requireNotNull(usage).window!!.decorView
                val boxes=UsageFeatures.Feature.values().map {root.findViewWithTag<CheckBox>("usage-feature-${it.id}")}
                check(boxes.all {it!=null&&it.height>=activity.dp(48)})
                check(boxes.single {it.tag=="usage-feature-vpn"}.isChecked)
                check(primary.get(activity)==null) {"Primary chooser appeared before use selection"}
                root.findViewWithTag<View>("usage-features-save").performClick()
            }
            test.waitForIdleSync()
            onMain {(primary.get(activity) as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick()}
            check(activity.prefs.getString(UsageFeatures.KEY,null)=="vpn")
            onMain {activity.navigate(ClientSettingsNavigation.ROOT)};test.waitForIdleSync()
            val root=activity.window.decorView
            onMain {
                check(root.findViewWithTag<View>("usage-features-settings")==null)
                check(root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.MINIMALISM}")==null)
                root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.APPEARANCE}").performClick()
                root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.MINIMALISM}").performClick()
                check(root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.VK}")==null)
                root.findViewWithTag<CheckBox>("usage-feature-vpn").isChecked=false
                root.findViewWithTag<CheckBox>("usage-feature-proxy").isChecked=true
                root.findViewWithTag<View>("usage-features-save").performClick()
            };test.waitForIdleSync()
            onMain {activity.navigate(ClientSettingsNavigation.VPN)};test.waitForIdleSync()
            onMain {
                check(root.findViewWithTag<View>("setting.ipv6")==null)
                check(root.findViewWithTag<View>("setting.mixedPort")!=null)
                check(root.findViewWithTag<View>("copy-proxy-socks5")!=null)
                activity.navigate(ClientSettingsNavigation.APPEARANCE)
            };test.waitForIdleSync()
            val features=UsageFeatures.Feature.values()
            for(mask in 1..15){
                onMain {
                    activity.navigate(ClientSettingsNavigation.MINIMALISM)
                    features.forEachIndexed {index,feature->root.findViewWithTag<CheckBox>("usage-feature-${feature.id}").isChecked=(mask and (1 shl index))!=0}
                    root.findViewWithTag<View>("usage-features-save").performClick()
                };test.waitForIdleSync()
                onMain {
                    activity.navigate(ClientSettingsNavigation.ROOT)
                    check(root.findViewWithTag<View>("usage-features-settings")==null)
                    check(root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.MINIMALISM}")==null)
                    check((root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.VK}")!=null)==(mask and 8!=0))
                    check((root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.SHARING}")!=null)==(mask and 2!=0))
                    check((root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.TLS}")!=null)==(mask and 3!=0))
                    check(root.findViewWithTag<View>("nav-Автодеплой")==null)
                    check((root.findViewWithTag<View>("settings-page-Автодеплой")!=null)==(mask and 9!=0))
                    check((root.findViewWithTag<View>("nav-Белые списки")!=null)==(mask and 8!=0))
                    check((root.findViewWithTag<View>("settings-page-Капча VK")!=null)==(mask and 8!=0))
                    check(root.findViewWithTag<View>("nav-Маршрутизация")!=null)
                    check(root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.GEO}")!=null)
                    activity.navigate("Главная")
                    check((root.findViewWithTag<View>("home-mode-switch").visibility==View.VISIBLE)==(mask and 2!=0&&mask!=2))
                    check((root.findViewWithTag<View>("warp-open")!=null)==(mask and 4!=0))
                    check((root.findViewWithTag<View>("telegram-open")!=null)==(mask and 10!=0))
                    check((root.findViewWithTag<View>("edge-telegram").visibility==View.VISIBLE)==(mask and 10!=0))
                }
            }
            onMain {
                activity.navigate(ClientSettingsNavigation.MINIMALISM)
                features.forEach {feature->root.findViewWithTag<CheckBox>("usage-feature-${feature.id}").isChecked=feature==UsageFeatures.Feature.PROXY}
                root.findViewWithTag<View>("usage-features-save").performClick()
                activity.navigate(ClientSettingsNavigation.APPEARANCE)
            };test.waitForIdleSync()
            val sourceBefore=ProfileStore.snapshot(activity)
            var managementCheckpoint=sourceBefore
            onMain {
                val preferences=activity.prefs.all.toMap();val iconBefore=LauncherIcons.current(activity).id
                root.findViewWithTag<View>("random-appearance").performClick()
                check(activity.prefs.all==preferences&&LauncherIcons.current(activity).id==iconBefore) {"Random draft changed saved appearance before Apply"}
                UserThemeColors.keys.forEach {key->UserThemeColors.parse(root.findViewWithTag<UserThemeColorsUi.ColorButton>(key).value)}
                check(root.findViewWithTag<android.widget.TextView>("random-appearance-summary").text.isNotBlank())
                var previous=UserThemeColors.keys.map {root.findViewWithTag<UserThemeColorsUi.ColorButton>(it).value}
                repeat(12){
                    root.findViewWithTag<View>("random-appearance").performClick()
                    val next=UserThemeColors.keys.map {root.findViewWithTag<UserThemeColorsUi.ColorButton>(it).value}
                    check(next!=previous&&AppearancePresets.all.any {it.colors==next}) {"Random theme repeated or lost its curated day/night pair"}
                    previous=next
                }
                check(activity.prefs.all==preferences&&LauncherIcons.current(activity).id==iconBefore)
                val compact=UserThemeColorsUi.view(activity)
                compact.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(288),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                compact.layout(0,0,compact.measuredWidth,compact.measuredHeight)
                val random=compact.findViewWithTag<android.widget.Button>("random-appearance")
                val lines=requireNotNull(random.layout)
                check(lines.lineCount<=2&&(0 until lines.lineCount).all {lines.getEllipsisCount(it)==0}) {"Random label is clipped at compact phone width"}
                check(lines.getLineEnd(lines.lineCount-1)==random.text.length)
                activity.navigate(ClientSettingsNavigation.APPEARANCE)
            };test.waitForIdleSync()
            onMain {
                val before=activity.prefs.all.toMap()
                val fingerprints=mutableSetOf<Int>()
                val image=android.graphics.Bitmap.createBitmap(320,640,android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    check(PatternWallpaper.choices.size==26)
                    PatternWallpaper.choices.keys.filter {it!="none"}.forEach {id->
                        PatternWallpaper.configure(activity,id);image.eraseColor(android.graphics.Color.TRANSPARENT)
                        PatternWallpaper.draw(android.graphics.Canvas(image),android.graphics.Rect(0,0,320,640),1f)
                        val pixels=IntArray(320*640);image.getPixels(pixels,0,320,0,0,320,640)
                        check(pixels.any {it!=0});fingerprints.add(pixels.contentHashCode())
                    }
                    check(fingerprints.size==25) {"Wallpaper variants must render distinct SVG motifs"}
                }finally{image.recycle();PatternWallpaper.configure(activity,activity.prefs.getString(PatternWallpaper.KEY,"none").orEmpty())}
                check(activity.prefs.all==before)
            }
            var chosen:String?=null
            val pickerPreferences=activity.prefs.all.toMap()
            onMain {
                val picker=UserColorPicker.show(activity,"#009944"){chosen=it}
                val pickerRoot=picker.window!!.decorView
                check(pickerRoot.findViewWithTag<View>("color-swatch-#009944")!=null)
                check(pickerRoot.findViewWithTag<View>("color-swatch-#FFFF00")!=null)
                val green=pickerRoot.findViewWithTag<View>("color-swatch-#009944")
                check((green.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor==UserThemeColors.parse("#009944")) {"Dialog theming replaced a palette swatch"}
                pickerRoot.findViewWithTag<View>("color-swatch-#FFFF00").performClick()
                picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            };test.waitForIdleSync()
            onMain {
                check(chosen==null&&activity.prefs.all==pickerPreferences) {"Cancelling picker changed preferences"}
                val accepted=UserColorPicker.show(activity,"#009944"){chosen=it}
                accepted.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            };test.waitForIdleSync()
            var popup:AlertDialog?=null
            onMain {
                check(chosen=="#009944") {"Picker returned unexpected color: $chosen"}
                check(activity.prefs.all==pickerPreferences) {"Picker saved preferences before Apply"}
                val menu=ThemedActionMenu(activity,root)
                menu.menu.add(0,0,0,"Enabled fixture action")
                menu.menu.add(0,1,1,"Disabled fixture action").isEnabled=false
                popup=menu.show()
            };test.waitForIdleSync()
            onMain {
                val themed=requireNotNull(popup)
                try {
                    check(themed.listView.adapter.isEnabled(0)&&!themed.listView.adapter.isEnabled(1))
                    val background=themed.window!!.decorView.background
                    val fill=if(background is android.graphics.drawable.InsetDrawable)background.drawable else background
                    check((fill as android.graphics.drawable.GradientDrawable).color!!.defaultColor==Loft.dialogSurface())
                }finally{themed.dismiss()}
            }
            onMain {
                UserThemeColors.keys.forEachIndexed {index,key->root.findViewWithTag<UserThemeColorsUi.ColorButton>(key).value=if(index%2==0)"#009944"else"#FFFF00"}
                root.findViewWithTag<View>("theme-colors-save-bottom").performClick()
            };test.waitForIdleSync()
            check(UserThemeColors.keys.all {activity.prefs.getString(it,null) in setOf("#009944","#FFFF00")})
            onMain {
                check(root.findViewWithTag<View>("theme-colors-preview")==null)
                if(Loft.dark)root.findViewWithTag<View>("theme-action").performClick()
                check(!Loft.dark)
                check(Loft.SURFACE==HomeUiTheme.palette(false).surface)
                check(Loft.ACCENT==HomeUiTheme.palette(false).accent)
                val save=root.findViewWithTag<View>("theme-colors-save")
                val fill=(save.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
                check(fill.colors!!.last()==Loft.translucent(Loft.SURFACE)) {"Light theme button did not receive the saved palette"}
                check(android.graphics.Color.alpha(fill.colors!!.last())==218)
            };test.waitForIdleSync()
            check(ProfileStore.snapshot(activity)==sourceBefore) {"Color apply changed stored profiles/settings"}
            onMain {root.findViewWithTag<View>("theme-colors-reset").performClick()};test.waitForIdleSync()
            check(UserThemeColors.keys.none {activity.prefs.contains(it)})
            check(activity.prefs.getString(UsageFeatures.KEY,null)=="proxy")
            check(ClientPrimarySelection.selected(activity)==null)
            val launcherIntent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            try {
                LauncherIcons.choices.forEach {choice->
                    onMain {LauncherIcons.select(activity,choice.id)}
                    check(LauncherIcons.current(activity).id==choice.id)
                    val enabled=context.packageManager.queryIntentActivities(launcherIntent,0)
                    check(enabled.size==1&&enabled.single().activityInfo.name==choice.alias) {"Expected exactly one selected launcher alias"}
                }
            }finally{onMain {LauncherIcons.select(activity,0)}}
            check(ProfileStore.snapshot(activity)==sourceBefore)
            // Construct only: no permission grant, posting, WebView, account or provider request.
            val challengeId=java.util.UUID.randomUUID().toString()
            val notification=CarrierChallengeNotifications.build(activity,challengeId)
            check(notification.visibility==android.app.Notification.VISIBILITY_PRIVATE)
            check(notification.timeoutAfter==150000L)
            check(notification.contentIntent.creatorPackage==context.packageName)
            if(android.os.Build.VERSION.SDK_INT>=31)check(notification.contentIntent.isImmutable)
            check(!notification.extras.toString().contains("https://"))
            notification.contentIntent.cancel()
            val cancelledChallenge=java.util.concurrent.atomic.AtomicBoolean(false)
            val pendingChallenge=CarrierChallenge.register("fixture-cancel","https://api.vk.ru/captcha") {value->cancelledChallenge.set(value=="error:cancelled")}
            val cancelNotification=CarrierChallengeNotifications.build(activity,pendingChallenge)
            check(cancelNotification.actions.size==2)
            check(cancelNotification.actions[0].title.toString()=="Открыть проверку")
            check(cancelNotification.actions[0].actionIntent==cancelNotification.contentIntent)
            check(cancelNotification.category==android.app.Notification.CATEGORY_STATUS)
            cancelNotification.actions.single {it.title.toString()=="Отменить проверку"}.actionIntent.send()
            val cancelDeadline=SystemClock.elapsedRealtime()+3000
            while(!cancelledChallenge.get()&&SystemClock.elapsedRealtime()<cancelDeadline)SystemClock.sleep(25)
            check(cancelledChallenge.get()&&CarrierChallenge.get(pendingChallenge)==null)
            cancelNotification.contentIntent.cancel()
            cancelNotification.actions.forEach {it.actionIntent.cancel()}
            val shortChallenge=requireNotNull(CarrierChallenge.registerBounded("fixture-short","https://api.vk.ru/captcha",{},timeoutMillis=9000))
            try {
                val shortNotification=CarrierChallengeNotifications.build(activity,shortChallenge)
                check(shortNotification.timeoutAfter in 1L..9000L) {"Notification outlives the native captcha request"}
                check(!shortNotification.extras.toString().contains("https://"))
                shortNotification.contentIntent.cancel()
                shortNotification.actions.forEach {it.actionIntent.cancel()}
            }finally{CarrierChallenge.remove(shortChallenge)}
            onMain {
                UserThemeColors.keys.forEachIndexed {index,key->root.findViewWithTag<UserThemeColorsUi.ColorButton>(key).value=if(index%2==0)"#009944"else"#FFFF00"}
                root.findViewWithTag<View>("theme-colors-save").performClick()
                LauncherIcons.select(activity,11)
                activity.finish()
            };test.waitForIdleSync()
            val restarted=test.startActivitySync(Intent().setClassName(context.packageName,LauncherIcons.choices.single {it.id==11}.alias).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                val loading=MainActivity::class.java.getDeclaredField("loading").apply {isAccessible=true}
                val loadedDeadline=SystemClock.elapsedRealtime()+10000
                var loaded=false
                while(!loaded&&SystemClock.elapsedRealtime()<loadedDeadline){onMain {loaded=!loading.getBoolean(restarted)};if(!loaded)SystemClock.sleep(25)}
                check(loaded)
                test.waitForIdleSync()
                onMain {
                    check(field.get(restarted)==null) {"Use chooser repeated after restart"}
                    (primary.get(restarted) as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick()
                    check(restarted.prefs.getString(UsageFeatures.KEY,null)=="proxy")
                    check(LauncherIcons.current(restarted).id==11)
                    restarted.navigate(ClientSettingsNavigation.APPEARANCE)
                    UserThemeColors.keys.forEachIndexed {index,key->check(restarted.window.decorView.findViewWithTag<UserThemeColorsUi.ColorButton>(key).value==if(index%2==0)"#009944"else"#FFFF00")}
                    check(ProfileStore.snapshot(restarted)==sourceBefore)
                    val managementRecord=org.json.JSONObject().put("username","fixture").put("password","fixture-password")
                        .put("panel_url","https://192.0.2.10:8443/").put("certificate_sha256","a".repeat(64))
                        .put("transport_template",org.json.JSONObject().put("host","192.0.2.10").put("user","root").put("sshPort","22").put("panelPort","8443").put("domain",""))
                    val deployStore=AndroidDeployStore(restarted)
                    val fixtureRequest=ServerManagementRequest.build("swg",ServerManagementRequest.Action.ADD_CLIENT,SwgServerManagementPlan.implementedActions,org.json.JSONObject().put("name","Fixture"),true)
                    val otherRequest=ServerManagementRequest.build("swg",ServerManagementRequest.Action.DISABLE_CLIENT,SwgServerManagementPlan.implementedActions,org.json.JSONObject().put("client_id","other"),true)
                    val endpoint="root@192.0.2.10:22|https://192.0.2.10:8443/|${"a".repeat(64)}"
                    var history=ServerManagementJournal.begin(org.json.JSONObject(),fixtureRequest,endpoint,100)
                    history=ServerManagementJournal.finish(history,fixtureRequest.getString("request_id"),true,101)
                    history=ServerManagementJournal.begin(history,otherRequest,"other-server",200)
                    deployStore.update(ServerManagementJournal.SECTION){log->history.keys().forEach {id->log.put(id,history.getJSONObject(id))}}
                    val managementBefore=ProfileStore.snapshot(restarted)
                    managementCheckpoint=managementBefore
                    val management=ServerManagementUi.view(restarted,deployStore,AndroidDeployHistory.Entry("fixture","swg",managementRecord))
                    val journal=management.findViewWithTag<android.widget.LinearLayout>("serverManagement.journal")
                    val journalText=(0 until journal.childCount).joinToString("\n"){(journal.getChildAt(it) as android.widget.TextView).text}
                    check(journalText.contains("Добавить клиента")&&journalText.contains("Подтверждено"))
                    check(!journalText.contains("Отключить клиента")&&!journalText.contains("неподтверждённые")) {"Another server's journal leaked into this server view"}
                    val password=management.findViewWithTag<View>("serverManagement.password")
                    val key=management.findViewWithTag<View>("serverManagement.key")
                    check(password.visibility==View.VISIBLE&&key.visibility==View.GONE)
                    management.findViewWithTag<View>("serverManagement.action.status").performClick()
                    check(management.findViewWithTag<android.widget.TextView>("serverManagement.status").text.toString().contains("SSH-пароль"))
                    check(!RemoteServerOperationGate.busy()) {"Empty password started a server request"}
                    management.findViewWithTag<android.widget.RadioGroup>("serverManagement.auth").check(management.findViewWithTag<View>("serverManagement.authKey").id)
                    check(password.visibility==View.GONE&&key.visibility==View.VISIBLE)
                    management.findViewWithTag<View>("serverManagement.action.clients").performClick()
                    check(management.findViewWithTag<android.widget.TextView>("serverManagement.status").text.toString().contains("SSH-ключ"))
                    check(!RemoteServerOperationGate.busy()) {"Opening management or changing auth started a remote operation"}
                    check(ProfileStore.snapshot(restarted)==managementBefore) {"Opening management changed its seeded fixture state"}
                }
                val actions=listOf(
                        ServerManagementRequest.Action.ADD_CLIENT to org.json.JSONObject().put("name","Fixture client"),
                        ServerManagementRequest.Action.DISABLE_CLIENT to org.json.JSONObject().put("client_id","fixture-client"),
                        ServerManagementRequest.Action.CLIENT_EXPIRY to org.json.JSONObject().put("client_id","fixture-client").put("expires_at",0L),
                        ServerManagementRequest.Action.CLIENT_EXPIRY to org.json.JSONObject().put("client_id","fixture-client").put("expires_at",2000000000L),
                        ServerManagementRequest.Action.RESTART to org.json.JSONObject().put("node","fixture-node").put("iface","wg0"))
                for((action,parameters) in actions){
                        var calls=0
                        val before=parameters.toString()
                        var captured:org.json.JSONObject?=null
                        lateinit var cancelled:AlertDialog
                        onMain {cancelled=ServerManagementDialogs.confirm(restarted,"192.0.2.10","Fixture client",action,parameters){calls++;captured=it}}
                        try{
                            onMain {
                                check(calls==0)
                                check(cancelled.findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("192.0.2.10"))
                                cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                            }
                            test.waitForIdleSync()
                            onMain {check(calls==0&&captured==null) {"Cancel submitted a server mutation"}}
                        }finally{onMain {cancelled.dismiss()}}
                        lateinit var confirmed:AlertDialog
                        onMain {confirmed=ServerManagementDialogs.confirm(restarted,"192.0.2.10","Fixture client",action,parameters){calls++;captured=it}}
                        try{
                            onMain {
                                parameters.put("ignored_after_capture","must not reach callback")
                                val positive=confirmed.getButton(AlertDialog.BUTTON_POSITIVE)
                                positive.performClick();positive.performClick()
                            }
                            test.waitForIdleSync()
                            onMain {check(calls==1&&captured!!.toString()==before) {"Confirmation must submit exactly one captured request"}}
                        }finally{onMain {confirmed.dismiss()}}
                }
                onMain {check(!RemoteServerOperationGate.busy());check(ProfileStore.snapshot(restarted)==managementCheckpoint)}
            }finally{onMain {(primary.get(restarted) as? AlertDialog)?.dismiss();LauncherIcons.select(restarted,0);restarted.finish()}}
            return "PASS fresh use chooser precedes core choice; selection persists and top settings controls remain reachable\nPASS proxy-only hides TUN fields without changing saved profiles\nPASS both-theme colors apply/reset independently of use/core selection\n"
        }finally{onMain {usage?.dismiss();(primary.get(activity) as? AlertDialog)?.dismiss();activity.finish()}}
    }
}
