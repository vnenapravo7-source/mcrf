package org.mcrf.engine.spi.v1;

/** SPI 1 candidate. Shared by host parent loader; never duplicated in a module APK. */
public interface Engine {
    int SPI_VERSION = 1;
    String MODULE_PACKAGE = "org.mcrf.engines.steer";
    String ENTRY_CLASS = "org.mcrf.engines.steer.SteerEngine";
    /** Choice IDs and manifest kinds intentionally differ for sing-box; never compare them directly. */
    enum Kind {
        SING_BOX("singbox","sing-box","org.mcrf.engines.singbox","org.mcrf.engines.singbox.SingBoxEngine",1),
        STEER("steer","steer",MODULE_PACKAGE,ENTRY_CLASS,2);
        public final String choiceId,manifestKind,packageName,entryClass;public final int coreApi;
        Kind(String id,String kind,String pkg,String entry,int api){choiceId=id;manifestKind=kind;packageName=pkg;entryClass=entry;coreApi=api;}
        public static Kind fromChoiceId(String id){for(Kind k:values())if(k.choiceId.equals(id))return k;throw new IllegalArgumentException("UNKNOWN_PRIMARY_CHOICE");}
        public boolean matchesManifestKind(String kind){return manifestKind.equals(kind);}
    }

    /** Informational only. Host verifies installed package identity before loading any code. */
    String capabilitiesJson();

    /** Pure preflight; must not open TUN, sockets or native worker. Unknown settings fail. */
    Validation validate(String serverUrl, String immutableSettingsJson);
    enum Mode { VPN, PROXY }
    enum TunAcquisition { NONE, PROVIDED, DEFERRED }
    /** Additive mode-aware preflight. Engines must explicitly validate proxy profiles. */
    default Validation validate(String profile, String settings, Mode mode) {
        return mode == Mode.VPN ? validate(profile,settings) : new Validation(false,"PROXY_NOT_SUPPORTED","mode");
    }
    /** DEFERRED needs a core override after native config/authoritative-plan capability validation. */
    default Validation validate(String profile,String settings,Mode mode,TunAcquisition acquisition) {
        if(acquisition==TunAcquisition.DEFERRED)return new Validation(false,"DEFERRED_TUN_NOT_SUPPORTED","tunAcquisition");
        if((mode==Mode.VPN&&acquisition!=TunAcquisition.PROVIDED)||(mode==Mode.PROXY&&acquisition!=TunAcquisition.NONE))
            return new Validation(false,"TUN_ACQUISITION_MODE","tunAcquisition");
        return validate(profile,settings,mode);
    }

    /** Host applies this immutable TUN plan; module never creates a VpnService/Builder. */
    String tunPlanJson(String serverUrl, String immutableSettingsJson);

    /** Blocking worker call; one call per fresh host-owned process, after validation. */
    void run(Request request, Host host) throws Exception;

    /** Best effort closure of Java/DNS resources; native termination requires worker exit. */
    void requestStop();

    /** Payload only. An ONLINE indication does not imply current server reachability. */
    Counters counters();
    /** Per-field availability: a core with TX/RX but no blocked counter must not invent zero. */
    default Stats stats() {
        Counters c=counters();
        return c.available ? new Stats(Metric.of(c.txPayload),Metric.of(c.rxPayload),Metric.of(c.blockedAttempts))
                : new Stats(Metric.unavailable(),Metric.unavailable(),Metric.unavailable());
    }
    final class Metric {
        /** null means unavailable; an observed zero is Long.valueOf(0). */
        public final Long value;
        private Metric(Long value){this.value=value;}
        public static Metric of(long value){if(value<0)throw new IllegalArgumentException("NEGATIVE_METRIC");return new Metric(value);}
        public static Metric unavailable(){return new Metric(null);}
    }
    final class Stats {
        public final Metric txPayload,rxPayload,blockedAttempts;
        public Stats(Metric tx,Metric rx,Metric blocked){
            if(tx==null||rx==null||blocked==null)throw new IllegalArgumentException("STATS_METRICS_REQUIRED");
            txPayload=tx;rxPayload=rx;blockedAttempts=blocked;
        }
        public boolean anyAvailable(){return txPayload.value!=null||rxPayload.value!=null||blockedAttempts.value!=null;}
        public boolean allAvailable(){return txPayload.value!=null&&rxPayload.value!=null&&blockedAttempts.value!=null;}
    }

