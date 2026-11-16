package org.mcrf.engine.spi.v1;

import java.io.IOException;
import java.util.*;

/** Parent-loaded host platform DTOs. No Android/gomobile/libbox type crosses this boundary.
 * Host implementations own Binder/PFD, generation checks, deadlines and cancellation.
 */
public interface Platform {
    /** Host creates/retains the only TUN, returning a borrowed fd already unmarshalled in worker.
     * Allowed once ONLY for validated VPN/DEFERRED request, via host WorkerRunGate claim.
     * Forbidden in proxy/PROVIDED mode. Native authoritative plan must be fully validated.
     */
    int openTun(long generation, TunPlan plan) throws IOException;
    List<InterfaceInfo> interfaces(long generation) throws IOException;
    ConnectionOwner connectionOwner(long generation, ConnectionQuery query) throws IOException;
    DnsReply exchangeDns(long generation, DnsQuery query) throws IOException;
    void cancelDns(long generation, long requestId);
    void startDefaultMonitor(long generation, DefaultListener listener) throws IOException;
    void closeDefaultMonitor(long generation);
    /** Same semantics as Engine.Host.prepareSocket, with worker-local borrowed fd only. */
    boolean protectAndBind(long generation, int borrowedSocketFd);
    Features features(long generation);
    WifiState wifiState(long generation) throws IOException;
    void clearDnsCache(long generation) throws IOException;
    void notification(long generation, Notice notice) throws IOException;
    void cancelNotification(long generation, String id, int type) throws IOException;
    void startNeighborMonitor(long generation, NeighborListener listener) throws IOException;
    void closeNeighborMonitor(long generation);
    void registerInterface(long generation, String name) throws IOException;
    String hostname(long generation) throws IOException;
    void traffic(long generation, long txTotal, long rxTotal);

