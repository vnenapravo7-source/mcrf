package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*

class LocalProxyConfigTest {
    private fun profile()=JSONObject().put("inbounds",JSONArray().put(JSONObject().put("type","mixed").put("tag","local-proxy").put("listen","127.0.0.1")))
    @Test fun generatesIndependentNonemptyCredentials(){val one=LocalProxyConfig.generate();val two=LocalProxyConfig.generate();assertTrue(one.getBoolean("enabled"));assertNotEquals(one.toString(),two.toString());assertEquals(32,one.getString("password").length);LocalProxyConfig.validate(one)}
    @Test fun bothCopyFormatsIncludeCredentials(){val auth=LocalProxyConfig.generate();for(scheme in listOf("socks5","http")){val link=java.net.URI(LocalProxyConfig.link(scheme,10808,auth));assertEquals(scheme,link.scheme);assertEquals("127.0.0.1",link.host);assertEquals(10808,link.port);assertEquals(auth.getString("username")+":"+auth.getString("password"),link.userInfo)}}
    @Test fun specialCredentialsArePercentEncoded(){val auth=JSONObject().put("enabled",true).put("username","u@ x").put("password","p:/#?");val uri=java.net.URI(LocalProxyConfig.link("http",10808,auth));assertEquals("u@ x:p:/#?",uri.userInfo);assertNull(uri.fragment);assertNull(uri.query)}
    @Test fun enabledIsActuallyAppliedToCoreInbound(){val auth=LocalProxyConfig.generate();val result=LocalProxyConfig.apply(profile(),auth).getJSONArray("inbounds").getJSONObject(0);assertEquals(auth.getString("password"),result.getJSONArray("users").getJSONObject(0).getString("password"))}
    @Test fun disabledHasNoCredentialsOrUsers(){val auth=LocalProxyConfig.generate(false);assertEquals("socks5://127.0.0.1:10808",LocalProxyConfig.link("socks5",10808,auth));assertFalse(LocalProxyConfig.apply(profile(),auth).getJSONArray("inbounds").getJSONObject(0).has("users"))}
}
