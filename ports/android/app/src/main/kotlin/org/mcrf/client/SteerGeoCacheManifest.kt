package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.steerprep.SteerGeoProjection
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** SOURCE ONLY low-level file pinning under origin-bound SteerGeoPreparation.
 * No generic Context entry that could skip origin checking. No creation/fetch.
 */
internal object SteerGeoCacheManifest {
    /** Internal file boundary shared with queued temporary-directory fixtures.
     * Production origin capture supplies only the actual same-UID filesDir.
     */
    internal fun captureFiles(filesDir: Path, plan: SteerGeoProjection.Plan, resolved: Map<String, String>): JSONArray {
        val expected = plan.neededSelectors.map { "geo-$it" }.toSet()
        require(resolved.keys == expected) { "Геоданные не соответствуют снимку категорий." }
        val result = JSONArray()
        if (expected.isEmpty()) return result
        val files = filesDir.toRealPath()
        val root = files.resolve("mcrf-geodata")
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath().parent == files)
        var total = 0L
        for (index in plan.neededSelectors) {
            val raw = java.nio.file.Paths.get(resolved.getValue("geo-$index"))
            require(raw.isAbsolute && raw == raw.normalize() && raw.parent == root) { "Требуется приватный файл геоданных." }
            val name = raw.fileName.toString()
            require(name.matches(Regex("[0-9a-f]{64}\\.json")))
            val before = Files.readAttributes(raw, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile && !before.isSymbolicLink && before.size() in 1..8L * 1024 * 1024)
            total += before.size()
            require(total <= 8L * 1024 * 1024) { "Суммарный размер геоданных превышает предел Steer." }
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newInputStream(raw, READ, NOFOLLOW_LINKS).use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n == -1) break
                    require(n > 0 && count + n <= before.size()) { "Файл геоданных изменился при подготовке." }
                    count += n
                    hash.update(chunk, 0, n)
                }
            }
            val after = Files.readAttributes(raw, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(count == before.size() && after.isRegularFile && !after.isSymbolicLink &&
                after.size() == before.size() && after.lastModifiedTime() == before.lastModifiedTime() &&
                after.fileKey() == before.fileKey()) { "Файл геоданных изменился при подготовке." }
            val selector = plan.selector(index)
            result.put(JSONObject().put("selector", index).put("file", name)
                .put("sha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                .put("bytes", count).put("kind", selector.getString("kind")).put("code", selector.getString("code")))
        }
        return result
    }
}