    enum Command { STATS, STOP, RELOAD }
    final class ControlRequest {
        public final long generation;public final Command command;
        public ControlRequest(long generation,Command command){if(generation<=0||command==null)throw new IllegalArgumentException("CONTROL");this.generation=generation;this.command=command;}
    }
    final class ControlReply {
        public final String code;public final Counters counters;public final Stats stats;
        public ControlReply(String code,Counters counters){this(code,counters,null);}
        private ControlReply(String code,Counters counters,Stats stats){if(code==null)throw new IllegalArgumentException("CONTROL_REPLY");this.code=code;this.counters=counters;this.stats=stats;}
        public static ControlReply forStats(Stats s){
            if(s==null)throw new IllegalArgumentException("STATS_REQUIRED");
            Counters legacy=s.allAvailable()?new Counters(s.txPayload.value,s.rxPayload.value,s.blockedAttempts.value):null;
            return new ControlReply(s.allAvailable()?"STATS":s.anyAvailable()?"STATS_PARTIAL":"STATS_UNAVAILABLE",legacy,s);
        }
    }
    /** Host generation gate is mandatory. STOP is a request, never a cleanup/death ACK. */
    default ControlReply control(ControlRequest request) {
        if(request.command==Command.STATS)return ControlReply.forStats(stats());
        if(request.command==Command.STOP){requestStop();return new ControlReply("STOP_REQUESTED_NOT_TERMINATED",null);}
        return new ControlReply("FRESH_WORKER_REQUIRED",null);
    }

    interface Host {
        /**
         * Called synchronously BEFORE connect, in the worker where fd is valid.
         * Host must protect AND bind the socket to the selected physical network.
         * If host VpnService is elsewhere, transfer a duplicate PFD via Binder;
         * never send the integer alone. ACK true only after both operations.
         * Timeout/stale generation/dead peer => false, native closes its original.
         * Host closes only its duplicate, including on exceptions.
         */
        boolean prepareSocket(long generation, int borrowedSocketFd);
        /** Host ignores old generation events. State means engine lifecycle, not reachability. */
        void state(long generation, String state, String reasonCode);
        boolean stopping(long generation);
        /** Actual application package, for mandatory in-VPN managed DNS ownership. */
        String packageName();
        /** Additive typed platform; absence is explicit, never a fabricated callback success. */
        default Platform platform(){throw new UnsupportedOperationException("HOST_PLATFORM_UNAVAILABLE");}
    }

