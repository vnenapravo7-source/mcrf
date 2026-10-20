import com.jcraft.jsch.JSch;
import org.mcrf.client.AndroidDeploySshAlgorithms;
import java.util.LinkedHashSet;

/** Offline audit: only class names and public algorithm choices, never credentials/network. */
class AndroidDeployJschAudit {
  public static void main(String[] args) throws Exception {
    for(String key:new String[]{"kex","server_host_key","cipher.c2s","mac.c2s","CheckKexes","CheckSignatures","PreferredAuthentications"})
      System.out.println("DEFAULT "+key+"="+JSch.getConfig(key));
    LinkedHashSet<String> classes=new LinkedHashSet<>();
    for(String key:AndroidDeploySshAlgorithms.INSTANCE.getNetworkPrimitives()) classes.add(JSch.getConfig(key));
    for(String key:AndroidDeploySshAlgorithms.INSTANCE.getPrivateKeyPrimitives()) classes.add(JSch.getConfig(key));
    for(String type:classes) {
      if(type==null) throw new IllegalStateException("Unmapped primitive");
      if(type.contains(".bc.")||type.contains(".jgss.")||type.contains(".jzlib.")) throw new IllegalStateException("Optional provider: "+type);
      Class.forName(type).getDeclaredConstructor().setAccessible(true);
      System.out.println("-keep class "+type+" { *; }");
    }
  }
}
