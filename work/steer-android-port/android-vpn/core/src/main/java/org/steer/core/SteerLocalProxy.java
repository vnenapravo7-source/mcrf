package org.steer.core;

import android.net.Network;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit authenticated loopback HTTP/SOCKS listener. Port0 is OS-assigned
 * and reported through generation-bound STARTING metadata; no LAN/standalone mode.
 * UDP association is TCP-authenticated and shares the VPN managed-DNS contract. */
public final class SteerLocalProxy implements AutoCloseable {
    public final int port;
    private final byte[] user,password;
    private boolean closed;
    public SteerLocalProxy(JSONObject json)throws JSONException{
        Set<String> keys=new HashSet<>();for(Iterator<String> it=json.keys();it.hasNext();)keys.add(it.next());
        if(!keys.equals(new HashSet<>(Arrays.asList("port","username","password"))))throw new IllegalArgumentException("Exact proxy configuration required");
        Object number=json.opt("port");
        if(!(number instanceof Integer||number instanceof Long))throw new IllegalArgumentException("Exact proxy port required");
        long value=((Number)number).longValue();if(value!=0&&(value<1024||value>65535))throw new IllegalArgumentException("Bounded fixed or OS-assigned proxy port required");
        port=(int)value;
        if(!(json.opt("username") instanceof String)||!(json.opt("password") instanceof String))throw new IllegalArgumentException("Proxy credentials required");
        String username=json.getString("username"),secret=json.getString("password");
        noControls(username);noControls(secret);
        user=username.getBytes(StandardCharsets.UTF_8);password=secret.getBytes(StandardCharsets.UTF_8);
        try(ProxyHandshake.Credentials checked=new ProxyHandshake.Credentials(user,password)){}
        catch(RuntimeException invalid){Arrays.fill(user,(byte)0);Arrays.fill(password,(byte)0);throw invalid;}
    }
    private static void noControls(String text){for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))throw new IllegalArgumentException("Proxy control character rejected");}
    /** Single-use options; frontend owns its own credential copies afterward. */
    public synchronized VpnProxyOwner open(Network vpn,TunnelDns dns,ProxyFlowReadiness readiness)throws IOException{
        if(closed)throw new IOException("Fresh proxy options required");
        try{return new VpnProxyOwner(vpn,dns,readiness,port,user,password);}
        finally{close();}
    }
    /** Actual module path: no unprotected fallback if preparation is refused. */
    public synchronized VpnProxyOwner openProtected(Network vpn,TunnelDns dns,ProxyFlowReadiness readiness,
            ProtectedTcpListener.Preparation preparation,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(closed)throw new IOException("Fresh proxy options required");
        try{return new VpnProxyOwner(vpn,dns,readiness,port,user,password,preparation,cancellation);}
        finally{close();}
    }
    @Override public synchronized void close(){closed=true;Arrays.fill(user,(byte)0);Arrays.fill(password,(byte)0);}
    @Override public String toString(){return "SteerLocalProxy(redacted)";}
}
