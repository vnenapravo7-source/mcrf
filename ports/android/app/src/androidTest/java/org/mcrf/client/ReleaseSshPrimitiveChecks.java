package org.mcrf.client;

import android.app.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Target-loader reflection only: no application keep rules are added for this test. */
final class ReleaseSshPrimitiveChecks {
    private static void check(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
    private static Object primitive(ClassLoader loader,String name)throws Exception {
        return Class.forName("com.jcraft.jsch.jce."+name,true,loader).getConstructor().newInstance();
    }
    private static Object call(Object object,String name,Class<?>[] types,Object... values)throws Exception {
        return object.getClass().getMethod(name,types).invoke(object,values);
    }
    static String run(Instrumentation test)throws Exception {
        ClassLoader loader=test.getTargetContext().getClassLoader();StringBuilder report=new StringBuilder();
        Object random=primitive(loader,"Random");byte[] randomBytes=new byte[32];
        call(random,"fill",new Class<?>[]{byte[].class,int.class,int.class},randomBytes,0,randomBytes.length);
        check(!Arrays.equals(randomBytes,new byte[32]),"SSH random did not fill the output");
        report.append("PASS target APK SSH random reflection and actual output\n");

        byte[] message="MCRF local SSH primitive fixture".getBytes(StandardCharsets.UTF_8);
        Object hash=primitive(loader,"SHA256");call(hash,"init",new Class<?>[]{});
        call(hash,"update",new Class<?>[]{byte[].class,int.class,int.class},message,0,message.length);
        check(Arrays.equals((byte[])call(hash,"digest",new Class<?>[]{}),MessageDigest.getInstance("SHA-256").digest(message)),"SSH SHA256 differs from platform digest");
        report.append("PASS target APK SSH SHA256 actual digest equals independent platform result\n");

        byte[] key=new byte[16],iv=new byte[16];for(int i=0;i<16;i++){key[i]=(byte)(i+1);iv[i]=(byte)(31-i);}
        byte[] encrypted=new byte[message.length],decoded=new byte[message.length];
        Object encrypt=primitive(loader,"AES128CTR"),decrypt=primitive(loader,"AES128CTR");
        Class<?>[] initTypes={int.class,byte[].class,byte[].class},updateTypes={byte[].class,int.class,int.class,byte[].class,int.class};
        call(encrypt,"init",initTypes,0,key,iv);call(encrypt,"update",updateTypes,message,0,message.length,encrypted,0);
        Cipher reference=Cipher.getInstance("AES/CTR/NoPadding");reference.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        check(Arrays.equals(encrypted,reference.doFinal(message)),"SSH AES-CTR differs from platform cipher");
        call(decrypt,"init",initTypes,1,key,iv);call(decrypt,"update",updateTypes,encrypted,0,encrypted.length,decoded,0);
        check(Arrays.equals(decoded,message),"SSH AES-CTR roundtrip failed");
        report.append("PASS target APK SSH AES128-CTR encryption and decryption\n");

        Object alice=primitive(loader,"ECDHN"),bob=primitive(loader,"ECDHN");
        call(alice,"init",new Class<?>[]{int.class},256);call(bob,"init",new Class<?>[]{int.class},256);
        byte[] aq=(byte[])call(alice,"getQ",new Class<?>[]{}),bq=(byte[])call(bob,"getQ",new Class<?>[]{});
        check(aq.length==65&&bq.length==65&&aq[0]==4&&bq[0]==4,"SSH P256 points are not uncompressed SEC1");
        byte[] ax=Arrays.copyOfRange(aq,1,33),ay=Arrays.copyOfRange(aq,33,65),bx=Arrays.copyOfRange(bq,1,33),by=Arrays.copyOfRange(bq,33,65);
        Class<?>[] pointTypes={byte[].class,byte[].class};
        check(Boolean.TRUE.equals(call(alice,"validate",pointTypes,bx,by))&&Boolean.TRUE.equals(call(bob,"validate",pointTypes,ax,ay)),"SSH P256 point validation failed");
        byte[] first=(byte[])call(alice,"getSecret",pointTypes,bx,by),second=(byte[])call(bob,"getSecret",pointTypes,ax,ay);
        check(first.length>0&&MessageDigest.isEqual(first,second),"SSH P256 shared secrets differ");
        Arrays.fill(first,(byte)0);Arrays.fill(second,(byte)0);
        report.append("PASS target APK SSH ECDH-P256 pair derives matching actual shared secrets\n");
        report.append("LIMIT local primitives only; no SSH handshake, login, VPS, VK or deployment tested\n");
        return report.toString();
    }
}
