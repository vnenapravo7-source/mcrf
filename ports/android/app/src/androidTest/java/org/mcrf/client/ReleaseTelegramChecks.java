package org.mcrf.client;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Manifest/service IPC only; does not require keeping application symbols in R8. */
final class ReleaseTelegramChecks {
    static void run(Instrumentation test,Consumer<String> report)throws Exception {
        Context context=test.getTargetContext();Intent service=new Intent().setClassName("org.mcrf.client","org.mcrf.client.TelegramProxyService");
        AtomicReference<Bundle> state=new AtomicReference<>();AtomicReference<Messenger> remote=new AtomicReference<>();
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper(),message->{if(message.what==3)state.set(new Bundle(message.getData()));return true;}));
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){Messenger peer=new Messenger(binder);remote.set(peer);Message subscribe=Message.obtain(null,1);subscribe.replyTo=reply;try{peer.send(subscribe);}catch(RemoteException error){throw new RuntimeException(error);}}public void onServiceDisconnected(ComponentName name){remote.set(null);}};
        final boolean[] bound={false};boolean started=false;
        try {
            test.runOnMainSync(()->bound[0]=context.bindService(service,connection,Context.BIND_AUTO_CREATE));
            await(test,()->state.get()!=null);if(state.get().getBoolean("wanted"))throw new Exception("Refusing to interrupt an existing Telegram bridge");
            context.startForegroundService(new Intent(service).setAction("org.mcrf.telegram.START"));started=true;
            await(test,()->state.get().getBoolean("running"));report.accept("Exact minified APK launches Telegram's owned native process");
            try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",19071),1000);socket.setSoTimeout(2000);socket.getOutputStream().write(new byte[]{5,1,0});if(socket.getInputStream().read()!=5||socket.getInputStream().read()!=0)throw new Exception("Release SOCKS greeting invalid");}
            report.accept("Minified Telegram process answers a real SOCKS5 greeting");
            context.startForegroundService(new Intent(service).setAction("org.mcrf.telegram.STOP"));
            await(test,()->!state.get().getBoolean("wanted")&&!state.get().getBoolean("running"));
            await(test,ReleaseTelegramChecks::free);report.accept("Minified Telegram STOP releases its owned native port");
        }finally {
            if(started)context.startForegroundService(new Intent(service).setAction("org.mcrf.telegram.STOP"));
            if(bound[0])test.runOnMainSync(()->{try{Message leave=Message.obtain(null,2);leave.replyTo=reply;Messenger peer=remote.get();if(peer!=null)peer.send(leave);}catch(RemoteException ignored){}context.unbindService(connection);});
        }
    }
    private static boolean free(){try(ServerSocket socket=new ServerSocket(19071,1,InetAddress.getByName("127.0.0.1"))){return true;}catch(Exception ignored){return false;}}
    private static void await(Instrumentation test,java.util.function.BooleanSupplier condition)throws Exception{long deadline=SystemClock.elapsedRealtime()+15000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline){test.waitForIdleSync();SystemClock.sleep(150);}if(!condition.getAsBoolean())throw new Exception("Release Telegram state timeout");}
}
