package org.mcrf.client;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Framework-only owner for one real installed core in its existing private worker.
 * No TUN, user profile, permission override or force-stop. */
final class ReleaseOwnedPrimaryWorker implements AutoCloseable {
    private static final String DESCRIPTOR="org.mcrf.client.primary.worker.v1";
    private final Context context;
    private final long generation=SystemClock.elapsedRealtimeNanos();
    private final Binder owner=new Binder();
    private final CountDownLatch connected=new CountDownLatch(1),dead=new CountDownLatch(1);
    private IBinder worker;private boolean bound,claimed;private int pid=-1;
    private File runDirectory;
    private final List<File> inputs=new ArrayList<>();
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){worker=binder;connected.countDown();}
        public void onServiceDisconnected(ComponentName name){}
    };
    static void check(boolean ok,String label){if(!ok)throw new IllegalStateException(label);}
    ReleaseOwnedPrimaryWorker(Instrumentation test){context=test.getTargetContext();}
    long generation(){return generation;}
    void initialize(IBinder platform)throws Exception{
        ComponentName component=new ComponentName(context.getPackageName(),context.getPackageName()+".ClientPrimaryProbeWorkerService");
        android.content.pm.ServiceInfo info=context.getPackageManager().getServiceInfo(component,0);
        check(!info.exported&&info.applicationInfo.uid==android.os.Process.myUid()&&info.processName.equals(context.getPackageName()+":primaryProbe"),"Private worker identity mismatch");
        bound=context.bindService(new Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE);
        check(bound&&connected.await(8,TimeUnit.SECONDS)&&DESCRIPTOR.equals(worker.getInterfaceDescriptor()),"Owned worker bind failed");
        Parcel request=Parcel.obtain(),reply=Parcel.obtain();
        try{
            request.writeInterfaceToken(DESCRIPTOR);Bundle fields=fields("BIND");request.writeBundle(fields);
            check(worker.transact(IBinder.FIRST_CALL_TRANSACTION,request,reply,0),"Worker bind transaction refused");reply.readException();Bundle value=reply.readBundle(context.getClassLoader());
            check(value!=null&&value.getLong("generation")==generation&&value.getInt("uid")==android.os.Process.myUid()&&info.processName.equals(value.getString("process")),"Worker handshake mismatch");
            pid=value.getInt("pid");check(pid>0&&pid!=android.os.Process.myPid(),"Worker process not isolated");claimed=true;worker.linkToDeath(dead::countDown,0);
        }finally{request.recycle();reply.recycle();}
        File module=new File(context.getPackageManager().getApplicationInfo("org.mcrf.engines.singbox",0).sourceDir);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(module)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
        Bundle init=call("INIT",b->{b.putString("choice","singbox");b.putString("apkSha256",sha.toString());b.putBinder("platform",platform);});check(init.getLong("version")>0,"Installed core initialization missing");
    }
    private Bundle fields(String command){Bundle b=new Bundle();b.putString("command",command);b.putLong("generation",generation);b.putBinder("owner",owner);return b;}
    private void send(Bundle b)throws Exception{Parcel p=Parcel.obtain();try{p.writeInterfaceToken(DESCRIPTOR);p.writeBundle(b);check(worker.transact(IBinder.FIRST_CALL_TRANSACTION,p,null,IBinder.FLAG_ONEWAY),"Owned worker transaction refused");}finally{p.recycle();}}
    private Bundle call(String command,java.util.function.Consumer<Bundle> extra)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Bundle> result=new AtomicReference<>();
        ResultReceiver callback=new ResultReceiver(new Handler(Looper.getMainLooper())){protected void onReceiveResult(int code,Bundle data){result.set(data);done.countDown();}};
        Parcel p=Parcel.obtain();ResultReceiver remote;try{callback.writeToParcel(p,0);p.setDataPosition(0);remote=ResultReceiver.CREATOR.createFromParcel(p);}finally{p.recycle();}
        Bundle b=fields(command);b.putParcelable("receiver",remote);extra.accept(b);send(b);
        check(done.await(25,TimeUnit.SECONDS),"Owned worker command timeout: "+command);Bundle value=result.get();
        check(value!=null&&value.getLong("generation")==generation&&!value.containsKey("error"),"Owned worker command failed: "+command+"/"+(value==null?"absent":value.getString("error")));return value;
    }
    void start(JSONObject config)throws Exception{
        runDirectory=new File(context.getCacheDir().getCanonicalFile(),"mcrf-run-"+UUID.randomUUID());check(runDirectory.mkdir(),"Owned state directory collision");android.system.Os.chmod(runDirectory.getPath(),0700);
        JSONObject envelope=new JSONObject().put("schema",1).put("mode","proxy").put("metered",false).put("config",config);
        JSONObject payload=new JSONObject().put("profile","").put("settings",envelope.toString()).put("dial","").put("dnsBootstrap",new JSONArray()).put("vpnNetworkHandle",0);
        File input=new File(context.getCacheDir().getCanonicalFile(),"mcrf-primary-input-"+UUID.randomUUID()+".json");check(input.createNewFile(),"Owned input collision");inputs.add(input);android.system.Os.chmod(input.getPath(),0600);
        try(FileOutputStream out=new FileOutputStream(input)){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Bundle value=call("START",b->{b.putString("file",input.getPath());b.putString("mode","PROXY");b.putString("stateDirectory",runDirectory.getPath());});
        check("RUNNING".equals(value.getString("status"))&&!input.exists(),"Native proxy not running/owned input not consumed");
    }
    public void close()throws Exception{
        try{
            if(claimed&&worker!=null&&worker.isBinderAlive()){Bundle b=fields("STOP");b.putParcelable("receiver",new ResultReceiver(null));try{send(b);}catch(DeadObjectException expected){}}
            if(claimed){check(dead.await(15,TimeUnit.SECONDS),"Owned worker death not acknowledged");File process=new File("/proc/"+pid);long until=SystemClock.elapsedRealtime()+3000;while(process.exists()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);check(!process.exists(),"Owned process remains; do not start another fixture");}
        }finally{if(bound){context.unbindService(connection);bound=false;}}
        for(File input:inputs)check(!input.exists()||input.delete(),"Owned input cleanup failed");
        if(runDirectory!=null&&runDirectory.exists()){
            check(runDirectory.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile())&&runDirectory.getName().matches("mcrf-run-[0-9a-f-]{36}")&&!Files.isSymbolicLink(runDirectory.toPath()),"Unsafe run directory");
            Files.walkFileTree(runDirectory.toPath(),new SimpleFileVisitor<Path>(){
                public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
                public FileVisitResult postVisitDirectory(Path directory,IOException error)throws IOException{if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;}
            });
        }
    }
}
