package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/** Framework-only black-box release check; no dependency on unobfuscated target Kotlin. */
public final class ReleaseRoutingCodecInstrumentation extends Instrumentation {
    private Bundle input;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);input=arguments;start();}
    private static void demand(boolean value){if(!value)throw new AssertionError("Release parser contract");}
    private static byte[] bytes(InputStream stream)throws Exception {
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){demand(out.size()+n<=64*1024*1024);out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
    @Override public void onStart() {
        Bundle report=new Bundle();
        try {
            demand((getTargetContext().getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0);
            ClassLoader loader=getTargetContext().getClassLoader();
            Class<?> codec=loader.loadClass("com.github.luben.zstd.ZstdInputStream");
            demand(codec.getClassLoader()==loader);
            String[] assets={"roscom-steam.mrs","metacubex-telegram-ip.mrs"};
            for(int behavior=0;behavior<assets.length;behavior++) {
                byte[] compressed=bytes(getContext().getAssets().open("routing/"+assets[behavior]));
                byte[] decoded=bytes((InputStream)codec.getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(compressed)));
                demand(decoded.length>=6&&decoded[0]==0x4d&&decoded[1]==0x52&&decoded[2]==0x53&&decoded[3]==1&&decoded[4]==behavior);
            }
            // Names are supplied from this exact release mapping; no extra target keep rules.
            String name=input.getString("yamlClass"),methodName=input.getString("yamlMethod");
            demand(name!=null&&name.matches("[A-Za-z0-9_.$]+")&&methodName!=null&&methodName.matches("[A-Za-z0-9_$]+"));
            Method parser=loader.loadClass(name).getDeclaredMethod(methodName,String.class);
            parser.setAccessible(true);demand(Modifier.isStatic(parser.getModifiers()));
            String document=new String(bytes(getContext().getAssets().open("routing/roscomvpn-mihomo.yaml")),java.nio.charset.StandardCharsets.UTF_8);
            Map<?,?> parsed=(Map<?,?>)parser.invoke(null,document);
            demand(parsed.get("rules") instanceof List&&parsed.get("rule-providers") instanceof Map);
            String[] invalid={"a: 1\na: 2\n","a: !!java.net.URL ['https://localhost/']\n","a: &cycle [*cycle]\n"};
            for(String text:invalid){boolean rejected=false;try{parser.invoke(null,text);}catch(InvocationTargetException expected){rejected=true;}demand(rejected);}
            report.putString("result","PASS: installed non-debug R8 target JNI reads 2 real MRS fixtures; mapped production YAML parser reads public profile and rejects duplicate/typed/cyclic documents");
            finish(Activity.RESULT_OK,report);
        }catch(Throwable error){
            android.util.Log.e("McrfReleaseCodec","Release codec regression",error);
            report.putString("failure",error.getClass().getName()+": "+error.getMessage());
            finish(Activity.RESULT_CANCELED,report);
        }
    }
}
