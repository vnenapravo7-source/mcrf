package org.mcrf.client

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import java.io.File
import java.security.MessageDigest

/** DEBUG-only scope of the future common P1 platform-owner fixture. This is
 * neither an ordinary routing option nor permission to start a VPN. No saved
 * state, grants, network access, package installation or arbitrary app list. */
object ClientP1OwnerFixtureScope {
    private const val HELPER = "org.mcrf.p1owner"
    private const val PEER = "org.mcrf.p1owner.peer"
    @JvmStatic fun pin(helperSha:String,peerSha:String):String {
        require(helperSha.matches(Regex("[0-9a-f]{64}"))&&peerSha.matches(Regex("[0-9a-f]{64}")))
        return MessageDigest.getInstance("SHA-256").digest("p1-shared-scope-v1\n$helperSha\n$peerSha\n".toByteArray(Charsets.US_ASCII)).joinToString(""){"%02x".format(it.toInt()and 255)}
    }

    @JvmStatic fun packages(context: Context, expectedSha256: String): List<String> {
        check(BuildConfig.DEBUG && context.packageName == "org.mcrf.client" &&
            Application.getProcessName() == context.packageName && Looper.myLooper() != Looper.getMainLooper())
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val pm = context.packageManager
        val helper = pm.getPackageInfo(HELPER, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES)
        val peer=pm.getPackageInfo(PEER,PackageManager.GET_SIGNING_CERTIFICATES)
        val owner = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = requireNotNull(helper.applicationInfo)
        val peerApp=requireNotNull(peer.applicationInfo)
        fun signers(info: android.content.pm.PackageInfo) = requireNotNull(info.signingInfo).apkContentsSigners.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { value -> "%02x".format(value.toInt() and 255) }
        }.sorted()
        check(app.enabled && app.uid >= 0 && app.uid != android.os.Process.myUid() &&
            (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && (app.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0 &&
            signers(helper).isNotEmpty() && signers(helper) == signers(owner)) { "P1_OWNER_SCOPE_IDENTITY" }
        check(peerApp.enabled&&peerApp.uid==app.uid&&helper.sharedUserId=="org.mcrf.p1fixture.shared"&&peer.sharedUserId==helper.sharedUserId&&
            (peerApp.flags and ApplicationInfo.FLAG_HAS_CODE)==0&&peerApp.splitSourceDirs.isNullOrEmpty()&&app.splitSourceDirs.isNullOrEmpty()&&
            (peerApp.flags and ApplicationInfo.FLAG_DEBUGGABLE)!=0&&(peerApp.flags and ApplicationInfo.FLAG_TEST_ONLY)!=0&&signers(peer)==signers(owner)) {"P1_OWNER_SCOPE_PEER_IDENTITY"}
        check(pm.getPackagesForUid(app.uid)?.toSet() == setOf(HELPER,PEER) &&
            requireNotNull(owner.applicationInfo).uid == android.os.Process.myUid() &&
            pm.getPackagesForUid(android.os.Process.myUid())?.toSet() == setOf(context.packageName)) { "P1_OWNER_SCOPE_UID_UNION" }
        val service = requireNotNull(helper.services).single { it.name == "$HELPER.OwnerProbeService" }
        check(service.enabled && service.exported && service.permission == "org.mcrf.client.permission.P1_OWNER")
        fun hash(sourceDir:String):String {
        val source = File(sourceDir).canonicalFile
        check(source.isFile && source.length() in 1L..(16L * 1024 * 1024)) { "P1_OWNER_SCOPE_APK_BOUND" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val bytes = ByteArray(65536); var total = 0L
            while (true) {
                val count = input.read(bytes); if (count < 0) break
                total += count; check(total <= 16L * 1024 * 1024) { "P1_OWNER_SCOPE_APK_BOUND" }
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        // Private historical field name p1OwnerHelperSha256 now binds BOTH
        // ordered APK hashes with domain separation, not merely the caller.
        check(pin(hash(app.sourceDir),hash(peerApp.sourceDir))==expectedSha256){"P1_OWNER_SCOPE_APK_PIN"}
        val fresh = pm.getPackageInfo(HELPER, PackageManager.GET_SIGNING_CERTIFICATES)
        check(fresh.longVersionCode == helper.longVersionCode && fresh.lastUpdateTime == helper.lastUpdateTime &&
            requireNotNull(fresh.applicationInfo).let { it.uid == app.uid && it.sourceDir == app.sourceDir } && signers(fresh) == signers(helper))
        val freshPeer=pm.getPackageInfo(PEER,PackageManager.GET_SIGNING_CERTIFICATES)
        check(freshPeer.longVersionCode==peer.longVersionCode&&freshPeer.lastUpdateTime==peer.lastUpdateTime&&
            requireNotNull(freshPeer.applicationInfo).let {it.uid==app.uid&&it.sourceDir==peerApp.sourceDir}&&signers(freshPeer)==signers(peer)&&pm.getPackagesForUid(app.uid)?.toSet()==setOf(HELPER,PEER))
        return listOf(context.packageName, HELPER,PEER)
    }
}
