package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import java.io.File
import java.util.UUID

class BackupUi @JvmOverloads constructor(private val activity: MainActivity, private val dialogShown: (AlertDialog) -> Unit = {}) {
    fun resetDefaults() {
        if(activity.active()) { activity.alert("Сброс настроек", "Сначала отключите соединение MCRF."); return }
        val names = arrayOf("Все настройки приложения и сети", "Приложение и поведение VPN", "Сеть, ядро и обновление подписок")
        val scopes = arrayOf(BackupDefaults.Scope.ALL_SETTINGS,BackupDefaults.Scope.GENERAL,BackupDefaults.Scope.ADVANCED)
        ThemedDialogBuilder(activity).setTitle("Что сбросить?").setItems(names) { _, index ->
            val scope = scopes[index]
            ThemedDialogBuilder(activity).setTitle("Сбросить: ${names[index]}?")
                .setMessage("Вернутся значения по умолчанию в выбранной области. Подключения, подписки, маршруты, авторизация локального прокси и VK, данные деплоя, доверенные SSH-ключи и сохранённые бэкапы останутся. Предыдущий зашифрованный набор будет сохранён.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сбросить") { _, _ ->
                    if(!activity.active() && !activity.working()) {
                        var done = false
                        activity.run(MainActivity.Operation { BackupDefaults.reset(activity,scope); done = true; "Настройки сброшены." },false,Runnable { if(done) activity.recreate() })
                    }
                }.show().also(dialogShown)
        }.setNegativeButton("Отмена",null).show().also(dialogShown)
    }
    private fun progress(message:String):AlertDialog=ThemedDialogBuilder(activity).setTitle("Бэкап")
        .setMessage(message).setView(android.widget.ProgressBar(activity)).create().apply {setCancelable(false);show();dialogShown(this)}
    fun export() {
        val dialog=ThemedDialogBuilder(activity).setTitle("Создать бэкап")
            .setMessage("Серверы, подписки, маршруты и настройки в одном файле без пароля. Файл содержит данные доступа; храните его в подходящем месте.")
            .setNegativeButton("Отмена",null).setPositiveButton("Создать",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(activity.working())return@setOnClickListener
            dialog.dismiss();val pending=progress("Подготавливаем бэкап…")
            var exported:File?=null
            activity.run(MainActivity.Operation {
                val bytes=AndroidBackup.export(activity.applicationContext,charArrayOf())
                try {
                    val file=File(activity.cacheDir,"mcrf-backup-${UUID.randomUUID()}.enc")
                    check(file.createNewFile());android.system.Os.chmod(file.absolutePath,0x180)
                    try {file.outputStream().use {it.write(bytes)};exported=file}catch(t:Exception){file.delete();throw t}
                    "Бэкап подготовлен. Выберите место сохранения."
                }finally {bytes.fill(0)}
            },false,Runnable {pending.dismiss();exported?.let {activity.saveBackupDocument(it)}})
        }}
        dialog.show();dialogShown(dialog)
    }
    fun chooseRestore() {
        if (activity.active()) { activity.alert("Бэкап", "Сначала отключите соединение MCRF. Восстановление заменит текущие подключения и маршруты."); return }
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 8)
    }
    fun restore(uri:Uri) {
        if(activity.working()||activity.active())return
        val pending=progress("Читаем и проверяем бэкап…")
        var preview:ChangePreview?=null;var legacy=false
        activity.run(MainActivity.Operation {
            val bytes=activity.contentResolver.openInputStream(uri)?.use(BackupCodec::read)?:error("Не удалось открыть бэкап.")
            try {legacy=!BackupCodec.isPortable(bytes);if(!legacy)preview=AndroidBackup.prepareRestore(activity.applicationContext,bytes,charArrayOf())}
            finally {bytes.fill(0)}
            if(legacy)"Старый защищённый бэкап не применён."else"Бэкап проверен. Просмотрите изменения."
        },false,Runnable {pending.dismiss();if(legacy)activity.alert("Старый бэкап","Этот файл зашифрован паролем. Создайте новый бэкап без пароля в версии приложения, где он был сохранён. Текущие данные не изменены.")else preview?.let(::showPreview)})
    }
    private fun showPreview(preview:ChangePreview) {
        if(activity.isFinishing||activity.isDestroyed)return
        ChangePreviewUi.show(activity,"Изменения из бэкапа",preview) {
            if(activity.active()){activity.alert("Бэкап","Сначала отключите соединение MCRF.");return@show}
            val pending=progress("Применяем бэкап…");var restored=false
            activity.run(MainActivity.Operation {
                check(!activity.active())
                AndroidBackup.applyRestore(activity.applicationContext,preview);restored=true;"Бэкап восстановлен"
            },false,Runnable {pending.dismiss();if(restored)activity.recreate()})
        }.also(dialogShown)
    }
    companion object {
        @JvmStatic fun cached(context: android.content.Context, path: String?): File? {
            if (path == null) return null
            val file = File(path).canonicalFile
            return file.takeIf { it.parentFile == context.cacheDir.canonicalFile && it.name.matches(Regex("mcrf-backup-[0-9a-f-]{36}\\.enc")) && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() <= BackupCodec.MAX_FILE }
        }
    }
}


