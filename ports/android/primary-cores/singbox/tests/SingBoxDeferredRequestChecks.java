package org.mcrf.engines.singbox;
import org.mcrf.engine.spi.v1.Engine;
/** Executes the real shared Request constructor; does not fork or stub SPI. */
public final class SingBoxDeferredRequestChecks {
    private static int checks;
    private static final String CAP="a".repeat(64);
    private static Engine.Request request(int fd,String profile,String settings,Engine.Mode mode,
                                          Engine.Validation validation,Engine.TunAcquisition acquisition,String capability) {
        return new Engine.Request(7,fd,settings,profile,"127.0.0.1","fixture",0,new String[0],mode,validation,acquisition,capability);
    }
    private static void rejects(Runnable run) {try{run.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError();}
    public static void main(String[] args) {
        var valid=Engine.Validation.deferredVpnValidated("profile","settings",CAP);
        var accepted=request(-1,"profile","settings",Engine.Mode.VPN,valid,Engine.TunAcquisition.DEFERRED,CAP);
        if(accepted.borrowedTunFd!=-1||accepted.tunAcquisition!=Engine.TunAcquisition.DEFERRED)throw new AssertionError();checks++;
        rejects(()->request(0,"profile","settings",Engine.Mode.VPN,valid,Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"changed","settings",Engine.Mode.VPN,valid,Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"profile","changed",Engine.Mode.VPN,valid,Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,valid,Engine.TunAcquisition.DEFERRED,"b".repeat(64)));
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,null,Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,new Engine.Validation(false,"FAILED","config"),Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,new Engine.Validation(true,"",""),Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,valid,Engine.TunAcquisition.PROVIDED,CAP));
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,valid,Engine.TunAcquisition.NONE,CAP));
        var proxy=Engine.Validation.proxyValidated("profile","settings");
        rejects(()->request(-1,"profile","settings",Engine.Mode.VPN,proxy,Engine.TunAcquisition.DEFERRED,CAP));
        rejects(()->request(-1,"profile","settings",Engine.Mode.PROXY,proxy,Engine.TunAcquisition.DEFERRED,CAP));
        request(-1,"profile","settings",Engine.Mode.PROXY,proxy,Engine.TunAcquisition.NONE,null);checks++;
        request(42,"profile","settings",Engine.Mode.VPN,null,Engine.TunAcquisition.PROVIDED,null);checks++;
        System.out.println("PASS "+checks+" real canonical deferred/provided/proxy constructor checks; no TUN/IPC");
    }
}
