package org.mcrf.runtime;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.net.*;

/** Separate UID probe: the primary's own UID is intentionally outside its
 * dispatcher TUN. Only one fixed documentation-address HTTP request exists. */
public final class P3OwnedTrafficProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){Bundle out=new Bundle();try{
  PackageManager pm=getContext().getPackageManager();
  if(!"traffic".equals(method)||Binder.getCallingUid()!=pm.getApplicationInfo("org.mcrf.p3test",0).uid||pm.checkSignatures("org.mcrf.p3test",getContext().getPackageName())!=PackageManager.SIGNATURE_MATCH)throw new SecurityException("Isolated signed caller only");
  try(Socket socket=new Socket()){
   socket.connect(new InetSocketAddress("198.51.100.7",8080),3000);socket.setSoTimeout(3000);
   socket.getOutputStream().write("GET /route HTTP/1.1\r\nHost: 198.51.100.7\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
   ByteArrayOutputStream reply=new ByteArrayOutputStream();byte[] bytes=new byte[256];int n;
   while((n=socket.getInputStream().read(bytes))>0&&reply.size()<4096)reply.write(bytes,0,n);
   out.putBoolean("passed",reply.toString("US-ASCII").endsWith("OWNED"));
  }
 }catch(Exception error){out.putString("error",error.getClass().getSimpleName());}return out;}
 public Cursor query(Uri u,String[]p,String s,String[]a,String o){throw new UnsupportedOperationException();}
 public String getType(Uri u){return null;}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[]a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[]a){throw new UnsupportedOperationException();}
}
