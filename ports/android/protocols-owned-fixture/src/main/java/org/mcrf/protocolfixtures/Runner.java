package org.mcrf.protocolfixtures;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Disposable x86 emulator only. No VPN, app profile, account or VPS mutation. */
public final class Runner extends Instrumentation {
    private String cases,runId;
    @Override public void onCreate(Bundle args){super.onCreate(args);cases=args.getString("cases");runId=args.getString("runId");start();}
    private static String sha(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)digest.update(buffer,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    @Override public void onStart(){
        Bundle result=new Bundle();Process child=null;
        try{
            Context context=getTargetContext();
            if(!android.os.Build.SUPPORTED_ABIS[0].equals("x86_64")||!(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish")))throw new IllegalStateException("owned x86 emulator only");
            if(cases==null||!cases.matches("TestMcrfProtocols[A-Za-z0-9_]+(?:,TestMcrfProtocols[A-Za-z0-9_]+)*")||runId==null||!runId.matches("[a-f0-9]{32}"))throw new IllegalStateException("explicit owned cases/run ID required");
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network physical=null;Network active=cm.getActiveNetwork();
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                if(caps!=null&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)){
                    if(physical==null)physical=network;
                    if(network.equals(active)){physical=network;break;}
                }
            }
            if(physical==null)throw new IllegalStateException("actual physical network unavailable");
            Process diagnostic=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libnetwork_diagnostic.so").getAbsolutePath(),Long.toUnsignedString(physical.getNetworkHandle())).redirectErrorStream(true).start();
            try{
                if(!diagnostic.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("network diagnostic timeout");
                byte[] details=new byte[1024];int count=diagnostic.getInputStream().read(details);
                result.putString("networkDiagnostic",count>0?new String(details,0,count,java.nio.charset.StandardCharsets.UTF_8).replace('\n',';'):"empty");
            }finally{if(diagnostic.isAlive())diagnostic.destroyForcibly();}
            File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libprotocols_fixture.so").getCanonicalFile();
            String expected=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData.getString("org.mcrf.fixture.sha256");
            if(expected==null||!sha(binary).equals(expected))throw new IllegalStateException("fixture binary digest changed");
            File log=new File(context.getFilesDir(),"protocols-owned-"+runId+".log");if(log.exists())throw new IllegalStateException("retain existing receipt; do not replay completed cases");
            ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"-test.v","-test.run=^("+cases.replace(",","|")+")$","-test.timeout=150s").directory(context.getCacheDir()).redirectErrorStream(true);
            builder.environment().put("MCRF_PROTOCOLS_FIXTURE_NETWORK_HANDLE",Long.toUnsignedString(physical.getNetworkHandle()));
            child=builder.start();child.getOutputStream().close();final Process owner=child;
            final ByteArrayOutputStream captured=new ByteArrayOutputStream();
            Thread reader=new Thread(()->{try{byte[] b=new byte[8192];for(int n;(n=owner.getInputStream().read(b))>=0;){if(captured.size()+n>1048576){owner.destroyForcibly();return;}captured.write(b,0,n);}}catch(Exception ignored){}});reader.setDaemon(true);reader.start();
            if(!child.waitFor(165,TimeUnit.SECONDS)){child.destroyForcibly();throw new IllegalStateException("native fixture owner timeout");}
            reader.join(2000);if(reader.isAlive())throw new IllegalStateException("native output owner not retired");
            try(FileOutputStream output=new FileOutputStream(log)){captured.writeTo(output);output.getFD().sync();}
            result.putString("log",log.getAbsolutePath());result.putInt("exit",child.exitValue());result.putString("binarySha256",expected);
            finish(child.exitValue()==0?-1:0,result);
        }catch(Exception error){result.putString("failure",error.getClass().getSimpleName()+": "+error.getMessage());finish(0,result);}
        finally{if(child!=null&&child.isAlive())child.destroyForcibly();}
    }
}
