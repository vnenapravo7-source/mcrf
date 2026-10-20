package org.steer.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Read-only Android29+ active-VPN owner lookup. Source-wired, not verified. The
 * platform can refuse or return INVALID_UID; that is a routing failure, NEVER
 * permission to use the VPN process UID, a guessed application or default.
 * No tuple/UID cache: a recycled source port may belong to a different app.
 * Packages sharing a Linux UID are necessarily one identity/union here. */
public final class SocketOwnerLookup {
    public static final class Owner {
        public final int uid;
        public final Set<String> packages;
        private Owner(int uid,Set<String> packages){this.uid=uid;this.packages=Collections.unmodifiableSet(new HashSet<>(packages));}
        @Override public String toString(){return "SocketOwner(redacted)";}
    }
    private final ConnectivityManager connectivity;
    private final PackageManager packages;
    final int hostUid;
    public SocketOwnerLookup(Context context){
        if(context==null)throw new IllegalArgumentException("Actual VPN host context required");
        Context app=context.getApplicationContext();if(app==null)throw new IllegalArgumentException("Actual VPN application required");
        connectivity=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);packages=app.getPackageManager();
        hostUid=app.getApplicationInfo().uid;
        if(connectivity==null||packages==null||hostUid<0)throw new IllegalArgumentException("Platform socket owner service required");
    }
    public Owner find(FlowIdentity original)throws IOException{
        if(original==null)throw new IllegalArgumentException("Original flow required");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("Socket owner lookup must be off UI");
        try{
            int uid=connectivity.getConnectionOwnerUid(original.protocol,
                new InetSocketAddress(InetAddress.getByAddress(original.source()),original.sourcePort),
                new InetSocketAddress(InetAddress.getByAddress(original.destination()),original.destinationPort));
            if(uid<0)throw new IOException("Original socket owner unavailable");
            String[] names=packages.getPackagesForUid(uid);
            if(names==null||names.length==0||names.length>512)throw new IOException("Original socket packages unavailable");
            Set<String> identity=new HashSet<>();
            for(String name:names){if(name==null||name.isEmpty()||name.length()>255)throw new IOException("Invalid original socket identity");identity.add(name);}
            // Package lookup and kernel socket lookup are not one atomic call.
            // Refuse a torn snapshot (UID recycling/shared-UID membership change)
            // rather than authorizing it with either the old packages or host UID.
            int fresh=connectivity.getConnectionOwnerUid(original.protocol,
                new InetSocketAddress(InetAddress.getByAddress(original.source()),original.sourcePort),
                new InetSocketAddress(InetAddress.getByAddress(original.destination()),original.destinationPort));
            String[] current=packages.getPackagesForUid(uid);
            if(fresh!=uid||current==null||current.length==0||current.length>512||
                !identity.equals(new HashSet<>(Arrays.asList(current))))throw new IOException("Original socket identity changed during lookup");
            return new Owner(uid,identity);
        }catch(SecurityException|IllegalArgumentException unavailable){throw new IOException("Active VPN socket owner lookup refused");}
    }
}
