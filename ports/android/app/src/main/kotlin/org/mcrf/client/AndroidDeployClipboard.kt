package org.mcrf.client

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

object AndroidDeployClipboard {
    fun sensitive(value: String): ClipData = sensitive(value,"MCRF")
    fun sensitive(value:String,label:String):ClipData = ClipData.newPlainText(label,value).apply {
        description.extras=PersistableBundle().apply {
            putBoolean(if(Build.VERSION.SDK_INT>=33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",true)
        }
    }
}
