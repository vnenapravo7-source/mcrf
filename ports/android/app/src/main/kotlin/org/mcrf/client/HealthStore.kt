package org.mcrf.client

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom

/** App-private, no-backup binary store. All methods serialize consent, budget and result publication.
 * Only salted server identifiers, numeric measurements and closed enums enter the file.
 * A failed/corrupt store fails closed; it never creates fresh probe allowance. */
class HealthStore(
    private val file: File? = null,
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Record(
        val since: Long,
        var sessions: Long = 0,
        var uptime: Long = 0,
        val successes: LongArray = LongArray(3),
        val errors: LongArray = LongArray(3),
        var reason: HealthDisconnect? = null,
        var network: HealthNetwork = HealthNetwork.UNKNOWN,
        var engine: HealthEngine = HealthEngine.UNKNOWN,
        var eventAt: Long? = null,
        var session: Long = 0,
        var connectedAt: Long? = null,
        var disconnected: Boolean = false,
        val samples: MutableList<HealthSample> = ArrayList()
    )
    class Permit internal constructor(internal val owner: HealthStore, internal val generation: Long) {
        internal var completed = false
    }
    private var salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val records = LinkedHashMap<String, Record>()
    private var consent = false
    private var healthy = true
    private var generation = 0L
    private var budgetWall = wall()
    private var budgetElapsed = elapsed()
    private var used = 0L
    private var lastProbeWall: Long? = null
    private var lastProbeElapsed: Long? = null

    init {
        if (file?.exists() == true) try { load(file) } catch (_: Exception) {
            healthy = false; consent = false; records.clear()
        }
    }

    @Synchronized fun settings() = HealthSettings(consent && healthy)
    @Synchronized fun usedBytes(): Long = used

    /** Turning off erases history immediately but preserves charged allowance and cooldown. */
    @Synchronized fun setEnabled(enabled: Boolean): Boolean {
        if (!healthy) return false
        generation++
        consent = enabled
        if (!enabled) records.clear()
        return persist()
    }

    /** Reserve the whole batch durably BEFORE opening any socket; never refund cancellation. */
    @Synchronized fun reserve(): Permit? {
        if (!settings().enabled) return null
        val now = wall(); val tick = elapsed()
        if (tick < budgetElapsed) { // reboot/clock discontinuity: retain usage for another full day
            budgetElapsed = tick; budgetWall = maxOf(now, budgetWall)
            lastProbeElapsed = tick; lastProbeWall = maxOf(now, lastProbeWall ?: now)
            if (!persist()) return null
        }
        if (now >= budgetWall && now - budgetWall >= HealthSettings.DAY_MS &&
            tick - budgetElapsed >= HealthSettings.DAY_MS) {
            budgetWall = now; budgetElapsed = tick; used = 0
        }
        if (now < budgetWall || lastProbeWall?.let { now < it || now - it < HealthSettings.INTERVAL_MS } == true ||
            lastProbeElapsed?.let { tick < it || tick - it < HealthSettings.INTERVAL_MS } == true) return null
        val charge = HealthSettings.ATTEMPTS * HealthSettings.ATTEMPT_BYTES
        if (used > HealthSettings.DAILY_BYTES - charge) return null
        used += charge; lastProbeWall = now; lastProbeElapsed = tick
        return if (persist()) Permit(this, generation) else null
    }

    @Synchronized fun accepts(permit: Permit) = settings().enabled && permit.owner === this &&
        permit.generation == generation && !permit.completed

    @Synchronized fun recordSample(serverId: String, permit: Permit, pingMs: Long?, failures: Int,
                                   network: HealthNetwork): Boolean {
        if (!accepts(permit)) return false
        require(failures in 0..HealthSettings.ATTEMPTS)
        require(if (failures == HealthSettings.ATTEMPTS) pingMs == null else pingMs != null && pingMs in 0..HealthSettings.TIMEOUT_MS.toLong())
        permit.completed = true
        prune()
        record(serverId).samples.add(HealthSample(wall(), pingMs, HealthSettings.ATTEMPTS, failures, network))
        prune()
        return persist()
    }

    /** Public event ingestion boundary for the later lifecycle slice; never starts any work. */
    @Synchronized fun ingest(serverId: String, event: HealthEvent): Boolean {
        if (!settings().enabled) return false
        require(event.session > 0)
        prune()
        val key = key(serverId)
        val r = records[key]
        if (event.kind != HealthEventKind.SESSION_STARTED && (r == null || r.session != event.session || r.disconnected)) return false
        val target = r ?: record(serverId)
        if (event.kind == HealthEventKind.SESSION_STARTED) {
            if (event.session <= target.session) return false
            accrue(target)
            target.session = event.session; target.connectedAt = null; target.disconnected = false
            target.sessions = increment(target.sessions)
        } else when (event.kind) {
            HealthEventKind.CONNECTED -> {
                if (target.connectedAt != null) return false
                target.connectedAt = elapsed()
                target.successes[HealthDimension.ENGINE.ordinal] = increment(target.successes[HealthDimension.ENGINE.ordinal])
            }
            HealthEventKind.DISCONNECTED -> {
                accrue(target); target.connectedAt = null; target.disconnected = true; target.reason = event.reason
            }
            HealthEventKind.ERROR -> target.errors[event.dimension.ordinal] = increment(target.errors[event.dimension.ordinal])
            HealthEventKind.RECOVERED -> target.successes[event.dimension.ordinal] = increment(target.successes[event.dimension.ordinal])
            else -> Unit
        }
        target.network = event.network; target.engine = event.engine; target.eventAt = wall()
        return persist()
    }

    @Synchronized fun snapshot(serverId: String): HealthSnapshot {
        if (!settings().enabled) return HealthSnapshot()
        if (prune()) persist()
        val r = records[key(serverId)] ?: return HealthSnapshot()
        val extra = r.connectedAt?.let { (elapsed() - it).coerceIn(0, HealthSettings.RETENTION_MS) } ?: 0
        return HealthSnapshot(r.samples.toList(), r.sessions, (r.uptime + extra).coerceAtMost(HealthSettings.RETENTION_MS),
            r.successes.toList(), r.errors.toList(), r.reason, r.samples.lastOrNull()?.network ?: r.network, r.engine, r.eventAt)
    }

    private fun increment(value: Long) = (value + 1).coerceAtMost(1_000_000_000L)
    private fun accrue(r: Record) {
        r.connectedAt?.let { r.uptime = (r.uptime + (elapsed() - it).coerceIn(0, HealthSettings.RETENTION_MS)).coerceAtMost(HealthSettings.RETENTION_MS) }
    }
    private fun key(id: String): String {
        require(id.length in 1..1024)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun record(id: String): Record = records.getOrPut(key(id)) {
        if (records.size >= HealthSettings.MAX_SERVERS) records.remove(records.keys.first())
        Record(wall())
    }
    private fun prune(): Boolean {
        val now = wall()
        var changed = records.entries.removeAll { now < it.value.since || now - it.value.since >= HealthSettings.RETENTION_MS }
        records.values.forEach { r -> changed = r.samples.removeAll { now < it.at || now - it.at >= HealthSettings.RETENTION_MS } || changed }
        while (records.values.sumOf { it.samples.size } > HealthSettings.MAX_SAMPLES) {
            records.values.filter { it.samples.isNotEmpty() }.minBy { it.samples.first().at }.samples.removeAt(0)
            changed = true
        }
        return changed
    }

    private fun persist(): Boolean {
        if (!healthy) return false
        val output = file ?: return true
        return try {
            val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.write(salt); out.writeBoolean(consent)
                out.writeLong(budgetWall); out.writeLong(budgetElapsed); out.writeLong(used)
                out.writeLong(lastProbeWall ?: -1); out.writeLong(lastProbeElapsed ?: -1)
                out.writeInt(records.size)
                records.forEach { (key, r) ->
                    out.writeUTF(key); out.writeLong(r.since); out.writeLong(r.sessions); out.writeLong(r.uptime)
                    r.successes.forEach(out::writeLong); r.errors.forEach(out::writeLong)
                    out.writeInt(r.reason?.ordinal ?: -1); out.writeInt(r.network.ordinal); out.writeInt(r.engine.ordinal)
                    out.writeLong(r.eventAt ?: -1); out.writeInt(r.samples.size)
                    r.samples.forEach { s -> out.writeLong(s.at); out.writeLong(s.pingMs ?: -1); out.writeInt(s.attempts); out.writeInt(s.failures); out.writeInt(s.network.ordinal) }
                }
            } }.toByteArray()
            require(bytes.size <= HealthSettings.MAX_STORAGE_BYTES)
            output.parentFile?.mkdirs()
            val temp = File(output.path + ".tmp")
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: Exception) { healthy = false; consent = false; records.clear(); generation++; false }
    }

    private fun load(input: File) {
        require(input.length() in 1..HealthSettings.MAX_STORAGE_BYTES.toLong())
        DataInputStream(ByteArrayInputStream(input.readBytes())).use { data ->
            require(data.readInt() == 1); data.readFully(salt); consent = data.readBoolean()
            budgetWall = data.readLong(); budgetElapsed = data.readLong(); used = data.readLong()
            require(budgetWall >= 0 && budgetElapsed >= 0 && used in 0..HealthSettings.DAILY_BYTES)
            lastProbeWall = data.readLong().takeIf { it >= 0 }; lastProbeElapsed = data.readLong().takeIf { it >= 0 }
            val count = data.readInt(); require(count in 0..HealthSettings.MAX_SERVERS)
            var samples = 0
            repeat(count) {
                val key = data.readUTF(); require(key.matches(Regex("[0-9a-f]{64}")) && key !in records)
                val r = Record(data.readLong(), data.readLong(), data.readLong())
                require(r.since >= 0 && r.sessions in 0..1_000_000_000L && r.uptime in 0..HealthSettings.RETENTION_MS)
                r.successes.indices.forEach { r.successes[it] = data.readLong().also { n -> require(n in 0..1_000_000_000L) } }
                r.errors.indices.forEach { r.errors[it] = data.readLong().also { n -> require(n in 0..1_000_000_000L) } }
                val reason = data.readInt(); require(reason in -1 until HealthDisconnect.entries.size)
                r.reason = HealthDisconnect.entries.getOrNull(reason)
                r.network = HealthNetwork.entries[data.readInt()]; r.engine = HealthEngine.entries[data.readInt()]
                r.eventAt = data.readLong().takeIf { it >= 0 }
                val n = data.readInt(); require(n in 0..HealthSettings.MAX_SAMPLES)
                samples += n; require(samples <= HealthSettings.MAX_SAMPLES)
                repeat(n) {
                    val at = data.readLong(); val ping = data.readLong(); val attempts = data.readInt(); val failures = data.readInt()
                    val network = HealthNetwork.entries[data.readInt()]
                    require(at >= 0 && attempts == HealthSettings.ATTEMPTS && failures in 0..attempts)
                    require(if (failures == attempts) ping == -1L else ping in 0..HealthSettings.TIMEOUT_MS.toLong())
                    r.samples.add(HealthSample(at, ping.takeIf { it >= 0 }, attempts, failures, network))
                }
                records[key] = r
            }
            require(data.available() == 0)
            if (!consent) records.clear()
            if (prune()) persist()
        }
    }
}
