package org.mcrf.client

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** TEST ONLY. No Android, storage, provider, network or diagnostic output. */
internal object CsqttOriginalClipboardConversion {
    /** No message, cause or captured stack: only this stable numeric rejection group leaves convert(). */
    class Rejected(val code:Int):RuntimeException(null,null,false,false)
    class Value(val link:String,val numbers:Map<String,Int>) {
        override fun toString()="CsqttOriginalClipboardConversion(redacted)"
    }
    fun convert(raw:JSONObject,preferences:JSONObject):Value {
        var code=1
        try {
        require(Csqtt25Profile.isProfile(raw)&&!CsqttGenerations.automatic(raw))
        code=2 // Explicit implementation.
        val impl=when(raw.optString("csqtt_implementation")) {
            "swg-panel-2.5.2"->252
            "swg-panel-2.5.3"->253
            else->error("UNSUPPORTED_IMPLEMENTATION")
        }
        code=3 // Normalize/validate the source profile.
        val profile=checkNotNull(Csqtt25Profile.fromJson("clipboard-fixture",raw).carrier)
        code=4 // Initial manual hash mode / absent token; no auto-mode expansion.
        require(profile.getString("vk_hash_mode")=="manual"&&profile.optString("vk_token").isEmpty())
        code=5 // Read-only manual supplements and TURN subnet policy.
        val deploy=preferences.optJSONObject(AndroidDeployBackupSettings.SECTION)?:JSONObject()
        val memory=object:VkRelayPersistence {
            override fun get(section:String)=JSONObject((deploy.optJSONObject(section)?:JSONObject()).toString())
            override fun update(section:String,change:(JSONObject)->Unit):Unit=error("WRITE_DISABLED")
            override fun remove(section:String):Unit=error("WRITE_DISABLED")
            override fun verifyCaptcha(url:String,cancelled:()->Boolean):String=error("NETWORK_DISABLED")
        }
        val account=VkRelayAccount(memory,{error("NETWORK_DISABLED")},{error("WAIT_DISABLED")})
        // Same order as NativeCarrier: manual supplements, then the actual global/override/cap policy.
        val merged=VkRelayRuntime.withManualSupplements(profile,account)
        code=6 // Actual global settings / per-server override / effective-count policy.
        val effective=CarrierStreamDefaults.apply(merged,
            preferences.optJSONObject(CarrierStreamDefaults.SECTION)?:JSONObject())
        code=7 // Effective manual hash mode / absent token.
        require(effective.getString("vk_hash_mode")=="manual"&&effective.optString("vk_token").isEmpty())
        code=8 // Default SRTP / obfuscation modes.
        require(Csqtt25Profile.srtpMode(effective)=="auto"&&effective.optString("obfs","unified")=="unified")
        code=9 // Default TURN transport / captcha modes.
        require(effective.optString("turn_transport","udp")=="udp"&&effective.optString("captcha_mode","auto")=="auto")
        code=10 // Default VK auth mode.
        require(effective.optString("vk_auth_mode","vkcalls")=="vkcalls")
        code=11 // Custom TURN IPs cannot be carried by this URI.
        require((effective.optJSONArray("custom_turn_ips")?.length()?:0)==0)
        code=12 // Custom identity fields cannot be carried by this URI.
        require(listOf("client_ids","salt","device_id","generation").none {effective.has(it)})
        code=13 // Known fingerprint mapping, not an observed original-app setting.
        val fingerprint=when(effective.optString("fingerprint","chrome")) {"chrome"->1;"firefox"->2;else->error("FINGERPRINT_UNMAPPED")}
        code=14 // Nonempty raw pool; native, not the stored array, has the six-hash cap.
        val rawHashes=effective.getJSONArray("hashes").let {a->(0 until a.length()).map(a::getString)}
        require(rawHashes.isNotEmpty())
        code=15 // Validate ALL raw entries before native projection, including the unused tail.
        require(rawHashes.distinct().size==rawHashes.size&&rawHashes.all {it.length>=16&&it.none(Char::isWhitespace)})
        // Same normalization/validation boundary as Csqtt25Profile.privateConfig before Go.
        val validated=checkNotNull(Csqtt25Profile.fromJson("clipboard-native",effective).carrier)
            .getJSONArray("hashes").let {a->(0 until a.length()).map(a::getString)}
        require(validated.all {it.length in 16..4096&&it.none(Char::isWhitespace)&&',' !in it})
        code=19 // Proven installed-bound Rust normalization, stable dedup, then first six.
        val hashes=nativeHashes(validated)
        require(hashes.size in 1..6&&hashes.all {it.length>=16&&it.none(Char::isWhitespace)})
        require(nativeHashes(hashes)==hashes) // Exporting these preserves the exact native order/pool.
        code=16 // Importable password.
        val password=effective.getString("password")
        require(password.isNotEmpty()&&password.none(Char::isWhitespace))
        code=17 // Importable effective worker count.
        val workers=effective.getInt("workers");require(workers in 3..100)
        code=18 // URI encoding / bounds.
        fun enc(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
        // Csqtt25Profile.encryptedLink / CsqttLinkQuery: split raw '+' separators BEFORE decoding each hash.
        val link="csqtt://connect?v=2&host=${enc(effective.getString("server"))}"+
            "&peer=${effective.getInt("server_port")}&password=${enc(password)}"+
            "&hashes=${hashes.joinToString("+"){enc(it)}}&workers=$workers"
        require(link.toByteArray(Charsets.UTF_8).size<=65536)
        return Value(link,linkedMapOf("workers" to workers,"hash_count" to hashes.size,
            "raw_hash_count" to rawHashes.size,"native_hash_count" to hashes.size,
            "trimmed_count" to rawHashes.size-hashes.size,"implementation" to impl,
            "hash_mode" to 0,"srtp_mode" to 0,"obfs_mode" to 0,"turn_mode" to 0,"captcha_mode" to 0,
            "fingerprint" to fingerprint,"original_fingerprint_assumed" to 2,
            "fingerprint_mismatch" to if(fingerprint==2)0 else 1,
            "redistribution" to if(effective.optBoolean("allow_hash_redistribution"))1 else 0))
        }catch(_:Exception){throw Rejected(code)}
    }

    /** Installed-bound BS4 profile.go:217 joins unchanged; g253 worker.rs:1284 + main.rs:263.
     * parse_hashes splits these six delimiters (NOT '+'), normalizes, stable-dedups, then take(6).
     * This is a test-only projection: it neither edits the saved pool nor changes native behavior. */
    private fun nativeHashes(hashes:List<String>):List<String> {
        fun rustSpace(c:Char)=c in '\u0009'..'\u000d'||c==' '||c=='\u0085'||c=='\u00a0'||c=='\u1680'||
            c in '\u2000'..'\u200a'||c=='\u2028'||c=='\u2029'||c=='\u202f'||c=='\u205f'||c=='\u3000'
        val seen=linkedSetOf<String>()
        for(token in hashes.joinToString(",").split(',', ';', '\n', '\r', '\t', ' ')) {
            var value=token.trim(::rustSpace).trim {it=='<'||it=='>'||it=='"'||it=='\''}
            if(value.isEmpty())continue
            val lower=value.map {if(it in 'A'..'Z')it+32 else it}.joinToString("")
            val join=lower.indexOf("/call/join/")
            if(join>=0)value=value.substring(join+"/call/join/".length)
            else if(lower.startsWith("http://")||lower.startsWith("https://"))continue
            val cut=value.indexOfFirst {it=='?'||it=='#'||it=='/'}
            if(cut>=0)value=value.substring(0,cut)
            value=value.trim(::rustSpace).trim('/')
            if(value.isNotEmpty())seen.add(value)
        }
        return seen.take(6)
    }

    /** Synthetic checks run before looking at the user's clipboard. Returns a count only. */
    fun checks():Int {
        val first="AbcdEFGH01234567+XYZ=="
        val second="zyxwvutsrqponmlk_01234567"
        fun raw()=JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
            .put("csqtt_implementation","swg-panel-2.5.3").put("server","fixture.invalid").put("server_port",46000)
            .put("password","fixture+&/?=пароль").put("hashes",JSONArray().put(first)).put("vk_hash_mode","manual")
        val settings=JSONObject().put(CarrierStreamDefaults.SECTION,JSONObject().put("csqtt25",80))
            .put(AndroidDeployBackupSettings.SECTION,JSONObject().put("vkSettings",JSONObject()
                .put("manualSupplementAll",true).put("manual",JSONArray().put(second))))
        val original=raw();val before=original.toString();val prefsBefore=settings.toString()
        val converted=convert(original,settings)
        check(original.toString()==before&&settings.toString()==prefsBefore)
        val query=CsqttLinkQuery.parse(java.net.URI(converted.link).rawQuery)
        fun dec(value:String)=java.net.URLDecoder.decode(value.replace("+","%2B"),"UTF-8")
        check(dec(query.getValue("password"))==original.getString("password"))
        check(query.getValue("hashes").split('+').map(::dec)==listOf(first,second))
        check(converted.numbers.getValue("workers")==80&&converted.numbers.getValue("hash_count")==2)
        check(converted.numbers.getValue("fingerprint_mismatch")==1)
        check(!converted.toString().contains("csqtt://"))
        check(convert(raw().put(CarrierStreamDefaults.OVERRIDE,30),settings).numbers.getValue("workers")==30)
        check(convert(raw().put("csqtt_implementation","swg-panel-2.5.2"),settings).numbers.getValue("implementation")==252)
        val tooMany=JSONArray((0..6).map {"fixture-call-0123456789-$it"})
        fun rejection(input:JSONObject,prefs:JSONObject=settings):Int {
            val failure=runCatching {convert(input,prefs)}.exceptionOrNull()
            check(failure is Rejected&&failure.message==null&&failure.cause==null&&failure.stackTrace.isEmpty())
            return failure.code
        }
        val manyRaw=raw().put("hashes",tooMany);val manyBefore=manyRaw.toString()
        val many=convert(manyRaw,JSONObject())
        val manyQuery=CsqttLinkQuery.parse(java.net.URI(many.link).rawQuery)
        check(many.numbers.getValue("raw_hash_count")==7&&many.numbers.getValue("native_hash_count")==6&&
            many.numbers.getValue("trimmed_count")==1&&manyQuery.getValue("hashes").split('+').map(::dec)==
            (0..5).map {"fixture-call-0123456789-$it"}&&manyRaw.toString()==manyBefore)
        var rejected=0
        val expected=listOf(2,2,1,3,3,8,9,8,16,16)
        for((index,change) in listOf<(JSONObject)->Unit>(
            {it.remove("csqtt_implementation")},{it.put("csqtt_implementation","compatible-2.5")},
            {it.put("csqtt_generation","legacy")},{it.put("vk_hash_mode","auto_js")},
            {it.put("vk_token","fixture-token")},{it.put("srtp_mode","wrap-srtp")},
            {it.put("turn_transport","tcp")},{it.put("obfs","audio")},
            {it.put("password","fixture with space")},{it.put("password"," fixture")}).withIndex()) {
            check(rejection(raw().also(change))==expected[index]);rejected++
        }
        check(rejection(raw().put("vk_hash_mode","auto_js").put("vk_token","fixture-token").put("hashes",JSONArray()))==4)
        check(rejection(raw().put("device_id","fixture-device"))==12)
        check(rejection(raw(),JSONObject().put(CarrierStreamDefaults.SECTION,JSONObject().put("csqtt25",11)))==6)
        check(rejection(raw().put("hashes",JSONArray((0..5).map {"fixture-call-0123456789-$it"}).put("bad hash")),JSONObject())==3)
        check(nativeHashes(listOf("<https://VK.RU/CALL/JOIN/AbcdEFGH01234567?x=1>","AbcdEFGH01234567",
            "abcdefgh01234567;AbcdEFGH01234567",first,"https://else.invalid/a"))==
            listOf("AbcdEFGH01234567","abcdefgh01234567",first))
        check(nativeHashes(listOf("\u0085'AbcdEFGH01234567'\u0085", "AbcdEFGH01234567/tail",first))==
            listOf("AbcdEFGH01234567",first))
        return 15+rejected
    }
}
