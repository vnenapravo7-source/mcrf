package org.mcrf.engine.protocol.pipe.v1;

import org.mcrf.engine.protocol.v1.PacketBackend;

/** Optional INTERNAL per-server packet role, never a second Android TUN or a
 * stream proxy/netstack. Caller owns rules, DNS, flow pinning and translation.
 * Requires an exact new module capability; presence of this type is not one. */
public interface PacketPipeBackend {
    String METADATA = "org.mcrf.engine.protocol.packet-pipe";
    String CAPABILITY = "owned_unix_dgram_v1";
    void startPacketPipe(Request request, PacketBackend.Host host) throws Exception;

    final class Request {
        public final long generation;
        public final int borrowedPrivateFd, mtu, peerPid;
        private final String configuration;
        public Request(long generation,int borrowedPrivateFd,int mtu,int peerPid,String configuration) {
            if(generation<=0||borrowedPrivateFd<0||mtu<1280||mtu>9000||peerPid<=0||configuration==null||configuration.isEmpty()||
                configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536||configuration.indexOf('\0')>=0)
                throw new IllegalArgumentException("PACKET_PIPE_INPUT");
            this.generation=generation;this.borrowedPrivateFd=borrowedPrivateFd;this.mtu=mtu;this.peerPid=peerPid;
            this.configuration=configuration;
        }
        public String secretConfiguration(){return configuration;}
        @Override public String toString(){return "PacketPipeBackend.Request(redacted)";}
    }
}
