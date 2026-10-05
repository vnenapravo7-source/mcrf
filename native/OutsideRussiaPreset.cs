using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
namespace SplifyWin {
  public static class OutsideRussiaPreset {
    public const string Name="Все кроме РФ";
    public static readonly string[] Sources={"https://raw.githubusercontent.com/vnenapravo7-source/iplist-domains/main/ru/ru.txt","https://raw.githubusercontent.com/ipverse/country-ip-blocks/master/country/ru/ipv4-aggregated.txt","https://raw.githubusercontent.com/ipverse/country-ip-blocks/master/country/ru/ipv6-aggregated.txt"};
    // Country allocation is approximate geography. Local/private networks never go to VPN.
    const string Local="ru\nsu\nxn--p1ai\n0.0.0.0/8\n10.0.0.0/8\n100.64.0.0/10\n127.0.0.0/8\n169.254.0.0/16\n172.16.0.0/12\n192.168.0.0/16\n224.0.0.0/4\n240.0.0.0/4\n::/128\n::1/128\nfc00::/7\nfe80::/10\nff00::/8";
    public static string Compose(string text){var fields=RouteCompiler.Parse(text);if(fields["ip_cidr"].Count(x=>!x.Contains(":"))<100||fields["ip_cidr"].Count(x=>x.Contains(":"))<20||fields["domain"].Count+fields["domain_suffix"].Count<20||fields["ip_cidr"].Any(x=>x.EndsWith("/0"))||fields["process_name"].Count+fields["process_path"].Count>0)throw new FormatException("Неполный список РФ. Пресет не применяется без доменов, IPv4 и IPv6.");return Local+"\n"+text;}
    public static async Task<RouteList> Create(NetworkCore core){var data=await Task.WhenAll(Sources.Select(url=>core.DownloadTextAsync(url)));return new RouteList{Name=Name,Text=Compose(String.Join("\n",data)),Target="proxy",MatchMode="addresses",InvertAddresses=true,ExcludeRussia=true,ExcludeTorrents=true,BuiltinPreset="outside-ru",SourceUrls=Sources.ToList(),PresetNames=new List<string>{Name}};}
    public static string Update(RouteList list,string text){return list.BuiltinPreset=="outside-ru"?Compose(text):text;}
  }
}
