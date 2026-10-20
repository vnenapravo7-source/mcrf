package org.steer.core;

import android.net.Network;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

/** One generation's loopback frontend +explicit-VPN connector. Never owns the
 * shared TunnelDns lifecycle. Configuration/admission by engine host remains
 * separate: constructing this is not a capability advertisement. */
public final class VpnProxyOwner implements AutoCloseable {
    private final AuthenticatedProxy frontend;
    private final VpnProxyDialer dialer;
    private final VpnProxyDatagrams datagrams;
    public VpnProxyOwner(Network vpn,TunnelDns dns,ProxyFlowReadiness readiness,int port,byte[] user,byte[] password)throws IOException {
        this(vpn,dns,readiness,user,password,ProxyListener.loopback(InetAddress.getByAddress(new byte[]{127,0,0,1}),port));
    }
    public VpnProxyOwner(Network vpn,TunnelDns dns,ProxyFlowReadiness readiness,int port,byte[] user,byte[] password,
            ProtectedTcpListener.Preparation preparation,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        this(vpn,dns,readiness,user,password,()->new AndroidProxyListener(InetAddress.getByAddress(new byte[]{127,0,0,1}),port,preparation,cancellation));
    }
    private VpnProxyOwner(Network vpn,TunnelDns dns,ProxyFlowReadiness readiness,byte[] user,byte[] password,ProxyListener.Factory factory)throws IOException{
        VpnProxyDialer pending=new VpnProxyDialer(vpn,dns,readiness,15000);
        VpnProxyDatagrams udp=null;
        try {
            udp=new VpnProxyDatagrams(vpn,dns);
            frontend=new AuthenticatedProxy(factory,user,password,pending,udp,15000,120000);
            if(frontend.port()<1024){frontend.close();throw new IOException("OS-assigned proxy port outside allowed range");}
            dialer=pending;datagrams=udp;
        }catch(IOException|RuntimeException failure){pending.close();if(udp!=null)udp.close();throw failure;}
    }
    public int port(){return frontend.port();}
    @Override public void close(){frontend.close();dialer.close();datagrams.close();}
    public boolean awaitClosed(long millis)throws InterruptedException {
        if(millis<1||millis>30000)throw new IllegalArgumentException("Bounded wait required");
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        // The validated1..30000ms bound above makes this narrowing exact.
        boolean front=frontend.awaitClosed((int)millis);
        long remaining=TimeUnit.NANOSECONDS.toMillis(Math.max(0,until-System.nanoTime()));
        if(remaining<1)return false;
        boolean streams=dialer.awaitClosed(remaining);
        remaining=TimeUnit.NANOSECONDS.toMillis(Math.max(0,until-System.nanoTime()));
        return remaining>0&&datagrams.awaitClosed(remaining)&&front&&streams;
    }
}
