package org.steer.core;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

/** One INTERNAL per-server frontend, not the application's user-facing local
 * VPN proxy. There is no original-app owner attribution here: classification
 * and DNS are the sole dispatcher's responsibility. Always loopback+auth,
 * protected before bind/listen, no TUN creation. Source candidate only. */
public final class NativeRouteOwner implements AutoCloseable {
    private final NativeRouteDialer backend;
    private final AuthenticatedProxy frontend;
    public NativeRouteOwner(String profile,String dialIpv4,String directory,SteerCore.SocketProtector protector,
                            byte[] user,byte[] password,AuthenticatedProxy.Cancellation stopping)throws IOException {
        NativeRouteDialer pending=new NativeRouteDialer(profile,dialIpv4,directory,protector,NativeRouteDialer.numericOnly(),stopping);
        AuthenticatedProxy front=null;
        try {
            InetAddress loopback=InetAddress.getByAddress(new byte[]{127,0,0,1});
            front=new AuthenticatedProxy(()->new AndroidProxyListener(loopback,0,fd->!stopping.cancelled()&&protector.protectSocket(fd)&&!stopping.cancelled(),stopping),
                user,password,pending,pending,15000,120000);
            if(front.port()<1024||stopping.cancelled())throw new IOException("Internal route frontend retired");
            backend=pending;frontend=front;
        }catch(IOException|RuntimeException|LinkageError failure){if(front!=null)front.close();pending.close();throw failure;}
    }
    public int port(){return frontend.port();}
    @Override public void close(){frontend.close();backend.close();}
    public boolean awaitClosed(long millis)throws InterruptedException {
        if(millis<1||millis>30000)throw new IllegalArgumentException("Bounded route retirement wait required");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        boolean front=frontend.awaitClosed((int)millis);
        long left=TimeUnit.NANOSECONDS.toMillis(Math.max(0,deadline-System.nanoTime()));
        return left>0&&backend.awaitClosed(left)&&front; // Not fresh-worker death ACK.
    }
    @Override public String toString(){return "NativeRouteOwner(redacted)";}
}
