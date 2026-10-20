package org.steer.core;
import android.net.Network;
import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Host-owned UDP/TCP DNS endpoint. Its upstream sockets are explicitly on the VPN.
 * Fake addresses are never recycled within a run, so stale DNS cannot reach another domain. */
public final class TunnelDns implements AutoCloseable,SteerCore.DualStackFlowRouter,SteerCore.PortFlowRouter,SteerCore.OwnedFlowRouter,SteerCore.ProxyFlowObserver {
    private final SteerConfiguration config;
    private final List<InetAddress> bootstrap;
    private final DatagramSocket udp;
    private final ServerSocket tcp;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32));
    private volatile boolean closed;
    private volatile Network vpn;
    private volatile OkHttpClient http;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"steer-dot-deadlines"));
    private final Set<Socket> tlsSockets=ConcurrentHashMap.newKeySet();
    private final Set<DatagramSocket> dnsDatagrams=ConcurrentHashMap.newKeySet();
    private final Set<Socket> dnsClients=ConcurrentHashMap.newKeySet();
    private final Map<String,Mapping> names=new HashMap<>();
    private final Map<Long,Mapping> addresses=new HashMap<>();
    private final Ipv6DnsRoutes ipv6Routes;
    private final ProxyFlowRegistry proxyFlows;
    private final DnsControlRoutes dnsControls;
    private final Map<String,RuleDnsUpstream> ruleUpstreams=new ConcurrentHashMap<>();
    private final SocketOwnerLookup socketOwners;
    private final ProxyCallerRegistry proxyCallers;
    private volatile RuleDnsUpstream globalUpstream;
    private long next=0xc6130001L;
    private static final class Mapping {long fake,real,expires;int action;String domain;}
    private boolean expired4(Mapping mapping,long now){return config.globalDnsPlan!=null?now>=mapping.expires:now>mapping.expires;}
    private synchronized Mapping record4(String name,String domain,long real,int action,long expires,long now)throws IOException{
        if(closed)throw new IOException("DNS owner closed");
        Mapping mapping=names.get(name);
        // Preserve the already delivered legacy1 contract. Model2 must never
        // change the destination/action behind an issued alias, or revive an
        // expired alias which a different request/flow may still retain.
        boolean model2=config.globalDnsPlan!=null;
        boolean replace=mapping==null||model2&&(mapping.real!=real||mapping.action!=action||!Objects.equals(mapping.domain,domain)||now>=mapping.expires);
        if(replace){
            if((model2?addresses.size():names.size())>=32768)throw new IOException("DNS mapping capacity reached");
            mapping=new Mapping();mapping.fake=next++;names.put(name,mapping);addresses.put(mapping.fake,mapping);
        }
        mapping.real=real;mapping.domain=domain;mapping.action=action;
        mapping.expires=model2?Math.max(mapping.expires,expires):expires;
        return mapping;
    }
    public TunnelDns(SteerConfiguration config,List<InetAddress> bootstrap) throws IOException {
        this(config,bootstrap,null);
    }
    /** Only an admitted dual-stack native owner may supply the adapter.
     * Existing production construction remains IPv4-only (null). Ownership of
     * adapter transfers on successful construction only; caller owns failure. */
    public TunnelDns(SteerConfiguration config,List<InetAddress> bootstrap,Ipv6DnsRoutes ipv6Routes) throws IOException {
        this(config,bootstrap,ipv6Routes,null);
    }
    public TunnelDns(SteerConfiguration config,List<InetAddress> bootstrap,Ipv6DnsRoutes ipv6Routes,ProxyFlowRegistry proxyFlows) throws IOException {
        this(config,bootstrap,ipv6Routes,proxyFlows,null);
    }
    public TunnelDns(SteerConfiguration config,List<InetAddress> bootstrap,Ipv6DnsRoutes ipv6Routes,ProxyFlowRegistry proxyFlows,SocketOwnerLookup socketOwners) throws IOException {
        this.ipv6Routes=ipv6Routes;
        this.proxyFlows=proxyFlows;
        if(config.ipv6!=(ipv6Routes!=null))throw new IOException("IPv6 control capture/adapter mismatch");
        dnsControls=config.globalDnsPlan==null?null:new DnsControlRoutes(config.ipv6,ipv6Routes==null?null:ipv6Routes::reserved);
        if((config.globalDnsPlan!=null||config.policy.requiresOwner()||config.policy.requiresFlow())&&socketOwners==null)throw new IOException("Original socket owner service required");
        // Outgoing host sockets NEVER replace the authenticated caller tuple.
        // Mixed flow rules use proxyRequest()/registerProxyRequest(), and require
        // fresh Android owner lookup on BOTH observed control/data sockets.
        // Independent module capability admission still precedes construction.
        this.socketOwners=socketOwners;
        proxyCallers=config.localProxy!=null&&(config.policy.requiresOwner()||config.policy.requiresFlow())?new ProxyCallerRegistry(socketOwners.hostUid):null;
        if(bootstrap.size()>8||(config.encryptedDnsHost!=null&&bootstrap.isEmpty()&&
                (config.globalDnsPlan==null||config.globalDnsPlan.bootstrap.isEmpty())))throw new IOException("One to eight admitted encrypted DNS bootstrap addresses required");
        for(InetAddress endpoint:bootstrap)if(!(endpoint instanceof Inet4Address)&&!(config.ipv6&&config.globalDnsPlan!=null&&endpoint instanceof Inet6Address))throw new IOException("Admitted numeric encrypted DNS bootstrap required");
        this.config=config;this.bootstrap=Collections.unmodifiableList(new ArrayList<>(bootstrap));
        InetAddress loop=InetAddress.getByAddress(new byte[]{127,0,0,1});
        ServerSocket listener=null;DatagramSocket datagram=null;IOException last=null;
        // TCP allocates first: a UDP-selected ephemeral port can still be held
        // by TCP TIME_WAIT after an earlier VPN run. Retry a UDP collision too.
        for(int attempt=0;attempt<8;attempt++) {
            ServerSocket candidate=new ServerSocket();
            try{candidate.bind(new InetSocketAddress(loop,0),32);datagram=new DatagramSocket(new InetSocketAddress(loop,candidate.getLocalPort()));listener=candidate;break;}
            catch(IOException failure){last=failure;candidate.close();}
        }
        if(listener==null||datagram==null)throw last==null?new IOException("DNS port allocation failed"):last;
        tcp=listener;udp=datagram;
    }
    public int port(){return udp.getLocalPort();}
    synchronized boolean attachedTo(Network network){return !closed&&vpn!=null&&vpn.equals(network);}
    @Override public boolean ipv6FlowsEnabled(){return !closed&&ipv6Routes!=null;}
    @Override public boolean portFlowsEnabled(){return !closed&&dnsControls!=null;}
    @Override public boolean ownerFlowsEnabled(){return !closed&&vpn!=null&&(config.globalDnsPlan!=null||config.policy.requiresOwner()||config.policy.requiresFlow())&&socketOwners!=null;}
    synchronized boolean ownsReadiness(ProxyFlowReadiness readiness){return !closed&&proxyFlows!=null&&readiness==proxyFlows;}
    @Override public boolean observesProxyFlows(){return !closed&&proxyFlows!=null;}
    @Override public void proxyFlowResult(byte[] alias,int targetPort,int localPort,boolean ready){
        if(!closed&&proxyFlows!=null)proxyFlows.reportNative(alias,targetPort,localPort,ready);
    }
    public synchronized void attachVpn(Network network) throws IOException {
        if(closed||network==null||vpn!=null)throw new IllegalStateException("Live unattached DNS owner required");vpn=network;
        if(config.dohUrl!=null&&config.globalDnsPlan==null) {
            String host=URI.create(config.dohUrl).getHost();
            List<InetAddress> control=new ArrayList<>();
            for(int i=0;i<bootstrap.size();i++)control.add(InetAddress.getByAddress(new byte[]{10,89,0,(byte)(4+i)}));
            http=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).socketFactory(network.getSocketFactory())
                .dns(name->{if(!name.equalsIgnoreCase(host)||control.isEmpty())throw new UnknownHostException("No configured DoH bootstrap");return control;})
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();
        }
        if(config.globalDnsPlan!=null){
            if(dnsControls==null)throw new IOException("Global DNS control owner required");
            globalUpstream=new RuleDnsUpstream(config.globalDnsPlan,DomainPolicy.PROXY,network,dnsControls,
                (host,cancellation)->{
                    checkDns(cancellation);
                    if(config.encryptedDnsHost==null||!DnsUpstreamPlan.normalizedHost(config.encryptedDnsHost).equals(host)||bootstrap.isEmpty())
                        throw new IOException("Pinned GLOBAL DNS bootstrap required");
                    return bootstrap; // provided before TUN, never recursive/platform DNS
                },deadlines,()->closed);
        }
        for(Map.Entry<String,DnsUpstreamPlan> entry:config.ruleDnsUpstreams.entrySet()){
            Integer action=config.ruleDnsPolicy.bindings().get(entry.getKey());
            if(action==null||dnsControls==null)throw new IOException("Unbound rule DNS transport");
            ruleUpstreams.put(entry.getKey(),new RuleDnsUpstream(entry.getValue(),action,network,dnsControls,
                this::bootstrapRuleDns,deadlines,()->closed));
        }
        new Thread(()->{while(!closed)try{DatagramPacket packet=new DatagramPacket(new byte[4096],4096);udp.receive(packet);byte[] q=Arrays.copyOf(packet.getData(),packet.getLength());
            try{workers.execute(()->{try{byte[] answer=resolve(q);udp.send(new DatagramPacket(answer,answer.length,packet.getSocketAddress()));}catch(IOException ignored){}});}catch(RejectedExecutionException busy){byte[] answer=DnsWire.reply(q,2,null,0);udp.send(new DatagramPacket(answer,answer.length,packet.getSocketAddress()));}
        }catch(IOException ignored){}},"steer-dns-udp").start();
        new Thread(()->{while(!closed)try{
            Socket socket=tcp.accept();dnsClients.add(socket);
            if(closed){closeSocket(socket);dnsClients.remove(socket);continue;}
            try{socket.setSoTimeout(15000);workers.execute(()->serveTcp(socket));}
            catch(IOException|RejectedExecutionException refused){closeSocket(socket);dnsClients.remove(socket);}
        }catch(IOException ignored){}},"steer-dns-tcp").start();
    }
    private void serveTcp(Socket socket) {
        try(Socket client=socket){DataInputStream in=new DataInputStream(client.getInputStream());DataOutputStream out=new DataOutputStream(client.getOutputStream());
            for(int i=0;i<64&&!closed;i++){int size=in.readUnsignedShort();if(size<12||size>4096)return;byte[] q=new byte[size];in.readFully(q);byte[] answer=resolve(q);out.writeShort(answer.length);out.write(answer);out.flush();}
        }catch(IOException ignored){}finally{dnsClients.remove(socket);}
    }
    private static byte[] bounded(InputStream in,int max) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){if(out.size()+n>max)throw new IOException("DNS body too large");out.write(block,0,n);}return out.toByteArray();}
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    private static void checkDns(AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(cancellation.cancelled())throw new IOException("DNS query cancelled");
    }
    private ScheduledFuture<?> cancelDns(AuthenticatedProxy.Cancellation cancellation,Runnable abort){
        return deadlines.scheduleWithFixedDelay(()->{if(closed||cancellation.cancelled())abort.run();},50,50,TimeUnit.MILLISECONDS);
    }
    private byte[] dotUpstream(byte[] query,AuthenticatedProxy.Cancellation cancellation) throws IOException {
        long until=android.os.SystemClock.elapsedRealtime()+15000;
        IOException last=new IOException("DoT deadline exceeded");
        for(int i=0;i<bootstrap.size();i++) {
            long remaining=until-android.os.SystemClock.elapsedRealtime();if(remaining<=0)break;
            checkDns(cancellation);Socket raw=vpn.getSocketFactory().createSocket();ScheduledFuture<?> alarm=null,cancelAlarm=null;
            tlsSockets.add(raw);
            try(Socket transport=raw) {
                if(closed)throw new IOException("DNS closed");
                // The deadline also bounds a peer that slowly drips frame bytes.
                alarm=deadlines.schedule(()->closeSocket(raw),remaining,TimeUnit.MILLISECONDS);
                cancelAlarm=cancelDns(cancellation,()->closeSocket(raw));
                InetAddress alias=InetAddress.getByAddress(new byte[]{10,89,0,(byte)(4+i)});
                transport.connect(new InetSocketAddress(alias,config.dotPort),(int)Math.min(8000,remaining));
                transport.setSoTimeout(10000);
                try(SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(transport,config.encryptedDnsHost,config.dotPort,true)) {
                    SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);
                    List<String> protocols=new ArrayList<>();for(String protocol:tls.getSupportedProtocols())if(protocol.equals("TLSv1.2")||protocol.equals("TLSv1.3"))protocols.add(protocol);
                    if(protocols.isEmpty())throw new IOException("Modern TLS unavailable");tls.setEnabledProtocols(protocols.toArray(new String[0]));
                    tls.setSoTimeout(10000);tls.startHandshake();
                    // RFC 7858: a two-byte DNS length prefix inside authenticated TLS.
                    ByteArrayOutputStream frame=new ByteArrayOutputStream(query.length+2);DataOutputStream out=new DataOutputStream(frame);out.writeShort(query.length);out.write(query);
                    tls.getOutputStream().write(frame.toByteArray());tls.getOutputStream().flush();
                    DataInputStream in=new DataInputStream(tls.getInputStream());int size=in.readUnsignedShort();
                    if(size<12)throw new IOException("Invalid DoT frame size");byte[] reply=new byte[size];in.readFully(reply);return reply;
                }
            }catch(IOException failure){last=failure;}
            finally{if(alarm!=null)alarm.cancel(false);if(cancelAlarm!=null)cancelAlarm.cancel(false);tlsSockets.remove(raw);}
        }
        throw last;
    }
    private byte[] upstream(byte[] query,AuthenticatedProxy.Cancellation cancellation) throws IOException {
        checkDns(cancellation);
        RuleDnsUpstream controlled=globalUpstream;
        if(controlled!=null)return controlled.exchange(query,cancellation);
        if(config.globalDnsPlan!=null)throw new IOException("Global DNS not attached");
        if(config.dotUrl!=null)return dotUpstream(query,cancellation);
        if(config.dohUrl!=null) {
            Request request=new Request.Builder().url(config.dohUrl).header("Accept","application/dns-message")
                .post(RequestBody.create(query,MediaType.get("application/dns-message"))).build();
            Call call=http.newCall(request);ScheduledFuture<?> cancelAlarm=cancelDns(cancellation,call::cancel);
            try(Response response=call.execute()) {
                ResponseBody body=response.body();
                if(response.code()!=200||body==null||body.contentType()==null||!body.contentType().type().equals("application")||!body.contentType().subtype().equals("dns-message"))throw new IOException("Invalid DoH response");
                return bounded(body.byteStream(),65535);
            }finally{cancelAlarm.cancel(false);}
        }
        InetAddress endpoint=InetAddress.getByAddress(new byte[]{10,89,0,3});
        DatagramSocket socket=new DatagramSocket();dnsDatagrams.add(socket);
        ScheduledFuture<?> alarm=null,cancelAlarm=null,streamCancel=null;Socket stream=null;
        try {
            if(closed||vpn==null)throw new IOException("DNS unavailable");
            alarm=deadlines.schedule(socket::close,15,TimeUnit.SECONDS);
            cancelAlarm=cancelDns(cancellation,socket::close);
            vpn.bindSocket(socket);socket.connect(endpoint,5353);socket.setSoTimeout(8000);socket.send(new DatagramPacket(query,query.length));
            DatagramPacket answer=new DatagramPacket(new byte[65535],65535);socket.receive(answer);byte[] bytes=Arrays.copyOf(answer.getData(),answer.getLength());
            if(bytes.length>=12&&(bytes[2]&2)!=0) {
                stream=vpn.getSocketFactory().createSocket();tlsSockets.add(stream);
                final Socket owned=stream;
                if(alarm!=null)alarm.cancel(false);
                alarm=deadlines.schedule(()->closeSocket(owned),8,TimeUnit.SECONDS);
                streamCancel=cancelDns(cancellation,()->closeSocket(owned));checkDns(cancellation);
                if(closed)throw new IOException("DNS closed");
                stream.connect(new InetSocketAddress(endpoint,5353),8000);stream.setSoTimeout(8000);DataOutputStream out=new DataOutputStream(stream.getOutputStream());out.writeShort(query.length);out.write(query);
                DataInputStream in=new DataInputStream(stream.getInputStream());byte[] full=new byte[in.readUnsignedShort()];in.readFully(full);return full;
            }
            return bytes;
        }finally{if(alarm!=null)alarm.cancel(false);if(cancelAlarm!=null)cancelAlarm.cancel(false);if(streamCancel!=null)streamCancel.cancel(false);socket.close();dnsDatagrams.remove(socket);if(stream!=null){closeSocket(stream);tlsSockets.remove(stream);}}
    }
    /** A proxy domain resolves to OUR alias, not the upstream IP. The subsequent
     * VPN socket therefore traverses exactly the same native domain route. */
    public InetAddress proxyAddress(ProxyHandshake.Target target)throws IOException {
        return proxyAddress(target,()->false);
    }
    public InetAddress proxyAddress(ProxyHandshake.Target target,AuthenticatedProxy.Cancellation cancellation)throws IOException {
        return proxyAddress(target,null,cancellation);
    }
    void bindProxyClient(AuthenticatedProxy.OriginalClient client)throws IOException{
        checkDns(client);if(!config.policy.requiresOwner()&&!config.policy.requiresFlow())return;
        if(socketOwners==null)throw new IOException("Original proxy owner lookup required");
        FlowIdentity control=client.originalClient();
        if(control.protocol!=6||!InetAddress.getByAddress(control.source()).isLoopbackAddress()||!InetAddress.getByAddress(control.destination()).isLoopbackAddress())
            throw new IOException("Observed local authenticated control required");
        SocketOwnerLookup.Owner owner=socketOwners.find(control);client.verifyOwner(owner.uid,owner.packages);checkDns(client);
    }
    Set<String> proxyCaller(AuthenticatedProxy.Cancellation cancellation)throws IOException{
        checkDns(cancellation);
        if(!requiresProxyRequest())return null;
        if(proxyCallers==null||socketOwners==null||!(cancellation instanceof AuthenticatedProxy.OriginalClient))throw new IOException("Original authenticated proxy client required");
        FlowIdentity original=((AuthenticatedProxy.OriginalClient)cancellation).originalClient();
        if(original.protocol!=6||!InetAddress.getByAddress(original.source()).isLoopbackAddress()||
            !InetAddress.getByAddress(original.destination()).isLoopbackAddress())throw new IOException("Local TCP client identity required");
        SocketOwnerLookup.Owner owner=socketOwners.find(original);
        ((AuthenticatedProxy.OriginalClient)cancellation).verifyOwner(owner.uid,owner.packages);checkDns(cancellation);return owner.packages;
    }
    void validateProxyDatagram(AuthenticatedProxy.Cancellation cancellation)throws IOException{
        checkDns(cancellation);
        if(!config.policy.requiresOwner()&&!config.policy.requiresFlow())return;
        if(socketOwners==null||!(cancellation instanceof AuthenticatedProxy.OriginalDatagram))throw new IOException("Observed authenticated UDP client required");
        AuthenticatedProxy.OriginalDatagram observed=(AuthenticatedProxy.OriginalDatagram)cancellation;
        FlowIdentity control=observed.originalClient(),datagram=observed.originalDatagram();
        if(control.protocol!=6||datagram.protocol!=17||!Arrays.equals(control.source(),datagram.source())||
                !Arrays.equals(control.destination(),datagram.destination())||!InetAddress.getByAddress(control.source()).isLoopbackAddress()||
                !InetAddress.getByAddress(control.destination()).isLoopbackAddress())throw new IOException("UDP control/source mismatch");
        // No cached caller UID: an unchanged local source port can be recycled
        // by another application while the TCP association still exists.
        SocketOwnerLookup.Owner tcpOwner=socketOwners.find(control),udpOwner=socketOwners.find(datagram);
        if(tcpOwner.uid!=udpOwner.uid||!tcpOwner.packages.equals(udpOwner.packages))throw new IOException("UDP client differs from authenticated control");
        observed.verifyOwner(tcpOwner.uid,tcpOwner.packages);
        checkDns(cancellation);
    }
    boolean requiresProxyRequest(){return config.policy.requiresOwner()||config.policy.requiresFlow();}
    /** Independently capability-gated by main/worker. Captured metadata is not
     * a cached ownership proof; every later lease check repeats actual lookup. */
    ProxyRequestContext proxyRequest(ProxyHandshake.Target target,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        checkDns(cancellation);
        if(socketOwners==null||!(cancellation instanceof AuthenticatedProxy.OriginalClient))throw new IOException("Observed original proxy client required");
        FlowIdentity control=((AuthenticatedProxy.OriginalClient)cancellation).originalClient();
        FlowIdentity data=cancellation instanceof AuthenticatedProxy.OriginalDatagram?
            ((AuthenticatedProxy.OriginalDatagram)cancellation).originalDatagram():control;
        SocketOwnerLookup.Owner controlOwner=socketOwners.find(control),dataOwner=data.protocol==6?controlOwner:socketOwners.find(data);
        if(controlOwner.uid!=dataOwner.uid||!controlOwner.packages.equals(dataOwner.packages))throw new IOException("Proxy client identity changed");
        ((AuthenticatedProxy.OriginalClient)cancellation).verifyOwner(controlOwner.uid,controlOwner.packages);
        ProxyRequestContext request=ProxyRequestContext.observed(control,data,controlOwner.uid,dataOwner.uid,controlOwner.packages,target);
        checkDns(cancellation);return request;
    }
    private void verifyProxyRequest(ProxyRequestContext expected,AuthenticatedProxy.Cancellation scope)throws IOException{
        if(expected==null)throw new IOException("Original proxy request required");
        ProxyRequestContext observed=proxyRequest(expected.requested(),scope);
        if(expected.uid!=observed.uid||!expected.packages().equals(observed.packages())||
                !Arrays.equals(expected.control().encode(),observed.control().encode())||
                !Arrays.equals(expected.data().encode(),observed.data().encode()))throw new IOException("Original proxy request changed");
        checkDns(scope);
    }
    static InetAddress proxySource(byte[] alias)throws IOException{
        if(alias==null||(alias.length!=4&&alias.length!=16))throw new IOException("Numeric proxy alias required");
        return InetAddress.getByAddress(alias.length==4?new byte[]{10,89,0,2}:Ipv6Routes.address(SteerConfiguration.TUN_IPV6));
    }
    ProxyCallerRegistry.Lease registerProxyCaller(FlowIdentity original,Set<String> caller)throws IOException{
        if(closed)throw new IOException("Proxy owner closed");
        if(!config.policy.requiresOwner())return null;
        if(proxyCallers==null)throw new IOException("Proxy caller owner unavailable");
        return proxyCallers.begin(original,caller);
    }
    ProxyCallerRegistry.Lease registerProxyRequest(FlowIdentity outgoing,ProxyRequestContext request,AuthenticatedProxy.Cancellation scope)throws IOException{
        checkDns(scope);
        if(!requiresProxyRequest())return null;
        if(proxyCallers==null)throw new IOException("Proxy request registry unavailable");
        verifyProxyRequest(request,scope); // fresh control AND UDP owner, after DNS/before connect
        return proxyCallers.beginRequest(outgoing,request,scope);
    }
    InetAddress proxyAddress(ProxyHandshake.Target target,Set<String> caller,AuthenticatedProxy.Cancellation cancellation)throws IOException {
        return proxyAddress(target,caller,null,cancellation);
    }
    InetAddress proxyAddress(ProxyHandshake.Target target,Set<String> caller,ProxyRequestContext request,AuthenticatedProxy.Cancellation cancellation)throws IOException {
        return proxyAddress(target,caller,request,cancellation,false);
    }
    InetAddress managedProxyDnsAddress(ProxyHandshake.Target target,Set<String> caller,ProxyRequestContext request,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        if(target==null||target.port!=53||request!=null&&request.protocol!=17)throw new IOException("Managed UDP DNS request required");
        // Admission only: this alias is NEVER connected to port53. Queries use
        // resolve() and its selected managed DNS transport, just as TUN capture.
        return proxyAddress(target,caller,request,cancellation,true);
    }
    private InetAddress proxyAddress(ProxyHandshake.Target target,Set<String> caller,ProxyRequestContext request,AuthenticatedProxy.Cancellation cancellation,boolean managedDns)throws IOException {
        checkDns(cancellation);
        if(closed||vpn==null)throw new IOException("DNS unavailable");
        if(request!=null&&!request.sameRequest(target))throw new IOException("Proxy request target changed");
        if(request!=null&&!request.packages().equals(caller))throw new IOException("Proxy request owner changed");
        if(config.policy.requiresFlow()&&request==null)throw new IOException("Original proxy flow required");
        if(config.policy.requiresOwner()&&(caller==null||caller.isEmpty()))throw new IOException("Original proxy caller packages required");
        if(target.port==53&&!managedDns)throw new IOException("Raw proxy port53 is reserved by the DNS routing contract");
        byte[] numeric=target.address();
        if(numeric==null){
            byte[] query=DnsWire.query(target.domain,1,ThreadLocalRandom.current().nextInt(65536));
            DnsWire.Answer answer=DnsWire.answer(query,resolve(query,cancellation));
            if(answer.rcode!=0)throw new IOException("Proxy DNS rejected");
            if(answer.ipv4!=0){long ip=answer.ipv4;numeric=new byte[]{(byte)(ip>>>24),(byte)(ip>>>16),(byte)(ip>>>8),(byte)ip};}
            else if(ipv6Routes!=null){
                query=DnsWire.query(target.domain,28,ThreadLocalRandom.current().nextInt(65536));
                DnsWire.Answer6 answer6=DnsWire.answer6(query,resolve(query,cancellation));
                if(answer6.rcode!=0)throw new IOException("Proxy DNS rejected");numeric=answer6.address();
            }
            if(numeric==null)throw new IOException("Proxy DNS has no admitted address");
        }else if(numeric.length==4){
            long ip=0;for(byte b:numeric)ip=(ip<<8)|(b&255);
            if((ip&0xfffffff0L)==0x0a590000L||(dnsControls!=null&&DnsControlRoutes.reserved(ip)))throw new IOException("Internal proxy target rejected");
            synchronized(this){
                if(closed)throw new IOException("DNS closed");
                if((ip&0xffff0000L)==0xc6130000L){
                    // A client can legitimately pass an alias obtained from
                    // our DNS. Preserve its DOMAIN policy, not raw-IP policy.
                    // Unknown/expired aliases never become routable literals.
                    Mapping known=addresses.get(ip);
                    if(known==null||expired4(known,android.os.SystemClock.elapsedRealtime())||
                            (known.action!=DomainPolicy.PROXY&&known.action!=DomainPolicy.DIRECT))
                        throw new IOException("Unknown proxy alias");
                }else{
                int action=config.policy.dnsAction(null,ip);
                if(action!=DomainPolicy.PROXY&&action!=DomainPolicy.DIRECT)throw new IOException("Proxy route rejected");
                long now=android.os.SystemClock.elapsedRealtime();
                Mapping mapping=record4("ip/"+Long.toHexString(ip),null,ip,action,now+60000,now);
                long fake=mapping.fake;numeric=new byte[]{(byte)(fake>>>24),(byte)(fake>>>16),(byte)(fake>>>8),(byte)fake};
                }
            }
        }else{
            if(ipv6Routes==null)throw new IOException("IPv6 proxy route unavailable");
            numeric=ipv6Routes.numericAlias(numeric,android.os.SystemClock.elapsedRealtime());
        }
        checkProxyAlias(numeric,caller,request);
        checkDns(cancellation);return InetAddress.getByAddress(numeric);
    }
    private void checkProxyAlias(byte[] numeric,Set<String> caller,ProxyRequestContext request)throws IOException{
        if(numeric==null||(numeric.length!=4&&numeric.length!=16))throw new IOException("Proxy alias unavailable");
        if(numeric.length==4){
            int ip=0;for(byte b:numeric)ip=(ip<<8)|(b&255);
            long route=request!=null?proxyRoute4(ip&0xffffffffL,request):config.policy.requiresOwner()?ownerRoute4(ip&0xffffffffL,caller):routeFlow(ip);
            if((route>>>32)==3)throw new IOException("Proxy route rejected");
        }else{
            if(ipv6Routes==null)throw new IOException("IPv6 proxy route unavailable");
            if((request!=null?ipv6Routes.routeForProxy(numeric,android.os.SystemClock.elapsedRealtime(),request):config.policy.requiresOwner()?ipv6Routes.routeForOwner(numeric,android.os.SystemClock.elapsedRealtime(),caller):routeFlow6(numeric))[0]==3)throw new IOException("Proxy route rejected");
        }
    }
    void validateManagedProxyDns(byte[] alias,ProxyRequestContext request,AuthenticatedProxy.Cancellation scope)throws IOException{
        if(request!=null&&request.destinationPort!=53)throw new IOException("Managed DNS request changed");
        validateProxyChannel(alias,request,scope);
    }
    void validateProxyChannel(byte[] alias,ProxyRequestContext request,AuthenticatedProxy.Cancellation scope)throws IOException{
        checkDns(scope);
        if(requiresProxyRequest()&&request==null)throw new IOException("Original UDP request required");
        if(request!=null){
            if(request.protocol!=17)throw new IOException("Original UDP protocol required");
            verifyProxyRequest(request,scope); // fresh actual TCP+UDP identity, never resolver worker UID
        }
        checkProxyAlias(alias,request==null?null:request.packages(),request);
        checkDns(scope);
    }
    public byte[] resolve(byte[] query) throws IOException {
        return resolve(query,()->false);
    }
    public byte[] resolve(byte[] query,AuthenticatedProxy.Cancellation cancellation) throws IOException {
        if(cancellation==null)throw new IllegalArgumentException("DNS cancellation owner required");
        checkDns(cancellation);if(closed||vpn==null)throw new IOException("DNS unavailable");
        DnsWire.Question q=DnsWire.question(query,false);
        byte[] familyReply=DnsWire.ipv4OnlyReply(query,config.ipv4OnlyDns);
        if(familyReply!=null){checkDns(cancellation);return familyReply;}
        try {
            if(q.type==28 && ipv6Routes!=null){byte[] reply=selectedUpstream(q.name,query,cancellation);checkDns(cancellation);return ipv6Routes.answer(query,reply,android.os.SystemClock.elapsedRealtime());}
            // API2 production admission remains IPv4; HTTPS/SVCB is not enabled.
            if(q.type!=1)return DnsWire.reply(query,0,null,0);
            DnsWire.Answer answer=DnsWire.answer(query,selectedUpstream(q.name,query,cancellation));checkDns(cancellation);
            if(answer.rcode!=0||answer.ipv4==0)return DnsWire.reply(query,answer.rcode,null,0);
            if((answer.ipv4&0xffff0000L)==0xc6130000L||(answer.ipv4&0xfffffff0L)==0x0a590000L||(dnsControls!=null&&DnsControlRoutes.reserved(answer.ipv4)))throw new IOException("Upstream returned reserved internal address");
            int action=config.policy.dnsAction(q.name,answer.ipv4);
            if(action==DomainPolicy.BLOCK)return DnsWire.reply(query,5,null,0);
            synchronized(this) {
                // TTL 0 remains 0 on the wire; a brief mapping grace only permits
                // the immediate connection after a non-cacheable DNS response.
                long now=android.os.SystemClock.elapsedRealtime();
                Mapping m=record4(q.name,q.name,answer.ipv4,action,Math.addExact(now,Math.max(1,answer.ttl)*1000L),now);
                return DnsWire.reply(query,0,m.fake,answer.ttl);
            }
        }catch(IOException|RuntimeException failure){return DnsWire.reply(query,2,null,0);}
    }
    private byte[] selectedUpstream(String name,byte[] query,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        DnsRulePolicy.Choice choice=config.ruleDnsPolicy.select(name);
        if(choice==null)return upstream(query,cancellation);
        RuleDnsUpstream transport=ruleUpstreams.get(choice.resolverTag);
        if(transport==null)throw new IOException("Rule DNS transport not attached");
        return transport.exchange(query,cancellation);
    }
    private List<InetAddress> bootstrapRuleDns(String host,AuthenticatedProxy.Cancellation cancellation)throws IOException{
        byte[] query=DnsWire.query(host,1,ThreadLocalRandom.current().nextInt(65536));
        // Deliberately GLOBAL raw upstream: no per-rule recursion or fake alias.
        try{
            DnsWire.Answer answer=DnsWire.answer(query,upstream(query,cancellation));checkDns(cancellation);
            if(answer.rcode==0&&answer.ipv4!=0){long ip=answer.ipv4;return Collections.singletonList(InetAddress.getByAddress(new byte[]{(byte)(ip>>>24),(byte)(ip>>>16),(byte)(ip>>>8),(byte)ip}));}
        }catch(IOException unavailable){if(!config.ipv6)throw unavailable;}
        checkDns(cancellation);if(!config.ipv6)throw new IOException("Rule TLS bootstrap has no admitted address");
        byte[] query6=DnsWire.query(host,28,ThreadLocalRandom.current().nextInt(65536));
        DnsWire.Answer6 answer6=DnsWire.answer6(query6,upstream(query6,cancellation));checkDns(cancellation);
        byte[] ip=answer6.address();if(answer6.rcode!=0||ip==null)throw new IOException("Rule TLS bootstrap has no admitted address");
        return Collections.singletonList(InetAddress.getByAddress(ip));
    }
    @Override public synchronized long routeFlowPort(int address,int port,int protocol){
        long ip=address&0xffffffffL;
        if(dnsControls!=null&&DnsControlRoutes.reserved(ip))return 3L<<32; // full original tuple/kernel host UID REQUIRED
        if(config.globalDnsPlan!=null&&(ip&0xfffffff0L)==0x0a590000L)return 3L<<32; // model2 never uses legacy bootstrap/control aliases
        return routeFlow(address);
    }
    @Override public synchronized long routeFlow(int address) {
        if(closed)return 3L<<32;
        long ip=address&0xffffffffL;
        if(dnsControls!=null&&DnsControlRoutes.reserved(ip))return 3L<<32; // exact-port callback REQUIRED
        if(config.globalDnsPlan!=null&&(ip&0xfffffff0L)==0x0a590000L)return 3L<<32; // no legacy bootstrap bypass in model2
        // Only dedicated internal aliases bypass user policy. Ordinary traffic to
        // the real resolver IP still obeys IP rules (including block/direct).
        if(ip>=0x0a590004L&&ip<0x0a590004L+bootstrap.size()) {
            byte[] bytes=bootstrap.get((int)(ip-0x0a590004L)).getAddress();long real=0;for(byte b:bytes)real=(real<<8)|(b&255);
            return (1L<<32)|real;
        }
        if((ip&0xfffffff0L)==0x0a590000L)return 3L<<32;
        if(config.policy.requiresOwner()||config.policy.requiresFlow())return 3L<<32; // no tuple/owner, no policy guess
        Mapping m=addresses.get(ip);
        if(m!=null)return !expired4(m,android.os.SystemClock.elapsedRealtime())?((long)m.action<<32)|m.real:(3L<<32);
        if((ip&0xffff0000L)==0xc6130000L)return 3L<<32;
        return ((long)config.policy.action(null,ip)<<32)|ip;
    }
    @Override public byte[] routeFlow6(byte[] address) {
        if(DnsControlRoutes.reserved6(address))return new byte[]{3}; // original tuple/kernel host UID REQUIRED
        return closed || ipv6Routes==null?Ipv6DnsRoutes.deny():ipv6Routes.route(address,android.os.SystemClock.elapsedRealtime());
    }
    private static byte[] owned4(long route){
        long ip=route&0xffffffffL;return new byte[]{(byte)(route>>>32),(byte)(ip>>>24),(byte)(ip>>>16),(byte)(ip>>>8),(byte)ip};
    }
    @Override public byte[] routeOwnedFlow(byte[] encoded){
        try{
            if(!ownerFlowsEnabled())return new byte[]{3};
            FlowIdentity original=FlowIdentity.parse(encoded);long ip=0;
            if(original.family==4){
                for(byte b:original.destination())ip=(ip<<8)|(b&255);
                if(dnsControls!=null&&DnsControlRoutes.reserved(ip)){
                    // Aliases/ports are not credentials. A foreign captured app
                    // must not guess a resolver alias to bypass user/app routes.
                    int uid=socketOwners.find(original).uid;
                    return owned4(closed?3L<<32:dnsControls.routeForUid(ip,original.destinationPort,original.protocol,uid,socketOwners.hostUid));
                }
                if((ip&0xfffffff0L)==0x0a590000L)return owned4(routeFlowPort((int)ip,original.destinationPort,original.protocol));
            }else{
                if(dnsControls!=null&&DnsControlRoutes.reserved6(original.destination())){
                    int uid=socketOwners.find(original).uid;
                    return closed?new byte[]{3}:dnsControls.route6ForUid(original.destination(),original.destinationPort,original.protocol,uid,socketOwners.hostUid);
                }
                if(ipv6Routes==null)return new byte[]{3};
            }
            if(!config.policy.requiresOwner()&&!config.policy.requiresFlow())return original.family==6?routeFlow6(original.destination()):owned4(routeFlow((int)ip));
            Set<String> packages=null;
            if(config.policy.requiresOwner()||proxyCallers!=null){
                SocketOwnerLookup.Owner owner=socketOwners.find(original); // actual outgoing tuple, not routed real destination
                ProxyCallerRegistry.Lease lease=proxyCallers==null?null:proxyCallers.requestLease(original,owner.uid);
                if(lease!=null){
                    ProxyRequestContext request=lease.context();
                    verifyProxyRequest(request,lease.scope()); // NEVER substitute outgoing host UID/source/port
                    byte[] routed=original.family==6?ipv6Routes.routeForProxy(original.destination(),android.os.SystemClock.elapsedRealtime(),request):owned4(proxyRoute4(ip,request));
                    verifyProxyRequest(request,lease.scope()); // owner/UDP source may have changed during policy work
                    // Fresh lookup/policy work can race STOP or transport close.
                    // The exact lease must still be active after both operations.
                    if(proxyCallers.request(original,owner.uid)!=request)throw new IOException("Proxy request retired during routing");
                    checkDns(lease.scope());return routed;
                }
                packages=owner.packages;
            }
            if(original.family==6)return closed?new byte[]{3}:ipv6Routes.routeForFlow(original,android.os.SystemClock.elapsedRealtime(),packages);
            return owned4(ownerRoute4(ip,packages,original));
        }catch(IOException|RuntimeException unavailable){return new byte[]{3};}
    }
    private synchronized long ownerRoute4(long ip,Set<String> packages){
        return ownerRoute4(ip,packages,null);
    }
    private synchronized long proxyRoute4(long ip,ProxyRequestContext request){
        if(closed||request==null)return 3L<<32;
        Mapping mapping=addresses.get(ip);
        if(mapping==null||expired4(mapping,android.os.SystemClock.elapsedRealtime()))return 3L<<32;
        String domain=mapping.domain;long real=mapping.real;
        byte[] numeric={(byte)(real>>>24),(byte)(real>>>16),(byte)(real>>>8),(byte)real};
        byte[] alias={(byte)(ip>>>24),(byte)(ip>>>16),(byte)(ip>>>8),(byte)ip};
        if(!request.matchesResolved(alias,domain,numeric))return 3L<<32;
        return ((long)config.policy.actionForProxy(domain,numeric,request)<<32)|real;
    }
    private synchronized long ownerRoute4(long ip,Set<String> packages,FlowIdentity original){
        if(closed)return 3L<<32;Mapping mapping=addresses.get(ip);
        if(mapping!=null){
            if(expired4(mapping,android.os.SystemClock.elapsedRealtime()))return 3L<<32;
            int action=original==null?config.policy.actionForOwner(mapping.domain,mapping.real,packages):config.policy.actionForFlow(mapping.domain,mapping.real,original,packages);
            return ((long)action<<32)|mapping.real;
        }
        if((ip&0xffff0000L)==0xc6130000L)return 3L<<32;
        int action=original==null?config.policy.actionForOwner(null,ip,packages):config.policy.actionForFlow(null,ip,original,packages);
        return ((long)action<<32)|ip;
    }
    @Override public void close(){
        if(closed)return;closed=true;udp.close();try{tcp.close();}catch(IOException ignored){}workers.shutdownNow();
        if(proxyFlows!=null)proxyFlows.close();
        if(proxyCallers!=null)proxyCallers.close();
        RuleDnsUpstream global=globalUpstream;if(global!=null)global.close();
        for(RuleDnsUpstream transport:ruleUpstreams.values())transport.close();
        if(dnsControls!=null)dnsControls.close();
        if(ipv6Routes!=null)ipv6Routes.close();
        for(Socket socket:tlsSockets)closeSocket(socket);
        for(Socket socket:dnsClients)closeSocket(socket);
        for(DatagramSocket socket:dnsDatagrams)socket.close();deadlines.shutdownNow();
        OkHttpClient client=http;
        // TLS close can write close_notify. Never perform it on the Android UI thread.
        if(client!=null)new Thread(()->{client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();},"steer-dns-close").start();
    }
}
