package org.steer.core;

/** No UI, service, app storage, VPN consent or network ownership in the library.
 * Host must use one isolated process per run until native stop/restart is implemented. */
public final class SteerCore {
    static { System.loadLibrary("steer_vpn"); }
    public static final int API_VERSION=2;
    public static final String PORT_VERSION="0.3.3-dev";
    public static final int TUN_MTU=1500;
    public interface SocketProtector { boolean protectSocket(int fd); }
    public interface FlowRouter { long routeFlow(int ipv4); }
    /** Optional exact-port/protocol routing for owned DNS control aliases. */
    public interface PortFlowRouter extends FlowRouter {
        default boolean portFlowsEnabled(){return false;}
        long routeFlowPort(int ipv4,int port,int protocol);
    }
    /** Source-only optional path. Merely implementing this interface is NOT
     * IPv6 admission; legacy/disabled owners retain the default false. */
    public interface DualStackFlowRouter extends FlowRouter {
        default boolean ipv6FlowsEnabled(){return false;}
        byte[] routeFlow6(byte[] ipv6);
    }
    public interface ProxyFlowObserver {
        boolean observesProxyFlows();
        void proxyFlowResult(byte[] alias,int targetPort,int localPort,boolean ready);
    }
    public interface OwnedFlowRouter extends FlowRouter {
        default boolean ownerFlowsEnabled(){return false;}
        /** FlowIdentity encoding in; action byte plus original-family real IP
         * out. Failure/unknown owner must deny, never fall back to default. */
        byte[] routeOwnedFlow(byte[] original);
    }
    private static boolean used;
    private SteerCore() {}
    public static native String serverHost(String url);
    /** Mechanical JNI protocol revision, NOT a tested feature/capability claim.
     * Old libraries must fail config2 preflight, not ignore new callbacks. */
    public static native int nativeRouterSchema();
    private static native int verifyTlsNative(String hostname,String ipv4,String stateDir);
    /** Bootstrap diagnostic before run(), with no VPN active in this process.
     * Performs a TLS handshake with system trust and name validation; no HTTP request. */
    public static synchronized int verifyTls(String hostname,String ipv4,String stateDir) {
        if(used)throw new IllegalStateException("TLS diagnostic must precede run");
        return verifyTlsNative(hostname,ipv4,stateDir);
    }
    private static native int runNative(String url,String dialIPv4,String stateDir,int fd,SocketProtector protector,String[] blockedIpv4,FlowRouter router,int dnsPort,String upstreamDns);
    /** tx payload, rx payload, rejected new-flow attempts (may include retried SYNs). */
    public static native long[] counters();
    public static int run(String url,String dialIPv4,String stateDir,int fd,SocketProtector protector) {
        return run(url,dialIPv4,stateDir,fd,protector,new String[0]);
    }
    public static int run(String url,String dialIPv4,String stateDir,int fd,SocketProtector protector,String[] blockedIpv4) {
        return run(url,dialIPv4,stateDir,fd,protector,blockedIpv4,null,0,null);
    }
    public static int run(String url,String dialIPv4,String stateDir,int fd,SocketProtector protector,String[] blockedIpv4,FlowRouter router,int dnsPort,String upstreamDns) {
        synchronized(SteerCore.class) {
            if(used) throw new IllegalStateException("One Steer run per process");
            if(url==null||dialIPv4==null||stateDir==null||fd<0||protector==null)
                throw new IllegalArgumentException("Missing host input");
            used=true;
        }
        return runNative(url,dialIPv4,stateDir,fd,protector,blockedIpv4,router,dnsPort,upstreamDns);
    }
}
