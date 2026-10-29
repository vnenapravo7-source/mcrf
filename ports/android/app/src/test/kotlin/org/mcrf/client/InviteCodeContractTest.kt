package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InviteCodeContractTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun inviteIsFirstSettingsItem(){assertEquals(ClientSettingsNavigation.INVITE,ClientSettingsNavigation.rootLeading.first())}
    @Test fun codeIsSixDigitsAndStateStoresOnlyToken(){val s=source("InviteCodeSync");assertTrue(s.contains("[0-9]{6}"));assertTrue(s.contains("data class State(val token:String"));assertFalse(s.contains("data class State(val code"));assertTrue(s.contains("put(\"token\""))}
    @Test fun partialProviderSetImportsWithoutBlocking(){val s=source("InviteCodeSync");assertTrue(s.contains("getString(\"bypassUrl\")"));assertTrue(s.contains("require(plan.entries.isNotEmpty())"));assertFalse(s.contains("не менее трёх серверов каждого типа"))}
    @Test fun automaticSyncUsesSubscriptionJob(){assertTrue(source("SubscriptionUpdates").contains("InviteCodeSync.refreshIfLinked(this)"));assertEquals(360,SubscriptionOptions().intervalMinutes)}
    @Test fun requiredEnginesCoverInviteFamilies(){assertEquals(linkedSetOf("qwdtt","wdtt","csqtt","csqtt25"),InviteCodeSync.requiredEngines())}
    @Test fun inviteStartsOneBatchEngineInstallerAutomatically(){val ui=source("InviteCodeUi");val engine=source("EngineImportUi");assertTrue(ui.contains("afterSuccess()"));assertTrue(ui.contains("install(missing)"));assertFalse(ui.contains("missing.first()"));assertTrue(engine.contains("fun showEngines(kinds:Set<String>)"));assertTrue(engine.contains("batchInstall=true"));assertTrue(engine.contains("prepareOnOpen=true"))}
    @Test fun mirrorIsUsedOnlyAfterTimeout(){val s=source("InviteCodeSync");assertTrue(s.contains("https://vnenapravo7.ru/wdtt/api/invite"));assertTrue(s.contains("https://outright7.site/wdtt/api/invite"));assertTrue(s.contains("catch(_:SocketTimeoutException)"));assertFalse(s.contains("catch(_:Exception){return request(endpoints[1]"))}
    @Test fun redeemHasStableDeviceIdentity(){val s=source("InviteCodeSync");assertTrue(s.contains("put(\"deviceId\",deviceId(context))"));assertTrue(s.contains("UUID.randomUUID()"));assertFalse(s.contains("put(\"code\",code).put(\"deviceId\",UUID.randomUUID()"))}
    @Test fun missingVpnSubscriptionDoesNotBlockBypassImport(){val sync=source("InviteCodeSync");val ui=source("InviteCodeUi");assertTrue(sync.contains("val subscription:String?"));assertTrue(sync.contains("SwgInviteSubscription.fetch(manifest.bypass)"));assertTrue(ui.contains("VPN-подписка для этого пользователя не задана"))}
    @Test fun validVpnSubscriptionRemainsStrict(){assertEquals("https://example.com/sub",InviteCodeSync.optionalSubscription("https://example.com/sub"))}
    @Test fun malformedPresentVpnSubscriptionIsNotSilentlyDiscarded(){
        try { InviteCodeSync.optionalSubscription("http://user:pass@example.com/#fragment");fail("Malformed present URL must fail") }
        catch(_:IllegalArgumentException){}
        assertNull(InviteCodeSync.optionalSubscription(""))
    }
}
