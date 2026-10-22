package org.steer.core;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SOURCE CANDIDATE: native protocol backend for an INTERNAL authenticated
 * per-server bridge. No TUN, VPN Network, default resolver or direct target
 * socket. It cannot claim original-app UID ownership from the loopback caller.
 * The parent dispatcher remains responsible for route and per-rule DNS choice.
 * Construction/use is NOT a tested module capability or host integration. */
public final class NativeRouteDialer implements AuthenticatedProxy.Dialer,SocksUdpAssociation.Dialer,AutoCloseable {
    public interface Resolver {
        /** Explicit selected-server DNS only, bounded/cancellable. The result
         * is numeric, never permission to fall back to system/default DNS. */
        byte[] resolve(ProxyHandshake.Target target,boolean udp,AuthenticatedProxy.Cancellation cancellation)throws IOException;
    }
    public static Resolver numericOnly(){return (target,udp,cancelled)->{
        byte[] address=target.address();
        if(cancelled.cancelled()||address==null)throw new IOException("Dispatcher-owned numeric destination required");
        return address;
    };}
    private final Object admission=new Object();
    private final Set<Session> sessions=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Resolver resolver;
    private final AuthenticatedProxy.Cancellation stopping;
    private final ThreadPoolExecutor nativeCalls=new ThreadPoolExecutor(0,32,15,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"steer-route-native");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor resolutions=new ThreadPoolExecutor(0,2,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"steer-route-dns");t.setDaemon(true);return t;});
    public NativeRouteDialer(String profile,String dialIpv4,String directory,SteerCore.SocketProtector protector,
                             Resolver resolver,AuthenticatedProxy.Cancellation stopping)throws IOException {
        if(profile==null||dialIpv4==null||directory==null||protector==null||resolver==null||stopping==null)throw new IllegalArgumentException("Owned route resolver/lifetime required");
        this.resolver=resolver;this.stopping=stopping;
        try {
            if(stopping.cancelled())throw new IOException("Route backend retired");
            SteerCore.prepareRoute(profile,dialIpv4,directory,fd->!cancelled()&&protector.protectSocket(fd)&&!cancelled());
            if(cancelled())throw new IOException("Route backend retired");
        }catch(IOException|RuntimeException|LinkageError failure){close();throw failure;}
    }
    private boolean cancelled(){return closed.get()||stopping.cancelled();}
    private void check(AuthenticatedProxy.Cancellation scope)throws IOException {
        if(cancelled()||scope.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("Route backend retired");
    }
    private Session openSession(ProxyHandshake.Target target,boolean udp,AuthenticatedProxy.Cancellation scope)throws IOException {
        if(target==null||scope==null)throw new IllegalArgumentException("Owned route destination required");
        check(scope);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        byte[] address=target.address();
        if(address==null){
            Future<byte[]> job;
            try {job=resolutions.submit(()->resolver.resolve(target,udp,()->cancelled()||scope.cancelled()||Thread.currentThread().isInterrupted()));}
            catch(RejectedExecutionException full){throw new IOException("Route DNS capacity");}
            try {for(;;){
                check(scope);long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("Route DNS deadline");
                try {address=job.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);break;}
                catch(TimeoutException waiting){/* owner/absolute deadline rechecked */}
                catch(ExecutionException|CancellationException failure){throw new IOException("Route DNS refused");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Route DNS interrupted");}
            }}finally{job.cancel(true);} // Actual resolver termination remains an owner barrier.
        }
        if(address==null||(address.length!=4&&address.length!=16))throw new IOException("Numeric route result required");
        // Reuse the strict target validator, including mapped-IPv4 refusal.
        ProxyHandshake.Target numeric=ProxyHandshake.Target.numeric(address,target.port);
        String literal=InetAddress.getByAddress(numeric.address()).getHostAddress(); // NEVER getByName.
        Session session;
        synchronized(admission){
            check(scope);if(sessions.size()>=32)throw new IOException("Route channel capacity");
            session=new Session(literal,target.port,udp,scope);sessions.add(session);
            try{nativeCalls.execute(session);}catch(RejectedExecutionException full){session.nativeFinished=true;session.close();throw new IOException("Route native capacity");}
        }
        boolean delivered=false;
        try {
            for(;;){
                check(scope);long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("Route connect deadline");
                try {if(session.ready.await(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS))break;}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Route connect interrupted");}
            }
            check(scope);session.check();if(!session.connected)throw new IOException("Native route connect refused");
            delivered=true;return session;
        }finally{if(!delivered)session.close();}
    }
    @Override public AuthenticatedProxy.Transport connect(ProxyHandshake.Target target,AuthenticatedProxy.Cancellation cancellation)throws IOException {return openSession(target,false,cancellation);}
    @Override public SocksUdpAssociation.Channel open(ProxyHandshake.Target target,AuthenticatedProxy.Cancellation cancellation)throws IOException {return openSession(target,true,cancellation);}
    private final class Session implements AuthenticatedProxy.Transport,SocksUdpAssociation.Channel,Runnable,SteerCore.RelayObserver {
        final String literal;final int port;final boolean udp;final AuthenticatedProxy.Cancellation scope;
        final Object ownership=new Object();final CountDownLatch ready=new CountDownLatch(1);
        final AtomicBoolean ended=new AtomicBoolean();
        volatile boolean connected,nativeFinished;
        ParcelFileDescriptor local,nativeOwner;
        Session(String literal,int port,boolean udp,AuthenticatedProxy.Cancellation scope)throws IOException {
            this.literal=literal;this.port=port;this.udp=udp;this.scope=scope;
            FileDescriptor a=new FileDescriptor(),b=new FileDescriptor();boolean pair=false;
            try {
                Os.socketpair(OsConstants.AF_UNIX,(udp?OsConstants.SOCK_DGRAM:OsConstants.SOCK_STREAM)|OsConstants.SOCK_CLOEXEC,0,a,b);pair=true;
                local=ParcelFileDescriptor.dup(a);nativeOwner=ParcelFileDescriptor.dup(b);
                if(udp)Os.setsockoptTimeval(local.getFileDescriptor(),OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,StructTimeval.fromMillis(500));
            }catch(ErrnoException|IOException error){nativeFinished=true;sessions.add(this);close();throw new IOException("Private route channel unavailable");}
            finally{if(pair){try{Os.close(a);}catch(ErrnoException ignored){}try{Os.close(b);}catch(ErrnoException ignored){}}}
        }
        void check()throws IOException {NativeRouteDialer.this.check(scope);if(ended.get())throw new IOException("Route channel retired");}
        @Override public boolean cancelled(){return ended.get()||NativeRouteDialer.this.cancelled()||scope.cancelled();}
        @Override public void completed(boolean value){if(ready.getCount()==0)return;connected=value&&!cancelled();ready.countDown();}
        @Override public void run(){
            try {if(!cancelled())SteerCore.relayRoute(literal,port,udp,nativeOwner.getFd(),15000,this);}
            catch(RuntimeException|LinkageError failure){/* Closed non-secret boundary, not connectivity proof. */}
            finally{
                completed(false);nativeFinished=true;
                synchronized(ownership){
                    if(nativeOwner!=null)try{nativeOwner.close();nativeOwner=null;}catch(IOException ignored){}
                    // TCP bytes already queued by native must remain readable,
                    // followed by EOF. Do not SHUT_RD/discard them at JNI return.
                    // Consumer close (or backend retirement) retains ownership.
                    if(!connected||udp||cancelled())close();
                }
            }
        }
        private ParcelFileDescriptor operation()throws IOException {synchronized(ownership){check();return local.dup();}}
        private IOException failure(ErrnoException error){return error.errno==OsConstants.EAGAIN?new SocketTimeoutException("Route receive timeout"):new IOException("Private route IO failed");}
        private void bounds(byte[] bytes,int offset,int count){if(bytes==null)throw new NullPointerException();if(offset<0||count<0||offset>bytes.length-count)throw new IndexOutOfBoundsException();}
        private final InputStream input=new InputStream(){
            public int read()throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
            public int read(byte[] bytes,int offset,int count)throws IOException {
                bounds(bytes,offset,count);if(count==0)return 0;
                try(ParcelFileDescriptor fd=operation()){
                    int n=Os.read(fd.getFileDescriptor(),bytes,offset,count);check();
                    if(n==0){Session.this.close();return -1;}return n;
                }
                catch(ErrnoException error){throw failure(error);}
            }
            public void close(){Session.this.close();}
        };
        private final OutputStream output=new OutputStream(){
            public void write(int value)throws IOException {write(new byte[]{(byte)value},0,1);}
            public void write(byte[] bytes,int offset,int count)throws IOException {
                bounds(bytes,offset,count);
                try(ParcelFileDescriptor fd=operation()){while(count>0){check();int n=Os.write(fd.getFileDescriptor(),bytes,offset,count);if(n<1)throw new IOException("Route write incomplete");offset+=n;count-=n;}}
                catch(ErrnoException error){throw failure(error);}
            }
            public void close(){Session.this.close();}
        };
        @Override public InputStream input()throws IOException {check();return input;}
        @Override public OutputStream output()throws IOException {check();return output;}
        @Override public ProxyHandshake.Target bound(){return ProxyHandshake.Target.unspecifiedBound();}
        @Override public void shutdownOutput()throws IOException {try(ParcelFileDescriptor fd=operation()){Os.shutdown(fd.getFileDescriptor(),OsConstants.SHUT_WR);}catch(ErrnoException error){throw failure(error);}}
        @Override public void send(byte[] payload)throws IOException {
            if(!udp||payload==null||payload.length>65507)throw new IOException("Bounded route datagram required");
            try(ParcelFileDescriptor fd=operation()){int n=Os.write(fd.getFileDescriptor(),payload,0,payload.length);check();if(n!=payload.length)throw new IOException("Whole route datagram required");}
            catch(ErrnoException error){throw failure(error);}
        }
        @Override public byte[] receive()throws IOException {
            if(!udp)throw new IOException("Route datagram channel required");
            byte[] bytes=new byte[65508];
            try(ParcelFileDescriptor fd=operation()){int n=Os.read(fd.getFileDescriptor(),bytes,0,bytes.length);check();if(n<0||n>65507)throw new IOException("Oversize route datagram");return Arrays.copyOf(bytes,n);}
            catch(ErrnoException error){throw failure(error);}
        }
        @Override public void close(){
            ended.set(true);ready.countDown();
            synchronized(ownership){
                if(local!=null){try{Os.shutdown(local.getFileDescriptor(),OsConstants.SHUT_RDWR);}catch(ErrnoException|IllegalStateException ignored){}
                    try{local.close();local=null;}catch(IOException ignored){/* Retain exact owner until retry/process death. */}}
                // Never recycle native's borrowed fd during a JNI invocation.
                if(nativeFinished&&nativeOwner!=null)try{nativeOwner.close();nativeOwner=null;}catch(IOException ignored){}
                if(nativeFinished&&local==null&&nativeOwner==null)sessions.remove(this);
            }
        }
    }
    @Override public void close(){
        synchronized(admission){closed.set(true);sessions.forEach(Session::close);nativeCalls.shutdown();resolutions.shutdown();}
    }
    public boolean awaitClosed(long millis)throws InterruptedException {
        if(!closed.get()||millis<1||millis>30000)throw new IllegalStateException("Closed route owner and bounded wait required");
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        boolean nativeDone=nativeCalls.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);
        boolean dnsDone=resolutions.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);
        sessions.forEach(Session::close);
        return nativeDone&&dnsDone&&sessions.isEmpty(); // Still NOT actual fresh-worker death.
    }
}
