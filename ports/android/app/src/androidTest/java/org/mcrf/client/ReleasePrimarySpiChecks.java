package org.mcrf.client;

import android.app.Instrumentation;

/** Checks delivery of the parent ABI only. Does not load or select any installed engine. */
final class ReleasePrimarySpiChecks {
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    static String run(Instrumentation test)throws Exception {
        ClassLoader loader=test.getTargetContext().getClassLoader();
        Class<?> engine=Class.forName("org.mcrf.engine.spi.v1.Engine",false,loader);
        Class<?> platform=Class.forName("org.mcrf.engine.spi.v1.Platform",false,loader);
        check(engine.isInterface()&&platform.isInterface()&&engine.getClassLoader()==loader&&platform.getClassLoader()==loader,"Shared SPI is not defined by the host loader");
        check(engine.getField("SPI_VERSION").getInt(null)==1,"Shared SPI version changed");
        Class<?> kind=Class.forName("org.mcrf.engine.spi.v1.Engine$Kind",false,loader);
        Object singbox=kind.getMethod("fromChoiceId",String.class).invoke(null,"singbox");
        Object steer=kind.getMethod("fromChoiceId",String.class).invoke(null,"steer");
        check("sing-box".equals(kind.getField("manifestKind").get(singbox))&&"steer".equals(kind.getField("manifestKind").get(steer)),"Primary choice/manifest mapping lost");
        StringBuilder report=new StringBuilder("PASS exact host APK retains shared SPI interfaces, version and primary identities\n");
        Class<?> query=Class.forName("org.mcrf.engine.spi.v1.Platform$DnsQuery",false,loader);
        byte[] original=new byte[12];Object value=query.getConstructor(long.class,byte[].class,int.class).newInstance(1L,original,8000);
        original[0]=9;byte[] first=(byte[])query.getMethod("packet").invoke(value);check(first[0]==0,"Shared DNS input was not defensively copied");
        first[0]=8;check(((byte[])query.getMethod("packet").invoke(value))[0]==0,"Shared DNS getter exposed mutable state");
        report.append("PASS exact host APK shared DNS DTO retains public methods and defensive copies\n");
        for(String name:new String[]{"org.mcrf.engines.singbox.SingBoxEngine","org.mcrf.engines.steer.SteerEngine"}) {
            boolean missing=false;try{Class.forName(name,false,loader);}catch(ClassNotFoundException expected){missing=true;}
            check(missing,"Separately delivered primary adapter leaked into base APK");
        }
        report.append("PASS primary adapter implementations are not bundled into the host\n");
        report.append("LIMIT parent ABI delivery only; legacy libbox removal, module loading, IPC and native worker are not accepted by this check\n");
        return report.toString();
    }
}
