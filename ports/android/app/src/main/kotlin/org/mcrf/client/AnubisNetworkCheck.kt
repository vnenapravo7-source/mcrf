package org.mcrf.client

import android.net.InetAddresses
import android.os.SystemClock
import android.widget.LinearLayout
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal object AnubisNetworkCheck {
    private const val ENDPOINT="https://ipwho.is/?fields=success,country_code,city,ip"
    private data class Result(val time:Long,val city:String,val country:String,val address:String?)

    private fun fetch(endpoint:String):Pair<Long,String> {
        val start=SystemClock.elapsedRealtime()
        val connection=URL(endpoint).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout=4000
            connection.readTimeout=4000
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            check(connection.responseCode==200)
            val body=connection.inputStream.use {source->
                val out=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(1024)
                while(true){
                    val count=source.read(buffer)
                    if(count<0)break
                    check(out.size()+count<=8192)
                    out.write(buffer,0,count)
                }
                out.toString("UTF-8")
            }
            return SystemClock.elapsedRealtime()-start to body
        }finally {connection.disconnect()}
    }

    private fun country(code:String)=code.uppercase(Locale.ROOT).let {
        if(it in Locale.getISOCountries())Locale("",it).getDisplayCountry(Locale("ru"))else ""
    }

    private fun check():Result = runCatching {
        val (time,body)=fetch(ENDPOINT)
        val data=JSONObject(body)
        check(data.optBoolean("success",false))
        val city=data.optString("city").filterNot(Char::isISOControl).take(100)
        Result(time,city,country(data.optString("country_code")),
            data.optString("ip").takeIf(InetAddresses::isNumericAddress))
    }.getOrElse {
        val (time,trace)=fetch(WarpProbePlan.URL)
        val address=trace.lineSequence().firstOrNull {it.startsWith("ip=")}?.substringAfter('=')
            ?.takeIf(InetAddresses::isNumericAddress)
        Result(time,"",country(AutoExclusions.country(trace)),address)
    }

    fun show(activity:MainActivity)=with(activity){
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        val status=text("Проверяем ответ HTTPS…",14,Loft.INK)
        content.addView(status)
        val dialog=ThemedDialogBuilder(this).setTitle("Проверка сети").setView(content).setNegativeButton("Закрыть",null).create()
        dialog.show()
        readPresentation(MainActivity.Operation {
            val result=runCatching {check()}.getOrNull()
            runOnUiThread {
                if(dialog.isShowing&&!isFinishing&&!isDestroyed){
                    status.text=if(result==null)"Не удалось получить ответ от сервиса проверки."else listOf(
                        "Ответ HTTPS · ${result.time} мс",listOf(result.city,result.country).filter(String::isNotBlank).joinToString(", ")
                            .ifBlank {"Местоположение не определено"}
                    ).joinToString("\n")
                    result?.address?.let {ip->
                        content.addView(button("Показать IP",false).apply {
                            setOnClickListener {status.append("\n$ip");visibility=android.view.View.GONE}
                        },space(8,0))
                    }
                }
            }
            ""
        })
    }
}
