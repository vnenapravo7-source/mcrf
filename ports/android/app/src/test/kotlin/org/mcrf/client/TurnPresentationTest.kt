package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class TurnPresentationTest {
    @Test fun streamRecordsAreStrictAndLateRecordsCannotReviveRetiredOwner(){
        val state=TurnStreamState()
        for(line in listOf("MCRF_STREAMS -1","MCRF_STREAMS 01","MCRF_STREAMS 65536","MCRF_STREAMS 2 secret","prefix MCRF_STREAMS 1"))state.offer(line.toByteArray())
        assertEquals(-1,state.current())
        state.offer("MCRF_STREAMS 12\r".toByteArray());assertEquals(12,state.current())
        state.stop();state.offer("MCRF_STREAMS 15".toByteArray());assertEquals(-1,state.current())
    }
    @Test fun telemetryKeepsIdentityAndStreamsWhileHandoverClearsThem(){
        val before=TrafficState.current
        try {
            TrafficState.reset("TURN");TrafficState.connection("TURN",true,"192.0.2.1",12)
            TrafficState.update(100,200,1000);TrafficState.update(200,400,2000)
            assertEquals(12,TrafficState.current.streams);assertEquals("192.0.2.1",TrafficState.current.serverIp)
            TrafficState.server("TURN");assertTrue(TrafficState.current.turn)
            TrafficState.handover("VPN");assertFalse(TrafficState.current.turn);assertEquals(-1,TrafficState.current.streams)
            assertEquals(200L,TrafficState.current.sent)
            TrafficState.connection("TURN",true,"192.0.2.1",0);TrafficState.stop()
            assertEquals(-1,TrafficState.current.streams)
        }finally {TrafficState.reset("");TrafficState.current=before}
    }
    @Test fun vpnNotificationShowsIdentityWithoutProxyAddress(){
        val state=TrafficState.Snapshot(0,0,0,0,"My TURN",false,true,true,"192.0.2.1",12)
        val vpn=ConnectionNotification.content(state,"VPN подключён · Прокси 127.0.0.1:10808",false,false)
        assertTrue(vpn.title.contains("My TURN"));assertTrue(vpn.expanded.contains("192.0.2.1"));assertTrue(vpn.text.contains("Потоки: 12"))
        assertFalse(vpn.expanded.contains("127.0.0.1"));assertFalse(vpn.expanded.contains("Прокси"))
        assertTrue(ConnectionNotification.content(state,"Прокси 127.0.0.1:10808",true,false).expanded.contains("127.0.0.1"))
    }
    @Test fun appearanceOnlyChangesOnColdStartOrRealForegroundTransition(){
        val policy=AppearanceForegroundPolicy()
        assertTrue(policy.enter(true));assertFalse(policy.enter(true))
        policy.stopped(true);assertFalse(policy.enter(true))
        policy.stopped(false);assertTrue(policy.enter(true))
        policy.stopped(false);assertFalse(policy.enter(false));assertFalse(policy.enter(true))
    }
    @Test fun randomAppearanceNeverTouchesIconOrConnectionPolicy(){
        val current=mapOf("launcherIcon" to "saved","tun" to "true",ConnectionAppearance.STYLE to ConnectionAppearance.DEFAULT)
        val changed=RandomAppearance.changes(current,listOf("one","two"),kotlin.random.Random(7))
        assertEquals((UserThemeColors.keys+listOf(PatternWallpaper.KEY,ConnectionAppearance.STYLE,ConnectionAppearance.EXIT,BottomNavigationStyle.KEY,
            AppTypography.INTERFACE,AppTypography.DISPLAY,AppearanceMotion.MATH,AppearanceMotion.MATH_BODY)).toSet(),changed.keys)
        assertNotEquals(ConnectionAppearance.DEFAULT,changed[ConnectionAppearance.STYLE])
        assertFalse(changed.containsKey("launcherIcon"));assertFalse(changed.containsKey("tun"))
    }
}