    interface DefaultListener { void update(long generation, DefaultInterface value); }
    interface NeighborListener { void update(long generation, List<Neighbor> values); }
    static <T> List<T> copy(List<T> values) {
        if(values==null||values.size()>65536)throw new IllegalArgumentException("DTO_LIST");
        for(T value:values)if(value==null)throw new IllegalArgumentException("DTO_LIST");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
    enum Availability { IMPLEMENTED, EMPTY_COMPATIBILITY, UNSUPPORTED }
    final class Features {
        public final boolean autoDetectInterfaceControl, procFs, networkExtension, includeAllNetworks;
        public final Availability wifi, cache, notifications, neighbors, shell, bridge;
        public Features(boolean control,boolean procFs,boolean extension,boolean all,
                        Availability wifi,Availability cache,Availability notices,Availability neighbors,
                        Availability shell,Availability bridge) {
            if(wifi==null||cache==null||notices==null||neighbors==null||shell==null||bridge==null)throw new IllegalArgumentException("FEATURES");
            autoDetectInterfaceControl=control;this.procFs=procFs;networkExtension=extension;includeAllNetworks=all;
            this.wifi=wifi;this.cache=cache;notifications=notices;this.neighbors=neighbors;this.shell=shell;this.bridge=bridge;
        }
    }
    final class InterfaceInfo {
        public final String name,type;public final int index,mtu,flags;public final boolean metered;
        public final List<String> addresses,dnsServers;
        public InterfaceInfo(String name,int index,int mtu,int flags,String type,boolean metered,List<String> addresses,List<String> dns) {
            if(name==null||type==null||index<0||mtu<0)throw new IllegalArgumentException("INTERFACE");
            this.name=name;this.index=index;this.mtu=mtu;this.flags=flags;this.type=type;this.metered=metered;
            this.addresses=copy(addresses);dnsServers=copy(dns);
        }
    }
    final class DefaultInterface {
        public final String name;public final int index;public final boolean metered,expensive;
        public DefaultInterface(String name,int index,boolean metered,boolean expensive) {
            if(name==null||index<0)throw new IllegalArgumentException("DEFAULT_INTERFACE");
            this.name=name;this.index=index;this.metered=metered;this.expensive=expensive;
        }
    }
    final class ConnectionQuery {
        public final int protocol,sourcePort,destinationPort;public final String source,destination;
        public ConnectionQuery(int protocol,String source,int sourcePort,String destination,int destinationPort) {
            if((protocol!=6&&protocol!=17)||source==null||destination==null||sourcePort<0||sourcePort>65535||destinationPort<0||destinationPort>65535)
                throw new IllegalArgumentException("OWNER_QUERY");
            this.protocol=protocol;this.source=source;this.sourcePort=sourcePort;this.destination=destination;this.destinationPort=destinationPort;
        }
    }
    final class ConnectionOwner {
        public final int uid;public final List<String> packageNames;
        public ConnectionOwner(int uid,List<String> packages){if(uid<0)throw new IllegalArgumentException("OWNER_UID");this.uid=uid;packageNames=copy(packages);}
    }
    final class DnsQuery {
        public final long requestId;public final int timeoutMillis;private final byte[] packet;
        public DnsQuery(long requestId,byte[] packet,int timeoutMillis) {
            if(requestId<=0||packet==null||packet.length<12||packet.length>65535||timeoutMillis<1||timeoutMillis>8000)
                throw new IllegalArgumentException("DNS_QUERY");
            this.requestId=requestId;this.packet=packet.clone();this.timeoutMillis=timeoutMillis;
        }
        public byte[] packet(){return packet.clone();}
    }
    final class DnsReply {
        public final int errno,rcode;private final byte[] packet;
        public DnsReply(byte[] packet,int errno,int rcode) {
            if(errno<0||rcode<0||rcode>15||packet==null||packet.length>65535||(errno==0&&packet.length<12)||(errno!=0&&packet.length!=0))
                throw new IllegalArgumentException("DNS_REPLY");
            this.packet=packet.clone();this.errno=errno;this.rcode=rcode;
        }
        public byte[] packet(){return packet.clone();}
    }
    final class WifiState {
        public final String ssid,bssid;
        public WifiState(String ssid,String bssid){if(ssid==null||bssid==null)throw new IllegalArgumentException("WIFI");this.ssid=ssid;this.bssid=bssid;}
    }
    final class Notice {
        public final String id,title,body;public final int type;
        public Notice(String id,int type,String title,String body){if(id==null||title==null||body==null)throw new IllegalArgumentException("NOTICE");this.id=id;this.type=type;this.title=title;this.body=body;}
    }
    final class Neighbor {
        public final String address,mac,interfaceName;
        public Neighbor(String address,String mac,String interfaceName){if(address==null||mac==null||interfaceName==null)throw new IllegalArgumentException("NEIGHBOR");this.address=address;this.mac=mac;this.interfaceName=interfaceName;}
    }
    final class HttpProxy {
        public final boolean enabled;public final String server;public final int port;public final List<String> bypass;
        public HttpProxy(boolean enabled,String server,int port,List<String> bypass) {
            if(server==null||(enabled&&(server.isEmpty()||port<1||port>65535)))throw new IllegalArgumentException("HTTP_PROXY");
            this.enabled=enabled;this.server=server;this.port=port;this.bypass=copy(bypass);
        }
    }
    /** Numeric CIDRs stay strings, validated by host's API-aware TUN policy before Builder. */
    final class Routes {
        public final List<String> inet4Address,inet6Address,inet4Routes,inet6Routes,inet4Exclude,inet6Exclude,inet4Range,inet6Range;
        public Routes(List<String> v4,List<String> v6,List<String> r4,List<String> r6,List<String> e4,List<String> e6,List<String> range4,List<String> range6) {
            inet4Address=copy(v4);inet6Address=copy(v6);inet4Routes=copy(r4);inet6Routes=copy(r6);
            inet4Exclude=copy(e4);inet6Exclude=copy(e6);inet4Range=copy(range4);inet6Range=copy(range6);
        }
    }
    final class TunPlan {
        public final int schema=1,mtu;public final boolean autoRoute,dnsEnabled,metered;
        public final Routes routes;public final List<String> dnsServers,includePackage,excludePackage;public final HttpProxy httpProxy;
        public TunPlan(int mtu,boolean autoRoute,boolean dnsEnabled,boolean metered,Routes routes,List<String> dns,
                       List<String> include,List<String> exclude,HttpProxy proxy) {
            if(mtu<576||mtu>65535||routes==null||proxy==null||(!routes.inet6Address.isEmpty()&&mtu<1280))throw new IllegalArgumentException("TUN_PLAN");
            this.mtu=mtu;this.autoRoute=autoRoute;this.dnsEnabled=dnsEnabled;this.metered=metered;this.routes=routes;
            dnsServers=copy(dns);includePackage=copy(include);excludePackage=copy(exclude);httpProxy=proxy;
        }
    }
}
