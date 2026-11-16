package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Durable one-time cohort, kept in the SAME encrypted transaction as server overlays.
 * A target identifies a compatible client implementation, NOT an exact remote patch.
 * Neither READY nor SOCKS authentication is remote evidence. */
internal object CsqttEngineMigration {
    const val KEY="csqttUnifiedMigration"
    const val SCHEMA=1
    const val SHORT_RETRY_BURST=3
    const val DEFER_MS=15*60_000L
    private val delays=listOf(DEFER_MS,6*60*60_000L,24*60*60_000L)
    data class Ticket(val id:String,val fingerprint:String,val epoch:String,val generation:String,
        val replacement:String,val attempt:Long,val profile:JSONObject)
    data class Evidence(val target:CsqttAutoDetection.Target,val effectiveImplementation:String,
        val remoteHttpsResponse:Boolean)

    internal fun canonical(value:Any?):String=when(value) {
        is JSONObject->value.keys().asSequence().sorted().joinToString(",","{","}") {JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]") {canonical(value.get(it))}
        null,JSONObject.NULL->"null"
        is String->JSONObject.quote(value)
        is Number->JSONObject.numberToString(value)
        is Boolean->value.toString()
        else->throw IllegalArgumentException("Unsupported CSQTT migration value")
    }
    fun equivalent(left:Any?,right:Any?)=canonical(left)==canonical(right)
    fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    /** Includes membership, raw overlays, names/options/sections; health-only updates
     * are harmless. Reads ALL physical occurrences before entries() deduplication. */
    private fun fingerprints(catalogue:ConnectionCatalogue,ids:Set<String>?=null):Map<String,String> {
        val entries=catalogue.entries().filter {it.carrier?.optString("type")=="csqtt"&&(ids==null||it.id in ids)}
        if(entries.isEmpty())return emptyMap()
        val occurrences=entries.associate {it.id to JSONArray()}
        val raw=JSONObject(catalogue.serialize())
        fun append(links:JSONArray,owner:String) {
            repeat(links.length()) {index->val link=links.getString(index)
                ServerImport.read(link).map {it.id}.distinct().forEach {id->
                    occurrences[id]?.put(JSONObject().put("owner",owner).put("source",link))
                }
            }
        }
        append(raw.getJSONArray("servers"),"manual")
        val subscriptions=raw.getJSONArray("subscriptions")
        repeat(subscriptions.length()) {index->val sub=subscriptions.getJSONObject(index);append(sub.getJSONArray("servers"),sub.getString("id"))}
        return entries.associate {entry->entry.id to digest(canonical(JSONObject()
            .put("entry",ConnectionEdits.snapshot(entry)).put("occurrences",occurrences.getValue(entry.id))
            .put("overlay",raw.optJSONObject("edits")?.opt(entry.id))
            .put("sections",raw.optJSONObject(ServerSectionChoice.KEY)?.opt(entry.id))))}
    }
    fun fingerprint(catalogue:ConnectionCatalogue,id:String):String?=fingerprints(catalogue,setOf(id))[id]
    fun initialize(catalogue:ConnectionCatalogue,replacement:String,epoch:String):Boolean {
        if(catalogue.csqttMigrationState()!=null)return false
        require(replacement.isNotBlank()&&epoch.isNotBlank())
        val rows=JSONObject()
        fingerprints(catalogue).forEach {(id,fingerprint)->
            rows.put(id,JSONObject().put("fingerprint",fingerprint)
                .put("attempts",0).put("nextAt",0L).put("status","pending"))
        }
        catalogue.csqttMigrationState(JSONObject().put("schema",SCHEMA).put("epoch",epoch).put("replacement",replacement).put("rows",rows))
        return true
    }
    private fun state(catalogue:ConnectionCatalogue)=catalogue.csqttMigrationState()?.also {require(it.getInt("schema")==SCHEMA)}
    /** Called for ordinary transactions too: an edit/delete/re-add ABA cannot
     * revive an old in-flight ticket even when its final carrier hash is equal. */
    fun invalidateChanged(before:ConnectionCatalogue,after:ConnectionCatalogue) {
        val journal=state(after)?:return
        val rows=journal.getJSONObject("rows")
        val pending=rows.keys().asSequence().filter {rows.getJSONObject(it).getString("status")=="pending"}.toSet()
        val previous=fingerprints(before,pending);val current=fingerprints(after,pending)
        pending.forEach {id->
            if(previous[id]!=current[id])rows.getJSONObject(id).put("status","stale")
        }
        after.csqttMigrationState(journal)
    }
    fun claim(catalogue:ConnectionCatalogue,generation:String,replacement:String,now:Long):Ticket? {
        val journal=state(catalogue)?:return null
        val rows=journal.getJSONObject("rows")
        val due=rows.keys().asSequence().filter {id->val row=rows.getJSONObject(id)
            row.getString("status")=="pending"&&row.getLong("nextAt")<=now}.toSet()
        val current=fingerprints(catalogue,due)
        var ticket:Ticket?=null
        for(id in due.sorted()) {
            val row=rows.getJSONObject(id)
            if(current[id]!=row.getString("fingerprint")){row.put("status","stale");continue}
            val attempts=row.getLong("attempts")
            require(attempts in 0 until Long.MAX_VALUE)
            val profile=catalogue.entries().single {it.id==id}.carrier!!
            // Bound retries by time, not by permanently abandoning an offline
            // server. After the short burst each retry waits a full day. The
            // monotonically increasing attempt is also the stale-result token.
            val delay=delays[attempts.coerceAtMost(delays.lastIndex.toLong()).toInt()]
            row.put("attempts",attempts+1).put("nextAt",now+delay)
            ticket=Ticket(id,row.getString("fingerprint"),journal.getString("epoch"),generation,replacement,attempts+1,JSONObject(profile.toString()))
            break
        }
        catalogue.csqttMigrationState(journal)
        return ticket
    }
    fun accepts(catalogue:ConnectionCatalogue,ticket:Ticket,generation:String,replacement:String):Boolean {
        val journal=state(catalogue)?:return false
        val row=journal.getJSONObject("rows").optJSONObject(ticket.id)?:return false
        return ticket.generation==generation&&ticket.replacement==replacement&&ticket.epoch==journal.getString("epoch")&&
            row.getString("status")=="pending"&&row.getLong("attempts")==ticket.attempt&&
            ticket.fingerprint==row.getString("fingerprint")&&fingerprint(catalogue,ticket.id)==ticket.fingerprint
    }
    /** Mutation touches the shared ID overlay only, applying to every physical
     * occurrence without rewriting original links, subscriptions or route refs. */
    fun complete(catalogue:ConnectionCatalogue,ticket:Ticket,generation:String,replacement:String,evidence:Evidence?):Boolean {
        if(!accepts(catalogue,ticket,generation,replacement))return false
        val journal=state(catalogue)!!;val row=journal.getJSONObject("rows").getJSONObject(ticket.id)
        if(evidence==null||!evidence.remoteHttpsResponse) {
            row.put("reason","unconfirmed")
            catalogue.csqttMigrationState(journal);return false
        }
        val target=evidence.target
        require(target in CsqttAutoDetection.order)
        require(evidence.effectiveImplementation==if(target.generation=="2.5")target.implementation else target.generation)
        val entry=catalogue.entries().single {it.id==ticket.id}
        val carrier=CsqttGenerations.migrationProfile(entry.carrier!!,target)
        catalogue.edit(entry.id,ConnectionEdits.snapshot(entry).put("carrier",carrier))
        row.put("status","confirmed").put("target",target.token).put("effectiveImplementation",evidence.effectiveImplementation)
            .put("proof","authenticated-socks-tls-http-response").put("replacement",replacement)
        catalogue.csqttMigrationState(journal)
        return true
    }
    fun nextAt(catalogue:ConnectionCatalogue):Long? {
        val rows=state(catalogue)?.getJSONObject("rows")?:return null
        return rows.keys().asSequence().map {rows.getJSONObject(it)}.filter {it.getString("status")=="pending"}.map {it.getLong("nextAt")}.minOrNull()
    }
    /** Fresh newest-first migration plan; the ordinary pinned/cache plan is untouched. */
    fun probe(profile:JSONObject,cancelled:()->Boolean,attempt:(JSONObject,CsqttAutoDetection.Target)->Evidence):Evidence? {
        for(target in CsqttAutoDetection.order) {
            if(cancelled()||Thread.currentThread().isInterrupted)return null
            // A newer family may have no lossless representation for this
            // profile's options. Skip that candidate, still probe its own family.
            val candidate=try {CsqttGenerations.migrationProfile(profile,target)}catch(_:Exception){continue}
            candidate.remove(CsqttGenerations.SELECTION);candidate.remove(CsqttGenerations.EPOCH)
            val proof=try {attempt(candidate,target)}catch(error:CsqttNativeFailure.Rejected) {
                if(error.kind==CsqttNativeFailure.Kind.PROTOCOL&&!cancelled()&&!Thread.currentThread().isInterrupted)continue
                return null
            }catch(_:Exception){return null}
            if(cancelled()||Thread.currentThread().isInterrupted||proof.target!=target||!proof.remoteHttpsResponse||
                proof.effectiveImplementation!=if(target.generation=="2.5")target.implementation else target.generation)return null
            return proof
        }
        return null
    }
}
