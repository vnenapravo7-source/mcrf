package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AndroidDeployQuickConnectionTest {
    private fun options(recipe:String,choice:String,calls:List<String> = emptyList(),automatic:Boolean=false)=
        AndroidDeployQuickConnection.options(recipe,choice,19001,19002,"pinterest.com","",calls,automatic,6)

    @Test fun quickChoicesCoverEveryExistingPanelWithoutInventingCsqttGeneration(){
        assertEquals(listOf("tcp","grpc"),AndroidDeployQuickConnection.choices("3xui").map {it.id})
        assertEquals(listOf("qwdtt","wdtt","csqtt","csqtt25"),AndroidDeployQuickConnection.choices("swg").map {it.id})
        assertEquals(listOf("CSQTT v2.1.9 · SWG","CSQTT v2.5 · SWG"),AndroidDeployQuickConnection.choices("swg").takeLast(2).map {it.label})
        assertEquals(7,AndroidDeployQuickConnection.choices("openflux").size)
        assertTrue(AndroidDeployQuickConnection.choices("unknown").isEmpty())
    }

    @Test fun realityQuickAddCreatesOnlySelectedTransport(){
        for(choice in listOf("tcp","grpc")){
            val selected=options("3xui",choice)
            assertEquals(choice=="tcp",selected.getBoolean("tcp"))
            assertEquals(choice=="grpc",selected.getBoolean("grpc"))
            assertEquals(if(choice=="grpc")"mcrf-19001"else"",selected.getString("grpc_service"))
            AndroidDeployPlan.validate("3xui",AndroidDeployTarget("192.0.2.1",sshPassword="fixture",panelUser="fixture",panelPassword="fixture-password",options=selected))
        }
    }

    @Test fun manualCallsAreDeduplicatedWithoutSixCallLimitOrImplicitGeneration(){
        val calls=(1..10).map {"fixture-call-$it"}
        val selected=options("swg","wdtt",calls+calls)
        val saved=selected.getJSONArray("vk_calls")
        assertEquals(10,saved.length())
        calls.forEachIndexed {index,value->assertEquals(value,saved.getString(index))}
        assertFalse(selected.getBoolean("automatic_calls"))
        assertFalse(selected.has("vk_call_group"))
    }

    @Test fun unknownProtocolRejectedBeforeAnyProvisioning(){
        assertThrows(IllegalArgumentException::class.java){options("openflux","unknown")}
        assertThrows(IllegalArgumentException::class.java){options("swg","csqtt99")}
    }

    @Test fun openingAndBackingNavigationDoesNotCreateDuplicateDestinations(){
        val navigation=AndroidDeployBrowserUi.Navigation()
        val servers=AndroidDeployBrowserUi.Destination("servers")
        val panel=AndroidDeployBrowserUi.Destination("panel","fixture")
        assertFalse(navigation.back())
        assertTrue(navigation.push(servers))
        assertFalse(navigation.push(servers))
        assertEquals(1,navigation.history.size)
        assertTrue(navigation.push(panel))
        assertTrue(navigation.back());assertEquals(servers,navigation.destination)
        assertTrue(navigation.back());assertEquals("home",navigation.destination.kind)
        assertFalse(navigation.back())
    }

    @Test fun navigationHistoryRemainsBounded(){
        val navigation=AndroidDeployBrowserUi.Navigation()
        repeat(100){navigation.push(AndroidDeployBrowserUi.Destination("panel","fixture-$it"))}
        assertEquals(64,navigation.history.size)
        repeat(64){assertTrue(navigation.back())}
        assertFalse(navigation.back())
        assertEquals("fixture-35",navigation.destination.id)
    }
}
