package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class SwgPanelReleaseResolverTest {
    private val sha = "a".repeat(40)
    private fun mainRef(value: String = sha, repo: String = SwgPanelReleaseResolver.REPOSITORY) = JSONObject()
        .put("ref", "refs/heads/main").put("url", "https://api.github.com/repos/$repo/git/refs/heads/main")
        .put("object", JSONObject().put("sha", value).put("type", "commit")
            .put("url", "https://api.github.com/repos/$repo/git/commits/$value")).toString()

    private fun bootstrap(marker: String = "") = listOf(
        "#!/usr/bin/env bash", "{", "set -euo pipefail",
        "REPO=\"\${SWG_REPO:-https://github.com/SanityProtocol/swg-panel}\"",
        "REF=\"\${SWG_REF:-}\"", "REF=\"\${REF:-main}\"", "export SWG_REF=\"\$REF\"",
        "if printf '%s' \"\$REF\" | grep -cE '^[0-9a-f]{7,40}$' >/dev/null; then",
        "  curl -fsSL \"\$REPO/archive/\$REF.tar.gz\"", "fi",
        "case \"\$METHOD-\$ROLE\" in", "baremetal-master|baremetal-host) export ROLE; SCRIPT=\"install-host.sh\" ;;", "esac",
        "run_script \"\$SCRIPT\"", "# " + "official bootstrap fixture ".repeat(30) + marker, "} # end of group", ""
    ).joinToString("\n").toByteArray()

    private data class Reply(
        val bytes: ByteArray,
        val status: Int = 200,
        val type: String = "text/plain; charset=utf-8",
        val length: Long = bytes.size.toLong(),
        val responseUrl: String? = null,
        val encoding: String? = null,
        val failRead: Boolean = false
    )

    private class FakeHttps(request: URL, val reply: Reply) : HttpsURLConnection(reply.responseUrl?.let(::URL) ?: request) {
        var disconnected = false
        var closed = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = reply.status
        override fun getContentType() = reply.type
        override fun getContentLengthLong() = reply.length
        override fun getContentEncoding() = reply.encoding
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(reply.bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reply.failRead) throw IOException("fixture connection lost")
                return super.read(b, off, len)
            }
            override fun close() { closed = true; super.close() }
        }
    }

    private inner class Fixture {
        val replies = mutableMapOf<String, Reply>()
        val requests = mutableListOf<String>()
        val connections = mutableListOf<FakeHttps>()
        val resolver = SwgPanelReleaseResolver(open = { url ->
            requests.add(url.toExternalForm())
            FakeHttps(url, replies[url.toExternalForm()] ?: error("Unexpected request: $url")).also(connections::add)
        })
        init { publish(sha) }
        fun publish(value: String, version: String = "1.8.11-beta") {
            replies[SwgPanelReleaseResolver.REF_URL] = Reply(mainRef(value).toByteArray(), type = "application/json")
            replies[SwgPanelReleaseResolver.rawUrl(value, "bootstrap.sh")] = Reply(bootstrap(value))
            replies[SwgPanelReleaseResolver.rawUrl(value, "VERSION")] = Reply("$version\n".toByteArray())
        }
    }

    @Test fun freshMainBindsBootstrapHashVersionAndInstallerRefToOneCommit() {
        val fixture = Fixture()
        val resolved = fixture.resolver.resolve()
        assertEquals(sha, resolved.recipe.commit)
        assertEquals("1.8.11-beta", resolved.recipe.version)
        assertEquals(SwgPanelReleaseResolver.REPOSITORY, resolved.recipe.repository)
        assertEquals(SwgPanelReleaseResolver.rawUrl(sha, "bootstrap.sh"), resolved.recipe.url)
        assertArrayEquals(bootstrap(sha), resolved.bootstrap)
        assertEquals(SwgPanelReleaseResolver.digest(bootstrap(sha)), resolved.recipe.sha256)
        assertEquals("https://api.github.com/repos/SanityProtocol/swg-panel/git/ref/heads/main", SwgPanelReleaseResolver.REF_URL)
        assertEquals(listOf(SwgPanelReleaseResolver.REF_URL, resolved.recipe.url, SwgPanelReleaseResolver.rawUrl(sha, "VERSION")), fixture.requests)
        for (http in fixture.connections) {
            assertFalse(http.instanceFollowRedirects)
            assertFalse(http.useCaches)
            assertEquals(15_000, http.connectTimeout)
            assertTrue(http.readTimeout in 1..30_000)
            assertEquals("identity", http.getRequestProperty("Accept-Encoding"))
            assertEquals("no-cache", http.getRequestProperty("Cache-Control"))
            assertTrue(http.closed && http.disconnected)
        }
        val target = AndroidDeployTarget("192.0.2.1", sshPassword = "fixture-password", options = JSONObject().put("create", false))
        val script = AndroidDeployScripts.build(resolved.recipe, target, "/tmp/mcrf-deploy-" + "a".repeat(32))
        assertTrue(script.contains("export SWG_REF='$sha'"))
        assertTrue(script.contains("export SWG_REPO='https://github.com/SanityProtocol/swg-panel'"))
        assertTrue(script.contains("bash installer.sh master"))
        assertEquals(String(resolved.bootstrap, Charsets.UTF_8), AndroidDeployScripts.pin(resolved.recipe, String(resolved.bootstrap, Charsets.UTF_8)))
    }

    @Test fun everyNewResolveFetchesMainAndNeverSilentlyReusesPreviousSuccess() {
        val fixture = Fixture()
        val first = fixture.resolver.resolve()
        val secondSha = "b".repeat(40)
        fixture.publish(secondSha, "1.8.12-beta")
        val second = fixture.resolver.resolve()
        assertEquals(sha, first.recipe.commit)
        assertEquals(secondSha, second.recipe.commit)
        assertNotEquals(first.recipe.sha256, second.recipe.sha256)
        assertEquals(2, fixture.requests.count { it == SwgPanelReleaseResolver.REF_URL })
        fixture.replies[SwgPanelReleaseResolver.REF_URL] = Reply(ByteArray(0), status = 503)
        assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
        assertEquals(7, fixture.requests.size)
    }

    @Test fun wrongRepositoryAndNonHexOrAbbreviatedCommitAreRejected() {
        for (repo in listOf("attacker/swg-panel", "SanityProtocol/other", "SanityProtocol/swg-panel/extra")) {
            assertThrows(IllegalArgumentException::class.java) { SwgPanelReleaseResolver.parseMainRef(mainRef(repo = repo)) }
        }
        for (bad in listOf("", "a".repeat(39), "a".repeat(41), "g".repeat(40), "A".repeat(40), "main", "$sha/bootstrap.sh", "$sha?x=1")) {
            assertThrows(IllegalArgumentException::class.java) { SwgPanelReleaseResolver.parseMainRef(mainRef(bad)) }
            assertThrows(IllegalArgumentException::class.java) { SwgPanelReleaseResolver.rawUrl(bad, "bootstrap.sh") }
        }
        for (path in listOf("../bootstrap.sh", "bootstrap.sh?x=1", "README.ru.md", "releases/latest")) {
            assertThrows(IllegalArgumentException::class.java) { SwgPanelReleaseResolver.rawUrl(sha, path) }
        }
    }

    @Test fun fullCommitAndMirroredApkReleaseBodiesNeverSelectPanelSource() {
        val mirror = JSONObject().put("tag_name", "csqtt-app-2.1.9").put("target_commitish", "main")
            .put("prerelease", false).put("assets", JSONArray().put(JSONObject().put("name", "csqtt.apk")))
        val fullCommit = JSONObject().put("sha", sha)
            .put("url", "https://api.github.com/repos/SanityProtocol/swg-panel/commits/$sha")
            .put("html_url", "https://github.com/SanityProtocol/swg-panel/commit/$sha").put("files", JSONArray())
        for (body in listOf(fullCommit.toString(), mirror.toString(), "[$mirror]", "<html>gateway</html>", "{}", mainRef() + " trailing")) {
            val fixture = Fixture()
            fixture.replies[SwgPanelReleaseResolver.REF_URL] = Reply(body.toByteArray(), type = "application/json")
            assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
            assertEquals(listOf(SwgPanelReleaseResolver.REF_URL), fixture.requests)
            assertTrue(fixture.connections.single().disconnected)
        }
    }

    @Test fun wrongRefObjectTypeAndBothOfficialUrlsFailClosedBeforeBootstrapFetch() {
        val rootUrl = "https://api.github.com/repos/SanityProtocol/swg-panel/git/refs/heads/main"
        val objectUrl = "https://api.github.com/repos/SanityProtocol/swg-panel/git/commits/$sha"
        fun withObject(key: String, value: Any) = JSONObject(mainRef()).apply { getJSONObject("object").put(key, value) }
        val invalid = listOf(
            JSONObject(mainRef()).put("ref", "refs/heads/dev"),
            JSONObject(mainRef()).put("ref", "refs/tags/main"),
            JSONObject(mainRef()).put("object", JSONArray()),
            JSONObject(mainRef()).put("object", JSONObject.NULL),
            withObject("type", "tag"), withObject("type", "tree"), withObject("type", "blob"), withObject("type", true),
            withObject("sha", 123),
            JSONObject(mainRef()).put("url", SwgPanelReleaseResolver.REF_URL),
            JSONObject(mainRef()).put("url", rootUrl.replace("SanityProtocol/", "other/")),
            JSONObject(mainRef()).put("url", rootUrl.replace("https://", "http://")),
            JSONObject(mainRef()).put("url", "$rootUrl?x=1"),
            JSONObject(mainRef()).put("url", "$rootUrl#fragment"),
            withObject("url", objectUrl.replace("SanityProtocol/", "other/")),
            withObject("url", objectUrl.replace(sha, "b".repeat(40))),
            withObject("url", objectUrl.replace("https://", "http://")),
            withObject("url", objectUrl.replace("/git/commits/", "/commits/")),
            withObject("url", "$objectUrl?x=1"), withObject("url", "$objectUrl#fragment")
        )
        for (value in invalid) {
            val fixture = Fixture()
            fixture.replies[SwgPanelReleaseResolver.REF_URL] = Reply(value.toString().toByteArray(), type = "application/json")
            assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
            assertEquals(listOf(SwgPanelReleaseResolver.REF_URL), fixture.requests)
            assertTrue(fixture.connections.single().disconnected)
        }
    }

    @Test fun permissiveJsonDuplicateRootAndNestedFieldsAndExcessiveDepthFailClosed() {
        val valid = mainRef()
        val malformed = listOf(
            valid.replace('"', '\''), valid.replace("\"sha\":", "sha:"),
            valid.replaceFirst("{", "{/* comment */"), valid.dropLast(1) + ",}",
            valid.replaceFirst("{", "{\"ref\":\"refs/heads/dev\","),
            valid.replaceFirst("{", "{\"\\u0072ef\":\"refs/heads/main\","),
            valid.replace("\"object\":{", "\"object\":{\"sha\":\"${"b".repeat(40)}\","),
            valid.replace("\"object\":{", "\"object\":{\"\\u0073ha\":\"$sha\","),
            valid.dropLast(1) + ",\"extra\":NaN}",
            valid.dropLast(1) + ",\"extra\":[1,]}",
            valid.dropLast(1) + ",\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"
        )
        for (body in malformed) assertThrows(IllegalArgumentException::class.java) {
            SwgPanelReleaseResolver.parseMainRef(body)
        }
        assertEquals(sha, SwgPanelReleaseResolver.parseMainRef(" \n$valid\t"))
    }

    @Test fun statusRedirectAndTransportFailuresStopAtAnyStage() {
        val urls = listOf(SwgPanelReleaseResolver.REF_URL, SwgPanelReleaseResolver.rawUrl(sha, "bootstrap.sh"), SwgPanelReleaseResolver.rawUrl(sha, "VERSION"))
        for ((index, url) in urls.withIndex()) for (status in listOf(204, 301, 302, 304, 403, 404, 429, 500)) {
            val fixture = Fixture()
            fixture.replies[url] = fixture.replies.getValue(url).copy(status = status)
            assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
            assertEquals(index + 1, fixture.requests.size)
            assertTrue(fixture.connections.all { it.disconnected })
        }
        for (url in urls) {
            val fixture = Fixture()
            fixture.replies[url] = fixture.replies.getValue(url).copy(failRead = true)
            assertThrows(IOException::class.java) { fixture.resolver.resolve() }
            assertTrue(fixture.connections.all { it.disconnected && it.closed })
        }
    }

    @Test fun changedResponseOriginPathEncodingAndMimeTypeFailClosed() {
        val fixtureUrl = SwgPanelReleaseResolver.rawUrl(sha, "bootstrap.sh")
        val invalid = listOf(
            Reply(bootstrap(), responseUrl = "http://raw.githubusercontent.com/SanityProtocol/swg-panel/$sha/bootstrap.sh"),
            Reply(bootstrap(), responseUrl = "https://raw.githubusercontent.com/foreign/swg-panel/$sha/bootstrap.sh"),
            Reply(bootstrap(), responseUrl = "$fixtureUrl?redirected=1"),
            Reply(bootstrap(), responseUrl = fixtureUrl.replace(sha, "b".repeat(40))),
            Reply(bootstrap(), encoding = "gzip"), Reply(bootstrap(), type = "text/html")
        )
        for (reply in invalid) {
            val fixture = Fixture()
            fixture.replies[fixtureUrl] = reply
            assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
            assertEquals(2, fixture.requests.size)
            assertTrue(fixture.connections.all { it.disconnected })
        }
    }

    @Test fun bodiesAreBoundedEvenWithoutContentLengthAndTruncationIsRejected() {
        for (length in listOf(-1L, 16_385L)) {
            val fixture = Fixture()
            fixture.replies[SwgPanelReleaseResolver.REF_URL] = Reply(ByteArray(16_385) { 65 }, type = "application/json", length = length)
            assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
            assertEquals(1, fixture.requests.size)
            assertTrue(fixture.connections.single().disconnected)
        }
        for ((file, limit) in listOf("bootstrap.sh" to 2_000_000, "VERSION" to 128)) {
            val url = SwgPanelReleaseResolver.rawUrl(sha, file)
            for (length in listOf(-1L, (limit + 1L))) {
                val fixture = Fixture()
                fixture.replies[url] = Reply(ByteArray(limit + 1) { 65 }, length = length)
                assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
                assertTrue(fixture.connections.all { it.disconnected })
            }
        }
        for (reply in listOf(Reply(ByteArray(0)), Reply(bootstrap(), length = bootstrap().size + 1L), Reply(byteArrayOf(0xc3.toByte(), 0x28)))) {
            val fixture = Fixture()
            fixture.replies[SwgPanelReleaseResolver.rawUrl(sha, "bootstrap.sh")] = reply
            assertThrows(IllegalArgumentException::class.java) { fixture.resolver.resolve() }
        }
    }

    @Test fun bootstrapMustRetainOfficialImmutableRefAndMasterContract() {
        val source = String(bootstrap(), Charsets.UTF_8)
        for (bad in listOf("<html>failure</html>", source.dropLast(30), source.replace("SanityProtocol/swg-panel", "other/panel"),
            source.replace("SWG_REF", "UNPINNED_REF"), source.replace("\$REF.tar.gz", "main.tar.gz"),
            source.replace("install-host.sh", "install-node.sh"), source.replace("master|", "other|"), source + "\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { SwgPanelReleaseResolver.validateBootstrap(bad) }
        }
        for (bad in listOf("csqtt-app-2.1.9", "latest", "1.8.11-beta\necho x", "<code>1.8.11-beta</code>")) {
            assertThrows(IllegalArgumentException::class.java) { SwgPanelReleaseResolver.parseVersion(bad) }
        }
        assertEquals("1.8.11-beta", SwgPanelReleaseResolver.parseVersion("1.8.11-beta\n"))
    }

    @Test fun cancellationBeforeResolutionNeverOpensConnection() {
        var opened = false
        val resolver = SwgPanelReleaseResolver(open = { opened = true; error("No network permitted") }, checkActive = { throw InterruptedException("cancel") })
        assertThrows(InterruptedException::class.java) { resolver.resolve() }
        assertFalse(opened)
    }

    @Test fun historicalRecipeAndSavedPanelProvisioningKeepTheirOriginalSource() {
        val legacy = AndroidDeployRecipe.all.single { it.id == "swg" }
        assertEquals("9c4a59d523ae4ec42fb8205198678c8a7ffc6263", legacy.commit)
        assertEquals("a64586c2a49acc3bab520a28c86903aa6291c213e3cac79485696edf5c2e2fd5", legacy.sha256)
        assertEquals("1.8.10-beta", legacy.version)
        val target = AndroidDeployTarget("192.0.2.1", sshPassword = "fixture-password", options = JSONObject().put("vk_calls", JSONArray().put("A".repeat(43))))
        val saved = JSONObject().put("ready", true).put("panel_url", "https://192.0.2.1:8443/")
            .put("username", "fixture").put("password", "fixture-password").put("certificate_sha256", "a".repeat(64))
            .put("connections", JSONArray()).put("recipe_id", "swg").put("core_commit", legacy.commit)
        val original = saved.toString()
        val script = AndroidDeployScripts.addTransport(legacy, target, saved)
        assertEquals(original, saved.toString())
        for (forbidden in listOf("installer.sh", "SWG_REF", "git clone", "systemctl restart")) assertFalse(script.contains(forbidden))
        val record = AndroidDeployHistory.record(saved, target, "swg")
        AndroidDeployBackupSettings.validate(JSONObject().put("deployResults", JSONObject().put("fixture/swg", record)))
        assertEquals(legacy.commit, record.getString("core_commit"))
    }
}