    final class Request {
        public final long generation;
        /** Worker-local borrowed fd. Native duplicates CLOEXEC; host retains ownership. */
        public final int borrowedTunFd;
        public final String settingsJson, serverUrl, dialIpv4, stateDirectory;
        public final long vpnNetworkHandle;
        public final Mode mode;
        public final TunAcquisition tunAcquisition;
        private final String[] dnsBootstrapIpv4;
        public Request(long generation, int borrowedTunFd, String settingsJson,
                       String serverUrl, String dialIpv4, String stateDirectory) {
            this(generation, borrowedTunFd, settingsJson, serverUrl, dialIpv4, stateDirectory, 0, new String[0]);
        }
        /** VPN handle from host; DNS bootstrap already resolved on physical network BEFORE TUN. */
        public Request(long generation, int borrowedTunFd, String settingsJson,
                       String serverUrl, String dialIpv4, String stateDirectory,
                       long vpnNetworkHandle, String[] dnsBootstrapIpv4) {
            this(generation,borrowedTunFd,settingsJson,serverUrl,dialIpv4,stateDirectory,vpnNetworkHandle,dnsBootstrapIpv4,Mode.VPN,null);
        }
        /** Proxy has fd=-1 ONLY with a supported proxy preflight bound to these exact inputs. */
        public Request(long generation, int borrowedTunFd, String settingsJson,
                       String serverUrl, String dialIpv4, String stateDirectory,
                       long vpnNetworkHandle, String[] dnsBootstrapIpv4, Mode mode, Validation validation) {
            this(generation,borrowedTunFd,settingsJson,serverUrl,dialIpv4,stateDirectory,vpnNetworkHandle,
                    dnsBootstrapIpv4,mode,validation,mode==Mode.PROXY?TunAcquisition.NONE:TunAcquisition.PROVIDED,null);
        }
        /** Deferred VPN has no fd until the host's single authoritative openTun callback/ACK. */
        public Request(long generation,int borrowedTunFd,String settingsJson,String serverUrl,String dialIpv4,
                       String stateDirectory,long vpnNetworkHandle,String[] dnsBootstrapIpv4,Mode mode,
                       Validation validation,TunAcquisition acquisition,String hostVerifiedCapabilitySha256) {
            if (generation <= 0 || mode == null || settingsJson == null || serverUrl == null
                    || dialIpv4 == null || stateDirectory == null || vpnNetworkHandle < 0
                    || dnsBootstrapIpv4 == null || dnsBootstrapIpv4.length > 8) throw new IllegalArgumentException("Missing run input");
            if(acquisition==null)throw new IllegalArgumentException("TUN_ACQUISITION_REQUIRED");
            if(mode==Mode.VPN&&acquisition==TunAcquisition.PROVIDED&&borrowedTunFd<0)throw new IllegalArgumentException("VPN_TUN_REQUIRED");
            if(mode==Mode.VPN&&acquisition==TunAcquisition.NONE)throw new IllegalArgumentException("VPN_TUN_REQUIRED");
            if(mode==Mode.VPN&&acquisition==TunAcquisition.DEFERRED&&(borrowedTunFd!=-1||vpnNetworkHandle!=0
                    ||validation==null||!validation.supported||validation.mode!=Mode.VPN
                    ||validation.tunAcquisition!=TunAcquisition.DEFERRED
                    ||!fingerprint(serverUrl,settingsJson).equals(validation.inputFingerprint)
                    ||hostVerifiedCapabilitySha256==null||!hostVerifiedCapabilitySha256.equals(validation.capabilityFingerprint)))
                throw new IllegalArgumentException("VALIDATED_DEFERRED_VPN_REQUIRED");
            if (mode == Mode.PROXY && (acquisition!=TunAcquisition.NONE||borrowedTunFd != -1 || vpnNetworkHandle != 0 || validation == null
                    || !validation.supported || validation.mode != Mode.PROXY
                    || !fingerprint(serverUrl,settingsJson).equals(validation.inputFingerprint)))
                throw new IllegalArgumentException("VALIDATED_PROXY_PROFILE_REQUIRED");
            this.generation = generation; this.borrowedTunFd = borrowedTunFd;
            this.settingsJson = settingsJson; this.serverUrl = serverUrl;
            this.dialIpv4 = dialIpv4; this.stateDirectory = stateDirectory;
            this.vpnNetworkHandle = vpnNetworkHandle; this.dnsBootstrapIpv4 = dnsBootstrapIpv4.clone();
            this.mode=mode;
            tunAcquisition=acquisition;
        }
        public String[] dnsBootstrapIpv4() { return dnsBootstrapIpv4.clone(); }
    }
    final class Validation {
        public final boolean supported;
        public final String code, field;
        public final Mode mode;
        public final TunAcquisition tunAcquisition;
        private final String inputFingerprint;
        private final String capabilityFingerprint;
        public Validation(boolean supported, String code, String field) {
            this(supported,code,field,Mode.VPN,"",TunAcquisition.PROVIDED,"");
        }
        private Validation(boolean supported,String code,String field,Mode mode,String fingerprint,TunAcquisition acquisition,String capability) {
            if (code == null || field == null || (!supported && code.isEmpty()))
                throw new IllegalArgumentException("Structured validation required");
            this.supported = supported; this.code = code; this.field = field;
            this.mode=mode;inputFingerprint=fingerprint;
            tunAcquisition=acquisition;capabilityFingerprint=capability;
        }
        /** Only engine's successful full proxy-config check should return this DTO. */
        public static Validation proxyValidated(String profile,String settings) {
            return new Validation(true,"","",Mode.PROXY,fingerprint(profile,settings),TunAcquisition.NONE,"");
        }
        /** Core asserts full VPN config + deferred native plan validated; HOST independently verifies capability SHA. */
        public static Validation deferredVpnValidated(String profile,String settings,String capabilitySha256) {
            if(capabilitySha256==null||!capabilitySha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("DEFERRED_CAPABILITY_SHA");
            return new Validation(true,"","",Mode.VPN,fingerprint(profile,settings),TunAcquisition.DEFERRED,capabilitySha256);
        }
    }
    static String fingerprint(String profile,String settings) {
        if(profile==null||settings==null)throw new IllegalArgumentException("INPUTS");
        try {
            java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
            byte[] profileBytes=profile.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] settingsBytes=settings.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sha.update(java.nio.ByteBuffer.allocate(4).putInt(profileBytes.length).array());sha.update(profileBytes);
            sha.update(java.nio.ByteBuffer.allocate(4).putInt(settingsBytes.length).array());
            byte[] result=sha.digest(settingsBytes);
            StringBuilder out=new StringBuilder();for(byte b:result)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    final class Counters {
        public final long txPayload, rxPayload, blockedAttempts;
        public final boolean available;
        public Counters(long tx, long rx, long blocked) {
            this(tx,rx,blocked,true);
        }
        private Counters(long tx,long rx,long blocked,boolean available) {
            if (tx < 0 || rx < 0 || blocked < 0) throw new IllegalArgumentException("Negative counter");
            txPayload = tx; rxPayload = rx; blockedAttempts = blocked;
            this.available=available;
        }
        public static Counters unavailable(){return new Counters(0,0,0,false);}
    }
}
