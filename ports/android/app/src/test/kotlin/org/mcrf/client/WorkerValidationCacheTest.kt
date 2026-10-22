package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
class WorkerValidationCacheTest {
    @Test fun reuseRequiresExactProfileSettingsModeAndCapabilities(){
        val cache=WorkerValidationCache<String>();cache.put("profile","settings","VPN","caps","ok")
        assertEquals("ok",cache.get("profile","settings","VPN","caps"))
        for(key in listOf(listOf("other","settings","VPN","caps"),listOf("profile","new","VPN","caps"),listOf("profile","settings","PROXY","caps"),listOf("profile","settings","VPN","newcaps")))
            assertNull(cache.get(key[0],key[1],key[2],key[3]))
    }
    @Test fun stopPermanentlyInvalidatesEvenLateValidation(){
        val cache=WorkerValidationCache<String>();cache.put("p","s","m","c","ok");cache.close();cache.put("p","s","m","c","late")
        assertNull(cache.get("p","s","m","c"))
    }
    @Test fun workersNeverShareAdmission(){
        val first=WorkerValidationCache<String>();first.put("p","s","m","c","ok")
        assertNull(WorkerValidationCache<String>().get("p","s","m","c"))
    }
}
