package org.mcrf.client

import android.app.AlertDialog
import android.text.InputType
import android.widget.LinearLayout
import org.json.JSONObject

/** Retains manual credentials editing after removing the duplicate sharing page. */
object LocalProxyAuthUi {
    fun edit(activity:MainActivity)=with(activity){
        if(active()){alert("Авторизация прокси","Сначала отключите MCRF.");return@with}
        val store=ClientTunStore(this);val old=store.auth()
        val user=editor("Логин").apply {setSingleLine(true);setText(old.getString("username"))}
        val password=editor("Пароль").apply {setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(old.getString("password"))}
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(12),dp(24),dp(12));addView(field("Логин",user));addView(field("Пароль",password))}
        val dialog=ThemedDialogBuilder(this).setTitle("Авторизация локального прокси").setView(body).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(active()){toast("Сначала отключите MCRF");return@setOnClickListener}
            try{store.saveAuth(JSONObject(old.toString()).put("username",user.text.toString()).put("password",password.text.toString()));password.text.clear();dialog.dismiss();render()}
            catch(_:Exception){password.error="Нужны логин и пароль длиной 1–255 байт без управляющих символов"}
        }}
        dialog.setOnDismissListener {password.text.clear()};dialog.show()
    }
}
