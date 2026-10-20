package org.steer.core;

import android.net.Network;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connected UDP channels bound explicitly to the same VPN as TunnelDns. Every
 * target (including numeric addresses) goes through its owned policy alias.
 * A successful send is not an upstream delivery/authentication acknowledgement.
 * Destination port53 deliberately uses the SAME managed DNS capture contract
 * as TUN packets; it never contacts a client-specified arbitrary resolver. */
public final class VpnProxyDatagrams implements SocksUdpAssociation.Dialer,AutoCloseable {
    private final Network vpn;private final TunnelDns dns;
    private final Object admission=new Object();private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<DatagramSocket> sockets=new HashSet<>();
    private final Set<String> retiredTuples=new HashSet<>();
    private final Set<Pending> pending=new HashSet<>();
    private final Set<DnsChannel> dnsChannels=new HashSet<>();
    private final Set<ProxyCallerRegistry.Lease> callerLeases=new HashSet<>();
    private final ThreadPoolExecutor resolvers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"steer-proxy-udp-dns");t.setDaemon(true);return t;});
    private static final long CONNECT_NANOS=TimeUnit.SECONDS.toNanos(15);
    private final class Pending {
        final AuthenticatedProxy.Cancellation cancellation;volatile Future<InetAddress> resolution;
        Pending(AuthenticatedProxy.Cancellation cancellation){this.cancellation=cancellation;}
        boolean cancelled(){return closed.get()||cancellation.cancelled();}
        void check()throws IOException{if(cancelled())throw new IOException("UDP owner closed");}
    }
    public VpnProxyDatagrams(Network vpn,TunnelDns dns){
        if(vpn==null||dns==null||!dns.attachedTo(vpn)){resolvers.shutdownNow();throw new IllegalArgumentException("Same owned VPN required");}
        this.vpn=vpn;this.dns=dns;
    }
    @Override public void validateClient(AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(closed.get())throw new IOException("UDP owner closed");dns.validateProxyDatagram(cancellation);
    }
    @Override public SocksUdpAssociation.Channel open(ProxyHandshake.Target target,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(target==null||cancellation==null)throw new IllegalArgumentException("Owned UDP target required");
        AtomicBoolean channelEnded=new AtomicBoolean();
        AuthenticatedProxy.Cancellation scope=AuthenticatedProxy.child(cancellation,()->closed.get()||channelEnded.get());
        ProxyRequestContext request=dns.requiresProxyRequest()?dns.proxyRequest(target,scope):null;
        Set<String> caller=request==null?dns.proxyCaller(scope):request.packages();
        Pending attempt=new Pending(scope);DatagramSocket socket=null;boolean delivered=false;ProxyCallerRegistry.Lease callerLease=null;
        synchronized(admission){
            if(closed.get()||sockets.size()+pending.size()+dnsChannels.size()>=32)throw new IOException("UDP channel capacity");
            pending.add(attempt);
        }
        try{
            attempt.check();long until=System.nanoTime()+CONNECT_NANOS;
            try{attempt.resolution=resolvers.submit(()->target.port==53?
                dns.managedProxyDnsAddress(target,caller,request,()->attempt.cancelled()||Thread.currentThread().isInterrupted()):
                dns.proxyAddress(target,caller,request,()->attempt.cancelled()||Thread.currentThread().isInterrupted()));}
            catch(RejectedExecutionException busy){throw new IOException("UDP resolver busy");}
            InetAddress alias;
            for(;;){
                attempt.check();long remaining=until-System.nanoTime();if(remaining<=0)throw new IOException("UDP DNS deadline");
                try{alias=attempt.resolution.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);break;}
                catch(TimeoutException waiting){/* cancellation/STOP rechecked at100ms */}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("UDP resolution interrupted");}
                catch(ExecutionException|CancellationException failure){throw new IOException("UDP resolution unavailable");}
            }
            attempt.check();
            if(target.port==53){
                // Requested resolver's domain/IP + original UDP source/app/53
                // gate admission; query names independently select managed DNS
                // plans in resolve(). No socket to an arbitrary port53 target,
                // and DIRECT here does not override that TUN capture contract.
                dns.validateManagedProxyDns(alias.getAddress(),request,scope);
                synchronized(admission){
                    attempt.check();DnsChannel channel=new DnsChannel(alias.getAddress(),request,scope,channelEnded);
                    dnsChannels.add(channel);delivered=true;return channel;
                }
            }
            socket=new DatagramSocket(null);
            // bindSocket does not protect/bypass the VPN. Physical-network
            // protection is exclusively the native route owner's decision.
            InetAddress source=TunnelDns.proxySource(alias.getAddress());
            vpn.bindSocket(socket);socket.bind(new InetSocketAddress(source,0));
            callerLease=dns.registerProxyRequest(FlowIdentity.of(17,source.getAddress(),socket.getLocalPort(),alias.getAddress(),target.port),request,scope);
            String tuple=Arrays.toString(alias.getAddress())+":"+target.port+":"+socket.getLocalPort();
            synchronized(admission){
                attempt.check();
                if(retiredTuples.size()>=32768||!retiredTuples.add(tuple))throw new IOException("UDP tuple retired");
                sockets.add(socket);
                if(callerLease!=null)callerLeases.add(callerLease);
            }
            socket.connect(alias,target.port);socket.setSoTimeout(500);attempt.check();
            SocksUdpAssociation.Channel channel=new Channel(socket,callerLease,alias.getAddress(),request,scope,channelEnded);delivered=true;return channel;
        }finally{
            if(!delivered)channelEnded.set(true);
            if(attempt.resolution!=null&&!delivered)attempt.resolution.cancel(true);
            synchronized(admission){pending.remove(attempt);if(!delivered&&socket!=null)sockets.remove(socket);}
            if(!delivered&&socket!=null)socket.close();
            if(!delivered&&callerLease!=null){callerLease.close();synchronized(admission){callerLeases.remove(callerLease);}}
        }
    }
    private final class Channel implements SocksUdpAssociation.Channel {
        final DatagramSocket socket;final AtomicBoolean ended;
        final ProxyCallerRegistry.Lease caller;
        final byte[] alias;final ProxyRequestContext request;final AuthenticatedProxy.Cancellation scope;
        Channel(DatagramSocket socket,ProxyCallerRegistry.Lease caller,byte[] alias,ProxyRequestContext request,AuthenticatedProxy.Cancellation scope,AtomicBoolean ended){
            this.socket=socket;this.caller=caller;this.alias=alias.clone();this.request=request;this.scope=scope;this.ended=ended;
        }
        public void send(byte[] bytes)throws IOException{
            if(bytes==null||bytes.length>SocksUdpPacket.MAX_PACKET||ended.get()||closed.get())throw new IOException("UDP send rejected");
            dns.validateProxyChannel(alias,request,scope);
            socket.send(new DatagramPacket(bytes,bytes.length));
        }
        public byte[] receive()throws IOException{
            // One extra byte distinguishes a maximum-sized message from a
            // truncated one. DatagramSocket never returns a partial success.
            byte[] bytes=new byte[SocksUdpPacket.MAX_PACKET+1];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);
            socket.receive(packet);if(packet.getLength()>SocksUdpPacket.MAX_PACKET)throw new IOException("UDP reply limit");
            dns.validateProxyChannel(alias,request,scope);
            return Arrays.copyOf(bytes,packet.getLength());
        }
        public void close(){if(!ended.compareAndSet(false,true))return;if(caller!=null)caller.close();socket.close();synchronized(admission){sockets.remove(socket);callerLeases.remove(caller);}}
    }
    private final class DnsChannel implements SocksUdpAssociation.Channel {
        final AuthenticatedProxy.Cancellation cancellation;
        final byte[] alias;final ProxyRequestContext request;
        final AtomicBoolean ended;
        final ArrayBlockingQueue<byte[]> replies=new ArrayBlockingQueue<>(1);
        DnsChannel(byte[] alias,ProxyRequestContext request,AuthenticatedProxy.Cancellation cancellation,AtomicBoolean ended){this.alias=alias.clone();this.request=request;this.cancellation=cancellation;this.ended=ended;}
        boolean cancelled(){return closed.get()||ended.get()||cancellation.cancelled();}
        public void send(byte[] query)throws IOException{
            if(query==null||query.length>4096||cancelled())throw new IOException("DNS datagram rejected");
            dns.validateManagedProxyDns(alias,request,cancellation);
            byte[] answer=dns.resolve(query.clone(),this::cancelled);
            dns.validateManagedProxyDns(alias,request,cancellation);
            if(cancelled()||!replies.offer(answer))throw new IOException("DNS reply unavailable");
        }
        public byte[] receive()throws IOException{
            if(cancelled())throw new IOException("DNS channel closed");
            try{
                byte[] answer=replies.poll(500,TimeUnit.MILLISECONDS);if(answer==null)throw new SocketTimeoutException();
                dns.validateManagedProxyDns(alias,request,cancellation);
                if(cancelled())throw new IOException("DNS channel closed");return answer;
            }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("DNS wait interrupted");}
        }
        public void close(){if(!ended.compareAndSet(false,true))return;replies.clear();synchronized(admission){dnsChannels.remove(this);}}
    }
    @Override public void close(){
        synchronized(admission){
            if(!closed.compareAndSet(false,true))return;
            for(Pending attempt:pending)if(attempt.resolution!=null)attempt.resolution.cancel(true);
            for(DatagramSocket socket:sockets)socket.close();sockets.clear();
            for(ProxyCallerRegistry.Lease caller:callerLeases)caller.close();callerLeases.clear();
            for(DnsChannel channel:new ArrayList<>(dnsChannels))channel.close();
        }
        resolvers.shutdownNow();
    }
    public boolean awaitClosed(long millis)throws InterruptedException{
        if(!closed.get()||millis<1||millis>30000)throw new IllegalStateException("Closed owner and bounded wait required");
        resolvers.awaitTermination(millis,TimeUnit.MILLISECONDS);
        synchronized(admission){return resolvers.isTerminated()&&pending.isEmpty()&&sockets.isEmpty()&&dnsChannels.isEmpty();}
    }
}
