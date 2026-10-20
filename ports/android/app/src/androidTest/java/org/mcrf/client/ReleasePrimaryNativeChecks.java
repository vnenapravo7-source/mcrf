package org.mcrf.client;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Framework-only same-UID Binder preflight against the EXACT minified host.
 * No app-class reflection, keep rules, TUN, native listener, account or profile selection. */
final class ReleasePrimaryNativeChecks {
    private static final String DESCRIPTOR="org.mcrf.client.primary.worker.v1";
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static void send(IBinder worker,Bundle fields)throws Exception{
        Parcel data=Parcel.obtain();try{data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(fields);
            check(worker.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY),"Worker rejected owned transaction");
        }finally{data.recycle();}
    }
    static String run(Instrumentation test)throws Exception{return run(test,false);}
    static String run(Instrumentation test,boolean steer)throws Exception{
        return run(test,steer,false);
    }
    static String runPacket(Instrumentation test)throws Exception{return run(test,false,true);}
    private static String run(Instrumentation test,boolean steer,boolean packet)throws Exception{
        final String choice=packet?"wg":steer?"steer":"singbox";
        Context context=test.getTargetContext();long generation=SystemClock.elapsedRealtimeNanos();
        Binder owner=new Binder();AtomicReference<IBinder> endpoint=new AtomicReference<>();CountDownLatch connected=new CountDownLatch(1),dead=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){endpoint.set(binder);connected.countDown();}
            public void onServiceDisconnected(ComponentName name){}
        };
        ComponentName component=new ComponentName(context.getPackageName(),context.getPackageName()+".ClientPrimaryProbeWorkerService");
        android.content.pm.ServiceInfo service=context.getPackageManager().getServiceInfo(component,0);
        check(!service.exported&&service.applicationInfo.uid==android.os.Process.myUid()&&service.processName.equals(context.getPackageName()+":primaryProbe"),"Private worker manifest identity changed");
        boolean bound=false,claimed=false;int pid=-1;List<File> inputs=new ArrayList<>();StringBuilder report=new StringBuilder();
        try{
            bound=context.bindService(new Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE);
            check(bound&&connected.await(8,TimeUnit.SECONDS),"Minified worker bind failed");
            IBinder worker=endpoint.get();check(DESCRIPTOR.equals(worker.getInterfaceDescriptor()),"Worker interface mismatch");
            Parcel request=Parcel.obtain(),reply=Parcel.obtain();
            try{
                request.writeInterfaceToken(DESCRIPTOR);Bundle fields=new Bundle();fields.putString("command","BIND");fields.putBinder("owner",owner);fields.putLong("generation",generation);request.writeBundle(fields);
                check(worker.transact(IBinder.FIRST_CALL_TRANSACTION,request,reply,0),"Worker handshake failed");reply.readException();Bundle result=reply.readBundle(context.getClassLoader());
                check(result!=null&&result.getInt("uid")==android.os.Process.myUid()&&result.getLong("generation")==generation&&service.processName.equals(result.getString("process")),"Worker UID/process/generation mismatch");
                pid=result.getInt("pid");check(pid>0&&pid!=android.os.Process.myPid(),"Native worker must be a separate owned process");claimed=true;
                worker.linkToDeath(dead::countDown,0);
            }finally{request.recycle();reply.recycle();}
            final IBinder remote=worker;
            class Commands {
                Bundle call(String command,java.util.function.Consumer<Bundle> extra)throws Exception{
                    CountDownLatch done=new CountDownLatch(1);AtomicReference<Bundle> result=new AtomicReference<>();
                    ResultReceiver callback=new ResultReceiver(new Handler(Looper.getMainLooper())){@Override protected void onReceiveResult(int code,Bundle data){result.set(data);done.countDown();}};
                    Parcel parcel=Parcel.obtain();ResultReceiver carrier;
                    try{callback.writeToParcel(parcel,0);parcel.setDataPosition(0);carrier=ResultReceiver.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
                    Bundle fields=new Bundle();fields.putString("command",command);fields.putBinder("owner",owner);fields.putLong("generation",generation);fields.putParcelable("receiver",carrier);extra.accept(fields);send(remote,fields);
                    check(done.await(20,TimeUnit.SECONDS),"Owned native preflight command timed out: "+command);
                    Bundle value=result.get();check(value!=null&&value.getLong("generation")==generation&&!value.containsKey("error"),"Owned native preflight failed: "+command+"/"+(value==null?"no-response":value.getString("error")));
                    return value;
                }
                boolean validate(JSONObject config)throws Exception{
                    JSONObject envelope=steer?config:new JSONObject().put("schema",1).put("mode","proxy").put("metered",false).put("config",config);
                    String profile=steer?"vless://00000000-0000-4000-8000-000000000001@localhost:18990?encryption=none&security=none&type=tcp#Steer-preflight-only":"";
                    JSONObject payload=new JSONObject().put("profile",profile).put("settings",envelope.toString()).put("dial","").put("dnsBootstrap",new JSONArray()).put("vpnNetworkHandle",0);
                    File file=new File(context.getCacheDir().getCanonicalFile(),"mcrf-primary-input-"+UUID.randomUUID()+".json");
                    check(file.createNewFile(),"Private native input collision");inputs.add(file);android.system.Os.chmod(file.getAbsolutePath(),0600);
                    try(FileOutputStream stream=new FileOutputStream(file)){stream.write(payload.toString().getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}
                    Bundle result=call("VALIDATE",fields->{fields.putString("file",file.getAbsolutePath());fields.putString("mode",steer?"VPN":"PROXY");});
                    check(!file.exists(),"Worker must consume and remove owned preflight input");return result.getBoolean("supported");
                }
            }
            Commands commands=new Commands();
            String installedHash=hash(new File(context.getPackageManager().getApplicationInfo("org.mcrf.engines."+choice,0).sourceDir));
            Bundle initialized=commands.call(packet?"INIT_PACKET":"INIT",fields->{fields.putString("choice",choice);fields.putString("apkSha256",installedHash);});
            check(packet?"PACKET_INITIALIZED".equals(initialized.getString("status")):initialized.getLong("version")>0,"Installed native module initialization absent");
            report.append("PASS exact minified host verifies and loads installed ").append(choice).append(" module in separate same-UID worker\n");
            if(!packet){
            JSONObject valid=steer?new JSONObject("{\"version\":1,\"defaultTarget\":\"proxy\",\"globalDns\":{\"provider\":\"Cloudflare\",\"protocol\":\"DoH\",\"custom\":\"\"},\"excludedApps\":[],\"vpnApps\":[],\"rules\":[]}"):new JSONObject().put("inbounds",new JSONArray().put(new JSONObject().put("type","mixed").put("listen","127.0.0.1").put("listen_port",10888)))
                .put("outbounds",new JSONArray().put(new JSONObject().put("type","direct").put("tag","direct"))).put("route",new JSONObject().put("final","direct"));
            check(commands.validate(valid),"Installed native validator rejected valid private config");
            JSONObject invalid=steer?new JSONObject(valid.toString()).put("advancedDns",new JSONObject().put("enabled",false)):new JSONObject(valid.toString()).put("inbounds",new JSONArray().put(new JSONObject().put("type","not-real-native-inbound")));
            check(!commands.validate(invalid),"Installed native validator accepted invalid private config");
            check(commands.validate(valid),"Rejected config poisoned subsequent valid preflight");
            report.append("PASS actual installed ").append(choice).append(" JNI validates valid/invalid/valid configs through minified host Binder path\n");
            }else report.append("PASS optional packet SPI parent identity and Context factory/JNI initialization survive R8; no full core in base\n");
        }finally{
            IBinder worker=endpoint.get();
            try{
                if(claimed&&worker!=null&&worker.isBinderAlive()){
                    Bundle stop=new Bundle();stop.putString("command","STOP");stop.putBinder("owner",owner);stop.putLong("generation",generation);stop.putParcelable("receiver",new ResultReceiver(null));
                    try{send(worker,stop);}catch(android.os.DeadObjectException expected){}
                }
                if(claimed)check(dead.await(15,TimeUnit.SECONDS),"Owned native preflight worker death not acknowledged");
            }finally{if(bound)context.unbindService(connection);}
            if(claimed){
                // Binder death can precede final kernel/zygote reaping briefly.
                // Still require the actual owned PID to disappear; never accept
                // binder disconnection alone or leave AUTO_CREATE bound on failure.
                File process=new File("/proc/"+pid);long until=SystemClock.elapsedRealtime()+3000;
                while(process.exists()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
                check(!process.exists(),"Owned native preflight process remains alive after reaping deadline");
            }
            for(File file:inputs)check(file.delete()||!file.exists(),"Owned native input cleanup failed");
        }
        report.append("PASS owned worker death and private input cleanup acknowledged; no native listener/TUN/profile/account action\n");
        report.append("LIMIT native preflight only, not runtime traffic or other ABI acceptance\n");return report.toString();
    }
}
