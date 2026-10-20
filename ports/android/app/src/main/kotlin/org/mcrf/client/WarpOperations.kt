package org.mcrf.client

import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Stateless orchestration. A candidate is committed only after trace through that candidate. */
object WarpOperations {
    data class Options(val protocol: String = "auto", val country: String = "foreign", val excludeDme: Boolean = true, val extended: Boolean = false) {
        init { require(protocol in setOf("auto", "wg", "awg")); WarpProfile.country(country); require(excludeDme){"Узел DME исключён во всех режимах."} }
    }
    class Verified internal constructor(val account: String, val server: ServerImport.Server, val trace: WarpProfile.Trace) {
        override fun toString() = "Verified WARP ${trace.country}/${trace.node}"
    }
    fun interface Backend { fun execute(request: JSONObject): JSONObject }
    /** Must fetch HTTPS /cdn-cgi/trace through the supplied profile; direct/system fetch is invalid. */
    fun interface TunnelTrace { fun fetch(config: String): String }
    const val MAX_SEARCH_ATTEMPTS = 3
    class SearchExhausted(val foreign: Boolean,val detail:String="") : IllegalArgumentException("Выход WARP не найден за 3 попытки. Старый профиль сохранён.")
    class ScanFailure(val reason:String):IllegalArgumentException(reason)
    /** Each attempt includes native scanning and trace validation; registration is never retried here. */
    fun search(rawAccount: String, backend: Backend, tunnelTrace: TunnelTrace, options: Options = Options(),
               cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): Verified {
        val account = WarpProfile.account(rawAccount).toString()
        val reasons=linkedSetOf<String>()
        for(attempt in 1..MAX_SEARCH_ATTEMPTS) {
            checkCancelled(cancelled)
            progress(attempt, MAX_SEARCH_ATTEMPTS)
            checkCancelled(cancelled)
            // Windows broadens the port/subnet search after its quick pass. Repeating
            // the same 2408-only pass three times does not provide equivalent coverage.
            try { return scan(account, backend, tunnelTrace, options.copy(extended=options.extended||attempt>1), cancelled) }
            catch(error: CancellationException) { throw error }
            catch(error: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException("WARP отменён") }
            catch(error: Exception) { checkCancelled(cancelled);reasons.add(if(error is ScanFailure)error.reason else "сканер или модуль не завершил проверку") }
        }
        throw SearchExhausted(options.country != "RU"&&options.country != "any",reasons.joinToString("; "))
    }
    fun register(backend: Backend, options: Options = Options(), cancelled: () -> Boolean = { false }): String {
        checkCancelled(cancelled)
        val response = backend.execute(WarpProfile.request("register", "", options.protocol, options.country, options.excludeDme, options.extended))
        checkCancelled(cancelled); checkResponse(response)
        val account = WarpProfile.account(response.getString("account"))
        require(account.optString("id").isNotBlank() && account.optString("token").isNotBlank()) { "Регистрация WARP не вернула данные аккаунта." }
        return account.toString()
    }
    fun scan(rawAccount: String, backend: Backend, tunnelTrace: TunnelTrace, options: Options = Options(), cancelled: () -> Boolean = { false }): Verified {
        val account = WarpProfile.account(rawAccount).toString()
        checkCancelled(cancelled)
        val response = backend.execute(WarpProfile.request("scan", account, options.protocol, options.country, options.excludeDme, options.extended))
        checkCancelled(cancelled); checkResponse(response,false)
        val candidates = response.getJSONArray("candidates")
        require(candidates.length() in 1..8) { "Сканер WARP не вернул подходящий выход." }
        val rejected=linkedMapOf<String,Int>()
        var triedAwg=false;var triedWg=false
        for(i in 0 until candidates.length()) {
            checkCancelled(cancelled)
            var phase="неподдерживаемая конфигурация"
            try {
                val candidate = candidates.getJSONObject(i)
                val profile = WireGuardProfile.parse(candidate.getString("config"))
                val awg = profile.version != WireGuardProfile.Version.WG
                require(candidate.getString("protocol") == if(awg) "awg" else "wg")
                require(options.protocol == "auto" || options.protocol == if(awg) "awg" else "wg")
                if(awg)triedAwg=true else triedWg=true
                val prepared = WarpProfile.prepare(profile.conf(), account)
                phase="не прошла проверка туннеля через sing-box"
                val rawTrace=tunnelTrace.fetch(prepared.original)
                phase="не подошли страна или узел выхода"
                val trace = WarpProfile.trace(rawTrace, options.country, options.excludeDme)
                checkCancelled(cancelled)
                return Verified(account, WarpProfile.prepare(prepared.original, account, trace), trace)
            } catch(e: CancellationException) { throw e }
            catch(_: InterruptedException) { Thread.currentThread().interrupt();throw CancellationException("WARP отменён") }
            catch(error: Exception) { checkCancelled(cancelled);val reason=if(error is java.util.concurrent.TimeoutException)"тайм-аут проверки туннеля" else phase;rejected[reason]=(rejected[reason] ?: 0)+1 }
        }
        // The native auto scan stops as soon as AWG finds endpoints. If those
        // candidates fail the app-core check, explicitly try WG like Windows does.
        if(options.protocol=="auto"&&triedAwg&&!triedWg) {
            try {return scan(account,backend,tunnelTrace,options.copy(protocol="wg"),cancelled)}
            catch(error:CancellationException){throw error}
            catch(error:InterruptedException){Thread.currentThread().interrupt();throw CancellationException("WARP отменён")}
            catch(error:Exception){checkCancelled(cancelled);val reason=if(error is ScanFailure)error.reason else "WireGuard не прошёл проверку";rejected[reason]=(rejected[reason] ?: 0)+1}
        }
        throw ScanFailure(rejected.entries.joinToString(", "){"${it.key}: ${it.value}"})
    }
    private fun checkResponse(response: JSONObject,cancelledMeansUser:Boolean=true) {
        val error=response.optString("error")
        if(error=="cancelled"&&cancelledMeansUser)throw CancellationException("Подбор WARP отменён.")
        if(error.isNotBlank())throw ScanFailure(when(error){
            "no_endpoints" -> "сканер не нашёл доступных адресов"
            "timeout","cancelled" -> "сканер исчерпал время поиска"
            else -> "модуль сканера завершился с ошибкой"
        })
    }
    private fun checkCancelled(cancelled: () -> Boolean) { if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("WARP отменён") }
}
