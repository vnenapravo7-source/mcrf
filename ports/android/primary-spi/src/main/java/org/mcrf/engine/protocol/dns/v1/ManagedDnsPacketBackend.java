package org.mcrf.engine.protocol.dns.v1;

import org.mcrf.engine.protocol.v1.PacketBackend;

/** Optional DNS extension, NOT a change to the locked packet API1. Parent loaded.
 * No native/Go/Android types, DNS queries, secrets or arbitrary forwarding rules.
 * Negotiated separately from BLOCK and admitted only for the SAME verified APK.
 * The kernel owns TCP/retransmission; the native adapter owns only bounded NAT
 * between two internal aliases and a worker-owned high-port DNS listener.
 */
public interface ManagedDnsPacketBackend extends PacketBackend {
    String CONTRACT = "owned_kernel_dns_nat_v1";
    String ALIAS4 = "198.19.255.53";
    String ALIAS6 = "fdfe:dcba:9876:dd54::53";
    void startManaged(Request request, Host host, Config config) throws Exception;
    /** Fresh native mapping only; the worker MUST still repeat kernel owner
     * lookup of the ORIGINAL tuple before every DNS exchange/reply. */
    Client dnsClient(int protocol, int translatedPort);

    final class Config {
        public final String address4, address6, sourceAddresses;
        public final int port;
        public Config(String address4,String address6,String sourceAddresses,int port) {
            if(address4==null||address6==null||sourceAddresses==null||port<1024||port>65535||
               address4.isEmpty()&&address6.isEmpty()||address4.length()>45||address6.length()>45||
               sourceAddresses.isEmpty()||sourceAddresses.length()>1024||
               !sourceAddresses.matches("[0-9a-fA-F:.,]+"))throw new IllegalArgumentException("PACKET_DNS_BRIDGE_INPUT");
            this.address4=address4;this.address6=address6;this.sourceAddresses=sourceAddresses;this.port=port;
        }
        @Override public String toString(){return "PacketDnsConfig(redacted)";}
    }
    final class Client {
        public final int protocol,sourcePort;
        public final String source,destination;
        public Client(int protocol,String source,int sourcePort,String destination){
            if((protocol!=6&&protocol!=17)||source==null||source.isEmpty()||source.length()>45||
               sourcePort<1||sourcePort>65535||(!ALIAS4.equals(destination)&&!ALIAS6.equals(destination)))
                throw new IllegalArgumentException("PACKET_DNS_CLIENT_INPUT");
            this.protocol=protocol;this.source=source;this.sourcePort=sourcePort;this.destination=destination;
        }
        @Override public String toString(){return "PacketDnsClient(redacted)";}
    }
}
