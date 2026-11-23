package org.mcrf.client

import android.app.AlertDialog
import android.text.InputType
import android.widget.LinearLayout

/** Shared clipboard/file/QR actions: caller retains engine consent and transactional ImportPlan commit. */
object CompatibilityImportUi {
    fun importEncrypted(activity: MainActivity, link: String, onPlain: (String) -> Unit) {
        require(CompatibilityImportEncrypted.isEncrypted(link))
        if (CompatibilityImportEncrypted.isAutomatic(link)) {
            var plain: String? = null
            activity.run(MainActivity.Operation {
                plain = CompatibilityImportEncrypted.decryptAutomatic(link); "Ссылка проверена"
            }, false, Runnable { val ready=plain; plain=null; ready?.let(onPlain) })
            return
        }
        if (!link.startsWith(CompatibilityImportEncrypted.PREFIX)) {
            activity.alert("Шифрованный импорт", "Эта версия ссылки пока не поддерживается. Обновите MCRF.")
            return
        }
        password(activity, "Шифрованный импорт", "Введи пароль от ссылки MCRF. До проверки пароля и всего профиля каталог не изменяется.") { secret ->
            var plain: String? = null
            activity.run(MainActivity.Operation {
                try { plain = CompatibilityImportEncrypted.decrypt(link, secret); "Пароль проверен" } finally { secret.fill('\u0000') }
            }, false, Runnable { secret.fill('\u0000'); val ready=plain; plain=null; ready?.let(onPlain) })
        }
    }
    fun shareEncrypted(activity: MainActivity, source: String, onEncrypted: (String) -> Unit) {
        var result: String? = null
        activity.run(MainActivity.Operation {
            result = CompatibilityImportEncrypted.encryptAutomatic(source); "Шифрованная ссылка создана"
        }, false, Runnable { val ready=result; result=null; ready?.let(onEncrypted) })
    }
    private fun password(activity: MainActivity, title: String, explanation: String, action: (CharArray) -> Unit) {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(8)) }
        val input = activity.editor("Пароль · от 8 символов").apply { isSaveEnabled=false;setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        panel.addView(activity.text(explanation, 12, Loft.MUTED), activity.space(0, 12)); panel.addView(input)
        val dialog = ThemedDialogBuilder(activity).setTitle(title).setView(panel).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null).create()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.length() !in 8..1024) { input.error = "Пароль: 8–1024 символа"; return@setOnClickListener }
            val secret=CharArray(input.length())
            try {
                android.text.TextUtils.getChars(input.text,0,secret.size,secret,0)
                input.text.clear(); dialog.dismiss(); action(secret)
            }catch(error:Throwable){secret.fill('\u0000');throw error}
        } }
        dialog.setOnDismissListener { input.text.clear() }; dialog.show()
    }
}
