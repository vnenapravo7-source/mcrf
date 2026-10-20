package org.mcrf.client

import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Real Android views with isolated encrypted drafts. Never presses an install/auth button. */
object AndroidDeployUiDeviceChecks {
    @JvmStatic fun run(test: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base=test.targetContext
        val preferenceNames=mutableSetOf<String>()
        val prefix="deploy-ui-${UUID.randomUUID()}-"
        val context=object : ContextWrapper(base) {
            override fun getFilesDir()=temporary
            override fun getApplicationContext(): Context=this
            override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
                val scoped=prefix+name;preferenceNames.add(scoped)
                return base.getSharedPreferences(scoped,mode)
            }
        }
        val store=AndroidDeployStore(context)
        val backend=AndroidDeployBackend(store)
        val activity=test.startActivitySync(Intent(base,MainActivity::class.java)
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun onMain(action: () -> Unit) {
            val failure=AtomicReference<Throwable?>()
            test.runOnMainSync { try { action() } catch(error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw IllegalStateException(it.message,it) }
        }
        fun switches(root: View): List<Switch> = when(root) {
            is Switch -> listOf(root)
            is ViewGroup -> (0 until root.childCount).flatMap { switches(root.getChildAt(it)) }
            else -> emptyList()
        }
        var dialog: Dialog?=null
        var ui: AndroidDeployUi?=null
        try {
            for(recipe in listOf("3xui","swg")) {
                store.update("deployDrafts") { drafts -> drafts.put(recipe,JSONObject()
                    .put("options",JSONObject().put("create",false).put("tcp",false).put("grpc",false)
                        .put("tcp_port","23456").put("grpc_port","23457"))) }
                onMain {
                    val editor=AndroidDeployUi(activity,store,backend) { error("UI fixture must not import a connection") }
                    ui=editor
                    val view=editor.deploy(recipe)
                    dialog=Dialog(activity).apply { setContentView(view);show() }
                }
                test.waitForIdleSync()
                onMain {
                    val root=dialog!!.window!!.decorView
                    val create=root.findViewWithTag<Switch>("androidDeploy.create")
                    val fields=root.findViewWithTag<View>("androidDeploy.provisioning")
                    val submit=root.findViewWithTag<Button>("androidDeploy.submit")
                    val passwordFields=root.findViewWithTag<View>("androidDeploy.auth.password")
                    val keyFields=root.findViewWithTag<View>("androidDeploy.auth.key")
                    check(passwordFields.visibility==View.VISIBLE && keyFields.visibility==View.GONE)
                    root.findViewWithTag<Button>("androidDeploy.auth.selectKey").performClick()
                    check(keyFields.visibility==View.VISIBLE && passwordFields.visibility==View.GONE)
                    check(store.get("deployDrafts").getJSONObject(recipe).getString("sshAuth")=="key")
                    root.findViewWithTag<Button>("androidDeploy.auth.selectPassword").performClick()
                    check(passwordFields.visibility==View.VISIBLE && keyFields.visibility==View.GONE)
                    passed.accept("$recipe: password/key choice exposes only selected fields and persists without network")
                    check(!create.isChecked && fields.visibility==View.GONE)
                    check(submit.text.toString()==AndroidDeployPresentation.action(false))
                    passed.accept("$recipe: panel-only draft hides all connection fields and uses panel-only action")
                    create.performClick()
                    check(create.isChecked && fields.visibility==View.VISIBLE)
                    check(submit.text.toString()==AndroidDeployPresentation.action(true))
                    val options=store.get("deployDrafts").getJSONObject(recipe).getJSONObject("options")
                    check(options.getString("tcp_port")=="23456" && options.getString("grpc_port")=="23457")
                    if(recipe=="3xui") {
                        check(switches(root).size==1)
                        check(options.getBoolean("tcp") && options.getBoolean("grpc"))
                        passed.accept("3x-ui: both transports enabled, redundant switches absent, edited ports preserved")
                    }
                    if(recipe=="swg") {
                        for(kind in AndroidDeploySwg.kinds) {
                            val select=root.findViewWithTag<android.widget.CheckBox>("androidDeploy.swg.$kind.enabled")
                            if(!select.isChecked)select.performClick()
                            check(root.findViewWithTag<View>("androidDeploy.swg.$kind.fields").visibility==View.VISIBLE)
                        }
                        val profiles=store.get("deployDrafts").getJSONObject(recipe).getJSONObject("options").getJSONArray("swg_profiles")
                        check((0 until profiles.length()).all {profiles.getJSONObject(it).getBoolean("enabled")})
                        check(AndroidDeploySwg.ports(JSONObject().put("swg_profiles",profiles)).distinct().size==5)
                        val wdtt=root.findViewWithTag<android.widget.CheckBox>("androidDeploy.swg.wdtt.enabled")
                        wdtt.performClick();check(root.findViewWithTag<View>("androidDeploy.swg.wdtt.fields").visibility==View.GONE)
                        wdtt.performClick();check(!backend.state.running)
                        passed.accept("SWG: multi-selection shows independent fields, unique ports and retained disabled drafts without calls/deploy")
                    }
                    create.performClick()
                    check(fields.visibility==View.GONE && !backend.state.running)
                    check(!store.get("deployDrafts").getJSONObject(recipe).getJSONObject("options").getBoolean("create"))
                    if(recipe=="3xui")check(root.findViewWithTag<View>("androidDeploy.newCallGroup")==null)
                    if(recipe=="swg") {
                        check(root.findViewWithTag<View>("androidDeploy.newCallGroup").visibility==View.GONE)
                        check(root.findViewWithTag<Button>("androidDeploy.vkSettings")!=null)
                    }
                    passed.accept("$recipe: toggling back autosaves without installation, calls or imports")
                }
                onMain { ui?.detach();dialog?.dismiss();ui=null;dialog=null }
            }
            // Exact crash class: legacy WDTT input can contain more calls than the
            // executable protocol limit. Opening/autosaving must retain, not execute it.
            val retainedCalls=org.json.JSONArray().apply {repeat(12){put("retained-fixture-call-$it")}}
            store.update("deployDrafts"){drafts->drafts.put("swg",JSONObject().put("options",JSONObject()
                .put("create",false).put("relay_kind","wdtt").put("vk_calls",retainedCalls)))}
            onMain {
                ui=AndroidDeployUi(activity,store,backend){error("No import in legacy draft preview")}
                dialog=Dialog(activity).apply {setContentView(ui!!.deploy("swg"));show()}
            };test.waitForIdleSync()
            val legacy=store.get("deployDrafts").getJSONObject("swg").getJSONObject("options")
            check(legacy.getJSONArray("vk_calls").toString()==retainedCalls.toString())
            check(legacy.getJSONArray("swg_profiles").getJSONObject(1).getJSONArray("vk_calls").toString()==retainedCalls.toString())
            check(!backend.state.running)
            onMain {ui?.detach();dialog?.dismiss();ui=null;dialog=null}
            passed.accept("Legacy WDTT twelve-call draft opens and autosaves without loss, deployment or protocol-limit bypass")
            check(store.get("deployResults").length()==0)
            passed.accept("Fixture creates no deployment results and does not modify production drafts")
            val target=AndroidDeployTarget("192.0.2.1",sshPassword="fixture-only",options=JSONObject().put("tcp_port",23456).put("grpc_port",23457))
            val saved=AndroidDeployHistory.record(JSONObject().put("panel_url","https://192.0.2.1:8443/panel/")
                .put("username","fixture").put("password","fixture-password").put("certificate_sha256","a".repeat(64))
                .put("ready",true).put("connections",org.json.JSONArray()),target,"3xui")
            store.update("deployResults"){it.put(AndroidDeployHistory.resultKey(target,"3xui"),saved)}
            onMain {
                val editor=AndroidDeployUi(activity,store,backend){error("No imports")};ui=editor
                val page=editor.buildPage();dialog=Dialog(activity).apply {setContentView(page);show()}
                page.findViewWithTag<Button>("androidDeploy.addTransport").performClick()
                check(page.findViewWithTag<Button>("androidDeploy.submit").text.toString()=="Добавить транспорт без переустановки")
                check(page.findViewWithTag<Switch>("androidDeploy.create").visibility==View.GONE)
                check(store.get("deployDrafts").getJSONObject("3xui").getJSONObject("options").getInt("tcp_port")==23456)
                check(!backend.state.running)
                check(store.get("deployResults").length()==1)
                passed.accept("History opens provision-only editor with saved ports, explicit action and no deployment, calls or history loss")
            }
        } finally {
            onMain { ui?.detach();dialog?.dismiss();activity.finish() }
            backend.close()
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
