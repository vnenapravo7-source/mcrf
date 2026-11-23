package org.mcrf.client;
import java.util.Set;
public final class ClientPrimaryCorePolicyChecks {
    private static int checks;
    private static void check(boolean value) {if(!value)throw new AssertionError();checks++;}
    private static void rejects(Runnable run) {try{run.run();}catch(IllegalArgumentException|SecurityException expected){checks++;return;}throw new AssertionError();}
    public static void main(String[] args) {
        check(ClientPrimaryCorePolicy.action(null,Set.of("singbox"))==ClientPrimaryCorePolicy.Action.CHOOSE);
        check(ClientPrimaryCorePolicy.action("",Set.of("steer"))==ClientPrimaryCorePolicy.Action.CHOOSE);
        check(ClientPrimaryCorePolicy.action("singbox",Set.of("steer"))==ClientPrimaryCorePolicy.Action.INSTALL);
        check(ClientPrimaryCorePolicy.action("steer",Set.of("steer"))==ClientPrimaryCorePolicy.Action.PREFLIGHT);
        rejects(()->ClientPrimaryCorePolicy.action("legacy-default",Set.of("singbox")));
        var core=ClientPrimaryCorePolicy.Core.SINGBOX;
        check(core.id.equals("singbox") && core.kind.equals("sing-box"));
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,"singbox",1,1,1,core.entryClass,true,true));
        ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,core.kind,1,1,1,core.entryClass,true,true);checks++;
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,core.kind,1,1,1,core.entryClass,true,false));
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,"org.mcrf.engines.steer",core.kind,1,1,1,core.entryClass,true,true));
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,core.kind,2,1,1,core.entryClass,true,true));
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,core.kind,1,2,1,core.entryClass,true,true));
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,core.kind,1,1,1,"OtherEntry",true,true));
        rejects(()->ClientPrimaryCorePolicy.requireIdentity(core,core.packageName,core.kind,1,1,1,core.entryClass,false,true));
        rejects(()->ClientPrimaryCorePolicy.requireCompatible(Set.of("ipv6","mixed","mtu1400"),Set.of("ipv4","mtu1500")));
        check(ClientPrimaryCorePolicy.unsupported(Set.of("ipv4","api"),Set.of("ipv4")).equals(Set.of("api")));
        ClientPrimaryCorePolicy.requireCompatible(Set.of("ipv4"),Set.of("ipv4","api"));checks++;
        System.out.println("PASS "+checks+" pure primary-core policy checks; no Android/JNI/loader claim");
    }
}
