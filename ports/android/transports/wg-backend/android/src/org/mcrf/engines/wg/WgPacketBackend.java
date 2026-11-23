package org.mcrf.engines.wg;

import android.content.Context;
import org.mcrf.engine.protocol.v1.OwnedPacketBackend;
import org.mcrf.engine.protocol.v1.PacketBackend;
import org.mcrf.engine.protocol.dns.v1.ManagedDnsPacketBackend;
import org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend;
import org.mcrf.wgbackend.wgbackend.Wgbackend;
import org.mcrf.wgbackend.wgbackend.Session;

/** Optional module entry. No native or Go classes are linked into the base APK. */
public final class WgPacketBackend implements PacketBackend.OwnerBackend, ManagedDnsPacketBackend, PacketPipeBackend {
    private final Context context;
    public WgPacketBackend(Context context) {
        if (context == null) throw new IllegalArgumentException("WG_CONTEXT_REQUIRED");
        this.context=context.getApplicationContext();
        go.Seq.setContext(context.getApplicationContext());
    }
    private volatile Config dnsConfig;
    private volatile Session nativeSession;
    private volatile int pipePeerPid;
    private volatile org.mcrf.wgbackend.wgbackend.PacketOwner packetOwner;
    private boolean startClaimed;
    private final OwnedPacketBackend lifecycle = new OwnedPacketBackend((fd, mtu, config, protector) -> {
        Session session;
        try {
            Config dns=dnsConfig;
            org.mcrf.wgbackend.wgbackend.PacketOwner owner=packetOwner;
            if(pipePeerPid>0) session=Wgbackend.startPacketPipe(fd,mtu,config,protector::prepare,pipePeerPid);
            else if(owner==null) session=dns==null?Wgbackend.start(fd,mtu,config,protector::prepare):
                Wgbackend.startManaged(fd,mtu,config,protector::prepare,dns.address4,dns.address6,dns.sourceAddresses,dns.port);
            else session=dns==null?Wgbackend.startIsolated(fd,mtu,config,protector::prepare,owner):
                Wgbackend.startManagedIsolated(fd,mtu,config,protector::prepare,dns.address4,dns.address6,dns.sourceAddresses,dns.port,owner);
        }
        catch (Exception error) {
            if ("WG_TUN_REJECTED".equals(error.getMessage())) throw new PacketBackend.InvalidTun();
            throw new IllegalStateException("WG_NATIVE_START_FAILED");
        }
        if (session == null) throw new IllegalStateException("WG_NATIVE_SESSION_MISSING");
        nativeSession=session;
        return ()->{nativeSession=null;session.close();};
    });

    private synchronized void claim(Config dns,int peerPid,PacketBackend.Request request,Host host){
        if(startClaimed)throw new IllegalStateException("WG_ONE_RUN");
        startClaimed=true;dnsConfig=dns;pipePeerPid=peerPid;
        if(host instanceof PacketBackend.OwnerHost) {
            PacketBackend.OwnerHost ownerHost=(PacketBackend.OwnerHost) host;
            packetOwner=(protocol,source,sourcePort,destination,destinationPort)->
                !ownerHost.stopping(request.generation)&&ownerHost.admitPacketOwner(request.generation,protocol,
                    source,sourcePort,destination,destinationPort)&&!ownerHost.stopping(request.generation);
        }
    }
    @Override public void start(PacketBackend.Request request, Host host) throws Exception { claim(null,0,request,host);lifecycle.start(request, host); }
    @Override public void startManaged(PacketBackend.Request request,Host host,Config config)throws Exception {
        if(config==null)throw new IllegalArgumentException("WG_DNS_CONFIG_REQUIRED");claim(config,0,request,host);lifecycle.start(request,host);
    }
    @Override public void startPacketPipe(PacketPipeBackend.Request request,Host host)throws Exception {
        Object capability=context.getPackageManager().getApplicationInfo("org.mcrf.engines.wg",android.content.pm.PackageManager.GET_META_DATA)
            .metaData.get(PacketPipeBackend.METADATA);
        if(!PacketPipeBackend.CAPABILITY.equals(capability)||request==null||host==null)
            throw new IllegalArgumentException("WG_PACKET_PIPE_NOT_ADVERTISED");
        PacketBackend.Request owned=new PacketBackend.Request(request.generation,request.borrowedPrivateFd,request.mtu,request.secretConfiguration());
        claim(null,request.peerPid,owned,host);
        // Reuse ONLY the existing ownership/late-STOP lifecycle. Factory above
        // explicitly selects the private-packet role, never the TUN entry.
        lifecycle.start(owned,host);
    }
    @Override public Client dnsClient(int protocol,int translatedPort){
        Session session=nativeSession;if(session==null)return null;
        org.mcrf.wgbackend.wgbackend.DnsClient client=session.dnsClient(protocol,translatedPort);
        return client==null?null:new Client(client.getProtocol(),client.getSource(),client.getSourcePort(),client.getDestination());
    }
    @Override public void close() { lifecycle.close(); }
}
