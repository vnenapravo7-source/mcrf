package org.steer.core;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owned frontend connector: explicit VPN Network only, no physical socket or
 * platform resolver fallback. DNS returns aliases carrying the native policy.
 * Not an admission/capability flag; the host must own this and its resolver for
 * one VPN generation, close frontend first, then this, then the DNS owner. */
public final class VpnProxyDialer implements AuthenticatedProxy.Dialer,AutoCloseable {
    private final Network vpn;
    private final TunnelDns dns;
    private final ProxyFlowReadiness readiness;
    private final long timeoutNanos;
    private final Object admission=new Object();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Attempt> attempts=ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<ProxyCallerRegistry.Lease> callerLeases=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor resolvers;
    private final ScheduledExecutorService deadlines;

    public VpnProxyDialer(Network vpn,TunnelDns dns,ProxyFlowReadiness readiness,int timeoutMillis) {
        if(vpn==null||dns==null||readiness==null||!dns.attachedTo(vpn)||!dns.ownsReadiness(readiness)||timeoutMillis<100||timeoutMillis>30000)
            throw new IllegalArgumentException("Owned VPN/resolver and bounded deadline required");
        this.vpn=vpn;this.dns=dns;this.readiness=readiness;this.timeoutNanos=TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        resolvers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{
            Thread thread=new Thread(r,"steer-proxy-resolve");thread.setDaemon(true);return thread;
        });
        deadlines=Executors.newSingleThreadScheduledExecutor(r->{
            Thread thread=new Thread(r,"steer-proxy-vpn-deadlines");thread.setDaemon(true);return thread;
        });
        deadlines.scheduleWithFixedDelay(()->{
            for(Attempt attempt:attempts)if(attempt.expired())attempt.abort();
        },50,50,TimeUnit.MILLISECONDS);
    }
    private final class Attempt {
        final long until=System.nanoTime()+timeoutNanos;
        final AuthenticatedProxy.Cancellation cancellation;
        final AtomicBoolean transportEnded=new AtomicBoolean();
        final AtomicBoolean terminal=new AtomicBoolean();
        volatile Future<InetAddress> resolution;
        volatile Socket socket;
        volatile ProxyFlowReadiness.Pending proof;
        volatile ProxyCallerRegistry.Lease caller;
        boolean handedOff; // admission lock; a late connect watchdog cannot kill a delivered stream
        Attempt(AuthenticatedProxy.Cancellation cancellation){this.cancellation=AuthenticatedProxy.child(cancellation,()->closed.get()||transportEnded.get());}
        boolean expired(){return closed.get()||cancellation.cancelled()||System.nanoTime()-until>=0;}
        void check()throws IOException{if(terminal.get()||expired())throw new IOException("Proxy connect cancelled");}
        void abort(){synchronized(admission){
            if(handedOff)return;
            transportEnded.set(true);
            terminal.set(true);
            Future<?> pending=resolution;if(pending!=null)pending.cancel(true);
            closeSocket(socket);
            closeCaller(caller);
            ProxyFlowReadiness.Pending waiting=proof;if(waiting!=null)waiting.close();
        }}
    }
    @Override public void authenticated(AuthenticatedProxy.OriginalClient client)throws IOException{dns.bindProxyClient(client);}
    @Override public AuthenticatedProxy.Transport connect(ProxyHandshake.Target target,
            AuthenticatedProxy.Cancellation cancellation)throws IOException {
        if(target==null||cancellation==null)throw new IllegalArgumentException("Target and cancellation required");
        Attempt attempt=new Attempt(cancellation);boolean delivered=false;
        synchronized(admission){if(closed.get())throw new IOException("Proxy owner closed");attempts.add(attempt);}
        try {
            attempt.check();
            ProxyRequestContext request=dns.requiresProxyRequest()?dns.proxyRequest(target,attempt.cancellation):null;
            Set<String> caller=request==null?dns.proxyCaller(attempt.cancellation):request.packages();
            attempt.check();
            try{attempt.resolution=resolvers.submit(()->dns.proxyAddress(target,caller,request,()->attempt.terminal.get()||attempt.expired()));}
            catch(RejectedExecutionException busy){throw new IOException("Proxy resolver busy");}
            InetAddress address;
            try {
                attempt.check();long remaining=attempt.until-System.nanoTime();
                if(remaining<=0)throw new TimeoutException();
                address=attempt.resolution.get(remaining,TimeUnit.NANOSECONDS);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Proxy connect interrupted");}
            catch(ExecutionException|CancellationException|TimeoutException failure){throw new IOException("Proxy DNS unavailable");}
            attempt.check();
            Socket socket=vpn.getSocketFactory().createSocket();attempt.socket=socket;
            synchronized(admission){
                if(closed.get()){closeSocket(socket);throw new IOException("Proxy owner closed");}
                sockets.add(socket);
            }
            attempt.check();
            InetAddress source=TunnelDns.proxySource(address.getAddress());
            socket.bind(new InetSocketAddress(source,0));
            attempt.caller=dns.registerProxyRequest(FlowIdentity.of(6,source.getAddress(),socket.getLocalPort(),address.getAddress(),target.port),request,attempt.cancellation);
            if(attempt.caller!=null)callerLeases.add(attempt.caller);
            attempt.proof=readiness.begin(address.getAddress(),target.port,socket.getLocalPort());
            attempt.check();
            long remaining=attempt.until-System.nanoTime();
            if(remaining<=0)throw new IOException("Proxy connect deadline");
            socket.connect(new InetSocketAddress(address,target.port),
                (int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
            socket.setTcpNoDelay(true);socket.setSoTimeout(0);
            try{
                if(!attempt.proof.awaitReady(attempt.until,()->attempt.expired()))throw new IOException("Proxy upstream unavailable");
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Proxy connect interrupted");}
            synchronized(admission){
                attempt.check();
                if(!attempt.terminal.compareAndSet(false,true))throw new IOException("Proxy connect cancelled");
                attempt.handedOff=true;
            }
            delivered=true;return new Stream(socket,attempt.caller,attempt.transportEnded);
        }finally{
            attempts.remove(attempt);
            if(attempt.proof!=null)attempt.proof.close();
            if(!delivered){attempt.abort();if(attempt.socket!=null)sockets.remove(attempt.socket);}
        }
    }
    private final class Stream implements AuthenticatedProxy.Transport {
        private final Socket socket;
        private final ProxyCallerRegistry.Lease caller;
        private final AtomicBoolean ended;
        Stream(Socket socket,ProxyCallerRegistry.Lease caller,AtomicBoolean ended){this.socket=socket;this.caller=caller;this.ended=ended;}
        public InputStream input()throws IOException{return socket.getInputStream();}
        public OutputStream output()throws IOException{return socket.getOutputStream();}
        public ProxyHandshake.Target bound(){return ProxyHandshake.Target.numeric(socket.getLocalAddress().getAddress(),socket.getLocalPort());}
        public void shutdownOutput()throws IOException{socket.shutdownOutput();}
        public void close(){ended.set(true);closeCaller(caller);closeSocket(socket);sockets.remove(socket);}
    }
    private void closeCaller(ProxyCallerRegistry.Lease caller){if(caller!=null){caller.close();callerLeases.remove(caller);}}
    private static void closeSocket(Socket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    @Override public void close(){
        synchronized(admission){
            if(!closed.compareAndSet(false,true))return;
            for(Attempt attempt:attempts)attempt.abort();
            for(ProxyCallerRegistry.Lease caller:callerLeases)closeCaller(caller);
            for(Socket socket:sockets)closeSocket(socket);
        }
        resolvers.shutdownNow();deadlines.shutdownNow();
    }
    /** DNS task cancellation is not proof of exit. Returns false until actual
     * owned executor threads are gone; no native-worker death claim here. */
    public boolean awaitClosed(long millis)throws InterruptedException {
        if(!closed.get()||millis<1||millis>30000)throw new IllegalStateException("Closed owner and bounded wait required");
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        resolvers.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);
        deadlines.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);
        return resolvers.isTerminated()&&deadlines.isTerminated();
    }
}
