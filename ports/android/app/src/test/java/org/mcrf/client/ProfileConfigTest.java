package org.mcrf.client;

import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class ProfileConfigTest {
    private final String direct = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"final\":\"direct\"},\"inbounds\":[{\"listen\":\"0.0.0.0\"}],\"experimental\":{\"clash_api\":{\"external_controller\":\"0.0.0.0:9090\"}}}";

    @Test public void loopbackOnly() throws Exception {
        JSONObject config = ProfileConfig.build(direct, false, 10808);
        assertEquals(1, config.getJSONArray("inbounds").length());
        assertEquals("127.0.0.1", config.getJSONArray("inbounds").getJSONObject(0).getString("listen"));
        assertFalse(config.has("experimental"));
    }
    @Test public void androidOwnsTun() throws Exception {
        JSONObject config = ProfileConfig.build(direct, true, 10808);
        JSONObject tun = config.getJSONArray("inbounds").getJSONObject(0);
        assertFalse(tun.has("interface_name"));
        assertEquals(2, tun.getJSONArray("address").length());
        assertEquals("gvisor", tun.getString("stack"));
    }
    @Test public void measurementDefaults() { assertEquals(60, ProfileConfig.PROBE_SECONDS); assertEquals(50, ProfileConfig.PROBE_TOLERANCE_MS); }
    @Test(expected = IllegalArgumentException.class) public void rejectPrivilegedPort() throws Exception { ProfileConfig.build(direct, false, 80); }
    @Test(expected = IllegalArgumentException.class) public void rejectNoOutput() throws Exception { ProfileConfig.build("{}", true, 10808); }
    @Test(expected = IllegalArgumentException.class) public void rejectMissingFinal() throws Exception { ProfileConfig.build(direct.replace("\"final\":\"direct\"", "\"final\":\"missing\""), false, 10808); }
    @Test(expected = IllegalArgumentException.class) public void rejectDuplicateTags() throws Exception { ProfileConfig.build("{\"outbounds\":[{\"tag\":\"a\"},{\"tag\":\"a\"}]}", false, 10808); }
    @Test(expected = IllegalArgumentException.class) public void rejectDesktopProcessRules() throws Exception {
        JSONObject config = new JSONObject(direct);
        config.getJSONObject("route").put("rules", new JSONArray().put(new JSONObject().put("process_path", new JSONArray().put("C:\\app.exe"))));
        ProfileConfig.build(config.toString(), true, 10808);
    }
    @Test public void retainPackageRoutes() throws Exception {
        JSONObject config = new JSONObject(direct);
        config.getJSONObject("route").put("rules", new JSONArray().put(new JSONObject().put("package_name", new JSONArray().put("org.example.client")).put("action", "route").put("outbound", "direct")));
        assertEquals("org.example.client", ProfileConfig.build(config.toString(), true, 10808).getJSONObject("route").getJSONArray("rules").getJSONObject(1).getJSONArray("package_name").getString(0));
    }
    @Test public void preserveNativeProtocolMatrix() throws Exception {
        java.io.InputStream input = getClass().getResourceAsStream("/protocols.json");
        assertNotNull(input);
        String source;
        try (input) { source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        JSONObject original = new JSONObject(source);
        JSONObject compiled = ProfileConfig.build(source, true, 10808);
        for (int i = 0; i < original.getJSONArray("outbounds").length(); i++) {
            JSONObject actual = new JSONObject(compiled.getJSONArray("outbounds").getJSONObject(i).toString());
            actual.remove("inet4_bind_address"); actual.remove("inet6_bind_address");
            assertEquals(original.getJSONArray("outbounds").getJSONObject(i).toString(), actual.toString());
        }
        assertEquals(original.getJSONArray("endpoints").toString(), compiled.getJSONArray("endpoints").toString());
        assertEquals(19, compiled.getJSONArray("outbounds").length());
        assertEquals(2, compiled.getJSONArray("endpoints").length());
    }
    @Test(expected = IllegalArgumentException.class) public void requireVpnForPackageRoutes() throws Exception {
        JSONObject config = new JSONObject(direct);
        config.getJSONObject("route").put("rules", new JSONArray().put(new JSONObject().put("package_name", "org.example.client")));
        ProfileConfig.build(config.toString(), false, 10808);
    }
    @Test public void localHopsDoNotBindWifi() throws Exception {
        JSONObject config = ProfileConfig.build("{\"outbounds\":[{\"type\":\"socks\",\"tag\":\"helper\",\"server\":\"127.0.0.1\",\"server_port\":19080}]}", true, 10808);
        assertEquals("127.0.0.1", config.getJSONArray("outbounds").getJSONObject(0).getString("inet4_bind_address"));
        assertEquals("mcrf-loopback", config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound"));
    }
}
