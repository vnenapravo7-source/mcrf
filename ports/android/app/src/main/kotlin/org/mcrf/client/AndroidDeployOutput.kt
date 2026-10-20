package org.mcrf.client

import org.json.JSONObject
import java.util.Base64

/** Exactly one bounded, structured installer result; a second marker cannot grow the console or masquerade as readiness. */
class AndroidDeployOutput {
    private var resultLine: String?=null
    fun accept(line: String): Boolean {
        if(!line.startsWith(PREFIX)) return false
        require(resultLine==null) { "Установщик вернул повторное подтверждение результата" }
        require(line.length in (PREFIX.length+1)..700_000) { "Некорректный размер результата установщика" }
        resultLine=line;return true
    }
    fun result(): JSONObject {
        val line=resultLine ?: error("Нет подтверждения результата установки")
        return try {
            val bytes=Base64.getDecoder().decode(line.removePrefix(PREFIX))
            require(bytes.size<=525_000)
            JSONObject(String(bytes,Charsets.UTF_8))
        } catch(e: Exception) { throw IllegalArgumentException("Установщик вернул повреждённый результат",e) }
    }
    fun encodedLine(): String = resultLine ?: ""
    companion object { const val PREFIX="MCRF_RESULT|" }
}
