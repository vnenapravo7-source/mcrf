using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
namespace SplifyWin {
 // Retained for reading existing settings; game discovery is no longer offered.
 public sealed class SteamGameTemplate {
  public int AppId{get;set;}public string Name{get;set;}public string Text{get;set;}public DateTime CheckedAt{get;set;}public string Status{get;set;}
 }
 public static class SteamGameTemplates {
  public static readonly string WardogsEntries=String.Join("\n",(@"54.115.0.0/16 54.228.0.0/16 54.216.0.0/16 3.218.0.0/16 app:WardogsClient-Win64-Shipping.exe
155.133.248.36/32 155.133.248.37/32 155.133.248.40/32 155.133.248.41/32 162.254.199.170/32 162.254.199.173/32 162.254.199.178/32 162.254.199.180/32 162.254.194.37/32 162.254.194.38/32 162.254.194.53/32 162.254.194.54/32 185.25.183.163/32 185.25.183.179/32 155.133.255.98/32 155.133.255.99/32 155.133.255.162/32 155.133.255.163/32 155.133.226.68/32 155.133.226.70/32 155.133.226.72/32 155.133.226.77/32 155.133.226.84/32 155.133.226.85/32 155.133.226.86/32 155.133.226.87/32 162.254.197.36/32 162.254.197.37/32 162.254.197.42/32 162.254.197.43/32 162.254.197.44/32 162.254.197.52/32 155.133.227.35/32 155.133.227.40/32 155.133.227.41/32 155.133.227.51/32 155.133.227.56/32 155.133.227.57/32 185.25.180.18/32 185.25.180.19/32 103.28.54.163/32 103.28.54.164/32 103.28.54.167/32 103.28.54.172/32 103.28.54.174/32 103.28.54.179/32 103.28.54.180/32 103.28.54.183/32 103.28.54.188/32 103.28.54.189/32
162.254.192.88/32 162.254.192.89/32 162.254.192.102/32 162.254.192.103/32 155.133.238.178/32 155.133.238.194/32 162.254.195.52/32 162.254.195.53/32 162.254.195.70/32 162.254.195.72/32 162.254.196.66/32 162.254.196.70/32 162.254.196.82/32 162.254.196.85/32 155.133.244.35/32 155.133.244.36/32 155.133.244.51/32 155.133.244.52/32 155.133.246.34/32 155.133.246.39/32 155.133.246.40/32 155.133.246.50/32 162.254.193.71/32 162.254.193.73/32 162.254.193.98/32 162.254.193.99/32 185.25.182.18/32 185.25.182.19/32 185.25.182.50/32 185.25.182.51/32 155.133.249.163/32 155.133.249.179/32 205.196.6.135/32 205.196.6.149/32 146.66.152.36/32 146.66.152.37/32 146.66.152.41/32 146.66.152.42/32 103.10.124.116/32 103.10.124.117/32 103.10.124.118/32 103.10.124.119/32 103.10.124.120/32 103.10.124.121/32 162.254.198.41/32 162.254.198.42/32 162.254.198.43/32 162.254.198.47/32 162.254.198.49/32 162.254.198.50/32 162.254.198.51/32 162.254.198.101/32 162.254.198.102/32 162.254.198.103/32 162.254.198.156/32 103.10.125.20/32 103.10.125.21/32 103.10.125.40/32 103.10.125.41/32 45.121.184.5/32 45.121.184.6/32 45.121.184.24/32 45.121.184.25/32 45.121.184.26/32 45.121.184.27/32 146.66.155.66/32 146.66.155.67/32 146.66.155.68/32 146.66.155.69/32 146.66.155.72/32 146.66.155.73/32 155.133.230.98/32 155.133.230.99/32 155.133.230.100/32 155.133.230.101/32 155.133.230.102/32 155.133.230.103/32 155.133.224.20/32 155.133.224.21/32 155.133.225.18/32 155.133.225.19/32 155.133.252.37/32 155.133.252.38/32 155.133.252.52/32 155.133.252.53/32 155.133.252.84/32 155.133.252.85/32 155.133.252.86/32 155.133.252.87/32 155.133.252.88/32 155.133.252.89/32 89.222.108.161/32 89.222.108.162/32 152.233.52.97/32 152.233.52.98/32").Split((char[])null,StringSplitOptions.RemoveEmptyEntries).Distinct());
  public static Tuple<ZapretProfile,RouteList> Wardogs(IEnumerable<ZapretStrategy> catalog){
   var strategy=catalog.FirstOrDefault(s=>s.Available&&s.Family=="Flowseal"&&s.ShortName=="ALT11");if(strategy==null)throw new InvalidOperationException("Flowseal ALT11 недоступна. Обновите набор стратегий.");
   var profile=new ZapretProfile{Name="Wardogs",Enabled=true,Settings=new ZapretSettings{Family=strategy.Family,Strategy=strategy.Id,ScopeText=WardogsEntries,MatchMode="any",GameFilterTcp=true,GameFilterUdp=true}};
   var route=new RouteList{Name="Wardogs · ALT11",Text=WardogsEntries,Target="zapret",ZapretProfileId=profile.Id,MatchMode="any",GameFilterTcp=true,GameFilterUdp=true,Enabled=true,BuiltinPreset="game:wardogs-alt11",Dns=new ExitDnsSettings{Provider="Cloudflare",Protocol="UDP"}};ZapretScope.Validate(route);return Tuple.Create(profile,route);
  }
  public static string Parse(string text){
   var root=new JavaScriptSerializer{MaxJsonLength=4000000}.DeserializeObject(text) as Dictionary<string,object>;object value;
   if(root==null||!root.TryGetValue("pops",out value)||root.ContainsKey("success")&&!Convert.ToBoolean(root["success"]))throw new FormatException("Steam не подтвердил пул SDR.");
   var pops=value as Dictionary<string,object>;if(pops==null)throw new FormatException("Неверный пул SDR.");var ips=new HashSet<string>();
   foreach(var pop in pops.Values){var fields=pop as Dictionary<string,object>;object relays;if(fields==null||!fields.TryGetValue("relays",out relays))continue;var list=relays as IEnumerable;if(list==null)continue;foreach(var relay in list){var data=relay as Dictionary<string,object>;if(data==null)continue;foreach(string key in new[]{"ipv4","ipv6"}){object raw;IPAddress ip;if(data.TryGetValue(key,out raw)&&IPAddress.TryParse(Convert.ToString(raw),out ip)&&!IPAddress.IsLoopback(ip)&&!ip.Equals(IPAddress.Any)&&!ip.Equals(IPAddress.IPv6Any))ips.Add(ip.ToString()+(ip.AddressFamily==AddressFamily.InterNetwork?"/32":"/128"));}}}
   if(ips.Count==0||ips.Count>10000)throw new FormatException("Нет пригодных адресов SDR.");return String.Join("\n",ips.OrderBy(x=>x,StringComparer.Ordinal));
  }
  public static List<SteamGameTemplate> Candidates(){return new List<SteamGameTemplate>{new SteamGameTemplate{AppId=570,Name="Dota 2 · пул SDR",Status="Отдельный ответ Steam; стратегия не проверена"}};}
  public static async Task<SteamGameTemplate> Fetch(Func<string,Task<string>> download){
   const string url="https://api.steampowered.com/ISteamApps/GetSDRConfig/v1/?appid=";
   string pool=Parse(await download(url+570));string common=Parse(await download(url+0));string fallback=Parse(await download(url+1867240));
   if(pool==common||pool==fallback)throw new FormatException("Steam вернул общий пул вместо отдельного списка. Шаблон не добавлен.");
   return new SteamGameTemplate{AppId=570,Name="Dota 2 · пул SDR",Text=pool,CheckedAt=DateTime.Now,Status="Пул отличается от общих; стратегия не проверена"};
  }
 }
}
