package org.mcrf.client

import org.mcrf.engine.spi.v1.Platform
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.IOException

/** Model only; actual Binder/FD/protect/network/death tests remain separate. */
class NativeRoutePlatformsTest {
    private class Parent {
        var binds=0;var closes=0;var hook:(()->Unit)?=null
        val platform=Proxy.newProxyInstance(Platform::class.java.classLoader,arrayOf(Platform::class.java,ClientPrimaryPlatformLifetime::class.java)){_,method,args->
            when(method.name){
                "onClose"->{@Suppress("UNCHECKED_CAST") val callback=args!![0] as ()->Unit;check(hook==null);hook=callback;null}
                "protectAndBind"->{binds++;true}
                "close"->{closes++;null}
                else->throw AssertionError("Unexpected parent call: ${method.name}")
            }
        } as Platform
    }
    @Test fun oneChildDeathCannotRetireParentOrAnotherServer(){
        val parent=Parent();val owner=NativeRoutePlatforms(parent.platform,7){false}
        val first=owner.child(0);val second=owner.child(1)
        assertTrue(first.protectAndBind(7,10));first.close()
        assertFalse(first.protectAndBind(7,10));assertTrue(second.protectAndBind(7,11))
        assertEquals(2,parent.binds);assertEquals(0,parent.closes)
        owner.close();assertFalse(second.protectAndBind(7,11));assertEquals(0,parent.closes)
    }
    @Test fun parentRetirementInvalidatesAllChildrenWithOneHook(){
        val parent=Parent();val owner=NativeRoutePlatforms(parent.platform,7){false}
        val children=(0..7).map(owner::child);var retired=0
        children.forEach {it.onClose {retired++}}
        parent.hook!!.invoke();assertEquals(8,retired)
        assertTrue(children.none {it.protectAndBind(7,10)});assertEquals(0,parent.closes)
    }
    @Test fun staleGenerationAndForbiddenCapabilitiesNeverReachParent(){
        val parent=Parent();val child=NativeRoutePlatforms(parent.platform,7){false}.child(0)
        assertFalse(child.protectAndBind(8,10));assertFalse(child.protectAndBind(7,-1))
        assertThrows(IOException::class.java){child.exchangeDns(7,Platform.DnsQuery(1,ByteArray(12),1000))}
        assertThrows(IOException::class.java){child.startDefaultMonitor(7){_,_->}}
        child.cancelDns(7,1);child.closeDefaultMonitor(7)
        assertEquals(0,parent.binds);assertEquals(0,parent.closes)
    }
    @Test fun retiredSlotsCannotBeResurrectedInTheSameRun(){
        val parent=Parent();val owner=NativeRoutePlatforms(parent.platform,7){false}
        owner.child(0).close()
        assertThrows(IllegalStateException::class.java){owner.child(0)}
        owner.close();assertThrows(IllegalStateException::class.java){owner.child(1)}
    }
    @Test fun failedCloseHookIsRetainedWithoutBlockingSiblingRetirement(){
        val parent=Parent();val owner=NativeRoutePlatforms(parent.platform,7){false}
        var attempts=0;var sibling=0
        owner.child(0).onClose {attempts++;if(attempts==1)throw IOException("owned fixture close pending")}
        owner.child(1).onClose {sibling++}
        assertThrows(IllegalStateException::class.java){owner.close()}
        assertEquals(1,sibling);owner.close();assertEquals(2,attempts);assertEquals(1,sibling);assertEquals(0,parent.closes)
    }
}
