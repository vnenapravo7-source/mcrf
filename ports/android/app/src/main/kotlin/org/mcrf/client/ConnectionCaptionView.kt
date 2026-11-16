package org.mcrf.client

import android.text.TextUtils
import android.widget.TextView

/** One line when it fits, otherwise ellipsize the name and keep the IP visible. */
internal class ConnectionCaptionView(host:MainActivity):TextView(host) {
    private var connectedCaption:Triple<String,String?,String?>?=null
    init {maxLines=2}
    fun label(value:CharSequence) {connectedCaption=null;text=value;contentDescription=value}
    fun connected(label:String,name:String?,address:String?) {
        connectedCaption=Triple(label,name,address);renderConnectedCaption()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw)renderConnectedCaption()
    }
    private fun renderConnectedCaption() {
        val (label,name,address)=connectedCaption?:return
        val complete=ConnectionPresentation.compactTarget(label,name,address)
        contentDescription=complete
        val available=(width-paddingLeft-paddingRight).toFloat()
        if(available<=0){text=complete;return}
        text=if(paint.measureText(complete)<=available)complete else {
            val server=name.orEmpty().filterNot(Char::isISOControl).trim().take(160)
            val ip=address.orEmpty().filterNot(Char::isISOControl).trim().take(160)
            val target=listOf(server,ip).filter(String::isNotBlank).joinToString(", ")
            // A wrapped target remains a unit: the second line must not contain only an IP.
            val prefix="$label:"
            val suffix=if(ip.isBlank())""else", $ip"
            val budget=(available-paint.measureText(suffix)).coerceAtLeast(0f)
            val fitted=TextUtils.ellipsize(server,paint,budget,TextUtils.TruncateAt.END).toString()
            if(paint.measureText(target)<=available) "$prefix\n$target"
            else if(fitted.isNotBlank()) "$prefix\n$fitted$suffix"
            else TextUtils.ellipsize(prefix,paint,available,TextUtils.TruncateAt.END).toString()+"\n"+
                TextUtils.ellipsize(target,paint,available,TextUtils.TruncateAt.MIDDLE)
        }
    }
}
