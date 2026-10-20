package org.steer.core;

import java.io.IOException;

/** Protected-upstream AAAA-to-owned-alias adapter. No DNS/socket/native call.
 * A dual-stack host must reserve this ULA /64 inside its TUN, never exclude it,
 * and pass each new flow through route(). Old IPv4 admission cannot enable it. */
public final class Ipv6DnsRoutes implements AutoCloseable {
    private final DomainPolicy policy;
    private final SyntheticIpv6Map aliases;
    private boolean closed;
    public Ipv6DnsRoutes(DomainPolicy policy, Ipv6Routes.Prefix prefix, int capacity) {
        if(policy==null || !policy.ipv6Enabled())throw new IllegalArgumentException("Explicit dual-stack policy required");
        this.policy=policy;aliases=new SyntheticIpv6Map(prefix,capacity);
    }
    /** Fresh random ULA /64 per generation avoids deterministic alias reuse
     * when the serial restarts after a previous VPN run.
     * A full captured ::/0, not an OS bypass, must cover this private prefix. */
    public static Ipv6DnsRoutes create(DomainPolicy policy){
        byte[] prefix=new byte[16];new java.security.SecureRandom().nextBytes(prefix);
        prefix[0]=(byte)0xfd;java.util.Arrays.fill(prefix,8,16,(byte)0);
        // Keep the fixed virtual interface's /64 distinct, even on collision.
        byte[] tun=Ipv6Routes.address("fdfe:dcba:9876::");
        if(java.util.Arrays.equals(prefix,tun))prefix[7]^=1;
        if(DnsControlRoutes.reserved6(prefix))prefix[7]^=1;
        return new Ipv6DnsRoutes(policy,new Ipv6Routes.Prefix(prefix,64),32768);
    }
    /** Native callback result: action byte (1 proxy,2 direct,3 deny), then16
     * network-order destination bytes. Malformed/expired input never falls back. */
    public static byte[] deny() { byte[] result=new byte[17];result[0]=(byte)DomainPolicy.BLOCK;return result; }
    public boolean reserved(byte[] address){return aliases.ownsNamespace(address);}
    public synchronized byte[] route(byte[] address,long now) {
        return routeForOwner(address,now,null);
    }
    public synchronized byte[] routeForOwner(byte[] address,long now,java.util.Set<String> packages) {
        return route(address,now,packages,null);
    }
    /** ORIGINAL alias tuple remains unchanged while DNS supplies real target.
     * Source/port/network rules must never see a tuple reconstructed from that
     * target, nor an action cached when DNS had no flow evidence. */
    public synchronized byte[] routeForFlow(FlowIdentity original,long now,java.util.Set<String> packages){
        if(original==null||original.family!=6)return deny();
        return route(original.destination(),now,packages,original);
    }
    private byte[] route(byte[] address,long now,java.util.Set<String> packages,FlowIdentity original){
        return route(address,now,packages,original,null);
    }
    public synchronized byte[] routeForProxy(byte[] address,long now,ProxyRequestContext request){
        if(request==null)return deny();return route(address,now,request.packages(),null,request);
    }
    private byte[] route(byte[] address,long now,java.util.Set<String> packages,FlowIdentity original,ProxyRequestContext request){
        if(Ipv6Routes.mappedIpv4(address))return deny();
        if(closed || address==null || address.length!=16 || now<0||DnsControlRoutes.reserved6(address))return deny();
        try {
            SyntheticIpv6Map.Decision decision=aliases.lookup(address,now);
            if(decision.kind==SyntheticIpv6Map.Kind.DENY)return deny();
            byte[] target=decision.address();
            if(request!=null&&(decision.kind!=SyntheticIpv6Map.Kind.RESOLVED||!request.matchesResolved(address,decision.domain,target)))return deny();
            int action=request!=null?policy.actionForProxy(decision.domain,target,request):
                original!=null?policy.action6ForFlow(decision.domain,target,original,packages):
                policy.requiresFlow()?DomainPolicy.BLOCK:policy.requiresOwner()?policy.action6ForOwner(decision.domain,target,packages):
                decision.kind==SyntheticIpv6Map.Kind.OUTSIDE?policy.action6(null,target):decision.action;
            if(action!=DomainPolicy.PROXY && action!=DomainPolicy.DIRECT)return deny();
            byte[] result=new byte[17];result[0]=(byte)action;System.arraycopy(target,0,result,1,16);return result;
        }catch(RuntimeException invalid){return deny();}
    }
    public synchronized byte[] answer(byte[] query,byte[] upstream,long now) throws IOException {
        if(closed || now<0)throw new IOException("IPv6 DNS owner unavailable");
        DnsWire.Question question=DnsWire.question(query,false);
        if(question.type!=28)throw new IOException("AAAA query required");
        DnsWire.Answer6 answer=DnsWire.answer6(query,upstream);
        byte[] address=answer.address();
        if(answer.rcode!=0 || address==null)return DnsWire.reply6(query,answer.rcode,null,0);
        try {
            int action=policy.dnsAction6(question.name,address);
            if(action==DomainPolicy.BLOCK)return DnsWire.reply6(query,5,null,0);
            byte[] alias=aliases.record(question.name,address,action,answer.ttl,now);
            return DnsWire.reply6(query,0,alias,answer.ttl);
        }catch(RuntimeException refused){throw new IOException("IPv6 DNS mapping refused");}
    }
    @Override public synchronized void close(){closed=true;aliases.close();}
    public synchronized byte[] numericAlias(byte[] address,long now)throws IOException{
        if(closed)throw new IOException("IPv6 DNS owner unavailable");
        try{
            SyntheticIpv6Map.Decision existing=aliases.lookup(address,now);
            if(existing.kind==SyntheticIpv6Map.Kind.DENY)throw new IOException("Unknown proxy alias");
            if(existing.kind==SyntheticIpv6Map.Kind.RESOLVED)return address.clone();
            int action=policy.dnsAction6(null,address);
            if(action!=DomainPolicy.PROXY&&action!=DomainPolicy.DIRECT)throw new IOException("Proxy route rejected");
            return aliases.recordNumeric(address,action,60,now);
        }catch(RuntimeException invalid){throw new IOException("Numeric IPv6 route refused");}
    }
}
