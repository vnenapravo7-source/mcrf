package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.text.method.PasswordTransformationMethod
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
    private fun password() = activity.editor("Пароль бэкапа").apply {
        setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }
    fun export() {
        val first = password(); val second = password()
        val form = activity.card().apply {
            addView(activity.text("Серверы, подписки, маршруты и настройки в одном зашифрованном файле. Пароль потребуется на другом устройстве. HWID и системные разрешения не переносятся.", 12, Loft.MUTED), activity.space(0, 12))
            addView(activity.field("Пароль · минимум 8 символов", first)); addView(activity.field("Повтор пароля", second))
        }
        val dialog = ThemedDialogBuilder(activity).setTitle("Создать бэкап").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Создать", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (activity.working()) return@setOnClickListener
            if (first.text.length !in 8..1024 || first.text.toString() != second.text.toString()) { activity.alert("Бэкап", "Пароль от 8 символов, повтор должен совпадать."); return@setOnClickListener }
            val secret = CharArray(first.text.length) { first.text[it] }; first.text.clear(); second.text.clear()
            var exported: File? = null
            // Confirmation ends this dialog; cancelling a visible dialog must never
            // suggest that an already-started export/restore was cancelled.
            dialog.dismiss()
            activity.run(MainActivity.Operation {
                try { val bytes = AndroidBackup.export(activity.applicationContext, secret)
                    val file = File(activity.cacheDir, "mcrf-backup-${UUID.randomUUID()}.enc"); check(file.createNewFile()); android.system.Os.chmod(file.absolutePath, 0x180)
                    try { file.outputStream().use { it.write(bytes) }; exported = file } catch (error: Exception) { file.delete(); throw error } finally { bytes.fill(0) }
                    "Бэкап зашифрован. Выберите место сохранения."
                } finally { secret.fill('\u0000') }
            }, false, Runnable { if (exported != null) { dialog.dismiss(); activity.saveBackupDocument(exported!!) } })
        } }; dialog.show(); dialogShown(dialog)
    }
    fun chooseRestore() {
        if (activity.active()) { activity.alert("Бэкап", "Сначала отключите соединение MCRF. Восстановление заменит текущие подключения и маршруты."); return }
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 8)
    }
    fun restore(uri: Uri) {
        val password = password()
        val form = activity.card().apply { addView(activity.text("После проверки файла текущие настройки будут заменены данными бэкапа. Предыдущий зашифрованный набор останется во внутреннем хранилище для восстановления.", 12, Loft.MUTED), activity.space(0, 12)); addView(activity.field("Пароль бэкапа", password)) }
        val dialog = ThemedDialogBuilder(activity).setTitle("Восстановить бэкап?").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Восстановить", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (activity.working() || activity.active()) return@setOnClickListener
            if (password.text.length !in 8..1024) { activity.alert("Бэкап", "Введите пароль бэкапа от 8 символов."); return@setOnClickListener }
            val secret = CharArray(password.text.length) { password.text[it] }; password.text.clear(); var restored = false
            dialog.dismiss()
            activity.run(MainActivity.Operation {
                try { val file = activity.contentResolver.openInputStream(uri)?.use(BackupCodec::read) ?: throw IllegalArgumentException("Не удалось открыть файл.")
                    try { AndroidBackup.restore(activity.applicationContext, file, secret); restored = true } finally { file.fill(0) }
                    "Бэкап восстановлен"
                } finally { secret.fill('\u0000') }
            }, false, Runnable { if (restored) { dialog.dismiss(); activity.recreate() } })
        } }; dialog.show(); dialogShown(dialog)
    }
    companion object {
        @JvmStatic fun cached(context: android.content.Context, path: String?): File? {
            if (path == null) return null
            val file = File(path).canonicalFile
            return file.takeIf { it.parentFile == context.cacheDir.canonicalFile && it.name.matches(Regex("mcrf-backup-[0-9a-f-]{36}\\.enc")) && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() <= BackupCodec.MAX_FILE }
        }
    }
}


