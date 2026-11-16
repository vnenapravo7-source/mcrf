package org.mcrf.client

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Official bootstrap tracks main, including beta panel versions. GitHub releases also mirror unrelated APKs.
 * Resolve once per NEW installation on the backend worker; never cache or fall back to the recovery recipe.
 */
internal class SwgPanelReleaseResolver(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val checkActive: () -> Unit = {}
) {
    data class Resolved(val recipe: AndroidDeployRecipe, val bootstrap: ByteArray)

    fun resolve(): Resolved {
        val commit = parseMainRef(text(get(REF_URL, MAX_REF_BYTES, true)))
        val bootstrap = get(rawUrl(commit, "bootstrap.sh"), 2_000_000, false)
        validateBootstrap(text(bootstrap))
        val version = parseVersion(text(get(rawUrl(commit, "VERSION"), 128, false)))
        checkActive()
        return Resolved(AndroidDeployRecipe("swg", REPOSITORY, commit, "bootstrap.sh", digest(bootstrap), version), bootstrap)
    }

    private fun get(address: String, limit: Int, json: Boolean): ByteArray {
        // Complete URL equality excludes foreign hosts/repos, credentials, queries, fragments and extra path segments.
        require(address == REF_URL || RAW_URL.matches(address)) { FAILURE }
        checkActive()
        val deadline = System.nanoTime() + 45_000_000_000L
        val http = open(URL(address))
        try {
            require(http.url.toExternalForm() == address) { FAILURE }
            http.instanceFollowRedirects = false
            http.useCaches = false
            http.connectTimeout = 15_000
            http.readTimeout = 30_000
            http.setRequestProperty("User-Agent", "MCRF-Android-SWG-Deploy")
            http.setRequestProperty("Accept", if (json) "application/vnd.github+json" else "text/plain")
            http.setRequestProperty("Accept-Encoding", "identity")
            http.setRequestProperty("Cache-Control", "no-cache")
            require(http.responseCode == 200) { FAILURE }
            require(http.url.toExternalForm() == address) { FAILURE }
            require(http.contentEncoding.isNullOrEmpty() || http.contentEncoding == "identity") { FAILURE }
            val type = http.contentType.orEmpty().substringBefore(';').trim().lowercase()
            require(if (json) type == "application/json" else type in setOf("text/plain", "application/octet-stream", "text/x-shellscript")) { FAILURE }
            val length = http.contentLengthLong
            require(length == -1L || length in 1L..limit.toLong()) { FAILURE }
            val bytes = http.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    require(remaining > 0) { FAILURE }
                    http.readTimeout = minOf(30_000L, remaining).toInt()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { FAILURE }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.isNotEmpty() && (length < 0 || length == bytes.size.toLong())) { FAILURE }
            require(System.nanoTime() < deadline) { FAILURE }
            checkActive()
            return bytes
        } finally {
            http.disconnect()
        }
    }

    companion object {
        const val REPOSITORY = "SanityProtocol/swg-panel"
        // The commit-detail endpoint includes file patches; resolve only the lightweight branch ref.
        const val REF_URL = "https://api.github.com/repos/$REPOSITORY/git/ref/heads/main"
        private const val MAX_REF_BYTES = 16 * 1024
        private const val FAILURE = "Не удалось проверить актуальный SWG Panel; VK и SSH не запущены. Повторите установку позже."
        private val SHA = Regex("[0-9a-f]{40}")
        private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        private val RAW_URL = Regex("https://raw\\.githubusercontent\\.com/SanityProtocol/swg-panel/[0-9a-f]{40}/(bootstrap\\.sh|VERSION)")

        internal fun rawUrl(commit: String, file: String): String {
            require(SHA.matches(commit) && file in setOf("bootstrap.sh", "VERSION")) { FAILURE }
            return "https://raw.githubusercontent.com/$REPOSITORY/$commit/$file"
        }

        internal fun parseMainRef(source: String): String {
            try {
                validateJson(source)
                val parser = JSONTokener(source)
                val value = parser.nextValue() as? JSONObject ?: error(FAILURE)
                require(parser.nextClean() == '\u0000')
                require(value.get("ref") == "refs/heads/main")
                // GitHub's response URL uses plural refs, although the GET endpoint uses singular ref.
                require(value.get("url") == "https://api.github.com/repos/$REPOSITORY/git/refs/heads/main")
                val target = value.get("object") as? JSONObject ?: error(FAILURE)
                require(target.get("type") == "commit")
                val sha = target.get("sha") as? String ?: error(FAILURE)
                require(SHA.matches(sha))
                require(target.get("url") == "https://api.github.com/repos/$REPOSITORY/git/commits/$sha")
                return sha
            } catch (_: Exception) {
                throw IllegalArgumentException(FAILURE)
            }
        }

        /** Android JSONTokener accepts comments, single quotes and duplicate keys.
         * Check bounded strict JSON syntax before trusting its decoded fields. */
        private fun validateJson(source: String) {
            require(source.length in 1..MAX_REF_BYTES)
            var at = 0
            fun whitespace() { while (at < source.length && source[at] in " \t\r\n") at++ }
            fun character(): Char = source.getOrNull(at) ?: error(FAILURE)
            fun string(): String {
                val begin = at
                require(character() == '"'); at++
                while (true) {
                    val ch = character(); at++
                    if (ch == '"') break
                    require(ch.code >= 0x20)
                    if (ch == '\\') {
                        val escaped = character(); at++
                        require(escaped in "\"\\/bfnrtu")
                        if (escaped == 'u') repeat(4) {
                            require(character() in "0123456789abcdefABCDEF"); at++
                        }
                    }
                }
                return JSONTokener(source.substring(begin, at)).nextValue() as String
            }
            fun value(depth: Int) {
                require(depth <= 32); whitespace()
                when (character()) {
                    '{' -> {
                        at++; whitespace()
                        val keys = hashSetOf<String>()
                        if (character() == '}') { at++; return }
                        while (true) {
                            whitespace(); require(keys.add(string())); whitespace()
                            require(character() == ':'); at++; value(depth + 1); whitespace()
                            if (character() == '}') { at++; break }
                            require(character() == ','); at++
                        }
                    }
                    '[' -> {
                        at++; whitespace()
                        if (character() == ']') { at++; return }
                        while (true) {
                            value(depth + 1); whitespace()
                            if (character() == ']') { at++; break }
                            require(character() == ','); at++
                        }
                    }
                    '"' -> { string() }
                    't', 'f', 'n' -> {
                        val literal = when (character()) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                        require(source.startsWith(literal, at)); at += literal.length
                    }
                    else -> {
                        val number = JSON_NUMBER.matchAt(source, at) ?: error(FAILURE)
                        at += number.value.length
                    }
                }
            }
            value(0); whitespace(); require(at == source.length)
        }

        internal fun parseVersion(source: String): String {
            val version = source.trim()
            require(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:-[A-Za-z0-9][A-Za-z0-9.-]{0,40})?").matches(version)) { FAILURE }
            return version
        }

        internal fun validateBootstrap(source: String) {
            // Validate the current immutable-ref/master dispatch contract without rewriting the upstream script.
            require(source.startsWith("#!/usr/bin/env bash\n") && source.length >= 512 &&
                source.none { it.isISOControl() && it !in "\n\r\t" }) { FAILURE }
            val required = listOf(
                "REPO=\"\${SWG_REPO:-https://github.com/SanityProtocol/swg-panel}\"",
                "REF=\"\${SWG_REF:-}\"", "REF=\"\${REF:-main}\"", "export SWG_REF=\"\$REF\"",
                "'^[0-9a-f]{7,40}$'", "\$REPO/archive/\$REF.tar.gz",
                "baremetal-master|baremetal-host)", "SCRIPT=\"install-host.sh\"", "run_script \"\$SCRIPT\""
            )
            require(required.all(source::contains) && source.trimEnd().lineSequence().last().startsWith("}")) { FAILURE }
        }

        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun text(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .also { require('\u0000' !in it) { FAILURE } }
        } catch (_: Exception) {
            throw IllegalArgumentException(FAILURE)
        }
    }
}
