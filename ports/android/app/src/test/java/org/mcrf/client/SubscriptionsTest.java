package org.mcrf.client;

import org.json.*;
import org.junit.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class SubscriptionsTest {
    private static final String A="vless://11111111-1111-4111-8111-111111111111@127.0.0.1:443?security=tls#A", B="trojan://fixture@127.0.0.1:444?security=tls#B";
    private static List<ServerImport.Server> servers(String source) throws Exception { return ServerImport.read(source); }
    @Test public void legacyCatalogueMigrates() throws Exception { ConnectionCatalogue data=new ConnectionCatalogue(new JSONObject().put("version",1).put("servers",new JSONArray().put(A)).toString()); assertEquals(1,data.entries().size()); assertTrue(data.subscriptions().isEmpty()); }
    @Test public void subscriptionRefreshPreservesManualAndIdentity() throws Exception { ConnectionCatalogue data=new ConnectionCatalogue(""); data.add(B); data.replaceSubscription("https://example.com/s?token=fixture",servers(A)); String id=data.entries().get(1).id,sourceId=data.subscriptions().get(0).getString("id");
        data.replaceSubscription("https://example.com/s?token=fixture",servers(A+"\n"+B)); assertEquals(2,data.entries().size()); assertEquals(id,data.entries().get(1).id); assertEquals(sourceId,data.subscriptions().get(0).getString("id")); }
    @Test public void refreshRemovesStaleNodesOnly() throws Exception { ConnectionCatalogue data=new ConnectionCatalogue(""); data.add(B); data.replaceSubscription("https://example.com/s",servers(A)); data.replaceSubscription("https://example.com/s",servers(B)); assertEquals(1,data.entries().size()); assertEquals("B",data.entries().get(0).name); }
    @Test public void emptySubscriptionNeverDeletesOldData() throws Exception { ConnectionCatalogue data=new ConnectionCatalogue(""); data.replaceSubscription("https://example.com/s",servers(A)); String before=data.serialize(); try { data.replaceSubscription("https://example.com/s",Collections.emptyList()); fail(); } catch(IllegalArgumentException expected) { } assertEquals(before,data.serialize()); }
    @Test public void removalKeepsManualDuplicate() throws Exception { ConnectionCatalogue data=new ConnectionCatalogue(""); data.add(A); data.replaceSubscription("https://example.com/s",servers(A+"\n"+B)); data.removeSubscription(data.subscriptions().get(0).getString("id")); assertEquals(1,data.entries().size()); assertEquals("A",data.entries().get(0).name); }
    @Test(expected=IllegalArgumentException.class) public void noPlaintextUrls() { SubscriptionClient.address("http://example.com/secret"); }
    @Test(expected=IllegalArgumentException.class) public void noUrlAuthentication() { SubscriptionClient.address("https://secret@example.com/sub"); }
    @Test(expected=IllegalArgumentException.class) public void noUrlFragments() { SubscriptionClient.address("https://example.com/sub#fragment"); }
    @Test public void downloadBase64AndRedirect() throws Exception { int[] calls={0}; List<ServerImport.Server> result=SubscriptionClient.download("https://example.com/sub",uri->{ calls[0]++; return calls[0]==1?response(302,"/next",""):response(200,null,Base64.getEncoder().encodeToString(A.getBytes(StandardCharsets.UTF_8))); }); assertEquals(2,calls[0]); assertEquals("A",result.get(0).name); }
    @Test public void downgradeIsRejectedBeforeRequest() throws Exception { int[] calls={0}; try { SubscriptionClient.download("https://example.com/sub",uri->{ calls[0]++; return response(302,"http://example.com/plain",""); }); fail(); } catch(IllegalArgumentException expected) { } assertEquals(1,calls[0]); }
    @Test public void redirectLoopIsBounded() throws Exception { int[] calls={0}; try { SubscriptionClient.download("https://example.com/sub",uri->{ calls[0]++; return response(302,"/again",""); }); fail(); } catch(IllegalArgumentException expected) { } assertEquals(6,calls[0]); }
    @Test public void transportErrorsDoNotExposeTokens() throws Exception { try { SubscriptionClient.download("https://example.com/sub?token=private-fixture",uri->{ throw new IOException(uri.toString()); }); fail(); } catch(IllegalArgumentException error) { assertFalse(error.getMessage().contains("private-fixture")); } }
    @Test public void bodyWithoutContentLengthIsBounded() throws Exception { try { SubscriptionClient.download("https://example.com/sub",uri->response(200,null,"a".repeat(ServerImport.MAX_BYTES+1))); fail(); } catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("4 МБ")); } }
    private static HttpURLConnection response(int code,String location,String body) throws IOException {
        return new HttpURLConnection(new URL("https://example.com/fixture")) {
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
            @Override public int getResponseCode() { return code; }
            @Override public String getHeaderField(String name) { return name.equals("Location")?location:null; }
            @Override public long getContentLengthLong() { return -1; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        };
    }
}
