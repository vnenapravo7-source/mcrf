package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import java.io.File

/** Guided Android reinstall. App downgrade always exports APK + portable backup outside the sandbox first. */
class VersionHistoryActivity:Activity() {
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private var pending:String?=null
    private var pendingInstall:String?=null
    private var pendingInstallRequest=0
    private var busy=false
    private var progress:AlertDialog?=null
    private var entries=emptyList<VersionArchiveStore.Entry>()
    override fun onCreate(savedInstanceState:Bundle?) {
        UserThemeColorsUi.configure(this)
        val chosen=getSharedPreferences("MainActivity",0).getString("theme","system")
        val dark=chosen=="dark"||chosen=="system"&&resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES
        Loft.apply(dark);setTheme(if(dark)R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState);pending=savedInstanceState?.getString("bundle");pendingInstall=savedInstanceState?.getString("installer")
        pendingInstallRequest=savedInstanceState?.getInt("installerRequest")?:0
        window.statusBarColor=Loft.BACKGROUND;window.navigationBarColor=Loft.BACKGROUND
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(12));background=Loft.rootBackground(this@VersionHistoryActivity)}
        root.addView(label("История версий и откат",21))
        root.addView(label("Хранятся установленная и две предыдущие сохранённые версии каждого пакета. История начинается с включения этой функции.",13))
        status=label("Чтение истории…",13);root.addView(status)
        body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply {addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(action("Закрыть"){finish()});setContentView(root)
        load()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun label(text:String,size:Int)=TextView(this).apply {this.text=text;textSize=size.toFloat();setTextColor(Loft.INK);setPadding(0,dp(8),0,dp(8))}
    private fun action(text:String,run:()->Unit)=Button(this).apply {
        this.text=text;minimumHeight=dp(48);setSingleLine(false);isAllCaps=false
        setTextColor(Loft.INK);background=Loft.buttonBackground(this@VersionHistoryActivity)
        setPadding(dp(16),dp(12),dp(16),dp(12));textSize=14f
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(8)}
        setOnClickListener {if(!busy)run()}
    }
    private fun work(message:String,operation:()->Unit,discard:()->Unit={},showProgress:Boolean=false,success:()->Unit={}) {
        if(busy)return
        busy=true;status.text=message;status.accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        if(showProgress)progress=AlertDialog.Builder(this).setTitle(message).setView(LinearLayout(this).apply {
            setPadding(dp(20),dp(12),dp(20),dp(20));gravity=android.view.Gravity.CENTER_VERTICAL
            addView(ProgressBar(this@VersionHistoryActivity),LinearLayout.LayoutParams(dp(40),dp(40)))
            addView(label("Готовим файл. После подготовки выберите папку и нажмите «Сохранить» в окне Android.",13),LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=dp(16)})
        }).setCancelable(false).show()
        Thread({
            try {operation();runOnUiThread {
                busy=false;progress?.dismiss();progress=null
                if(isDestroyed||isFinishing)discard()
                else try {status.text="Готово";success()}catch(e:Exception){discard();failure(e,"Не удалось открыть системное окно. Установленные версии не изменены.")}
            }}
            catch(e:Exception){discard();runOnUiThread {busy=false;progress?.dismiss();progress=null;if(!isDestroyed&&!isFinishing)failure(e)}}
        },"mcrf-version-history").apply {isDaemon=true}.start()
    }
    private fun failure(error:Exception,fallback:String="Не удалось завершить операцию с архивом. Проверьте свободное место и повторите.") {
        val message=if(error is IllegalArgumentException||error is IllegalStateException)ClientEvents.redact(error.message).takeIf {Regex("[А-Яа-яЁё]").containsMatchIn(it)}?:fallback else fallback
        status.text=message
        ClientEvents.record(this,"История версий","Ошибка подготовки или сохранения комплекта: ${ClientEvents.redact(error.toString())}")
        AlertDialog.Builder(this).setTitle("Не удалось выполнить действие").setMessage(message).setPositiveButton("ОК",null).show()
    }
    private fun load() {
        var loaded=emptyList<VersionArchiveStore.Entry>()
        work("Сохранение установленных версий…",{
            VersionArchive.captureInstalled(applicationContext)
            val store=VersionArchive.store(applicationContext);loaded=VersionArchive.packages(applicationContext).flatMap(store::entries)
        }) {
            entries=loaded;body.removeAllViews();status.text="Сохранено версий: ${loaded.size}"
            loaded.groupBy {it.packageId}.forEach {(id,versions)->
                body.addView(label(if(id==packageName)"MCRF" else EngineModules.kind(id)?.let {if(it=="bs")"БС · CSQTT / QWDTT"else it}?:id,17))
                versions.forEachIndexed {index,entry->body.addView(action("${entry.version} · ${entry.code}${if(index==0)" · последняя сохранённая"else""}\n${EngineStorage.format(this,entry.bytes)}") {choose(entry)})}
            }
        }
    }
    private fun choose(entry:VersionArchiveStore.Entry) {
        if(entry.packageId==packageName) {
            AlertDialog.Builder(this).setTitle("Откат MCRF: ${entry.version}")
                .setMessage("Android не устанавливает старую версию поверх новой. Сначала сохраните комплект: выбранный APK и бэкап без пароля. Затем распакуйте его, удалите MCRF, установите APK и восстановите бэкап. Пошаговая инструкция будет внутри комплекта.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить комплект"){_,_->prepareBundle(entry)}.show()
        } else {
            if(EngineModules.kind(entry.packageId)==null){AlertDialog.Builder(this).setTitle("Старый общий пакет").setMessage("Архив общего пакета сохранён. Для управляемого отката используйте отдельные пакеты движков: общий пакет не позволяет переустановить только один движок.").setPositiveButton("ОК",null).show();return}
            AlertDialog.Builder(this).setTitle("Движок ${entry.version}")
                .setMessage("Для более старой версии сначала удалите только этот модуль, затем установите сохранённый APK. Профили MCRF останутся. Перед переустановкой отключите соединение. Подпись и совместимость APK проверяются повторно.")
                .setNegativeButton("Закрыть",null).setNeutralButton("Удалить текущий"){_,_->remove(entry)}
                .setPositiveButton("Установить сохранённый"){_,_->install(entry)}.show()
        }
    }
    private fun prepareBundle(entry:VersionArchiveStore.Entry) {
            var prepared:File?=null
            work("Подготовка APK и бэкапа…",{
                    // Export reads an atomic snapshot; it does not replace live profiles or stop VPN.
                    val apk=VersionArchive.verify(applicationContext,entry)
                    val current=VersionArchive.capture(applicationContext,packageName)
                    val recoveryApk=VersionArchive.verify(applicationContext,current)
                    val backup=AndroidBackup.export(applicationContext,charArrayOf())
                    val file=File(cacheDir,"mcrf-rollback-${java.util.UUID.randomUUID()}.zip")
                    try {
                        java.util.zip.ZipOutputStream(file.outputStream()).use {zip->
                            fun bytes(name:String,data:ByteArray){zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(data);zip.closeEntry()}
                            zip.putNextEntry(java.util.zip.ZipEntry("MCRF.apk"));apk.inputStream().use {it.copyTo(zip)};zip.closeEntry()
                            zip.putNextEntry(java.util.zip.ZipEntry("MCRF-current.apk"));recoveryApk.inputStream().use {it.copyTo(zip)};zip.closeEntry()
                            bytes("MCRF-backup.enc",backup)
                            bytes("README.txt",("MCRF ${entry.version} (${entry.code})\nAPK SHA-256: ${entry.sha}\nТекущая версия для возврата: ${current.version}; SHA-256: ${current.sha}\n\n1. Сохраните этот ZIP вне данных MCRF, например в Загрузках, и храните его как файл с данными доступа.\n2. Распакуйте ZIP и убедитесь, что MCRF.apk и MCRF-backup.enc доступны.\n3. Удалите установленное приложение MCRF средствами Android. Это удалит его локальные данные. Отдельные APK движков не удаляйте.\n4. Установите MCRF.apk из распакованного комплекта.\n5. В настройках MCRF откройте восстановление бэкапа, выберите MCRF-backup.enc.\n6. Просмотрите изменения и подтвердите восстановление. Если старая версия не понимает новые параметры, восстановление откажет без частичного применения; установите MCRF-current.apk из этого комплекта и восстановите сохранённый бэкап, чтобы вернуться на новую версию.\n").toByteArray(Charsets.UTF_8))
                        };prepared=file
                    }catch(e:Exception){file.delete();throw e}finally{backup.fill(0)}
            },discard={prepared?.delete();pending=null},showProgress=true) {
                val file=requireNotNull(prepared);pending=file.name
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"MCRF-rollback-${entry.code}.zip"),101)
            }
    }
    private fun remove(entry:VersionArchiveStore.Entry) {
        var lease:EngineUpdateGate.Mutation?=null
        work("Проверка сохранённой версии…",{
            require(!McrfVpnService.hasActiveSession()&&!McrfVpnService.cleanupPending()&&!EngineUpdates.installationPending(applicationContext)){"Сначала отключите соединение и завершите установку движков."}
            VersionArchive.verify(applicationContext,entry)
            VersionArchive.capture(applicationContext,entry.packageId)
            lease=EngineUpdateGate.beginMutation(applicationContext,"version_history","remove",requireNotNull(EngineModules.kind(entry.packageId)))
                ?:error("Движок используется или другая установка ещё не завершена.")
        },discard={lease?.let {EngineUpdateGate.complete(applicationContext,it.pending.token){true}}}) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.parse("package:${entry.packageId}")).putExtra(Intent.EXTRA_RETURN_RESULT,true),requireNotNull(lease).pending.requestCode)
        }
    }
    private fun install(entry:VersionArchiveStore.Entry) {
        if(!packageManager.canRequestPackageInstalls()){startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")));return}
        var prepared:File?=null
        var lease:EngineUpdateGate.Mutation?=null
        work("Проверка совместимости APK…",{
            require(!McrfVpnService.hasActiveSession()&&!McrfVpnService.cleanupPending()&&!EngineUpdates.installationPending(applicationContext)){"Сначала отключите соединение и завершите установку движков."}
            val apk=VersionArchive.verify(applicationContext,entry)
            EnginePackages.verifyArchive(applicationContext,apk,EngineModules.kind(entry.packageId),entry.code)
            val file=File(cacheDir,"mcrf-engine-${java.util.UUID.randomUUID()}.apk")
            try {apk.inputStream().use {input->file.outputStream().use {input.copyTo(it)}};EnginePackages.verifyArchive(applicationContext,file,EngineModules.kind(entry.packageId),entry.code);prepared=file}
            catch(e:Exception){file.delete();throw e}
            val installed=try {VersionArchive.capture(applicationContext,entry.packageId)}catch(_:android.content.pm.PackageManager.NameNotFoundException){null}
            require(installed?.sha!=entry.sha){"Выбранная версия уже установлена."}
            lease=EngineUpdateGate.beginMutation(applicationContext,"version_history","apk",requireNotNull(EngineModules.kind(entry.packageId)),entry.sha,entry.bytes)
                ?:error("Движок используется или другая установка ещё не завершена.")
        },discard={prepared?.delete();lease?.let {EngineUpdateGate.complete(applicationContext,it.pending.token){true}}}) {
            val file=requireNotNull(prepared)
            pendingInstall=file.name
            pendingInstallRequest=requireNotNull(lease).pending.requestCode
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(Uri.parse("content://$packageName.engine-package/${file.name}"),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true),pendingInstallRequest)
        }
    }
    @Deprecated("Android result contract") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==101) {
            val name=pending;pending=null
            if(name==null||!name.matches(Regex("mcrf-rollback-[a-f0-9-]{36}\\.zip")))return
            val file=File(cacheDir,name)
            if(resultCode!=RESULT_OK||data?.data==null){file.delete();status.text="Сохранение отменено. Комплект не записан.";return}
            val uri=data.data!!
            work("Сохранение комплекта…",{
                require(!java.nio.file.Files.isSymbolicLink(file.toPath())&&file.canonicalFile.parentFile==cacheDir.canonicalFile)
                try {contentResolver.openOutputStream(uri,"wt")?.use {out->file.inputStream().use {it.copyTo(out)}}?:error("Не удалось сохранить комплект.")}
                finally{file.delete()}
            },showProgress=true) {Toast.makeText(this,"Комплект сохранён",Toast.LENGTH_LONG).show();status.text="Комплект сохранён. Распакуйте его и следуйте README.txt. Приложение и данные автоматически не удаляются."}
        }
        if(EngineUpdateGate.isInstallerRequest(requestCode)) {
            EngineUpdates.installerReturned(applicationContext,requestCode,resultCode)
            if(requestCode==pendingInstallRequest){
                pendingInstall?.takeIf {it.matches(Regex("mcrf-engine-[a-f0-9-]{36}\\.apk"))}?.let {File(cacheDir,it).delete()}
                pendingInstall=null;pendingInstallRequest=0
            }
            load()
        }
    }
    override fun onDestroy() {progress?.dismiss();progress=null;
        if(isFinishing)pending?.takeIf {it.matches(Regex("mcrf-rollback-[a-f0-9-]{36}\\.zip"))}?.let {File(cacheDir,it).delete()}
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("bundle",pending);outState.putString("installer",pendingInstall);outState.putInt("installerRequest",pendingInstallRequest);super.onSaveInstanceState(outState)}
}
