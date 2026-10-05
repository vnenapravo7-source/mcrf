using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace SplifyWin {
  public static class RouteCompiler {
    static Dictionary<string,object> D(params object[] values){var d=new Dictionary<string,object>();for(int i=0;i<values.Length;i+=2)d[(string)values[i]]=values[i+1];return d;}
    public static Dictionary<string,List<string>> Parse(string text){
      var groups=new Dictionary<string,List<string>>{{"domain",new List<string>()},{"domain_suffix",new List<string>()},{"ip_cidr",new List<string>()},{"process_name",new List<string>()},{"process_path",new List<string>()}};
      foreach(var raw in (text??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){
        string line=raw.Split('#')[0].Trim().TrimStart('\uFEFF');if(line.Length==0||line.StartsWith("!")||line.StartsWith("["))continue;
        string domainKind="domain_suffix";if(line.IndexOf(',')>=0){var columns=line.Split(',');string kind=columns[0].Trim().ToUpperInvariant();if(kind=="DOMAIN"||kind=="DOMAIN-SUFFIX"||kind=="IP-CIDR"||kind=="IP-CIDR6"){line=columns[1].Trim();if(kind=="DOMAIN")domainKind="domain";}else throw new FormatException("Неподдерживаемый тип записи в списке маршрутов");}
        if(line.StartsWith("app:",StringComparison.OrdinalIgnoreCase)){groups["process_name"].Add(line.Substring(4).Trim());continue;}
        if(line.StartsWith("path:",StringComparison.OrdinalIgnoreCase)){groups["process_path"].Add(line.Substring(5).Trim());continue;}
        var parts=line.Split(new[]{' ','\t'},StringSplitOptions.RemoveEmptyEntries);IPAddress address;
        var candidates=parts.Length>1&&IPAddress.TryParse(parts[0].Split('%')[0],out address)?parts.Skip(1):new[]{line};
        foreach(var candidate in candidates){var value=candidate.Trim();if(value=="localhost"||value=="localhost.localdomain"||value=="broadcasthost"||value.StartsWith("ip6-")||value.EndsWith(".local"))continue;
          if(IPAddress.TryParse(value,out address)){if(IPAddress.IsLoopback(address)||address.Equals(IPAddress.Any)||address.Equals(IPAddress.IPv6Any))continue;groups["ip_cidr"].Add(value+(address.AddressFamily==AddressFamily.InterNetwork?"/32":"/128"));continue;}
          if(value.Contains("/")){var cidr=value.Split('/');int bits;if(cidr.Length!=2||!IPAddress.TryParse(cidr[0],out address)||!Int32.TryParse(cidr[1],out bits)||bits<0||bits>(address.AddressFamily==AddressFamily.InterNetwork?32:128))throw new FormatException("Некорректная IP-сеть в списке маршрутов");groups["ip_cidr"].Add(value);continue;}
          value=value.TrimStart('*','.');if(value.StartsWith("||"))value=value.Substring(2).TrimEnd('^');
          if(!Regex.IsMatch(value,@"^(?=.{1,253}$)[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)*$"))throw new FormatException("Некорректный домен в списке маршрутов");groups[domainKind].Add(value.ToLowerInvariant());
        }
      }
      return groups.ToDictionary(x=>x.Key,x=>x.Value.Distinct(StringComparer.OrdinalIgnoreCase).ToList());
    }
    static Dictionary<string,object> Any(Dictionary<string,List<string>> groups,params string[] keys){var children=keys.Where(k=>groups[k].Count>0).Select(k=>D(k,groups[k].ToArray())).ToArray();return children.Length==1?children[0]:D("type","logical","mode","or","rules",children);}
    public static List<Dictionary<string,object>> Compile(RouteList list,string target){
      var groups=Parse(list.Text);bool addresses=groups["domain"].Count+groups["domain_suffix"].Count+groups["ip_cidr"].Count>0,apps=groups["process_name"].Count+groups["process_path"].Count>0;
      string mode=list.MatchMode??"any";var result=new List<Dictionary<string,object>>();
      if(mode=="in-apps"||mode=="except-apps"){
        if(!addresses||!apps)throw new FormatException("Для правила «"+list.Name+"» укажите и приложения, и домены/IP.");
        var application=Any(groups,"process_name","process_path");if(mode=="except-apps")application["invert"]=true;
        result.Add(D("type","logical","mode","and","rules",new[]{Any(groups,"domain","domain_suffix","ip_cidr"),application}));
      }else if(mode=="apps"){if(!apps)throw new FormatException("В правиле нет приложений");result.Add(Any(groups,"process_name","process_path"));}
      else if(mode=="addresses"){if(!addresses)throw new FormatException("В правиле нет адресов");result.Add(Any(groups,"domain","domain_suffix","ip_cidr"));}
      else{if(addresses)result.Add(Any(groups,"domain","domain_suffix","ip_cidr"));if(apps)result.Add(Any(groups,"process_name","process_path"));}
      if(list.InvertAddresses){if(list.Target!="proxy"||mode!="addresses"||apps||!addresses)throw new FormatException("«Все кроме РФ» поддерживается только для VPN с условием адресов.");var exclusions=Any(groups,"domain","domain_suffix","ip_cidr");exclusions["invert"]=true;result=new List<Dictionary<string,object>>{exclusions};}
      foreach(var rule in result){rule["action"]="route";rule["outbound"]=target;}return result;
    }
  }
}
