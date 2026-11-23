package org.mcrf.runtime;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Adds a fixed Cloudflare DoH endpoint and certificate-verified gstatic IPs
 * for composed SSH/TCP-only DNS acceptance. Never disables certificate checks. */
final class P3ProtocolFixtureNetwork {
    final P3SteerFixtureNetwork owner;
    P3ProtocolFixtureNetwork(P3SteerFixtureNetwork owner){this.owner=owner;}
    boolean relay(String host,int port,Socket peer,DataInputStream input,OutputStream output)throws Exception {
        if(port!=443)return false;
        String destination=host;
        if(host.equals("www.gstatic.com"))destination=owner.healthAddresses.iterator().next();
        else if(!host.equals("1.1.1.1")&&!owner.healthAddresses.contains(host)){
            if(!host.matches("[0-9]+(?:\\.[0-9]+){3}"))return false;
            InetAddress address=InetAddress.getByName(host);if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
            Socket raw=new Socket();owner.physical.bindSocket(raw);raw.connect(new InetSocketAddress(host,443),3500);raw.setSoTimeout(3500);
            try(SSLSocket verified=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,"www.gstatic.com",443,true)){
                SSLParameters parameters=verified.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");parameters.setServerNames(Collections.singletonList(new SNIHostName("www.gstatic.com")));verified.setSSLParameters(parameters);verified.startHandshake();
            }finally{raw.close();}
            owner.healthAddresses.add(host);
        }
        Socket remote=new Socket();owner.resources.add(remote);owner.physical.bindSocket(remote);remote.connect(new InetSocketAddress(destination,443),3500);remote.setSoTimeout(12000);
        output.write(new byte[]{5,0,0,1,127,0,0,1,0,0});output.flush();
        Future<?> upstream=owner.tasks.submit(()->owner.copy(input,remote));
        try{owner.copy(remote.getInputStream(),peer);}finally{remote.close();upstream.cancel(true);}return true;
    }
}
