package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Tests only MCRF. No other app data is read or changed. No VPN consent is bypassed. */
public final class DeviceSmokeInstrumentation extends Instrumentation {
    private int passed;
    private String mode;
    private String fixtureName;
    private String expectedExit;
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); mode = arguments == null ? "native" : arguments.getString("mode", "native"); fixtureName = arguments == null ? "direct.json" : arguments.getString("fixture", "direct.json"); expectedExit = arguments == null ? "" : arguments.getString("expectedExit", ""); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        File temporary = new File(getTargetContext().getCacheDir(), "mcrf-test-" + UUID.randomUUID());
        int code = Activity.RESULT_OK;
        try {
            if(mode.equals("p2-expansion-private")){if(!temporary.mkdir())throw new Exception("Cannot create expanded P2 fixture");ExpandedP2DeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("connection-animations")){report.append(ConnectionAnimationDeviceChecks.run(this));check(true,"Whole P0 item40 presentation batch in fresh disposable UID");return;}
            if(mode.equals("usage-colors")){report.append(UsageColorsDeviceChecks.run(this));check(true,"Isolated first-use choices and custom theme colors");return;}
            if(mode.equals("minimalism-readonly")){report.append(UsageMinimalismReadOnlyChecks.run(this));check(true,"All six independent feature combinations, read-only presentation");return;}
            if(mode.equals("routing-warm")){if(!temporary.mkdir())throw new Exception("Cannot create routing warm fixture");RoutingWarmDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("edge-ui")){report.append(ReleaseEdgeUiChecks.run(this));check(true,"Presentation-only edge panel drag/hide/restore");return;}
            if(mode.equals("server-swipe")){report.append(ServerSwipeDeviceChecks.run(this));check(true,"Owned server row edit/ping/swipe action isolation");return;}
            if(mode.equals("deploy-ui")){if(!temporary.mkdir())throw new Exception("Cannot create deployment UI fixture");AndroidDeployUiDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-choice")){report.append(ClientPrimaryChoiceDeviceChecks.run(this));check(true,"Read-only first-launch primary-core chooser presentation");return;}
            if(mode.equals("dual-primary-choice")){report.append(ClientPrimaryChoiceDeviceChecks.runDual(this));check(true,"Read-only installed-both main-traffic chooser presentation");return;}
            if(mode.equals("app-update-ui")){report.append(AppUpdateDialogDeviceChecks.run(this));check(true,"Read-only compact update dialog");return;}
            if(mode.equals("docs-tour")){report.append(DocsTourDeviceChecks.run(this));check(true,"Isolated real-interface documentation tour");return;}
            if(mode.equals("packet-native")){PacketNativeDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("packet-vpn")){PacketVpnDeviceChecks.run(getTargetContext(),fixtureName,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("wg-policy")){WgPolicyDeviceChecks.run(getTargetContext(),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("published-engines")){PublishedEngineDeviceChecks.run(getTargetContext(),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-modules")){ClientPrimaryModuleDeviceChecks.run(getTargetContext(),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-revoke")){ClientPrimaryVpnDeviceChecks.runRevoke(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-vpn")){ClientPrimaryVpnDeviceChecks.run(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("p1-original-owner")){ClientPrimaryVpnDeviceChecks.runOriginalOwner(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},fixtureName);return;}
            if(mode.equals("primary-direct-domain")||mode.equals("primary-direct-domain-inverted")||mode.equals("primary-direct-domain-negative")||mode.equals("primary-direct-domain-inverted-negative")){ClientPrimaryVpnDeviceChecks.runDirectDomain(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},mode.contains("inverted"),mode.endsWith("negative"));return;}
            if(mode.equals("primary-direct")||mode.equals("primary-direct-inverted")||mode.equals("primary-direct-negative")||mode.equals("primary-direct-inverted-negative")){ClientPrimaryVpnDeviceChecks.runDirect(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},mode.contains("inverted"),mode.endsWith("negative"));return;}
            if(mode.equals("primary-retirement")){ClientPrimaryVpnDeviceChecks.runRetirement(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-destroyed-retirement")){ClientPrimaryVpnDeviceChecks.runDestroyedProxyRetirement(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-connect-pending")){ClientPrimaryVpnDeviceChecks.runConnectPending(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("primary-steer-vpn")){steerVpn(temporary);return;}
            if(mode.equals("p1-steer-vpn")){steerVpn(temporary);return;}
            if(mode.equals("p1-steer-proxy")){steerVpn(temporary);return;}
            if(mode.equals("p1-steer-tls")){steerVpn(temporary);return;}
            if(mode.equals("dns-no-edit")){if(!temporary.mkdir())throw new IllegalStateException("Cannot create DNS no-edit fixture");AdvancedDnsNoEditDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("presets-ui")){if(!temporary.mkdir())throw new IllegalStateException("Cannot create preset UI fixture");RoutingPresetUiDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("lan-sharing")){LanSharingDeviceChecks.run(this,fixtureName,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("ssh-primitives")){report.append(ReleaseSshPrimitiveChecks.run(this));check(true,"Local SSH primitives available without network or account access");return;}
            if(mode.equals("primary-spi")){report.append(ReleasePrimarySpiChecks.run(this));check(true,"Parent ABI delivery without loading installed primary engines");return;}
            if(mode.equals("openflux-authorized")){ClientAuthorizedServerDeviceChecks.openFluxDiagnostic(getTargetContext(),message->report.append(message).append('\n'),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("openflux-provider-stage")){ClientAuthorizedServerDeviceChecks.openFluxDiagnostic(getTargetContext(),message->report.append(message).append('\n'),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},true);return;}
            if(mode.equals("openflux-provider-stage-v2")){ClientAuthorizedServerDeviceChecks.openFluxDiagnostic(getTargetContext(),message->report.append(message).append('\n'),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},true,2);return;}
            if(mode.equals("openflux-provider-stage-v3")){ClientAuthorizedServerDeviceChecks.openFluxDiagnostic(getTargetContext(),message->report.append(message).append('\n'),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},true,3);return;}
            if(mode.equals("backup-ui-cancel")){if(!temporary.mkdir())throw new IllegalStateException("Cannot create backup cancellation fixture");BackupUiCancelDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("wireguard-peer")){ClientWireGuardPeerDeviceChecks.run(getTargetContext(),fixtureName,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("authorized-servers")){if(fixtureName!=null && fixtureName.matches("mcrf-relay-input-[0-9a-f]{32}\\.json"))RelayIncidentDeviceChecks.run(this,fixtureName,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});else ClientAuthorizedServerDeviceChecks.run(getTargetContext(),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},!"authorized-relays-only".equals(fixtureName));return;}
            if(mode.equals("warp-authorized")){
                if("human-authorized-warp-timing".equals(fixtureName))WarpTimingDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});
                else WarpAuthorizedDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}},"human-authorized-single-warp-registration".equals(fixtureName));
                return;
            }
            if(mode.equals("settings-pages")){report.append(ReleaseSettingsPageChecks.run(this));check(true,"Read-only settings subpages, proxy icons, background links and WARP home action");return;}
            if(mode.equals("russian-apps")){if(!temporary.mkdir())throw new IllegalStateException("Cannot create installed-app fixture");RoutingDefaultsRussianAppsDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("core-live-api")||mode.equals("core-live-ntp")){
                ProbeCoordinator.INSTANCE.awaitQuiescent(getTargetContext());
                java.util.function.Consumer<String> livePassed=message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}};
                if(mode.equals("core-live-api"))CoreTuningLoopbackDeviceChecks.api(this,livePassed);else CoreTuningLoopbackDeviceChecks.ntp(this,livePassed);
                return;
            }
            if(mode.equals("optional-workers")){ClientOptionalDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("optional-features")){
                try(EngineUpdateGate.Usage usage=EngineUpdateGate.INSTANCE.acquireUsage(getTargetContext())){
                    if(usage==null)throw new IllegalStateException("Engine installation or mutation is active");
                    OptionalCoreDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});
                }
                return;
            }
            if(mode.equals("probe-transition")){ClientProbeTransitionDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("vk-deploy")){if(!temporary.mkdir())throw new Exception("Cannot create VK/deploy fixture");AndroidDeployDeviceChecks.run(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("dnstt-loopback")){if(!temporary.mkdir())throw new IllegalStateException("Cannot create owned DNSTT fixture");DnsttLoopbackDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("dns-runtime")){DnsRuntimeDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("advanced-dns")){check(true,AdvancedDnsDeviceChecks.run(getTargetContext()));return;}
            if(mode.equals("client-runtime")){
                ClientRuntimeDeviceChecks.run(getTargetContext(),message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});
                return;
            }
            if(mode.equals("client-runtime")||mode.startsWith("core-api-")){
                if(!temporary.mkdir())throw new Exception("Cannot create runtime fixture");go.Seq.setContext(getTargetContext().getApplicationContext());
                SetupOptions setup=new SetupOptions();setup.setBasePath(temporary.getAbsolutePath());setup.setWorkingPath(temporary.getAbsolutePath());setup.setTempPath(temporary.getAbsolutePath());setup.setFixAndroidStack(true);Libbox.setup(setup);
                java.util.function.Consumer<String> nativePassed=message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}};
                if(mode.equals("advanced-dns"))check(true,AdvancedDnsDeviceChecks.run(getTargetContext()));
                else if(mode.equals("core-api-singbox"))CoreTuningApiDeviceChecks.singBoxApi(this,nativePassed);
                else if(mode.equals("core-api-mozilla"))CoreTuningApiDeviceChecks.mozillaStore(this,nativePassed);
                else if(mode.equals("core-api-ntp"))CoreTuningApiDeviceChecks.ntp(this,nativePassed);
                else if(mode.equals("core-api-clash"))CoreTuningApiDeviceChecks.clashApi(this,nativePassed);
                else ClientRuntimeDeviceChecks.run(getTargetContext(),nativePassed);
                return;
            }
            if(mode.equals("telegram")){TelegramDeviceChecks.run(this,message->{report.append(message).append("\n");passed++;});return;}
            if(mode.equals("openflux")){if(!temporary.mkdir())throw new Exception("Cannot create OpenFlux fixture");OpenFluxDeviceChecks.run(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("engine-import")){EnginePackageDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("bs-service")){if(!temporary.mkdir())throw new Exception("Cannot create BS fixture");BsServiceDeviceChecks.run(getTargetContext(),temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("provided-carriers")){ProvidedCarrierDeviceChecks.run(getTargetContext(),fixtureName,message->{report.append(message).append("\n");});return;}
            if(mode.equals("preferences")){if(!temporary.mkdir())throw new Exception("Cannot create preference fixture");PreferenceDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("deep-links")){DeepLinkDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("presets")){if(!temporary.mkdir())throw new Exception("Cannot create preset fixture");PresetDeviceChecks.run(this,temporary,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return;}
            if(mode.equals("carriers")){if(!temporary.mkdir())throw new Exception("Cannot create carrier fixture");carrierCheck(temporary);return;}
            if (mode.equals("dns-system")) {
                physicalDnsProbe();
                return;
            }
            if (mode.equals("ui")) { uiCheck(); return; }
            if (mode.equals("ui-design")) { designCheck(); return; }
            if (mode.equals("ui-adaptive")) { adaptiveUi(); return; }
            if (mode.equals("servers-compact")) { compactServers(); return; }
            if (mode.equals("home-fit")) { homeFits(true); ServerGroupDisclosureDeviceChecks.run(this,message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}});return; }
            if (mode.equals("theme-switch")) { homeFits(true); return; }
            if (mode.equals("ui-controls")) { controlsCheck(); return; }
            if (mode.equals("backup")) {
                if(!temporary.mkdir()) throw new Exception("Cannot create backup fixture");
                BackupDeviceChecks.run(getTargetContext(), temporary, message -> { try { check(true,message); } catch(Exception error) { throw new RuntimeException(error); } }); return;
            }
            if (mode.equals("probe")) {
                JSONObject output=new JSONObject().put("type","direct").put("tag","probe-fixture");
                ServerImport.Server node=new ServerImport.Server("fixture:direct-probe","Isolated direct probe","direct","127.0.0.1",0,output,null,null);
                java.util.Map<String,Long> measured=ProbeCoordinator.INSTANCE.measure(getTargetContext(),java.util.List.of(node));
                check(measured.containsKey(node.id),"Probe process returns its isolated node result");
                check(measured.get(node.id)>=0,"Probe uses libbox URL test successfully, not TCP port timing");
                check(!new File(getTargetContext().getCacheDir(),"probe").exists(),"No shared persistent probe directory"); return;
            }
            if (mode.equals("routing-ui")) {
                if(!temporary.mkdir()) throw new Exception("Cannot create routing UI fixture");
                Context isolated=new ContextWrapper(getTargetContext()) { @Override public File getFilesDir() { return temporary; } };
                routingUi(isolated); return;
            }
            if (mode.equals("service-proxy") || mode.equals("service-vpn") || mode.equals("service-routing")) {
                serviceProxy(mode.equals("service-vpn"));
                return;
            }
            if (!temporary.mkdir()) throw new Exception("Cannot create isolated test directory");
            go.Seq.setContext(getTargetContext().getApplicationContext());
            SetupOptions options = new SetupOptions(); options.setBasePath(temporary.getAbsolutePath()); options.setWorkingPath(temporary.getAbsolutePath()); options.setTempPath(temporary.getAbsolutePath()); options.setFixAndroidStack(true);
            Libbox.setup(options);
            check(Libbox.version().contains("1.14.2-lx.11"), "Native library version: " + Libbox.version());
            for (String name : new String[]{"direct.json", "protocols.json"}) {
                String source;
                try (java.io.InputStream input = getContext().getAssets().open(name)) { source = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                for (boolean tun : new boolean[]{false, true}) {
                    JSONObject config = ProfileConfig.build(source, tun, 10808);
                    Libbox.checkConfig(config.toString());
                    check(true, "Native check " + name + (tun ? " TUN" : " proxy"));
                }
            }
            org.json.JSONArray linkCases;
            try (java.io.InputStream input=getContext().getAssets().open("link-cases.json")) { linkCases=new org.json.JSONArray(new String(input.readAllBytes(),StandardCharsets.UTF_8)); }
            java.util.List<ServerImport.Server> imported=new java.util.ArrayList<>();
            for(int i=0;i<linkCases.length();i++) imported.addAll(ServerImport.read(linkCases.getJSONObject(i).getString("link")));
            Libbox.checkConfig(ProfileConfig.build(ServerImport.build(imported,"",true,60,50,"Cloudflare").toString(),true,10808).toString());
            check(true,"Imported link pool accepted by Android native core");
            JSONObject routedState=RouteRules.defaults();
            routedState.getJSONArray("rules").put(new JSONObject().put("id","block").put("name","Block").put("text","blocked.example").put("mode","addresses").put("target","block"))
                .put(new JSONObject().put("id","dns").put("name","DNS").put("text","allowed.example").put("mode","addresses").put("target","direct").put("dns",DnsConfig.defaults()));
            JSONObject routed=RouteRules.apply(ProfileConfig.build(ServerImport.build(imported,"",true,60,50,"Cloudflare").toString(),true,10808),routedState,true);
            Libbox.checkConfig(routed.toString()); check(true,"Ordered routes and list DNS accepted by Android core");
            for(String provider:DnsConfig.PROVIDERS) if(!provider.equals("Свой")) for(String protocol:DnsConfig.protocols(provider)) {
                JSONObject config=ProfileConfig.build(ServerImport.build(imported,"",true,60,50,"Cloudflare").toString(),true,10808);
                DnsConfig.applyGenerated(config,new JSONObject().put("provider",provider).put("protocol",protocol)); Libbox.checkConfig(config.toString());
            }
            check(true,"All built-in DNS transport configurations accepted by Android core");
            final File storeDirectory = new File(temporary, "store"); if (!storeDirectory.mkdir()) throw new Exception("Cannot create profile fixture");
            Context isolated = new ContextWrapper(getTargetContext()) { @Override public File getFilesDir() { return storeDirectory; } };
            ProfileStore store = new ProfileStore(isolated);
            String fixture = "{\"secret\":\"mcrf-smoke-fixture-only\"}";
            store.write(fixture);
            check(fixture.equals(store.read()), "Android Keystore roundtrip");
            String encrypted = new String(Files.readAllBytes(new File(storeDirectory, "profile.enc").toPath()), StandardCharsets.ISO_8859_1);
            check(!encrypted.contains("mcrf-smoke-fixture-only"), "No plaintext profile on disk");
            byte[] bytes = Files.readAllBytes(new File(storeDirectory, "profile.enc").toPath()); bytes[bytes.length - 1] ^= 1; Files.write(new File(storeDirectory, "profile.enc").toPath(), bytes);
            boolean rejected = false; try { store.read(); } catch (Exception expected) { rejected = true; }
            check(rejected, "Reject tampered profile");
            Connections catalogue=new Connections(isolated); catalogue.load();
            check(catalogue.add(linkCases.getJSONObject(0).getString("link"))==1,"Encrypted catalogue import");
            Connections reopened=new Connections(isolated); reopened.load(); check(reopened.entries().size()==1,"Encrypted catalogue restored");
            check(reopened.add(linkCases.getJSONObject(0).getString("link"))==0,"Catalogue duplicate rejected");
            reopened.remove(reopened.entries().get(0).id); check(reopened.entries().isEmpty(),"Catalogue removal saved");
            RoutingStore routes=new RoutingStore(isolated); routes.load(); routes.save(routedState); RoutingStore restored=new RoutingStore(isolated); restored.load(); check(restored.snapshot().getJSONArray("rules").length()==routedState.getJSONArray("rules").length(),"Encrypted routing restored including default rules");
            reopened.add(linkCases.getJSONObject(0).getString("link")); reopened.addSubscription("https://example.com/sub?token=private-fixture",java.util.List.of(imported.get(1)));
            Connections subscriptionRestored=new Connections(isolated); subscriptionRestored.load(); check(subscriptionRestored.entries().size()==2&&subscriptionRestored.subscriptions().size()==1,"Encrypted subscription catalogue restored");
            String encryptedCatalogue=new String(Files.readAllBytes(new File(storeDirectory,"connections.enc").toPath()),StandardCharsets.ISO_8859_1); check(!encryptedCatalogue.contains("private-fixture"),"Subscription URL token never stored as plaintext");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            report.append("FAIL ").append(error.getClass().getName()).append(": ").append(error.getMessage()).append('\n');
            for (StackTraceElement line : error.getStackTrace()) report.append("  ").append(line).append('\n');
        } finally {
            try { delete(temporary, temporary); } catch (Exception error) { code = Activity.RESULT_CANCELED; report.append("FAIL cleanup: ").append(error.getMessage()).append('\n'); }
            result.putString("stream", report.toString() + passed + " checks passed\n");
            result.putInt("passed", passed); finish(code, result);
        }
    }

    private void steerVpn(File temporary) throws Exception {
        check(fixtureName.matches("mcrf-steer-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.json"),"STEER_INPUT_NAME");
        File cache=getTargetContext().getCacheDir().getCanonicalFile();
        File input=new File(cache,fixtureName);
        check(input.getCanonicalFile().getParentFile().equals(cache)&&input.getCanonicalFile().equals(input)&&
            !Files.isSymbolicLink(input.toPath())&&input.isFile()&&input.length()>0&&input.length()<=16384,"STEER_PRIVATE_INPUT");
        check(temporary.mkdir(),"STEER_TEMP_CREATE");
        android.system.Os.chmod(temporary.getPath(),0700);
        File copy=new File(temporary,"steer-fixture-input.json");
        check(copy.createNewFile(),"STEER_TEMP_INPUT");android.system.Os.chmod(copy.getPath(),0600);
        byte[] bytes=Files.readAllBytes(input.toPath());check(bytes.length>0&&bytes.length<=16384,"STEER_INPUT_SIZE");
        Files.write(copy.toPath(),bytes);
        try {
            java.util.function.Consumer<String> sink=message->{try{check(true,message);}catch(Exception error){throw new RuntimeException(error);}};
            if(mode.equals("p1-steer-proxy"))ClientSteerVpnDeviceChecks.runP1Proxy(getTargetContext(),temporary,sink);
            else if(mode.equals("p1-steer-vpn")||mode.equals("p1-steer-tls"))ClientSteerVpnDeviceChecks.runP1(getTargetContext(),temporary,sink);
            else ClientSteerVpnDeviceChecks.run(getTargetContext(),temporary,sink);
        }finally {
            File receipt=new File(temporary,"steer-vpn-device-receipt.json");
            if(receipt.exists()) {
                check(!Files.isSymbolicLink(receipt.toPath())&&receipt.getCanonicalFile().getParentFile().equals(temporary.getCanonicalFile())&&receipt.length()<=8192,"STEER_RECEIPT_PATH");
                JSONObject value=new JSONObject(new String(Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8));
                boolean p1=mode.equals("p1-steer-vpn")||mode.equals("p1-steer-proxy")||mode.equals("p1-steer-tls");
                java.util.Set<String> booleans=new java.util.HashSet<>(java.util.Arrays.asList("actual_gate_passed","android_segment_passed","host_peer_crossmatch_required","revoke_verified","cleanup_verified","user_state_unchanged"));
                if(p1)booleans.add("full_p1_accepted");
                java.util.Set<String> numbers=new java.util.HashSet<>(java.util.Arrays.asList("schema","mainPid","workerPid","generation","vpnNetworkHandle","ownerUid","physicalNetworkHandle","postBodyBytes","protectAndBindAcks","lastBoundNetworkHandle"));
                java.util.Set<String> hashes=new java.util.HashSet<>(java.util.Arrays.asList("inputSha256","getResponseSha256","postResponseSha256"));
                if(p1){hashes.add("moduleSha256");hashes.add("ipv6ResponseSha256");}
                for(java.util.Iterator<String> keys=value.keys();keys.hasNext();) {
                    String key=keys.next();Object field=value.get(key);
                    if(p1&&key.equals("p1_dns")){check(field instanceof org.json.JSONArray,"STEER_RECEIPT_DNS");ClientSteerP1DnsChecks.validateEvidence((org.json.JSONArray)field);}
                    else if(p1&&key.equals("p1_tls")){check(field instanceof org.json.JSONArray,"STEER_RECEIPT_TLS");ClientSteerP1DnsChecks.validateTlsEvidence((org.json.JSONArray)field);}
                    else if(p1&&key.equals("p1_proxy")){check(field instanceof org.json.JSONArray,"STEER_RECEIPT_PROXY");ClientSteerP1ProxyChecks.validateEvidence((org.json.JSONArray)field);}
                    else if(booleans.contains(key))check(field instanceof Boolean,"STEER_RECEIPT_BOOLEAN");
                    else if(numbers.contains(key))check((field instanceof Integer||field instanceof Long)&&((Number)field).longValue()>=0,"STEER_RECEIPT_NUMBER");
                    else if(hashes.contains(key))check(field instanceof String&&((String)field).matches("[0-9a-f]{64}"),"STEER_RECEIPT_HASH");
                    else if(key.equals("stage"))check(field instanceof String&&((String)field).matches(p1?"[A-Z0-9_]{1,64}":"[A-Z_]{1,64}"),"STEER_RECEIPT_STAGE");
                    else if(key.equals("tunInterface"))check(field instanceof String&&((String)field).matches("[A-Za-z0-9_.:-]{1,32}"),"STEER_RECEIPT_INTERFACE");
                    else throw new IllegalStateException("STEER_RECEIPT_UNKNOWN_FIELD");
                }
                check(value.getInt("schema")== (p1?2:1)&&!value.getBoolean("actual_gate_passed")&&value.getBoolean("host_peer_crossmatch_required")&&!value.getBoolean("revoke_verified"),"STEER_RECEIPT_SCOPE");
                if(p1)check(!value.getBoolean("full_p1_accepted"),"STEER_P1_SCOPE");
                report.append("STEER_RECEIPT ").append(value.toString()).append('\n');
            }
        }
    }

    private void serviceProxy(boolean vpn) throws Exception {
        String fixture;
        try (java.io.InputStream input = getContext().getAssets().open(fixtureName)) { fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        if(mode.equals("service-routing")) {
            JSONObject sourceConfig=new JSONObject("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\",\"inet4_bind_address\":\"127.0.0.1\"}],\"dns\":{\"servers\":[{\"type\":\"hosts\",\"tag\":\"chosen\",\"predefined\":{\"allowed.mcrf.invalid\":[\"127.0.0.1\"],\"blocked.mcrf.invalid\":[\"127.0.0.1\"]}}],\"final\":\"chosen\"},\"route\":{\"final\":\"direct\",\"default_domain_resolver\":\"chosen\"}}");
            JSONObject routes=RouteRules.defaults(); routes.getJSONArray("rules").put(new JSONObject().put("id","block").put("name","Block").put("text","blocked.mcrf.invalid").put("mode","addresses").put("target","block"));
            fixture=RouteRules.apply(ProfileConfig.build(sourceConfig.toString(),false,10808),routes,false).toString();
        }
        final String source = fixture;
        final String previousProfile = new ProfileStore(getTargetContext()).read();
        String knownDemo;
        try (java.io.InputStream input = getContext().getAssets().open("direct.json")) { knownDemo = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        if (!previousProfile.isEmpty() && !previousProfile.equals(source) && !previousProfile.equals(knownDemo)) throw new Exception("Existing profile detected: device test will not replace user data");
        if (vpn) { physicalDnsProbe(); check(httpsProbe(), "Internet baseline before VPN"); }
        MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        SystemClock.sleep(500);
        final android.content.SharedPreferences preferences=activity.getPreferences(Context.MODE_PRIVATE);
        final boolean previousTun=preferences.getBoolean("tun",true);
        final int previousPort=preferences.getInt("port",10808);
        final boolean previousApply=preferences.getBoolean("applyRoutes",true);
        final boolean previousCatalogue=preferences.getBoolean("catalogueProfile",false);
        io.nekohasekai.libbox.CommandClient logsClient = null;
        final StringBuilder coreLogs = new StringBuilder();
        try {
            runOnMainSync(() -> {
                activity.openDiagnosticPage();
                View root = activity.getWindow().getDecorView();
                for (EditText editor : views(root, EditText.class)) if (editor.getMinLines() >= 10) editor.setText(source);
                for (EditText editor : views(root, EditText.class)) if (editor.getMinLines() < 10) editor.setText("10808");
                for (Switch control : views(root, Switch.class)) control.setChecked(vpn);
                for (Button button : views(root, Button.class)) if (button.getText().toString().equals("Подключить")) button.performClick();
            });
            if (vpn && android.net.VpnService.prepare(getTargetContext()) != null) { Bundle progress = new Bundle(); progress.putString("stream", "Confirm the Android VPN dialog on the phone.\n"); sendStatus(0, progress); }
            waitForStatus(vpn ? "VPN подключён" : "Прокси ", vpn ? 60000 : 12000); check(true, vpn ? "Android VPN service and TUN started" : "Foreground proxy service started");
            io.nekohasekai.libbox.CommandClientHandler handler = (io.nekohasekai.libbox.CommandClientHandler) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{io.nekohasekai.libbox.CommandClientHandler.class}, (object, method, arguments) -> {
                if (method.getName().equals("writeLogs")) {
                    io.nekohasekai.libbox.LogIterator iterator = (io.nekohasekai.libbox.LogIterator) arguments[0];
                    synchronized (coreLogs) { while (iterator.hasNext()) { String message = iterator.next().getMessage(); if ((!vpn || message.contains("gstatic") || message.contains("local-proxy") || message.contains("mcrf-loopback") || message.contains("inbound/tun[tun-in]: started") || message.contains("sing-box started")) && coreLogs.length() < 64000) coreLogs.append(message).append('\n'); } }
                }
                return null;
            });
            io.nekohasekai.libbox.CommandClientOptions commands = new io.nekohasekai.libbox.CommandClientOptions(); commands.addCommand(Libbox.CommandLog);
            logsClient = Libbox.newCommandClient(handler, commands); logsClient.connect(); SystemClock.sleep(300);
            if (vpn) {
                android.net.ConnectivityManager connectivity = getTargetContext().getSystemService(android.net.ConnectivityManager.class);
                android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
                check(capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN), "VPN is the system default network");
                check(httpsProbe(), "HTTPS and DNS through system VPN");
                if (fixtureName.startsWith("live-vless-")) {
                    javax.net.ssl.HttpsURLConnection proof = (javax.net.ssl.HttpsURLConnection) new java.net.URL("https://api.ipify.org").openConnection(java.net.Proxy.NO_PROXY);
                    proof.setConnectTimeout(8000); proof.setReadTimeout(8000);
                    try {
                        String address = new String(proof.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
                        check(!expectedExit.isEmpty() && address.equals(expectedExit), "Public exit matches temporary VPS (" + fixtureName + ")");
                    } finally { proof.disconnect(); }
                }
                runOnMainSync(() -> activity.moveTaskToBack(true)); SystemClock.sleep(2000);
                check(httpsProbe(), "VPN remains functional with app in background");
            }
            try (java.net.ServerSocket origin = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                origin.setSoTimeout(5000);
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger();
                try {
                    java.util.concurrent.Future<?> responder = executor.submit(() -> {
                        try (java.net.Socket accepted = origin.accept()) {
                            received.incrementAndGet();
                            accepted.setSoTimeout(5000);
                            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(accepted.getInputStream(), StandardCharsets.US_ASCII));
                            String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                            accepted.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nMCRF".getBytes(StandardCharsets.US_ASCII));
                        } catch (Exception error) { throw new RuntimeException(error); }
                    });
                    if(mode.equals("service-routing")) {
                        boolean denied=false;
                        try(java.net.Socket blocked=new java.net.Socket("127.0.0.1",10808)) {
                            blocked.setSoTimeout(5000); blocked.getOutputStream().write(("GET http://blocked.mcrf.invalid:"+origin.getLocalPort()+"/ HTTP/1.1\r\nHost: blocked.mcrf.invalid\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            String response=new String(blocked.getInputStream().readAllBytes(),StandardCharsets.US_ASCII); denied=!response.contains("200 OK")&&!response.endsWith("MCRF");
                        } catch(java.io.IOException expected) { denied=true; }
                        check(denied&&received.get()==0,"Blocked domain never reaches Android local origin");
                    }
                    try (java.net.Socket client = new java.net.Socket("127.0.0.1", 10808)) {
                        client.setSoTimeout(5000);
                        String host=mode.equals("service-routing")?"allowed.mcrf.invalid":"127.0.0.1";
                        String request = "GET http://"+host+":" + origin.getLocalPort() + "/ HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n";
                        client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                        String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                        check(response.contains("200 OK") && response.endsWith("MCRF"), "HTTP through real Android mixed proxy");
                        if(mode.equals("service-routing")) check(received.get()==1,"Allowed domain reaches Android local origin");
                    }
                    responder.get(6, java.util.concurrent.TimeUnit.SECONDS);
                } finally { executor.shutdownNow(); }
            }
            runOnMainSync(() -> { for (Button button : views(activity.getWindow().getDecorView(), Button.class)) if (button.getText().toString().equals("Остановить")) button.performClick(); });
            waitForStatus("Отключено", 12000); check(true, "Foreground service stopped");
            try (java.net.ServerSocket free = new java.net.ServerSocket(10808, 1, java.net.InetAddress.getByName("127.0.0.1"))) { check(true, "Proxy port released"); }
            File[] cache = getTargetContext().getCacheDir().listFiles(); boolean clean = true;
            if (cache != null) for (File item : cache) if (item.getName().startsWith("mcrf-run-")) clean = false;
            check(clean, "Runtime snapshots removed");
        } catch (Exception error) {
            SystemClock.sleep(200);
            if (vpn) report.append("DNS callbacks answers=").append(McrfVpnService.dnsAnswers).append(" errors=").append(McrfVpnService.dnsErrors).append(" code=").append(McrfVpnService.dnsErrorCode).append('\n');
            synchronized (coreLogs) { report.append("CORE LOG (isolated fixture only):\n").append(coreLogs); }
            throw error;
        } finally {
            if (logsClient != null) try { logsClient.disconnect(); } catch (Exception ignored) { }
            runOnMainSync(() -> { getTargetContext().startService(new Intent(getTargetContext(), McrfVpnService.class).setAction(McrfVpnService.STOP)); activity.finish(); });
            SystemClock.sleep(300);
            if(preferences.getBoolean("tun",true)==vpn&&preferences.getInt("port",10808)==10808&&!preferences.getBoolean("applyRoutes",true)&&!preferences.getBoolean("catalogueProfile",false)) preferences.edit().putBoolean("tun",previousTun).putInt("port",previousPort).putBoolean("applyRoutes",previousApply).putBoolean("catalogueProfile",previousCatalogue).commit();
            // The profile was empty before this test. Preserve any profile the user changed concurrently.
            if (new ProfileStore(getTargetContext()).read().equals(source)) {
                if (previousProfile.isEmpty()) new android.util.AtomicFile(new File(getTargetContext().getFilesDir(), "profile.enc")).delete();
                else new ProfileStore(getTargetContext()).write(previousProfile);
            }
        }
    }

    private static boolean httpsProbe() throws Exception {
        javax.net.ssl.HttpsURLConnection connection = (javax.net.ssl.HttpsURLConnection) new java.net.URL("https://www.gstatic.com/generate_204").openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        try { return connection.getResponseCode() == 204; } finally { connection.disconnect(); }
    }

    private void designCheck() throws Exception {
        Loft.apply(false);
        for(int[] pair:new int[][]{{Loft.INK,Loft.BACKGROUND},{Loft.MUTED,Loft.BACKGROUND},{Loft.ON_DARK,Loft.ACCENT},{Loft.ON_DARK,Loft.GRAPHITE}}) {
            double one=luminance(pair[0]),two=luminance(pair[1]); check((Math.max(one,two)+.05)/(Math.min(one,two)+.05)>=4.5,"Small-text palette contrast >= 4.5");
        }
        for(int size:new int[]{96,192,576}) for(boolean active:new boolean[]{false,true}) {
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888);
            try { Loft.Dial dial=new Loft.Dial(true); dial.setBounds(0,0,size,size); dial.state(active,false); dial.draw(new android.graphics.Canvas(bitmap)); boolean inside=true;
                for(int point=0;point<size;point++) if(android.graphics.Color.alpha(bitmap.getPixel(point,0))>0||android.graphics.Color.alpha(bitmap.getPixel(point,size-1))>0||android.graphics.Color.alpha(bitmap.getPixel(0,point))>0||android.graphics.Color.alpha(bitmap.getPixel(size-1,point))>0) inside=false;
                check(inside,"Circular control outline and status dot stay inside "+size+" px bounds (active="+active+")");
            } finally { bitmap.recycle(); }
        }
    }
    private static double luminance(int color) {
        double[] channels={android.graphics.Color.red(color)/255d,android.graphics.Color.green(color)/255d,android.graphics.Color.blue(color)/255d};
        for(int i=0;i<3;i++) channels[i]=channels[i]<=.04045?channels[i]/12.92:Math.pow((channels[i]+.055)/1.055,2.4);
        return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;
    }
    private void carrierCheck(File temporary)throws Exception{
        Context context=getTargetContext();File olc=NativeCarrier.executable(context,"olcrtc"),dnstt=NativeCarrier.executable(context,"dnstt");check(olc.canExecute()&&dnstt.canExecute(),"Installed signature-matched OLC/DNSTT executables are accessible");
        File invalid=new File(temporary,"invalid.json");Files.write(invalid.toPath(),"{}".getBytes(StandardCharsets.UTF_8));
        for(String type:new String[]{"olcrtc","dnstt"}){Process p=new ProcessBuilder(NativeCarrier.executable(context,type).getAbsolutePath(),type.equals("dnstt")?"--mcrf-config":"--qthrone-config",invalid.getAbsolutePath()).redirectErrorStream(true).start();try{check(p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)&&p.exitValue()!=0,type+" executes under Android and rejects invalid configuration");}finally{p.destroyForcibly();}}
        File server=new File(dnstt.getParentFile(),"libdnstt_testserver.so"),priv=new File(temporary,"server.key"),pub=new File(temporary,"server.pub");check(server.canExecute(),"DNSTT fixture server is debug-only installed executable");
        Process key=new ProcessBuilder(server.getAbsolutePath(),"-gen-key","-privkey-file",priv.getAbsolutePath(),"-pubkey-file",pub.getAbsolutePath()).redirectErrorStream(true).start();try{check(key.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)&&key.exitValue()==0,"DNSTT generates isolated Noise key pair");}finally{key.destroyForcibly();}
        String publicKey=new String(Files.readAllBytes(pub.toPath()),StandardCharsets.US_ASCII).trim();int udp;try(java.net.DatagramSocket reserved=new java.net.DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1"))){udp=reserved.getLocalPort();}
        java.util.concurrent.ExecutorService responder=java.util.concurrent.Executors.newCachedThreadPool();
        try(java.net.ServerSocket remote=new java.net.ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))){
            remote.setSoTimeout(15000);java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean(true);java.util.concurrent.atomic.AtomicInteger handled=new java.util.concurrent.atomic.AtomicInteger();
            responder.submit(()->{while(running.get()){try{java.net.Socket socket=remote.accept();responder.submit(()->{try(socket){socket.setSoTimeout(8000);java.io.InputStream in=socket.getInputStream();java.io.OutputStream out=socket.getOutputStream();byte[] methods=in.readNBytes(3);if(!java.util.Arrays.equals(methods,new byte[]{5,1,0}))return;out.write(new byte[]{5,0});byte[] request=in.readNBytes(10);if(request.length!=10||request[0]!=5||request[1]!=1||request[3]!=1)return;out.write(new byte[]{5,0,0,1,127,0,0,1,0,1});java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(in,StandardCharsets.US_ASCII));String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}if(line==null)return;handled.incrementAndGet();out.write("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));byte[] payload=new byte[8192];java.util.Arrays.fill(payload,(byte)'M');out.write(payload);}catch(Exception ignored){}});}catch(Exception unavailable){break;}}});
            Process upstream=new ProcessBuilder(server.getAbsolutePath(),"-udp","127.0.0.1:"+udp,"-privkey-file",priv.getAbsolutePath(),"t.mcrf.invalid","127.0.0.1:"+remote.getLocalPort()).redirectErrorStream(true).start();Thread drain=new Thread(()->{try(java.io.InputStream output=upstream.getInputStream()){byte[] buffer=new byte[4096];while(output.read(buffer)>=0){}}catch(Exception ignored){}});drain.setDaemon(true);drain.start();
            try{
                SystemClock.sleep(200);JSONObject profile=new JSONObject().put("type","dnstt").put("domain","t.mcrf.invalid").put("key",publicKey).put("udp","127.0.0.1:"+udp);
                try(NativeCarrier carrier=NativeCarrier.start(context,profile,()->false)){
                    check(NativeCarrier.socksReady(carrier.port,carrier.username,carrier.password),"DNSTT local listener rejects no-auth and requires per-session credentials");
                    try(java.net.Socket socket=new java.net.Socket("127.0.0.1",carrier.port)){socket.setSoTimeout(12000);java.io.OutputStream out=socket.getOutputStream();java.io.InputStream in=socket.getInputStream();out.write(new byte[]{5,1,2});check(in.read()==5&&in.read()==2,"DNSTT SOCKS selects authenticated method");byte[] user=carrier.username.getBytes(StandardCharsets.UTF_8),password=carrier.password.getBytes(StandardCharsets.UTF_8);out.write(1);out.write(user.length);out.write(user);out.write(password.length);out.write(password);check(in.read()==1&&in.read()==0,"DNSTT accepts only the session credentials");out.write(new byte[]{5,1,0,1,127,0,0,1,0,80});check(in.readNBytes(10).length==10,"DNSTT encrypted tunnel reaches remote SOCKS endpoint");out.write("GET / HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));String response=new String(in.readAllBytes(),StandardCharsets.US_ASCII);check(response.contains("200 OK")&&response.endsWith("M".repeat(8192))&&handled.get()==1,"8192-byte HTTP response traverses the real DNSTT Noise/DNS tunnel");}
                }
                try(java.net.ServerSocket released=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){check(true,"DNSTT fixture is closed");}
            }finally{running.set(false);upstream.destroyForcibly();upstream.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}
        }finally{responder.shutdownNow();}
        boolean clean=true;File[] files=context.getCacheDir().listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith("mcrf-carrier-"))clean=false;check(clean,"Private carrier config and runtime files removed");
    }
    private void homeFits(boolean themeOnly) throws Exception {
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        boolean original=Loft.dark;
        android.content.SharedPreferences appearance=activity.getPrefs();
        String originalPreference=appearance.getString("theme",null);
        try { waitForActivity(activity);
            runOnMainSync(()-> {
                activity.navigate("Маршрутизация");
                View root=activity.getWindow().getDecorView();
                for(int toggle=0;toggle<2;toggle++) {
                    views(root,View.class).stream().filter(v->"theme-action".equals(v.getTag())).findFirst().get().performClick();
                    if(activity.isChangingConfigurations()||activity.isFinishing())throw new AssertionError("Theme recreates the Activity");
                    if(!views(root,View.class).stream().anyMatch(v->"screen-Маршрутизация".equals(v.getTag())))throw new AssertionError("Theme lost the current page");
                }
                activity.navigate("Главная");
            }); waitForIdleSync();
            check(true,"Both theme switches preserve the Activity and current page");
            if(themeOnly)return;
            for(boolean dark:new boolean[]{false,true}) {
                runOnMainSync(()-> { Loft.apply(dark); activity.render(); }); waitForIdleSync();
                for(int[] size:new int[][]{{320,480},{320,568},{360,640},{640,320}}) {
                    java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
                    runOnMainSync(()-> {
                        View root=measureContent(activity,activity.dp(size[0]),activity.dp(size[1]));
                        android.widget.ScrollView scroll=views(root,android.widget.ScrollView.class).get(0);
                        HomeLayout summary=views(root,HomeLayout.class).get(0);
                        if(summary.getHeight()>scroll.getHeight()-scroll.getPaddingTop()-scroll.getPaddingBottom())error.set("Connection controls need scrolling: "+summary.getHeight()+" / "+scroll.getHeight());
                        if(!views(root,View.class).stream().anyMatch(v->"home-servers".equals(v.getTag())))error.set("Embedded servers missing");
                        if(views(root,HomeLayout.class).size()!=1||views(root,ModeSwitch.class).size()!=1||views(root,TelegramControls.class).size()!=1)error.set("Home controls missing");
                        for(Button button:views(summary,Button.class))if(button.getWidth()<=0||button.getHeight()<activity.dp(48))error.set("Small or unmeasured action");
                        try {android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image));
                            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(getTargetContext().getExternalCacheDir(),"mcrf-home-"+(dark?"dark":"light")+"-"+size[0]+"x"+size[1]+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
                        }catch(Exception e){error.set(e.toString());}
                    });check(error.get()==null,"Connection controls fit above the scrolling embedded servers "+size[0]+"x"+size[1]+" "+(dark?"dark":"light")+(error.get()==null?"":" ("+error.get()+")"));
                }
            }
        } finally { runOnMainSync(()-> { if(originalPreference==null)appearance.edit().remove("theme").commit();else appearance.edit().putString("theme",originalPreference).commit();Loft.apply(original); activity.finish(); }); }
    }
    private void controlsCheck() throws Exception {
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        boolean original=Loft.dark;
        try { waitForActivity(activity);
            for(boolean dark:new boolean[]{false,true}) {
                runOnMainSync(()->Loft.apply(dark));
                for(Long delay:new Long[]{null,0L,100L,101L,-1L}) {
                    double one=luminance(Loft.pingColor(delay)),two=luminance(Loft.pingSurface(delay));
                    check((Math.max(one,two)+.05)/(Math.min(one,two)+.05)>=4.5,"Ping badge contrast "+delay+" "+dark);
                }
                java.util.concurrent.atomic.AtomicInteger clicks=new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicReference<Boolean> chosen=new java.util.concurrent.atomic.AtomicReference<>();
                runOnMainSync(()-> {
                    ModeSwitch control=new ModeSwitch(activity,true,value->{clicks.incrementAndGet();chosen.set(value);});
                    control.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(288),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(activity.dp(56),View.MeasureSpec.EXACTLY));control.layout(0,0,control.getMeasuredWidth(),control.getMeasuredHeight());
                    if(!views(control,Button.class).get(0).isSelected())throw new AssertionError("VPN position missing");
                    control.setMode(false,false); if(!views(control,Button.class).get(1).isSelected())throw new AssertionError("Proxy position missing");
                    views(control,Button.class).get(0).performClick();
                });check(clicks.get()==1&&Boolean.TRUE.equals(chosen.get()),"Mode updates do not submit a connection; taps invoke one action "+dark);
                for(boolean auto:new boolean[]{false,true}) {
                    java.util.concurrent.atomic.AtomicReference<ServerRow> result=new java.util.concurrent.atomic.AtomicReference<>();
                    java.util.concurrent.atomic.AtomicInteger selected=new java.util.concurrent.atomic.AtomicInteger();
                    ServerImport.Server node=ServerImport.read("socks://127.0.0.1:18889#Isolated%20server").get(0);
                    runOnMainSync(()-> { ServerRow row=new ServerRow(activity,node,true,true,37L,selected::incrementAndGet,value->{},()->{},auto);result.set(row);
                        row.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(272),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));row.layout(0,0,row.getMeasuredWidth(),row.getMeasuredHeight());row.performClick(); });
                    check(views(result.get(),android.widget.CheckBox.class).get(0).getVisibility()==(auto?View.VISIBLE:View.GONE)&&selected.get()==(auto?0:1)&&(!auto||!views(result.get(),android.widget.CheckBox.class).get(0).isChecked()),"Checkbox visibility and row action follow auto mode "+auto+" "+dark);
                    java.util.concurrent.atomic.AtomicBoolean rendered=new java.util.concurrent.atomic.AtomicBoolean();
                    runOnMainSync(()-> { for(TextView view:views(result.get(),TextView.class))if("37 мс".contentEquals(view.getText())) {
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));int pixels=0;
                        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(bitmap.getPixel(x,y)==Loft.pingColor(37L))pixels++;rendered.set(pixels>10);bitmap.recycle();
                    } });check(rendered.get(),"Numeric ping text really renders on its badge "+auto+" "+dark);
                }
            }
            ServerImport.Server editable=ServerImport.read("vless://11111111-1111-4111-8111-111111111111@fixture.invalid:443?security=tls&type=grpc&serviceName=fixture#Editor%20fixture").get(0);
            java.util.concurrent.atomic.AtomicReference<android.app.AlertDialog> dialog=new java.util.concurrent.atomic.AtomicReference<>();
            runOnMainSync(()->dialog.set(new ConnectionEditor(activity,editable).show()));waitForIdleSync();
            try { dialogLayout(dialog.get(),activity,"connection-editor");
                runOnMainSync(()-> { for(Button button:views(dialog.get().getWindow().getDecorView(),Button.class))if("Расширенные настройки".contentEquals(button.getText()))button.performClick(); });waitForIdleSync();
                dialogLayout(dialog.get(),activity,"connection-advanced");
            }finally{runOnMainSync(()->dialog.get().dismiss());}
        } finally { runOnMainSync(()-> { Loft.apply(original);activity.finish(); }); }
    }
    private void compactServers() throws Exception {
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitForActivity(activity);
            java.util.concurrent.atomic.AtomicInteger selected=new java.util.concurrent.atomic.AtomicInteger(),marked=new java.util.concurrent.atomic.AtomicInteger(),edits=new java.util.concurrent.atomic.AtomicInteger();
            ServerImport.Server node=ServerImport.read("vless://11111111-1111-4111-8111-111111111111@127.0.0.1:443?security=tls&type=grpc&serviceName=test#A%20very%20long%20server%20name%20for%20narrow%20screens").get(0);
            for(int width:new int[]{272,320,592}) {
                java.util.concurrent.atomic.AtomicReference<ServerRow> row=new java.util.concurrent.atomic.AtomicReference<>();
                runOnMainSync(()-> {
                    ServerRow view=new ServerRow(activity,node,true,true,37L,selected::incrementAndGet,value->marked.incrementAndGet(),edits::incrementAndGet,false); row.set(view);
                    view.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
                });
                check(row.get().getHeight()>=activity.dp(64)&&row.get().getHeight()<=activity.dp(76),"Compact server row fits 76 dp at width "+width);
                java.util.concurrent.atomic.AtomicBoolean fits=new java.util.concurrent.atomic.AtomicBoolean(true);
                runOnMainSync(()-> { ServerRow view=row.get(); for(int i=0;i<view.getChildCount();i++){View child=view.getChildAt(i);if(child.getVisibility()!=View.GONE&&(child.getWidth()<=0||child.getLeft()<0||child.getRight()>view.getWidth()))fits.set(false);}
                    for(android.widget.CheckBox box:views(view,android.widget.CheckBox.class))if(box.getVisibility()!=View.GONE&&(box.getWidth()<activity.dp(48)||box.getHeight()<activity.dp(48)))fits.set(false);
                    for(Button button:views(view,Button.class))if(button.getVisibility()!=View.GONE&&(button.getWidth()<activity.dp(48)||button.getHeight()<activity.dp(48)))fits.set(false);
                }); check(fits.get(),"Server title and 48 dp actions are not clipped at "+width);
                runOnMainSync(()-> { row.get().performClick(); views(row.get(),android.widget.CheckBox.class).get(0).toggle();
                    for(Button button:views(row.get(),Button.class))if(!(button instanceof android.widget.CheckBox))button.performClick(); });
            }
            check(selected.get()==3&&marked.get()==3&&edits.get()==3,"Selection, automatic pool and always-visible edit have independent actions");
            runOnMainSync(()-> { try {
                android.widget.LinearLayout list=activity.card(); list.setPadding(activity.dp(4),activity.dp(4),activity.dp(4),activity.dp(4));
                Long[] delays={37L,88L,156L,-1L,null};
                for(int i=0;i<5;i++){if(i>0)activity.divider(list,0,0); list.addView(new ServerRow(activity,node,i==0,i<3,delays[i],()->{},value->{},()->{},false));}
                list.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(320),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));list.layout(0,0,list.getMeasuredWidth(),list.getMeasuredHeight());
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(list.getWidth(),list.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);list.draw(new android.graphics.Canvas(image));
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(getTargetContext().getExternalCacheDir(),"mcrf-ui-compact-servers.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            }catch(Exception e){throw new RuntimeException(e);} });
        } finally { runOnMainSync(activity::finish); }
    }
    private void uiCheck() throws Exception {
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        boolean originalTheme=Loft.dark;
        try {
            SystemClock.sleep(800); waitForIdleSync();
            for(boolean dark:new boolean[]{false,true}) {
            runOnMainSync(()->{Loft.apply(dark);activity.render();});waitForIdleSync();
            boolean routesPrepared=false;
            for(String page:new String[]{"Главная","Маршрутизация","Главная","Маршрутизация","Автодеплой","Логи","Настройки"}) {
                java.util.concurrent.atomic.AtomicBoolean navigated=new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicLong navigationMillis=new java.util.concurrent.atomic.AtomicLong();
                runOnMainSync(()-> { for(Button button:views(activity.getWindow().getDecorView(),Button.class)) if(page.contentEquals(button.getContentDescription()==null?"":button.getContentDescription())) {navigated.set(true);long started=SystemClock.elapsedRealtime();button.performClick();navigationMillis.set(SystemClock.elapsedRealtime()-started);break;} });
                check(navigated.get(),"Current navigation action exists: "+page);
                if(page.equals("Маршрутизация")){
                    if(routesPrepared){
                        java.util.concurrent.atomic.AtomicBoolean synchronousReady=new java.util.concurrent.atomic.AtomicBoolean();
                        runOnMainSync(()->synchronousReady.set(activity.getWindow().getDecorView().findViewWithTag("rules-heading")!=null));
                        check(synchronousReady.get(),"Unchanged routes reopen from prepared local presentation without background loading");
                    }
                    long started=SystemClock.elapsedRealtime();java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
                    while(!ready.get()&&SystemClock.elapsedRealtime()-started<10000){runOnMainSync(()->ready.set(activity.getWindow().getDecorView().findViewWithTag("rules-heading")!=null));if(!ready.get())SystemClock.sleep(20);}
                    check(ready.get(),"Routes background presentation completes");
                    report.append("ROUTES_NAVIGATION_DISPATCH_MS=").append(navigationMillis.get()).append(" READY_WAIT_MS=").append(SystemClock.elapsedRealtime()-started).append(" THEME=").append(dark?"dark":"light").append('\n');
                    routesPrepared=true;
                }
                waitForIdleSync(); SystemClock.sleep(200);
                final java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>();
                runOnMainSync(()-> {
                    View root=measureContent(activity); int[] frame=new int[2]; root.getLocationOnScreen(frame); int visible=0;
                    for(Button button:views(root,Button.class)) if(visibleWithin(button,root)) {
                        visible++;
                        int[] position=new int[2]; button.getLocationOnScreen(position);
                        if(!horizontalFits(button,root)) failure.set("Horizontal overflow: "+button.getText()+" description="+button.getContentDescription()+" tag="+button.getTag()+" bounds="+globalBounds(button)+" root="+globalBounds(root));
                    }
                    if(visible<3) failure.set("Screen has no measured navigation controls");
                });
                check(failure.get()==null,"Mobile "+page+" buttons within measured device viewport"+(failure.get()==null?"":" ("+failure.get()+")"));
                for(int[] size:new int[][]{{320,640},{640,320}}) {
                    final java.util.concurrent.atomic.AtomicBoolean fits=new java.util.concurrent.atomic.AtomicBoolean(true);
                    runOnMainSync(()-> {
                        float density=getTargetContext().getResources().getDisplayMetrics().density;
                        View root=measureContent(activity,Math.round(size[0]*density),Math.round(size[1]*density)); int[] frame=new int[2]; root.getLocationOnScreen(frame);
                        for(Button button:views(root,Button.class)) if(visibleWithin(button,root)) {
                            int[] position=new int[2]; button.getLocationOnScreen(position);
                            if(!horizontalFits(button,root)) fits.set(false);
                        }
                    });
                    check(fits.get(),"Mobile "+page+" layout "+size[0]+"x"+size[1]+" dp");
                }
                // Render only MCRF's own view tree, never another app or the lock screen.
                // Restore the actual window and let TextView autosize/layout settle after
                // synthetic portrait/landscape measurements before capturing its pixels.
                runOnMainSync(()->{measureContent(activity);activity.getWindow().getDecorView().requestLayout();});
                waitForIdleSync();SystemClock.sleep(100);
                final java.util.concurrent.atomic.AtomicReference<Exception> renderError=new java.util.concurrent.atomic.AtomicReference<>();
                final String previewName="mcrf-ui-"+(page.equals("Главная")?"home":page.equals("Маршрутизация")?"routes":page.equals("Автодеплой")?"deploy":page.equals("Логи")?"logs":"settings")+(dark?"-dark":"-light")+".png";
                runOnMainSync(()-> {
                    try {
                        View root=measureContent(activity);
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                        root.draw(new android.graphics.Canvas(bitmap));
                        File image=new File(getTargetContext().getExternalCacheDir(),previewName);
                        try(java.io.FileOutputStream output=new java.io.FileOutputStream(image)) { if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)) throw new Exception("Preview compression failed"); }
                        finally { bitmap.recycle(); }
                    } catch(Exception error) { renderError.set(error); }
                });
                if(renderError.get()!=null) throw renderError.get();
                if(page.equals("Серверы")) {
                    final java.util.concurrent.atomic.AtomicBoolean hidden=new java.util.concurrent.atomic.AtomicBoolean(true);
                    runOnMainSync(()-> { View root=measureContent(activity); for(TextView label:views(root,TextView.class)) if(label.getText().toString().equals("Замер раз в, с")&&visibleWithin(label,root)) hidden.set(false); });
                    check(hidden.get(),"Measurement controls hidden with automatic selection off");
                }
            }
            }
        } finally { runOnMainSync(()->{Loft.apply(originalTheme);activity.finish();}); }
    }

    private void adaptiveUi() throws Exception {
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ActivityMonitor monitor=null;
        try {
            waitForActivity(activity); final MainActivity first=activity;
            runOnMainSync(()-> { for(Button button:views(first.getWindow().getDecorView(),Button.class)) if("Настройки".contentEquals(button.getContentDescription()==null?"":button.getContentDescription())) { button.performClick(); break; } }); waitForIdleSync();
            java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>();
            runOnMainSync(()-> {
                View root=measureContent(first,first.dp(320),first.dp(640));
                android.view.WindowInsets keyboard=new android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.ime(),android.graphics.Insets.of(0,0,0,first.dp(200))).build(); root.dispatchApplyWindowInsets(keyboard);
                root=measureContent(first,first.dp(320),first.dp(640));
                if(root.getPaddingBottom()<first.dp(200)) failure.set("Keyboard inset ignored");
                for(Button button:views(root,Button.class)) if("Маршрутизация".contentEquals(button.getContentDescription()==null?"":button.getContentDescription())) { int[] pos=new int[2],frame=new int[2]; button.getLocationOnScreen(pos); root.getLocationOnScreen(frame); if(pos[1]+button.getHeight()>frame[1]+root.getHeight()-root.getPaddingBottom()) failure.set("Navigation overlaps keyboard"); }
                root.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder().build());
            }); check(failure.get()==null,"Own app layout respects simulated keyboard inset"+(failure.get()==null?"":" ("+failure.get()+")"));
            monitor=addMonitor(MainActivity.class.getName(),null,false); runOnMainSync(first::recreate);
            Activity replacement=monitor.waitForActivityWithTimeout(10_000); check(replacement instanceof MainActivity,"Activity recreated without crashing"); activity=(MainActivity)replacement; waitForActivity(activity); final MainActivity restored=activity;
            java.util.concurrent.atomic.AtomicBoolean samePage=new java.util.concurrent.atomic.AtomicBoolean(); runOnMainSync(()-> { View root=measureContent(restored); for(TextView label:views(root,TextView.class)) if(label.getText().toString().equals("Локальный прокси")) samePage.set(true); });
            check(samePage.get(),"Settings page restored after activity recreation");
        } finally { if(monitor!=null) removeMonitor(monitor); MainActivity current=activity; runOnMainSync(current::finish); }
    }
    private void routingUi(Context isolated) throws Exception {
        RoutingStore routes=new RoutingStore(isolated); routes.load(); JSONObject initial=RouteRules.defaults();
        initial.getJSONArray("rules").put(new JSONObject().put("id","ui-fixture").put("name","Fixture route").put("text","example.com").put("mode","addresses").put("target","block").put("enabled",true)); routes.save(initial);
        Intent intent=new Intent(getTargetContext(),MainActivity.class).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)startActivitySync(intent);
        java.util.concurrent.atomic.AtomicReference<android.app.AlertDialog> shown=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            waitForActivity(activity);
            java.util.concurrent.atomic.AtomicReference<android.widget.LinearLayout> page=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Switch> toggle=new java.util.concurrent.atomic.AtomicReference<>();
            runOnMainSync(()-> {
                android.widget.LinearLayout form=new android.widget.LinearLayout(activity); form.setOrientation(android.widget.LinearLayout.VERTICAL); new RoutingPage(activity,routes).build(form); page.set(form);
                int width=activity.dp(320); form.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)); form.layout(0,0,width,form.getMeasuredHeight());
                for(Switch control:views(form,Switch.class)) if(control.getText().toString().equals("Fixture route")) { toggle.set(control); control.setChecked(false); }
            });
            check(toggle.get()!=null,"Routing list has measured switch"); waitForActivity(activity);
            check(!routeById(routes.snapshot(),"ui-fixture").getBoolean("enabled"),"Routing switch persists disabled state");
            check(toggle.get().isEnabled()&&views(page.get(),Switch.class).contains(toggle.get()),"Routing switch updates without rebuilding the page");
            JSONObject disabled=routeById(routes.snapshot(),"ui-fixture");
            runOnMainSync(()->shown.set(new RoutingPage(activity,routes).edit(disabled))); waitForIdleSync(); check(shown.get()!=null,"Routing editor opens");
            runOnMainSync(()-> { java.util.List<EditText> fields=views(shown.get().getWindow().getDecorView(),EditText.class); fields.get(0).setText("Changed fixture"); fields.get(1).setText("DOMAIN,blocked.mcrf.invalid"); });
            dialogLayout(shown.get(),activity,"routes-editor");
            runOnMainSync(()->shown.get().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()); waitForActivity(activity);
            JSONObject edited=routeById(routes.snapshot(),"ui-fixture");
            check(edited.getString("name").equals("Changed fixture")&&!edited.getBoolean("enabled")&&edited.getString("text").contains("blocked.mcrf.invalid"),"Routing editor saves without reverting switch");
            shown.set(null); runOnMainSync(()->shown.set(activity.protocols())); waitForIdleSync(); check(shown.get()!=null,"Protocol table opens from packaged catalogue"); dialogLayout(shown.get(),activity,"protocols");
        } finally { runOnMainSync(()-> { if(shown.get()!=null) shown.get().dismiss(); activity.finish(); }); }
    }
    private static JSONObject routeById(JSONObject state,String id) throws Exception {
        org.json.JSONArray rules=state.getJSONArray("rules");
        for(int i=0;i<rules.length();i++)if(id.equals(rules.getJSONObject(i).optString("id")))return rules.getJSONObject(i);
        throw new Exception("Fixture route missing: "+id);
    }
    private void waitForActivity(MainActivity activity) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10_000;
        while(true) { java.util.concurrent.atomic.AtomicBoolean working=new java.util.concurrent.atomic.AtomicBoolean(); runOnMainSync(()->working.set(activity.working())); if(!working.get()) { waitForIdleSync(); return; } if(SystemClock.elapsedRealtime()>deadline) throw new Exception("Activity work did not complete"); SystemClock.sleep(50); }
    }
    private void dialogLayout(android.app.AlertDialog dialog,MainActivity activity,String name) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(()-> {
            try {
                View root=dialog.getWindow().getDecorView(); int width=activity.dp(320),height=activity.dp(640); root.forceLayout(); root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST)); root.layout(0,0,width,root.getMeasuredHeight());
                int[] frame=new int[2]; root.getLocationOnScreen(frame); int measured=0;
                for(TextView label:views(root,TextView.class)) if(visibleWithin(label,root)) { measured++; int[] pos=new int[2]; label.getLocationOnScreen(pos); if(label.getWidth()<=0||pos[0]<frame[0]||pos[0]+label.getWidth()>frame[0]+width) failure.set("Dialog text overflows: "+label.getText()); }
                if(measured<5) failure.set("Dialog has no measured fields");
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888); root.draw(new android.graphics.Canvas(bitmap));
                try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(getTargetContext().getExternalCacheDir(),"mcrf-ui-"+name+".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); } finally { bitmap.recycle(); }
            } catch(Exception error) { failure.set(error.toString()); }
        });
        check(failure.get()==null,"Dialog "+name+" fits 320 dp"+(failure.get()==null?"":" ("+failure.get()+")"));
    }
    private static View measureContent(Activity activity) {
        View decor=activity.getWindow().getDecorView(); int width=decor.getWidth(),height=decor.getHeight();
        return measureContent(activity,width,height);
    }
    private static View measureContent(Activity activity,int width,int height) {
        ViewGroup frame=activity.findViewById(android.R.id.content); View content=frame.getChildAt(0);
        // Android may defer layout while the display is asleep. Test only the app's own tree,
        // at the actual viewport size; do not wake/unlock or resize the user's device.
        content.forceLayout(); content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); content.layout(0,0,width,height);
        return content;
    }
    private static android.graphics.RectF globalBounds(View view) {
        android.graphics.Matrix matrix=new android.graphics.Matrix();
        view.transformMatrixToGlobal(matrix);
        android.graphics.RectF bounds=new android.graphics.RectF(0,0,view.getWidth(),view.getHeight());
        matrix.mapRect(bounds);return bounds;
    }
    private static boolean horizontalFits(View view,View root) {
        android.graphics.RectF bounds=globalBounds(view),frame=globalBounds(root);
        // getLocationOnScreen is the transformed origin, not the bounding-box left
        // for rotated arrows. Compare the entire transformed rectangle, without clipping it.
        return view.getWidth()>0&&bounds.left>=frame.left-1&&bounds.right<=frame.right+1;
    }
    private static boolean visibleWithin(View view,View root) {
        for(View item=view;item!=null;) { if(item.getVisibility()!=View.VISIBLE) return false; if(item==root) return true; android.view.ViewParent parent=item.getParent(); item=parent instanceof View?(View)parent:null; }
        return false;
    }

    private void physicalDnsProbe() throws Exception {
        android.net.ConnectivityManager manager = getTargetContext().getSystemService(android.net.ConnectivityManager.class);
        android.net.Network physical = null;
        for (android.net.Network network : manager.getAllNetworks()) {
            android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && (physical == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))) physical = network;
        }
        if (physical == null) throw new Exception("Missing physical network for DNS probe");
        // Only a public test hostname; no browser traffic or private network names.
        for (int type : new int[]{android.net.DnsResolver.TYPE_A, android.net.DnsResolver.TYPE_AAAA}) {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> answer = new java.util.concurrent.atomic.AtomicReference<>("timeout");
            android.os.CancellationSignal signal = new android.os.CancellationSignal();
            android.net.DnsResolver.getInstance().query(physical, "www.gstatic.com", type,
                android.net.DnsResolver.FLAG_NO_CACHE_LOOKUP | android.net.DnsResolver.FLAG_NO_CACHE_STORE | android.net.DnsResolver.FLAG_NO_RETRY,
                Runnable::run, signal, new android.net.DnsResolver.Callback<java.util.List<java.net.InetAddress>>() {
                    @Override public void onAnswer(java.util.List<java.net.InetAddress> addresses, int rcode) {
                        answer.set("rcode=" + rcode + " " + addresses.stream().map(java.net.InetAddress::getHostAddress).collect(java.util.stream.Collectors.joining(","))); done.countDown();
                    }
                    @Override public void onError(android.net.DnsResolver.DnsException error) { answer.set("error=" + error.code); done.countDown(); }
                });
            if (!done.await(8, java.util.concurrent.TimeUnit.SECONDS)) signal.cancel();
            report.append("Physical DNS without MCRF/cache, type=").append(type).append(": ").append(answer.get()).append('\n');
        }
    }

    private void waitForStatus(String prefix, int milliseconds) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (SystemClock.elapsedRealtime() < deadline) {
            String state = McrfVpnService.status;
            if (state.startsWith(prefix)) return;
            if (state.startsWith("Ошибка")) throw new Exception(state);
            SystemClock.sleep(100);
        }
        throw new Exception("Service timeout: " + McrfVpnService.status);
    }

    private static <T> java.util.List<T> views(View root, Class<T> type) {
        java.util.List<T> found = new java.util.ArrayList<>(); if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) { ViewGroup group = (ViewGroup) root; for (int i = 0; i < group.getChildCount(); i++) found.addAll(views(group.getChildAt(i), type)); }
        return found;
    }

    private void check(boolean condition, String name) throws Exception { if (!condition) throw new Exception(name); passed++; report.append("PASS ").append(name).append('\n'); }
    private static void delete(File root, File item) throws Exception {
        String base = root.getCanonicalPath(); String path = item.getCanonicalPath();
        if (!root.getName().startsWith("mcrf-test-") || !(path.equals(base) || path.startsWith(base + File.separator))) throw new Exception("Unsafe fixture cleanup");
        if (!item.exists()) return;
        if (item.isDirectory()) { File[] children = item.listFiles(); if (children == null) throw new Exception("Unreadable fixture directory"); for (File child : children) delete(root, child); }
        if (!item.delete()) throw new Exception("Could not remove test fixture");
    }
}
