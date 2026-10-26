package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Offline regressions for the four human reports; no login, network or user data. */
class UserRequestedFixesTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun sameSectionImportRefreshesInsteadOfNoOpNavigation(){
        assertFalse(ConnectionSections.needsNavigation("Главная","Главная"))
        assertFalse(ConnectionSections.needsNavigation(ClientSettingsNavigation.WHITELISTS,ClientSettingsNavigation.WHITELISTS))
        assertTrue(source("MainActivity").contains("needsNavigation(destination,current))navigate(requireNotNull(destination))else render()"))
        assertTrue(source("EngineImportUi").contains("showImportedConnections(pending.plan?.entries.orEmpty())"))
    }
    @Test fun crossSectionAndMixedImportsKeepIntendedDestination(){
        assertTrue(ConnectionSections.needsNavigation("Главная",ClientSettingsNavigation.WHITELISTS))
        assertFalse(ConnectionSections.needsNavigation(null,"Главная"))
    }
    @Test fun existingSitesRemainDefaults(){assertEquals(listOf("google.com","gitlab.com","youtube.com"),BsDetectionSites.load {_,fallback->fallback})}
    @Test fun allTwelveRequestedPresetsArePresent(){assertEquals(12,BsDetectionSites.presets.distinct().size);assertTrue(BsDetectionSites.presets.containsAll(listOf("livew.com","discord.gg","zoom.us","pinterest.com","netflix.com")))}
    @Test fun customSitesAreNormalizedAndUsedByPosition(){
        val sites=BsDetectionSites.load {key,_->listOf(" HTTPS://EXAMPLE.ORG/ ","custom.example.org","github.com")[BsDetectionSites.keys.indexOf(key)]}
        assertEquals(listOf("example.org","custom.example.org","github.com"),sites)
        assertEquals("https://custom.example.org/",BsDetectionSites.url(sites[1]))
    }
    @Test fun malformedSitesCannotBecomeCredentialsPortsOrLocalAddressProbes(){
        listOf("","http://example.org","https://user:pass@example.org","example.org:443","https://example.org/path","example.org?q=1","example.org#token","127.0.0.1","[::1]","localhost","router.local","x.test","a..com","https://example.org\\@evil.com").forEach {value->assertTrue(value,runCatching {BsDetectionSites.normalize(value)}.isFailure)}
    }
    @Test fun corruptStoredValueFallsBackOnlyForThatSlot(){
        val result=BsDetectionSites.load {key,fallback->if(key==BsDetectionSites.keys[1])"http://bad"else if(key==BsDetectionSites.keys[2])"github.com"else fallback}
        assertEquals(listOf("google.com","gitlab.com","github.com"),result)
    }
    @Test fun thirdSiteUsesActualSelectedCoreAndStaleMonitorResultsAreDiscarded(){
        assertTrue(source("McrfVpnService").contains("running.health(target,BsDetectionSites.url(site),2500)"))
        assertTrue(source("BsDetectionMonitor").contains("snapshot()==stamp&&sites()==capturedSites"))
        assertTrue(source("DirectAccessCheck").contains("sites.take(if(includeYoutube)3 else 2)"))
    }
    @Test fun backupAndSettingsHaveAllThreeEditableSites(){
        assertTrue(source("AndroidBackup").contains("+BsDetectionSites.keys"))
        assertTrue(source("AndroidBackup").contains("BsDetectionSites.normalize(values.getString(it))"))
        assertTrue(source("BsDetectionSitesUi").contains("picker(choices.toTypedArray()"))
        assertTrue(source("ClientTunUi").contains("form.view.addView(BsDetectionSitesUi.view(activity),0,activity.space(0,20))"))
    }
    @Test fun vkUsesWebCompatibilityAgentWithoutChangingChromiumVersion(){
        val original="Mozilla/5.0 (Linux; Android 14; Device; wv) AppleWebKit/537.36 Version/4.0 Chrome/130.0.1.2 Mobile Safari/537.36"
        val web=VkRelayWebLogin.browserAgent(original)
        assertFalse(web.contains("; wv"));assertFalse(web.contains("Version/4.0"));assertTrue(web.contains("Chrome/130.0.1.2"))
        assertTrue(VkRelayWebLogin.desktopAgent(original).contains("Chrome/130.0.1.2"))
    }
    @Test fun alternativeVkLoginDoesNotInjectBridgeOrEraseCookiesOrLink(){
        val code=source("VkRelayLoginActivity")
        assertTrue(code.contains("Другой вход"));assertTrue(code.contains("oauthStarted=false"))
        assertFalse(code.contains("removeAllCookies"));assertFalse(code.contains("addJavascriptInterface"));assertTrue(code.contains("handler.cancel()"))
    }
    @Test fun oauthStateAndExactCallbackAreStillRequired(){
        val callback=VkRelayCallback {fail("A mismatched callback must not be stored")}
        val url="https://oauth.vk.com/blank.html#access_token=fixture&user_id=1&state=wrong"
        assertEquals(VkRelayCallback.Result.REJECT,callback.accept(url,"expected",true))
        assertEquals(VkRelayCallback.Result.IGNORE,callback.accept(url,"expected",false))
    }
    @Test fun engineDialogCopyIsShortAndAvoidsDuplicatedAbiAndByteCounts(){
        val text=EngineImportPresentation.summary(listOf("Steer"),"2,3 МБ","Steer")
        assertEquals(3,text.lines().size);assertFalse(text.contains("Сейчас"));assertFalse(text.contains("байт"))
        assertEquals("Скачать и установить",EngineImportPresentation.button(true))
    }
    @Test fun multipleEnginePackagesIdentifyCurrentOneAndProgress(){
        val text=EngineImportPresentation.summary(listOf("A","B"),"5 МБ","B","1 МБ")
        assertTrue(text.contains("Сейчас: B"));assertTrue(text.contains("Загружено: 1 МБ"))
    }
    @Test fun verifiedDownloadHandsOffOnceAndAndroidConsentIsNotBypassed(){
        val code=source("EngineImportUi")
        assertTrue(code.contains("pending.automaticInstall=false"));assertTrue(code.contains("EnginePackages.verifyRequirements"))
        assertTrue(code.contains("packageManager.canRequestPackageInstalls()"));assertTrue(code.contains("Intent.ACTION_INSTALL_PACKAGE"))
        assertTrue(code.contains("phase == \"install-ready\"&&pending.automaticInstall&&!active()"))
    }
}
