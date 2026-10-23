using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
namespace SplifyWin {
 public static class VpnDnsBootstrap {
  public static string[] SystemServers(string ownTun){
   var addresses=new List<string>();foreach(var nic in NetworkInterface.GetAllNetworkInterfaces()){
    if(nic.OperationalStatus!=OperationalStatus.Up||nic.NetworkInterfaceType==NetworkInterfaceType.Loopback||String.Equals(nic.Name,ownTun,StringComparison.OrdinalIgnoreCase))continue;
    try{var properties=nic.GetIPProperties();if(properties.GatewayAddresses.Count==0)continue;addresses.AddRange(properties.DnsAddresses.Where(ip=>!IPAddress.IsLoopback(ip)&&!ip.Equals(IPAddress.Any)&&!ip.Equals(IPAddress.IPv6Any)).Select(ip=>ip.ToString()));}catch(NetworkInformationException){}
   }return addresses.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
  }
  // This is a fallback AFTER explicit rules. Only OS resolver addresses on
  // DNS port 53 bypass the default VPN; no blanket private/internet bypass.
  public static Dictionary<string,object> Rule(IEnumerable<string> servers){
   var cidrs=new List<string>();foreach(string value in servers){IPAddress ip;if(IPAddress.TryParse(value,out ip)){if(ip.AddressFamily==AddressFamily.InterNetworkV6)ip=new IPAddress(ip.GetAddressBytes());cidrs.Add(ip+ (ip.AddressFamily==AddressFamily.InterNetwork?"/32":"/128"));}}
   if(cidrs.Count==0)return null;
   return new Dictionary<string,object>{{"inbound",new[]{"tun-in"}},{"ip_cidr",cidrs.Distinct().ToArray()},{"port",new[]{53}},{"action","route"},{"outbound","direct"}};
  }
 }
}
