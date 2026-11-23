package org.mcrf.runtime;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only owned DNS and one public connectivity relay. TLS is not terminated:
 * production validates the real gstatic certificate. No arbitrary forwarding. */
final class P3SteerFixtureNetwork implements AutoCloseable {
    final Network physical;
    final Set<String> healthAddresses=new HashSet<>();
    final AtomicInteger tlsConnects=new AtomicInteger(),dnsQueries=new AtomicInteger();
    final ExecutorService tasks=Executors.newCachedThreadPool();
    final List<Closeable> resources=Collections.synchronizedList(new ArrayList<>());
    P3SteerFixtureNetwork(Network physical)throws Exception {
        this.physical=physical;
        for(InetAddress address:physical.getAllByName("www.gstatic.com"))if(address instanceof Inet4Address)healthAddresses.add(address.getHostAddress());
        if(healthAddresses.isEmpty())throw new IOException("Physical health IPv4 missing");
    }
    boolean relay(String host,int port,Socket peer,DataInputStream in,OutputStream out,AtomicBoolean fail)throws Exception {
        if(port!=443||!(host.equals("www.gstatic.com")||healthAddresses.contains(host)))return false;
        tlsConnects.incrementAndGet();
        if(fail.get()){out.write(new byte[]{5,5,0,1,127,0,0,1,0,0});out.flush();return true;}
        Socket remote=new Socket();resources.add(remote);physical.bindSocket(remote);
        remote.connect(new InetSocketAddress(healthAddresses.iterator().next(),443),3500);remote.setSoTimeout(12000);
        out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
        Future<?> upstream=tasks.submit(()->copy(in,remote));
        try{copy(remote.getInputStream(),peer);}finally{remote.close();upstream.cancel(true);}
        return true;
    }
    void copy(InputStream source,Socket destination){try{byte[] data=new byte[8192];int count;OutputStream target=destination.getOutputStream();while((count=source.read(data))>=0){target.write(data,0,count);target.flush();}}catch(Exception ignored){}finally{try{destination.shutdownOutput();}catch(Exception ignored){}}}
    void associate(Socket control,DataInputStream input,OutputStream output,String localHost)throws Exception {
        DatagramSocket udp=new DatagramSocket(null);resources.add(udp);physical.bindSocket(udp);udp.bind(new InetSocketAddress(localHost,0));udp.setSoTimeout(15000);
        byte[] ip=InetAddress.getByName(localHost).getAddress();ByteArrayOutputStream reply=new ByteArrayOutputStream();reply.write(new byte[]{5,0,0,1});reply.write(ip);reply.write(udp.getLocalPort()>>8);reply.write(udp.getLocalPort());output.write(reply.toByteArray());output.flush();
        Future<?> responder=tasks.submit(()->{try{while(!udp.isClosed()){
            byte[] bytes=new byte[2048];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);udp.receive(packet);
            int length=packet.getLength();if(length<22||bytes[0]!=0||bytes[1]!=0||bytes[2]!=0||bytes[3]!=1)continue;
            if((bytes[4]&255)!=198||(bytes[5]&255)!=51||(bytes[6]&255)!=100||(bytes[7]&255)!=53||bytes[8]!=0||bytes[9]!=53)continue;
            byte[] answer=dns(Arrays.copyOfRange(bytes,10,length));if(answer==null)continue;
            byte[] response=new byte[10+answer.length];System.arraycopy(bytes,0,response,0,10);System.arraycopy(answer,0,response,10,answer.length);
            udp.send(new DatagramPacket(response,response.length,packet.getSocketAddress()));
        }}catch(Exception ignored){}});
        try{while(input.read()!=-1){}}finally{udp.close();responder.cancel(true);}
    }
    byte[] dns(byte[] query)throws Exception {
        if(query.length<17||query[4]!=0||query[5]!=1)return null;
        int end=12;StringBuilder name=new StringBuilder();
        while(end<query.length){int n=query[end++]&255;if(n==0)break;if(n>63||end+n>query.length)return null;if(name.length()>0)name.append('.');name.append(new String(query,end,n,"US-ASCII"));end+=n;}
        if(end+4>query.length)return null;int type=((query[end]&255)<<8)|(query[end+1]&255);end+=4;
        boolean known=name.toString().equalsIgnoreCase("www.gstatic.com");boolean answer=known&&type==1;
        byte[] header=Arrays.copyOf(query,end);header[2]=(byte)0x81;header[3]=(byte)(known?0x80:0x83);header[6]=0;header[7]=(byte)(answer?1:0);header[8]=header[9]=header[10]=header[11]=0;dnsQueries.incrementAndGet();
        if(!answer)return header;
        ByteArrayOutputStream result=new ByteArrayOutputStream();result.write(header);result.write(new byte[]{(byte)0xc0,12,0,1,0,1,0,0,0,1,0,4});result.write(InetAddress.getByName(healthAddresses.iterator().next()).getAddress());return result.toByteArray();
    }
    public void close(){synchronized(resources){for(Closeable resource:resources)try{resource.close();}catch(Exception ignored){}}tasks.shutdownNow();}
}
