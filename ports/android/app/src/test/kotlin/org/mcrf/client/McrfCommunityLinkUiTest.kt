package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source UI contract; Android handler launch remains a device acceptance gate. */
class McrfCommunityLinkUiTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first{it.isFile}.readText()
    @Test fun twoEntryPointsUseOneImplicitHttpsHandler(){
        val main=source("MainActivity")
        val header=main.substringAfter("val brand = LinearLayout").substringBefore("val brandTitle=")
        assertFalse(main.contains("mcrf-community-logo"))
        val root=main.substringAfter("private fun settings()").substringBefore("private fun settingsPage(")
        assertTrue(root.indexOf("mcrf-community-settings")>root.indexOf("page.addView(reset"))
        assertTrue(root.contains("McrfCommunityLink.open(this@MainActivity)"))
        val entry=root.substringAfter("tag=\"mcrf-community-settings\"").substringBefore("},space(0,12))")
        assertTrue(entry.contains("gravity=Gravity.CENTER;"));assertTrue(entry.contains("background=null"))
        val link=source("McrfCommunityLink")
        assertTrue(link.contains("https://t.me/+GZzB-XMAz4YzYjNi"));assertTrue(link.contains("Intent.ACTION_VIEW"))
        assertTrue(link.contains("ActivityNotFoundException"));assertTrue(link.contains("activity.toast("))
        assertFalse(link.contains("setPackage("));assertFalse(link.contains("setComponent("));assertFalse(link.contains("org.telegram"))
    }
    @Test fun freshInstallShowsOnce() {
        assertTrue(CommunityUpdateNotice.shouldShow("2.0.0",-1,100,-1,100))
        assertFalse(CommunityUpdateNotice.shouldShow("2.0.0",10223,100,100,100))
        assertTrue(CommunityUpdateNotice.shouldShow("2.0.0",10223,200,100,200))
    }

    @Test fun onlyFirstUpdateIntoV2Shows() {
        assertFalse(CommunityUpdateNotice.shouldShow("0.7.3",10062,100,100,200))
        assertTrue(CommunityUpdateNotice.shouldShow("2.0.0",10063,100,100,300))
        assertFalse(CommunityUpdateNotice.shouldShow("2.0.0",10178,100,100,400))
        assertFalse(CommunityUpdateNotice.shouldShow("2.0-preview.10224",10223,100,100,500))
    }
    @Test fun compactNoticeUsesSharedLinkAndContinuesStartupAfterDismissal() {
        val main=source("MainActivity")
        val notice=main.substringAfter("private fun offerCommunityUpdateNotice():Boolean").substringBefore("private fun continueStartupChoices()")
        assertTrue(notice.contains(".setPositiveButton(\"Перейти\"){_,_->McrfCommunityLink.open(this)}"))
        assertTrue(notice.contains(".setNegativeButton(\"Закрыть\",null)"))
        assertTrue(notice.contains("onboardingVisible=false;startupChoices()"))
        assertTrue(notice.indexOf(".commit()")<notice.indexOf(".show()"))
    }
}
