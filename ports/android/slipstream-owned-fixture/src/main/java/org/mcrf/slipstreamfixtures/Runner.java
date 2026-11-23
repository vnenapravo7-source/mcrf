package org.mcrf.slipstreamfixtures;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Emulator-only normal app sandbox: Unix sockets need app-owned storage. */
public final class Runner extends Instrumentation {
    private String cases,runId;
    @Override public void onCreate(Bundle args){super.onCreate(args);cases=args.getString("cases");runId=args.getString("runId");start();}
    private static String sha(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)digest.update(buffer,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    @Override public void onStart(){
        Bundle result=new Bundle();Process child=null;
        try{
            Context context=getTargetContext();
            if(!android.os.Build.SUPPORTED_ABIS[0].equals("x86_64")||!(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish")))throw new IllegalStateException("owned x86 emulator only");
            if(cases==null||!cases.matches("TestMcrfSlipstream[A-Za-z0-9_]+(?:,TestMcrfSlipstream[A-Za-z0-9_]+)*")||runId==null||!runId.matches("[a-f0-9]{32}"))throw new IllegalStateException("explicit owned cases/run ID required");
            File directory=new File(context.getApplicationInfo().nativeLibraryDir);
            String hashes=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData.getString("org.mcrf.fixture.hashes");
            if(hashes==null||(hashes.split("\\|").length!=5&&hashes.split("\\|").length!=6))throw new IllegalStateException("complete native identities required");
            for(String entry:hashes.split("\\|")){
                String[] pair=entry.split(":");
                if(pair.length!=2||!pair[0].matches("libslipstream[a-z_]*\\.so")||!sha(new File(directory,pair[0])).equals(pair[1]))throw new IllegalStateException("native digest changed");
            }
            File binary=new File(directory,"libslipstream_fixture_android.so");
            if(!binary.exists())binary=new File(directory,"libslipstream_fixture.so");
            File log=new File(context.getFilesDir(),"slipstream-owned-"+runId+".log");if(log.exists())throw new IllegalStateException("retain old attempt");
            ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"-test.v","-test.run=^("+cases.replace(",","|")+")$","-test.timeout=180s").directory(context.getCacheDir()).redirectErrorStream(true);
            builder.environment().put("TMPDIR",context.getCacheDir().getAbsolutePath());
            builder.environment().put("MCRF_SLIPSTREAM_TEST_CORE",new File(directory,"libslipstream_core.so").getAbsolutePath());
            File diagnostic=new File(directory,"libslipstream_diagnostic_server.so");
            result.putString("diagnosticSha256",sha(diagnostic));
            builder.environment().put("MCRF_SLIPSTREAM_TEST_SERVER",diagnostic.getAbsolutePath());
            builder.environment().put("MCRF_SLIPSTREAM_TEST_CSERVER",new File(directory,"libslipstream_diagnostic_cserver.so").getAbsolutePath());
            builder.environment().put("MCRF_SLIPSTREAM_TEST_WRAPPER",new File(directory,"libslipstream.so").getAbsolutePath());
            if(cases.contains("TestMcrfSlipstreamOwnedWirePhysicalNetwork")){
                ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
                Network physical=manager.getActiveNetwork();
                NetworkCapabilities capabilities=physical==null?null:manager.getNetworkCapabilities(physical);
                if(capabilities==null||!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
                    throw new IllegalStateException("owned fixture requires a physical network");
                builder.environment().put("MCRF_SLIPSTREAM_TEST_PHYSICAL",Long.toString(physical.getNetworkHandle()));
            }
            child=builder.start();child.getOutputStream().close();final Process owner=child;
            final ByteArrayOutputStream captured=new ByteArrayOutputStream();
            Thread reader=new Thread(()->{try{byte[] b=new byte[8192];for(int n;(n=owner.getInputStream().read(b))>=0;){if(captured.size()+n>1048576){owner.destroyForcibly();return;}captured.write(b,0,n);}}catch(Exception ignored){}});reader.setDaemon(true);reader.start();
            if(!child.waitFor(195,TimeUnit.SECONDS)){child.destroyForcibly();throw new IllegalStateException("native fixture timeout");}
            reader.join(2000);if(reader.isAlive())throw new IllegalStateException("native output not retired");
            try(FileOutputStream output=new FileOutputStream(log)){captured.writeTo(output);output.getFD().sync();}
            result.putString("binarySha256",sha(binary));result.putInt("exit",child.exitValue());finish(child.exitValue()==0?-1:0,result);
        }catch(Exception error){result.putString("failure",error.getClass().getSimpleName()+": "+error.getMessage());finish(0,result);}
        finally{if(child!=null&&child.isAlive())child.destroyForcibly();}
    }
}
