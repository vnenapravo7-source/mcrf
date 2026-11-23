package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HomeServersOverviewTest {
    private fun source(name:String)=listOf(
        File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")
    ).first {it.isFile}.readText()

    private fun assertOrder(source:String,vararg fragments:String) {
        var previous=-1
        for(fragment in fragments) {
            val position=source.indexOf(fragment)
            assertTrue("Missing or out of order: $fragment",position>previous)
            previous=position
        }
    }

    @Test fun bothSectionsKeepConnectionControlsSeparateFromServerManagement() {
        val main=source("MainActivity")
        val home=main.substringAfter("private fun home(whitelists:Boolean=false)").substringBefore("private fun serversPage()")
        assertTrue(main.contains("private fun whiteLists()=home(whitelists=true)"))
        assertTrue(home.contains("serversPage().build(serverCard,whitelistOnly=whitelists,controlsHost=toolbar)"))
        assertTrue(home.contains("val controlsColumn=if(wideTv)"))
        assertTrue(home.contains("val serverColumn=if(wideTv)"))
        assertOrder(home,"controlsColumn.addView(connectionCard)","val tools=card()","addView(autoBsChoice()","addView(modeSwitch,","val serverCard=serverCard(")
        assertFalse(home.substringBefore("val tools=card()").contains("addView(autoBsChoice()"))
        assertFalse(home.substringAfter("val serverCard=serverCard(").contains("addView(modeSwitch"))
        assertTrue(home.contains("ConnectionControlsDock(this,tools,viewport)"))
    }
    @Test fun actionsStatusAndSwipeWorkflowsStayReachable() {
        val main=source("MainActivity");val servers=source("ServersPage")
        for(action in listOf("toggleConnection()","autoBsChoice()","changeMode(it)","serverActionsMenu","WARP · добавить сервер","Добавить в TG Proxy","Логи"))assertTrue(action,main.contains(action))
        assertTrue(servers.contains("callbacks.vkSettings?.invoke()"))
        assertTrue(servers.contains("callbacks.telegramProxy?.invoke()"))
        assertFalse(servers.contains("bs-transport-actions"))
        assertFalse(servers.contains("ClientSettingsNavigation.OPENFLUX"))
        assertFalse(servers.contains("serverActionsMenu(it)"))
        assertTrue(servers.contains("if(!whitelistOnly)setOnLongClickListener"))
        assertTrue(servers.contains("TransportComparisonUi.show(this@with,false)"))
        for(action in listOf("ProbeCoordinator.cancel","measure(entries)","refreshSubscriptions()","chooseInterval(it)","ServerSwipeRow","encryptedShare","ConnectionEditor","ClipboardManager","scanServerQr()"))assertTrue(action,servers.contains(action))
        assertTrue(main.contains("Intent(this,QrScanActivity::class.java)"))
        for(state in listOf("servers-loading","Ошибка подключения","home-connection-status","home-connection-target","CarrierCaptchaActivity"))assertTrue(state,main.contains(state))
        assertFalse(main.contains("tag=\"selected-server\""))
        assertTrue(servers.contains("servers-empty"));assertTrue(servers.contains("Не удалось прочитать группы подписок"))
    }
    @Test fun titleAndSelectionCanWrapAndActionsHave48dpTargets() {
        val main=source("MainActivity");val servers=source("ServersPage")
        val card=main.substringAfter("private fun serverCard(").substringBefore("fun serverActionsMenu")
        assertFalse(card.contains("text(\"Серверы\""))
        assertTrue(card.contains("selectedServer=null"));assertFalse(card.contains("addView(selectedServer"))
        assertFalse(card.contains("setSingleLine"));assertFalse(card.contains("dp(48)"))
        assertTrue(servers.contains("view.minimumWidth=dp(48);view.minimumHeight=dp(48)"))
        assertTrue(servers.replace(" ","").contains("LinearLayout.LayoutParams(dp(48),dp(48))"))
        assertTrue(servers.contains("setPadding(0,dp(8),0,dp(4))"))
        assertTrue(servers.contains("controlsHost.addView(if(narrow)HorizontalScrollView"))
        assertTrue(servers.contains("else actions, space(0, 0))"))
        assertTrue(servers.contains("gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48);setPadding(dp(8),0,dp(8),0)"))
        val home=main.substringAfter("private fun home(whitelists:Boolean=false)").substringBefore("private fun serversPage()")
        assertTrue(home.contains("setPadding(0,dp(2),0,dp(2))"))
        assertTrue(home.contains("minimumHeight=dp(36)"))
        val switching=main.substringAfter("private fun switchConnectionSection(").substringBefore("private fun serverCard(")
        assertFalse(switching.contains("shell.removeAllViews()"))
        assertFalse(switching.contains("page.alpha"))
        assertFalse(switching.contains("connectionButton"))
        assertTrue(switching.contains("refreshConnectionServers(bs)"))
    }
    @Test fun autoSelectionAdaptsToNarrowScreensWithoutHidingControls() {
        val row=source("MainActivity").substringAfter("private fun connectionOptionRow(").substringBefore("private fun autoBsChoice()")
        assertTrue(row.contains("isBaselineAligned=false"))
        assertTrue(row.contains("LinearLayout.LayoutParams(choice.minimumWidth,dp(48))"))
        assertTrue(row.contains("setSingleLine(false);maxLines=3"))
        val choice=source("MainActivity").substringAfter("private fun autoBsChoice()").substringBefore("private fun whiteLists()")
        assertFalse(choice.contains("controlHeader"));assertFalse(choice.contains("controlOptions"))
        assertTrue(choice.contains("},LinearLayout.LayoutParams(dp(48),dp(48)))"))
        assertTrue(choice.contains("setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);minimumWidth=0"))
        assertTrue(choice.contains("LinearLayout.LayoutParams(0,dp(48),1f)"))
        assertTrue(choice.contains("addView(selectionInterval,LinearLayout.LayoutParams(dp(48),dp(48)))"))
        assertFalse(choice.contains("},LinearLayout.LayoutParams(dp(44),dp(48)))"))
        assertTrue(choice.contains("contentWidthDp()-64 < 216+76*resources.configuration.fontScale"))
        assertTrue(choice.contains("details.addView(child,LinearLayout.LayoutParams(0,dp(48),1f))"))
        assertTrue(choice.contains("addView(AutoToleranceUi.button(this@MainActivity),LinearLayout.LayoutParams(dp(72),dp(48)))"))
        assertOrder(choice,
            "addView(connectionOptionRow(\"Автоопределение БС\",\"auto-bs-control\",detector)",
            "if(contentWidthDp()-64 < 216+76*resources.configuration.fontScale)")
    }
}
