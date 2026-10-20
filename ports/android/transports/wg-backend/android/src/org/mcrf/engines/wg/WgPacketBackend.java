package org.mcrf.engines.wg;

import android.content.Context;
import org.mcrf.engine.protocol.v1.OwnedPacketBackend;
import org.mcrf.engine.protocol.v1.PacketBackend;
import org.mcrf.engine.protocol.dns.v1.ManagedDnsPacketBackend;
import org.mcrf.wgbackend.wgbackend.Wgbackend;
import org.mcrf.wgbackend.wgbackend.Session;

/** Optional module entry. No native or Go classes are linked into the base APK. */
public final class WgPacketBackend implements PacketBackend, ManagedDnsPacketBackend {
    public WgPacketBackend(Context context) {
        if (context == null) throw new IllegalArgumentException("WG_CONTEXT_REQUIRED");
        go.Seq.setContext(context.getApplicationContext());
    }
    private volatile Config dnsConfig;
    private volatile Session nativeSession;
    private boolean startClaimed;
    private final OwnedPacketBackend lifecycle = new OwnedPacketBackend((fd, mtu, config, protector) -> {
        Session session;
        try {
            Config dns=dnsConfig;
            session = dns==null?Wgbackend.start(fd, mtu, config, protector::prepare):
                Wgbackend.startManaged(fd,mtu,config,protector::prepare,dns.address4,dns.address6,dns.sourceAddresses,dns.port);
        }
        catch (Exception error) {
            if ("WG_TUN_REJECTED".equals(error.getMessage())) throw new PacketBackend.InvalidTun();
            throw new IllegalStateException("WG_NATIVE_START_FAILED");
        }
        if (session == null) throw new IllegalStateException("WG_NATIVE_SESSION_MISSING");
        nativeSession=session;
        return ()->{nativeSession=null;session.close();};
    });

    private synchronized void claim(Config dns){if(startClaimed)throw new IllegalStateException("WG_ONE_RUN");startClaimed=true;dnsConfig=dns;}
    @Override public void start(Request request, Host host) throws Exception { claim(null);lifecycle.start(request, host); }
    @Override public void startManaged(Request request,Host host,Config config)throws Exception {
        if(config==null)throw new IllegalArgumentException("WG_DNS_CONFIG_REQUIRED");claim(config);lifecycle.start(request,host);
    }
    @Override public Client dnsClient(int protocol,int translatedPort){
        Session session=nativeSession;if(session==null)return null;
        org.mcrf.wgbackend.wgbackend.DnsClient client=session.dnsClient(protocol,translatedPort);
        return client==null?null:new Client(client.getProtocol(),client.getSource(),client.getSourcePort(),client.getDestination());
    }
    @Override public void close() { lifecycle.close(); }
}
