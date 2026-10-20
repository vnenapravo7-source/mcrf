package org.mcrf.client

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Private in-memory challenge registry: URLs/proofs never enter saved state or logs. */
object CarrierChallenge {
    class Request(val nativeId: String, val url: String, val reply: (String) -> Unit, val retired:(String)->Unit={},val timeoutMillis:Long=150000,val automatic:Boolean=false,val expired:()->Unit={}) {
        val id=UUID.randomUUID().toString()
        @Volatile var opened=false
        val created=System.nanoTime()
        private var expiryTask:java.util.concurrent.ScheduledFuture<*>?=null
        private var expiryRetired=false
        @Synchronized internal fun attachExpiry(task:java.util.concurrent.ScheduledFuture<*>) {
            // Reply/STOP may remove a published request before schedule() returns.
            if(expiryRetired)task.cancel(false) else expiryTask=task
        }
        @Synchronized internal fun cancelExpiry() {
            expiryRetired=true
            expiryTask?.cancel(false)
            expiryTask=null
        }
        fun remainingMillis(now:Long=System.nanoTime()):Long=(timeoutMillis-(now-created)/1_000_000L).coerceAtLeast(0)
    }
    private val requests=ConcurrentHashMap<String,Request>()
    private val delivery=CarrierChallengeDelivery()
    private val expiryWorker=java.util.concurrent.ScheduledThreadPoolExecutor(1,{task->
        Thread(task,"mcrf-captcha-expiry").apply {isDaemon=true}
    }).apply {setRemoveOnCancelPolicy(true)}
    private var browserOwner:String?=null
    /** One live manual browser across notification taps and activity instances. */
    @Synchronized fun claimBrowser(id:String):Boolean {
        expire()
        if(get(id)==null)return false
        if(browserOwner!=null)return false
        browserOwner=id
        return true
    }
    @Synchronized fun releaseBrowser(id:String) {if(browserOwner==id)browserOwner=null}
    internal fun expire(now:Long=System.nanoTime()) {
        requests.values.forEach {request->
            if(now-request.created>request.timeoutMillis*1_000_000L&&requests.remove(request.id,request))expired(request)
        }
    }
    fun allowed(url: String): Boolean = try {
        val uri=URI(url);url.length<=8192 && uri.scheme.equals("https",true) && uri.userInfo==null && uri.port in setOf(-1,443) &&
            uri.host?.lowercase() in setOf("vk.ru","vk.com","api.vk.ru","api.vk.com","id.vk.ru","id.vk.com","m.vk.ru","m.vk.com","oauth.vk.com","oauth.vk.ru","login.vk.ru","login.vk.com","www.vk.ru","www.vk.com")
    } catch(_:Exception){false}
    /** Handles both qThrone's 4-field and WDTT Plus's request-id 5-field IPC. */
    data class Envelope(val nativeId:String,val mode:String,val url:String,val flightKey:String) {
        override fun toString()="CaptchaEnvelope(redacted)"
    }
    fun fields(line:String):Pair<String,String>?=envelope(line)?.let {it.nativeId to it.url}
    fun envelope(line: String,strictSwg:Boolean=false): Envelope? {
        if(!line.startsWith("CAPTCHA_SOLVE|")||line.length>32768) return null
        val fields=line.split('|',limit=6)
        val id:String;val url:String;val mode:String
        when(fields.size){4->{id="";mode=fields[1];url=fields[2]};5->{id=fields[1];mode=fields[2];url=fields[3]};else->return null}
        if(id.length>128||id.any { it.isISOControl()||it=='|' }||!allowed(url)) return null
        if(strictSwg&&(mode !in setOf("auto","manual","selected")||fields.last().isBlank()||fields.last().any {it.isISOControl()}))return null
        // ID-bearing protocols identify one waiter by ID. Legacy CSQTT has no
        // ID: hash the complete IPC envelope so a fresh session at the same URL
        // is not mistaken for a duplicate. Never retain the session proof itself.
        val key=if(id.isNotEmpty())"id:$id" else "digest:"+java.security.MessageDigest.getInstance("SHA-256")
            .digest(line.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        return Envelope(id,mode,url,key)
    }
    /** Leave one second for IPC delivery before the pinned native automatic waiter expires. */
    fun nativeTimeoutMillis(kind:String,mode:String,nativeId:String=""):Long {
        // New QWDTT waiter IDs denote the adapter retaining upstream10/60/120s.
        // Do not impose a changed native contract on installed untagged modules.
        val originalBudgets=kind=="csqtt"||kind=="qwdtt"&&nativeId.matches(Regex("[0-9a-f]{32}"))
        return when {
            mode=="auto"&&kind=="wdtt"->24000
            mode=="auto"&&originalBudgets->9000
            mode=="manual"&&originalBudgets->59000
            mode=="selected"&&originalBudgets->119000
            else->150000
        }
    }
    fun register(nativeId: String,url: String,reply:(String)->Unit):String {
        return register(nativeId,url,reply,{})
    }
    fun register(nativeId:String,url:String,reply:(String)->Unit,retired:(String)->Unit):String {
        require(allowed(url));expire();val request=Request(nativeId,url,reply,retired);requests[request.id]=request;return request.id
    }
    /** Bounded admission for SWG/manual REST; legacy providers keep their existing path. */
    @Synchronized fun registerBounded(nativeId:String,url:String,reply:(String)->Unit,retired:(String)->Unit={},timeoutMillis:Long=150000,automatic:Boolean=false,expired:()->Unit={}):String? {
        require(timeoutMillis in 1..150000)
        require(allowed(url));expire()
        if(requests.size>=32)return null
        val request=Request(nativeId,url,reply,retired,timeoutMillis,automatic,expired)
        requests[request.id]=request
        // Notifications can expire while MCRF is backgrounded and no Activity
        // polls get(). A bounded timer must still retire the exact native waiter.
        request.attachExpiry(expiryWorker.schedule({get(request.id)},timeoutMillis+1,java.util.concurrent.TimeUnit.MILLISECONDS))
        return request.id
    }
    fun get(id:String):Request? {
        val request=requests[id]?:return null
        if(System.nanoTime()-request.created<=request.timeoutMillis*1_000_000L)return request
        if(requests.remove(id,request))expired(request);return null
    }
    fun unopened():Request? {
        expire()
        return requests.values.filter { !it.opened&&!it.automatic }.minByOrNull {it.created}
    }
    fun pendingManual():Request? {
        expire()
        return requests.values.filter { !it.automatic }.minByOrNull {it.created}
    }
    fun isManualPending(id:String):Boolean=get(id)?.automatic==false
    fun respond(id:String,value:String) {
        if(value.length !in 1..16384 || value.any { it.isISOControl() || it=='|' }) return
        val request=requests.remove(id)?:return
        retire(request)
        if(System.nanoTime()-request.created<=request.timeoutMillis*1_000_000L)request.reply(value)
    }
    /** Re-check expiry/exact request on the worker; rejection leaves the request available. */
    fun respondAsync(id:String,value:String):Boolean {
        if(value.length !in 1..16384 || value.any {it.isISOControl()||it=='|'})return false
        if(get(id)==null)return true
        return delivery.offer {respond(id,value)}
    }
    private fun retire(request:Request){request.cancelExpiry();releaseBrowser(request.id);runCatching {request.retired(request.id)}}
    private fun expired(request:Request){retire(request);runCatching {request.expired()}}
    /** Cancellation only, after host expiry. Never send an untagged fallback that
     * could accidentally answer another native waiter (notably unmodified2.5). */
    fun cancelExpiredScopedAsync(kind:String,nativeId:String,reply:(String)->Unit):Boolean {
        if(!scopedExpirySupported(kind,nativeId))return false
        return delivery.offer {reply("error:timeout")}
    }
    internal fun scopedExpirySupported(kind:String,id:String):Boolean=when(kind) {
        "wdtt"->id.matches(Regex("[0-9]{1,10}-[0-9]{1,20}"))
        "qwdtt"->id.matches(Regex("[0-9a-f]{32}"))
        "csqtt"->id.matches(Regex("[0-9a-f]{48}"))
        else->false
    }
    fun remove(id:String){requests.remove(id)?.let(::retire)}
    fun command(nativeId:String,value:String)="CAPTCHA_RESULT|"+(if(nativeId.isNotEmpty()) "$nativeId|" else "")+value+"\n"
}
