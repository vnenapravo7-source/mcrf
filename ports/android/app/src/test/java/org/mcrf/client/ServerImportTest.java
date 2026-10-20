package org.mcrf.client;

import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ServerImportTest {
    static JSONArray cases() throws Exception {
        try(java.io.InputStream input=ServerImportTest.class.getResourceAsStream("/link-cases.json")) { assertNotNull(input); return new JSONArray(new String(input.readAllBytes(),StandardCharsets.UTF_8)); }
    }
    @Test public void sharedCorpus() throws Exception {
        JSONArray cases=cases();
        for(int i=0;i<cases.length();i++) {
            JSONObject sample=cases.getJSONObject(i); ServerImport.Server entry=ServerImport.read(sample.getString("link")).get(0);
            JSONObject node=entry.outbound==null?entry.endpoint:entry.outbound;
            assertEquals(sample.getString("type"),node.getString("type")); assertEquals(sample.getString("name"),entry.name);
            assertEquals(sample.getString("transport"),node.optJSONObject("transport")==null?"tcp":node.getJSONObject("transport").getString("type"));
        }
    }
    @Test public void selectionDefaults() throws Exception {
        String links=cases().getJSONObject(0).getString("link")+"\n"+cases().getJSONObject(1).getString("link");
        List<ServerImport.Server> entries=ServerImport.read(links); JSONObject config=ServerImport.build(entries,"",true,60,50,"Cloudflare");
        JSONArray outputs=config.getJSONArray("outbounds"); JSONObject group=outputs.getJSONObject(outputs.length()-1);
        assertEquals("60s",group.getString("interval")); assertEquals(50,group.getInt("tolerance")); assertEquals("https",config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("type"));
        JSONObject manual=ServerImport.build(entries,entries.get(1).id,false,60,50,"Системный");
        assertEquals(2,manual.getJSONArray("outbounds").length()); assertFalse(manual.toString().contains("interval"));
    }
    @Test public void credentialsRemainExact() throws Exception {
        ServerImport.Server trojan=ServerImport.read(cases().getJSONObject(5).getString("link")).get(0);
        assertEquals("test:p@ss+word",trojan.outbound.getString("password"));
        assertTrue(ServerImport.read(cases().getJSONObject(13).getString("link")).get(0).outbound.getJSONObject("tls").getBoolean("enabled"));
    }
    @Test public void deduplicateAndDecodeSubscription() throws Exception {
        String link=cases().getJSONObject(0).getString("link"); assertEquals(1,ServerImport.read(link+"\n"+link).size());
        assertEquals(ServerImport.read(link).get(0).id,ServerImport.read(Base64.getEncoder().encodeToString(link.getBytes(StandardCharsets.UTF_8))).get(0).id);
    }
    @Test public void errorsDoNotExposeCredentials() throws Exception {
        try { ServerImport.read(cases().getJSONObject(0).getString("link")+"\ninvalid://very-private-password@host"); fail(); }
        catch(IllegalArgumentException error) { assertFalse(error.getMessage().contains("very-private")); assertTrue(error.getMessage().contains("2")); }
    }
    @Test(expected=IllegalArgumentException.class) public void unavailableEngineDoesNotStart() throws Exception {
        List<ServerImport.Server> entries=ServerImport.read("qwdtt://config?data=fixture"); assertTrue(entries.get(0).needsEngine()); ServerImport.build(entries,entries.get(0).id,false,60,50,"Cloudflare");
    }
    @Test(expected=IllegalArgumentException.class) public void missingSelectionDoesNotFallBack() throws Exception { ServerImport.build(ServerImport.read(cases().getJSONObject(0).getString("link")),"missing",false,60,50,"Cloudflare"); }
    @Test public void vmessWebsocket() throws Exception {
        JSONObject vmess=new JSONObject().put("add","127.0.0.1").put("port","465").put("id","11111111-1111-4111-8111-111111111111").put("net","ws").put("path","/socket").put("tls","tls").put("ps","VMess");
        ServerImport.Server entry=ServerImport.read("vmess://"+Base64.getEncoder().encodeToString(vmess.toString().getBytes(StandardCharsets.UTF_8))).get(0);
        assertEquals("ws",entry.outbound.getJSONObject("transport").getString("type")); assertEquals("VMess",entry.name);
    }
}
