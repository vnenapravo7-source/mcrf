package org.mcrf.client

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

internal object McrfCommunityLink {
    const val URL="https://t.me/+GZzB-XMAz4YzYjNi"
    fun open(activity:MainActivity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE))
        }catch(_:ActivityNotFoundException){activity.toast("Не найдено приложение для открытия ссылки. Установите браузер или Telegram.")}
        catch(_:SecurityException){activity.toast("Android не разрешил открыть ссылку.")}
    }
}
