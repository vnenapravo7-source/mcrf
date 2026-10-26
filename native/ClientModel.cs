using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Security.Cryptography;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Web;
using System.Web.Script.Serialization;

namespace SplifyWin {
  public static class BsDetectionSites {
    public static readonly string[] Defaults={"google.com","gitlab.com","youtube.com"};
    public static readonly string[] Presets={"google.com","gitlab.com","github.com","youtube.com","discord.gg","pinterest.com","wikipedia.org","netflix.com","livew.com","microsoft.com","twitch.tv","zoom.us"};
    public static string Normalize(string value){Uri uri;string text=(value??"").Trim();if(text.Length==0||text.Length>1024||!System.Text.RegularExpressions.Regex.IsMatch(text,@"^(?:https://)?[^/:?#\\\s]+/?$",System.Text.RegularExpressions.RegexOptions.IgnoreCase)||!Uri.TryCreate(text.Contains("://")?text:"https://"+text,UriKind.Absolute,out uri)||uri.Scheme!="https"||!String.IsNullOrEmpty(uri.UserInfo)||!uri.IsDefaultPort||uri.Authority.Contains(":")||uri.AbsolutePath!="/"||!String.IsNullOrEmpty(uri.Query)||!String.IsNullOrEmpty(uri.Fragment))throw new FormatException("Укажите публичный домен без пути, порта и параметров.");string host=new System.Globalization.IdnMapping().GetAscii(uri.DnsSafeHost).ToLowerInvariant();var labels=host.Split('.');if(host.Length>253||labels.Length<2||labels.Any(l=>l.Length<1||l.Length>63||!System.Text.RegularExpressions.Regex.IsMatch(l,@"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$"))||!labels.Last().Any(c=>c>='a'&&c<='z')||new[]{"local","localhost","internal","invalid","test"}.Contains(labels.Last()))throw new FormatException("Укажите публичный домен сайта.");return host;}
    public static string[] Load(IEnumerable<string> values){var source=(values??new string[0]).ToArray();return Defaults.Select((fallback,i)=>{try{return Normalize(i<source.Length?source[i]:fallback);}catch{return fallback;}}).ToArray();}
  }
  public static class SecretStorage {
    const string Prefix="MCRF-SECRET-1\n";
    public static string Read(string path){return ReadValidated(path,null);}
    public static string ReadValidated(string path,Action<string> validate){string text=File.ReadAllText(path,Encoding.UTF8);bool encrypted=text.StartsWith(Prefix,StringComparison.Ordinal);string value=encrypted?Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(text.Substring(Prefix.Length)),Encoding.UTF8.GetBytes("MCRF secret v1"),DataProtectionScope.CurrentUser)):text;if(validate!=null)validate(value);if(!encrypted)Write(path,value);return value;}
    public static void Write(string path,string text){string encrypted=Prefix+Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(text),Encoding.UTF8.GetBytes("MCRF secret v1"),DataProtectionScope.CurrentUser));string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temp,encrypted,new UTF8Encoding(false));if(File.Exists(path))File.Replace(temp,path,null);else File.Move(temp,path);}finally{if(File.Exists(temp))File.Delete(temp);}}
    // External engines need plaintext while running. Restrict access before writing any secret bytes.
    public static void WriteRuntime(string path,string text){using(var file=new FileStream(path,FileMode.Create,FileAccess.Write,FileShare.Read)){var security=new FileSecurity();security.SetAccessRuleProtection(true,false);security.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User,FileSystemRights.FullControl,AccessControlType.Allow));security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid,null),FileSystemRights.FullControl,AccessControlType.Allow));File.SetAccessControl(path,security);using(var writer=new StreamWriter(file,new UTF8Encoding(false)))writer.Write(text);}}
  }
  public sealed class ServerNode {
    public string Id {get;set;} public string Name {get;set;} public string Protocol {get;set;}
    public string Link {get;set;} public string Host {get;set;} public int Port {get;set;} public string Source {get;set;}
    public string CsqttGeneration {get;set;}
    public VkTurnServerPreferences TurnPreferences {get;set;}
    public int Latency {get;set;} public bool Healthy {get;set;}public bool UseInAutoSelection {get;set;}
    public ServerNode(){Id=Guid.NewGuid().ToString("N");Name="";Protocol="";Link="";Host="";Port=443;Latency=-1;UseInAutoSelection=true;}
    public string TransportLabel {get{if(Protocol=="csqtt")return CsqttGenerations.For(this).Label;if(Protocol=="wireguard"||Protocol=="awg"||Protocol=="wdtt"||Protocol=="qwdtt")return Protocol.ToUpperInvariant();try{var u=new Uri(Link);var q=HttpUtility.ParseQueryString(u.Query);var transport=q["type"]??q["network"]??"tcp";var security=q["security"];return Protocol.ToUpperInvariant()+" · "+transport.ToUpperInvariant()+(String.IsNullOrEmpty(security)||security=="none"?"":" · "+security.ToUpperInvariant());}catch{return Protocol.ToUpperInvariant();}}}
  }
  public static class BsServers {
    public static bool Is(ServerNode node){return node!=null&&(TurnEngines.IsTurn(node)||node.Protocol=="qwdtt"||node.Protocol=="openflux");}
  }
  public static class AutomaticServers {
    public static bool Allowed(ServerNode node){return node!=null&&!String.Equals(node.Protocol,"openflux",StringComparison.OrdinalIgnoreCase);}
    public static int Priority(ServerNode node){int index=Array.IndexOf(new[]{"csqtt","qwdtt","wdtt","openflux","s3-bypass"},node.Protocol);return index<0?Int32.MaxValue:index;}
    public static bool ExcludeMeasurement(ServerNode node){if(Allowed(node))return false;if(node!=null){node.Healthy=false;node.Latency=-1;}return true;}
    public static List<ServerNode> StartupPool(ClientState state,Func<ServerNode,bool> available){
      if(state.IndependentWarpOutputs&&!state.VpnEnabled)return new List<ServerNode>();
      var nodes=state.Servers.Where(n=>n!=null&&(!state.AutoSelect||n.UseInAutoSelection)&&(!(state.AutoSelect||state.AutoDetectBs)||Allowed(n))&&available(n)&&(!state.IndependentWarpOutputs||!WarpPicker.IsWarp(n)));
      if(state.AutoDetectBs)nodes=nodes.Where(n=>BsServers.Is(n)==state.AutoBsActive);
      if(!state.AutoSelect)return nodes.Where(n=>n.Id==state.SelectedServerId).ToList();
      var result=nodes.ToList();
      if(state.AutoBsActive)result=result.OrderBy(Priority).ThenBy(n=>n.Latency<0?Int32.MaxValue:n.Latency).ToList();
      if(state.AutoSelectMode=="recent"){
        var order=(state.RecentServerIds??new List<string>()).Select((id,index)=>new{id,index}).GroupBy(x=>x.id,StringComparer.Ordinal).ToDictionary(x=>x.Key,x=>x.First().index,StringComparer.Ordinal);
        result=result.OrderBy(n=>n.Id==state.SelectedServerId?-1:order.ContainsKey(n.Id)?order[n.Id]:Int32.MaxValue).ThenBy(Priority).ToList();
      }
      return result;
    }
    public static ServerNode[] TransitionCandidates(ClientState state,bool toBs,Func<ServerNode,bool> available){
      var nodes=state.Servers.Where(n=>Allowed(n)&&n.UseInAutoSelection&&!WarpPicker.IsWarp(n)&&available(n)&&BsServers.Is(n)==toBs);
      return nodes.OrderBy(n=>toBs?Priority(n):0).ThenBy(n=>n.Latency<0?Int32.MaxValue:n.Latency).ToArray();
    }
  }
  public static class SubscriptionMerge {
    static bool SameLink(ServerNode a,ServerNode b){return a.Link==b.Link&&(a.Protocol!="csqtt"||b.Protocol!="csqtt"||CsqttGenerations.For(a).Id==CsqttGenerations.For(b).Id);}
    public static void InheritCsqttGenerations(IEnumerable<ServerNode> imported,IEnumerable<ServerNode> previous,string source){foreach(var node in imported.Where(CsqttGenerations.NeedsChoice)){var exact=previous.Where(p=>p.Protocol=="csqtt"&&p.Link==node.Link).ToArray();var matches=exact.Length>0?exact:previous.Where(p=>p.Protocol=="csqtt"&&p.Source==source&&p.Host==node.Host&&p.Port==node.Port).ToArray();var ids=matches.Select(p=>CsqttGenerations.For(p).Id).Distinct().ToArray();if(ids.Length==1)node.CsqttGeneration=ids[0];}}
    static string Identity(ServerNode node){if(node.Protocol=="csqtt")return "csqtt|"+CsqttGenerations.For(node).Id+"|"+node.Host.ToLowerInvariant()+"|"+node.Port;try{var uri=new Uri(node.Link);var query=HttpUtility.ParseQueryString(uri.Query);return node.Protocol+"|"+node.Host.ToLowerInvariant()+"|"+node.Port+"|"+(query["type"]??query["network"]??"tcp");}catch{return node.Protocol+"|"+node.Host+"|"+node.Port;}}
    public static void Apply(ClientState state,List<ServerNode> imported,string source){
      var previous=state.Servers.ToList();
      foreach(var node in imported){
        var old=previous.FirstOrDefault(x=>SameLink(x,node));
        if(old==null&&source!=null){var candidates=previous.Where(x=>x.Source==source&&Identity(x)==Identity(node)).ToArray();if(candidates.Length==1&&imported.Count(x=>Identity(x)==Identity(node))==1)old=candidates[0];}
        if(old!=null){if(node.Protocol=="csqtt"&&String.IsNullOrEmpty(node.CsqttGeneration))node.CsqttGeneration=old.CsqttGeneration;node.Id=old.Id;node.UseInAutoSelection=old.UseInAutoSelection;node.Latency=old.Latency;node.Healthy=old.Healthy;if(old.TurnPreferences!=null&&(source!=null||node.TurnPreferences==null)){node.TurnPreferences=old.TurnPreferences;node.Name=old.Name;}}
        if(old==null)node.UseInAutoSelection=false;
        node.Source=source??(old==null?null:old.Source);
      }
      if(source!=null){state.Servers.RemoveAll(x=>x.Source==source);state.SubscriptionUrl=source;if(state.SubscriptionUrls==null)state.SubscriptionUrls=new List<string>();if(!state.SubscriptionUrls.Contains(source))state.SubscriptionUrls.Add(source);}
      foreach(var node in imported){state.Servers.RemoveAll(x=>SameLink(x,node)||x.Id==node.Id);state.Servers.Add(node);}
    }
  }
  public sealed class RoutePreset {
    public RoutePreset[] Parts{get;set;}public string OriginalName{get;set;}
    public string SourceLabel{get{return Parts==null?RouteSources.OwnerLabel(SourceOwner??"vnenapravo7-source"):String.Join(", ",Parts.Select(p=>p.SourceLabel).Distinct());}}
    public string DisplayName{get{return Name;}}
    public string Name{get;set;}public string Repository{get;set;}public string File{get;set;}public bool OutsideRussia{get;set;}
    public string Kind{get{return Parts==null?(OutsideRussia?"Исключение РФ":File.EndsWith("-ip.txt")||File.StartsWith("Subnets/")?"IP-сети":"Домены") :String.Join(" и ",Parts.Select(p=>p.Kind).Distinct());}}
    public string Summary{get;set;}
    public string SourceOwner{get;set;}public string SourceBranch{get;set;}public bool AdBlock{get;set;}
    public string Description{get{
      string explanation=OutsideRussia?"В VPN идут адреса за пределами РФ; российские адреса и торрент-трафик исключены.":AdBlock?"Подборка адресов для блокировки рекламы и трекеров.":File.StartsWith("all-")?"Сводный файл автора источника. Это не весь интернет и не результат проверки вашей сети.":File.StartsWith("main-")?"Основная подборка автора iplist-domains. Её состав определяется источником, а не программой.":File.StartsWith("geoblock-")&&!File.StartsWith("geoblock-v-rf-")?"Подборка географических ограничений автора cdn-list. Направление ограничения нужно сверять с содержимым источника.":File.StartsWith("geoblock-v-rf-")?"Отдельная подборка cdn-list с меткой geoblock-v-rf; не объединяется с общей подборкой геоблокировок.":File.StartsWith("block-")||File.StartsWith("zablok-v-rf-")?"Подборка блокировок автора cdn-list; наличие адреса не означает, что он заблокирован именно в вашей сети.":"Домены или IP-сети выбранного сервиса из обновляемого источника.";
      if(Parts!=null)return (Summary??explanation)+"\nИсточник: "+SourceLabel+"\n"+String.Join("\n",Parts.Select(p=>p.Url));return (Summary??explanation)+"\nИсточник: "+(SourceOwner??"vnenapravo7-source")+"/"+Repository+" · "+File;
    }}
    public string Url{get{return "https://raw.githubusercontent.com/"+(SourceOwner??"vnenapravo7-source")+"/"+Repository+"/"+(SourceBranch??"main")+"/"+File;}}
  }
  public static class RoutePresets {
    public static string MetadataIssue(RouteList route){
      if((route.PresetNames??new List<string>()).Any(String.IsNullOrWhiteSpace)||(route.PresetEntries??new Dictionary<string,string>()).Keys.Any(String.IsNullOrWhiteSpace))return "Некорректное имя сохранённого пресета. Исходные записи сохранены; импортируйте исправленный список для замены метаданных.";
      var snapshots=route.PresetEntries??new Dictionary<string,string>();var identities=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
      foreach(var entry in snapshots){var preset=Resolve(entry.Key);string identity=entry.Value??"";if(preset!=null){if(String.IsNullOrWhiteSpace(entry.Value))return "Сохранённый снимок пресета пуст. Исходные записи сохранены; автоматическая замена отключена.";try{identity=String.Join("\n",RouteCompiler.Parse(entry.Value).OrderBy(p=>p.Key).SelectMany(p=>p.Value.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(v=>v,StringComparer.OrdinalIgnoreCase).Select(v=>p.Key+":"+v.ToLowerInvariant())));if(identity.Length==0)return "Сохранённый снимок пресета не содержит записей. Исходный список сохранён; автоматическая замена отключена.";}catch(FormatException){return "Сохранённый снимок пресета повреждён. Исходные записи сохранены; автоматическая замена отключена.";}}
        string key=preset==null?entry.Key:preset.Name,previous;if(identities.TryGetValue(key,out previous)&&!String.Equals(previous,identity,StringComparison.Ordinal))return "Снимки одного пресета противоречат друг другу. Исходные записи сохранены; автоматическая замена отключена.";identities[key]=identity;
      }return null;
    }
    public static string[] AttachedNames(RouteList route){return (route.PresetNames??new List<string>()).Concat((route.PresetEntries??new Dictionary<string,string>()).Keys).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();}
    public static RoutePreset Resolve(string name){if(String.IsNullOrWhiteSpace(name))return null;var exact=Groups.FirstOrDefault(p=>String.Equals(p.Name,name,StringComparison.OrdinalIgnoreCase));if(exact!=null)return exact;var aliases=Groups.Where(p=>String.Equals(p.OriginalName,name,StringComparison.OrdinalIgnoreCase)).Take(2).ToArray();return aliases.Length==1?aliases[0]:null;}
    // Do not let beforefieldinit load the external preset bundle before the
    // application's runtime resource store has been initialized.
    static RoutePresets(){}
    static readonly RoutePreset[] OriginalItems=new[]{
      new RoutePreset{Name="Все кроме РФ",Repository="iplist-domains",File="ru/ru.txt",OutsideRussia=true},
      new RoutePreset{Name="Нейросети",Repository="iplist-domains",File="ai-domains.txt"},
      new RoutePreset{Name="Нейросети",Repository="iplist-domains",File="ai-ip.txt"},
      new RoutePreset{Name="Все списки",Repository="iplist-domains",File="all-domains.txt"},
      new RoutePreset{Name="Все списки",Repository="iplist-domains",File="all-ip.txt"},
      new RoutePreset{Name="Аниме",Repository="iplist-domains",File="anime-domains.txt"},
      new RoutePreset{Name="Аниме",Repository="iplist-domains",File="anime-ip.txt"},
      new RoutePreset{Name="Discord",Repository="iplist-domains",File="ds-domains.txt"},
      new RoutePreset{Name="Discord",Repository="iplist-domains",File="ds-ip.txt"},
      new RoutePreset{Name="Facebook",Repository="iplist-domains",File="fb-domains.txt"},
      new RoutePreset{Name="Facebook",Repository="iplist-domains",File="fb-ip.txt"},
      new RoutePreset{Name="Google",Repository="iplist-domains",File="ggl-domains.txt"},
      new RoutePreset{Name="Google",Repository="iplist-domains",File="ggl-ip.txt"},
      new RoutePreset{Name="GitHub",Repository="iplist-domains",File="github-domains.txt"},
      new RoutePreset{Name="GitHub",Repository="iplist-domains",File="github-ip.txt"},
      new RoutePreset{Name="Instagram",Repository="iplist-domains",File="inst-domains.txt"},
      new RoutePreset{Name="Instagram",Repository="iplist-domains",File="inst-ip.txt"},
      new RoutePreset{Name="Основной список",Repository="iplist-domains",File="main-domains.txt"},
      new RoutePreset{Name="Основной список",Repository="iplist-domains",File="main-ip.txt"},
      new RoutePreset{Name="Для взрослых",Repository="iplist-domains",File="pron-domains.txt"},
      new RoutePreset{Name="Для взрослых",Repository="iplist-domains",File="pron-ip.txt"},
      new RoutePreset{Name="Rutor / RuTracker",Repository="iplist-domains",File="rutor-rutracker-domains.txt"},
      new RoutePreset{Name="Rutor / RuTracker",Repository="iplist-domains",File="rutor-rutracker-ip.txt"},
      new RoutePreset{Name="Spotify",Repository="iplist-domains",File="spotify-domains.txt"},
      new RoutePreset{Name="Spotify",Repository="iplist-domains",File="spotify-ip.txt"},
      new RoutePreset{Name="Telegram",Repository="iplist-domains",File="tg-domains.txt"},
      new RoutePreset{Name="Telegram",Repository="iplist-domains",File="tg-ip.txt"},
      new RoutePreset{Name="WhatsApp",Repository="iplist-domains",File="wa-domains.txt"},
      new RoutePreset{Name="WhatsApp",Repository="iplist-domains",File="wa-ip.txt"},
      new RoutePreset{Name="X / Twitter",Repository="iplist-domains",File="x-domains.txt"},
      new RoutePreset{Name="X / Twitter",Repository="iplist-domains",File="x-ip.txt"},
      new RoutePreset{Name="AppStore",Repository="iplist-domains",File="yalbaka-domains.txt"},
      new RoutePreset{Name="AppStore",Repository="iplist-domains",File="yalbaka-ip.txt"},
      new RoutePreset{Name="YouTube",Repository="iplist-domains",File="yt-domains.txt"},
      new RoutePreset{Name="YouTube",Repository="iplist-domains",File="yt-ip.txt"},
      new RoutePreset{Name="SCity",Repository="iplist-domains",File="scity-domains.txt"},
      new RoutePreset{Name="Akamai",Repository="cdn-list",File="akamai-ip.txt"},
      new RoutePreset{Name="Все списки",Repository="cdn-list",File="all-domains.txt"},
      new RoutePreset{Name="Все списки",Repository="cdn-list",File="all-ip.txt"},
      new RoutePreset{Name="Amazon AWS",Repository="cdn-list",File="aws-ip.txt"},
      new RoutePreset{Name="Заблокированные сайты",Repository="cdn-list",File="block-domains.txt"},
      new RoutePreset{Name="Cloudflare",Repository="cdn-list",File="cloudflare-ip.txt"},
      new RoutePreset{Name="CloudFront",Repository="cdn-list",File="cloudfront-ip.txt"},
      new RoutePreset{Name="DDoS-Guard",Repository="cdn-list",File="ddos_guard-ip.txt"},
      new RoutePreset{Name="DigitalOcean",Repository="cdn-list",File="digitalocean-ip.txt"},
      new RoutePreset{Name="Discord",Repository="cdn-list",File="discord-ip.txt"},
      new RoutePreset{Name="Fastly",Repository="cdn-list",File="fastly-ip.txt"},
      new RoutePreset{Name="Геоблокировки",Repository="cdn-list",File="geoblock-domains.txt"},
      new RoutePreset{Name="Геоблокировки",Repository="cdn-list",File="geoblock-ip.txt"},
      new RoutePreset{Name="Геоблокировки в РФ",Repository="cdn-list",File="geoblock-v-rf-domains.txt"},
      new RoutePreset{Name="Геоблокировки в РФ",Repository="cdn-list",File="geoblock-v-rf-ip.txt"},
      new RoutePreset{Name="Hetzner",Repository="cdn-list",File="hetzner-ip.txt"},
      new RoutePreset{Name="HODCA — всё",Repository="cdn-list",File="hodca-allin1-domains.txt"},
      new RoutePreset{Name="HODCA — всё",Repository="cdn-list",File="hodca-allin1-ip.txt"},
      new RoutePreset{Name="OVH",Repository="cdn-list",File="ovh-ip.txt"},
      new RoutePreset{Name="Cloudflare",Repository="cdn-list",File="svc_cloudflare-domains.txt"},
      new RoutePreset{Name="CloudFront",Repository="cdn-list",File="svc_cloudfront-domains.txt"},
      new RoutePreset{Name="DigitalOcean",Repository="cdn-list",File="svc_digitalocean-domains.txt"},
      new RoutePreset{Name="Discord",Repository="cdn-list",File="svc_discord-domains.txt"},
      new RoutePreset{Name="Hetzner",Repository="cdn-list",File="svc_hetzner-domains.txt"},
      new RoutePreset{Name="OVH",Repository="cdn-list",File="svc_ovh-domains.txt"},
      new RoutePreset{Name="Заблокированные в РФ",Repository="cdn-list",File="zablok-v-rf-ip.txt"},
      new RoutePreset{Name="Megamori — облегчённый",SourceOwner="neomikanagi",Repository="megamori",File="ads-all-lite.list",AdBlock=true},
      new RoutePreset{Name="Megamori — полный",SourceOwner="neomikanagi",Repository="megamori",File="megamori.list",AdBlock=true},
      new RoutePreset{Name="OISD — небольшой",SourceOwner="burjuyz",Repository="RuRulesets",File="oisd/include-domain-oisd_small.lst",AdBlock=true},
      new RoutePreset{Name="OISD — полный",SourceOwner="burjuyz",Repository="RuRulesets",File="oisd/include-domain-oisd_big.lst",AdBlock=true},
      new RoutePreset{Name="AdAway — реклама и трекеры",SourceOwner="burjuyz",Repository="RuRulesets",File="adaway/include-domain-adaway_alive_hosts_mail_fb.lst",AdBlock=true},
      new RoutePreset{Name="StevenBlack — реклама и трекеры",SourceOwner="StevenBlack",SourceBranch="master",Repository="hosts",File="hosts",AdBlock=true}
    }.Concat(CommunityPresets()).ToArray();
    public static readonly RoutePreset[] Items=PresetStorage.Review(OriginalItems);
    public static readonly RoutePreset[] Groups=Items.GroupBy(p=>p.Name,StringComparer.OrdinalIgnoreCase).Select(g=>{var p=g.First();return new RoutePreset{Name=p.Name,OriginalName=p.OriginalName,Repository=p.Repository,File=p.File,SourceOwner=p.SourceOwner,SourceBranch=p.SourceBranch,OutsideRussia=p.OutsideRussia,AdBlock=p.AdBlock,Summary=p.Summary,Parts=g.ToArray()};}).OrderBy(p=>p.Name,StringComparer.CurrentCultureIgnoreCase).ToArray();
    static IEnumerable<RoutePreset> CommunityPresets(){
      var services=new[]{new[]{"cloudflare","Cloudflare"},new[]{"cloudfront","CloudFront"},new[]{"digitalocean","DigitalOcean"},new[]{"discord","Discord"},new[]{"google_ai","Google AI (Gemini)"},new[]{"google_meet","Google Meet"},new[]{"google_play","Google Play"},new[]{"hdrezka","HDRezka"},new[]{"hetzner","Hetzner"},new[]{"meta","Meta · Facebook, Instagram, WhatsApp"},new[]{"ovh","OVH"},new[]{"roblox","Roblox"},new[]{"telegram","Telegram"},new[]{"tiktok","TikTok"},new[]{"twitter","X / Twitter"},new[]{"youtube","YouTube"}};
      var subnets=new HashSet<string>(new[]{"cloudflare","cloudfront","digitalocean","discord","google_meet","hetzner","meta","ovh","roblox","telegram","twitter"});
      foreach(var service in services){yield return Community(service[1],"Services/"+service[0]+".lst","Домены сервиса из ITDog. Для одного сервиса объединяются с IP-пресетами.");if(subnets.Contains(service[0]))yield return Community(service[1],"Subnets/IPv4/"+service[0]+".lst","IP-сети сервиса из ITDog. Общие сети CDN могут обслуживать и другие сайты.");}
      foreach(var category in new[]{
        new[]{"anime","Аниме","Тематическая подборка аниме-сайтов ITDog."},
        new[]{"block","Заблокировано в РФ","Ресурсы из категории блокировок в РФ. Не включает автоматически все остальные категории ITDog; это не результат проверки вашей сети."},
        new[]{"geoblock","Сервисы, не пускающие из РФ","Сервисы, которые сами ограничивают доступ с российских IP-адресов. Обычно нужен зарубежный выход. Google AI вынесен в отдельный пресет."},
        new[]{"hodca","Хостинги и CDN (HODCA)","Подборка ITDog: Hetzner, OVH, DigitalOcean, Cloudflare, AWS и Akamai. Не весь интернет и не гарантированный перечень блокировок вашей сети."},
        new[]{"news","Новости","Тематическая подборка новостных ресурсов ITDog."},
        new[]{"porn","Для взрослых","Тематическая подборка сайтов для взрослых ITDog."}
      })yield return Community(category[1],"Categories/"+category[0]+".lst",category[2]);
      yield return Community("Сборная подборка для пользователей в РФ","Russia/inside-raw.lst","ITDog Russia inside: Аниме, Block, GeoBlock, Новости, Для взрослых, HDRezka, Meta, TikTok, X/Twitter, YouTube и домены Discord. Не весь интернет и не «Все кроме РФ».");
      yield return Community("Российские сервисы из-за рубежа","Russia/outside-raw.lst","ITDog Russia outside: российские ресурсы, доступные только с российских IP-адресов. Для пользователей за рубежом; нужен российский выход. Не «Все кроме РФ».");
      yield return Community("Заблокировано в Украине","Ukraine/inside-raw.lst","Подборка ресурсов, заблокированных в Украине, по источникам uablacklist.net и zaborona.help. Не российский список.");
    }
    static RoutePreset Community(string name,string file,string summary){return new RoutePreset{Name=name,Repository="allow-domains",SourceOwner="itdoginfo",File=file,Summary=summary};}
  }
  public sealed class RouteList {
    public bool OriginalEngine{get;set;}
    public bool WardogsAutoPause{get;set;}
    public ExitDnsSettings Dns{get;set;}
    public Dictionary<string,string> PresetEntries{get;set;}
    public bool? GameFilterTcp{get;set;} public bool? GameFilterUdp{get;set;}
    [System.Web.Script.Serialization.ScriptIgnore] public bool GameTcpEnabled{get{return GameFilterTcp??GameFilter;}}
    [System.Web.Script.Serialization.ScriptIgnore] public bool GameUdpEnabled{get{return GameFilterUdp??GameFilter;}}
    public string Id {get;set;} public string Name {get;set;} public string Text {get;set;}
    public string Target {get;set;} public bool Enabled {get;set;} public string SourceUrl {get;set;} public string ZapretProfileId{get;set;} public bool GameFilter{get;set;}
    public bool ExcludeRussia{get;set;}public bool ExcludeTorrents{get;set;}public string ExcludedText{get;set;}public string CustomText{get;set;}public string DirectCustomText{get;set;}public string CustomTextDirection{get;set;}public bool InvertAddresses{get;set;}public string BuiltinPreset{get;set;}public string MatchMode{get;set;}public List<string> SourceUrls{get;set;}public List<string> PresetNames{get;set;}public List<string> DirectPresetNames{get;set;}
    public RouteList(){Id=Guid.NewGuid().ToString("N");Name="Новый список";Text="";Target="proxy";Enabled=true;}
  }
  public static class RouteSources {
    public static string OwnerLabel(string owner){return owner=="vnenapravo7-source"?"7":owner=="itdoginfo"?"itdog":owner=="splify2"?"splify2":owner;}
    public static string SourceLabel(RouteList route){var urls=Urls(route);return urls.Length==0?"Вручную":String.Join(", ",urls.Select(url=>{Uri uri;if(!Uri.TryCreate(url,UriKind.Absolute,out uri))return "Неизвестно";var parts=uri.AbsolutePath.Trim('/').Split('/');return OwnerLabel((uri.Host=="raw.githubusercontent.com"||uri.Host=="github.com")&&parts.Length>0?parts[0]:uri.Host);}).Distinct());}
    public static string UpdatedText(RouteList route,string downloaded){string custom=route.CustomText;if(String.IsNullOrWhiteSpace(custom)&&(route.BuiltinPreset=="setup-service:discord"||route.BuiltinPreset=="service-constructor:discord"||route.BuiltinPreset=="setup-service:discord-voice"||route.BuiltinPreset=="service-constructor:discord-voice"))custom=ZapretChecks.Services.First(s=>s.Id=="discord").Domains;return OutsideRussiaPreset.Update(route,downloaded)+(String.IsNullOrWhiteSpace(custom)?"":"\n"+custom);}
    public static string[] Urls(RouteList route){return (route.SourceUrls??new List<string>()).Concat(String.IsNullOrWhiteSpace(route.SourceUrl)?new string[0]:new[]{route.SourceUrl}).Distinct(StringComparer.Ordinal).ToArray();}
    public static string Identity(RouteList route){return String.Join("\n",Urls(route));}
    public static RouteList Merge(IEnumerable<RouteList> routes){var selected=routes.ToArray();if(selected.Length<1)throw new InvalidOperationException("Выберите списки");var first=selected[0];if(selected.Any(r=>!String.Equals(new JavaScriptSerializer().Serialize(r.Dns),new JavaScriptSerializer().Serialize(first.Dns),StringComparison.Ordinal)||r.WardogsAutoPause!=first.WardogsAutoPause||r.OriginalEngine!=first.OriginalEngine||r.Target!=first.Target||r.ZapretProfileId!=first.ZapretProfileId||r.MatchMode!=first.MatchMode||r.Enabled!=first.Enabled||r.GameTcpEnabled!=first.GameTcpEnabled||r.GameUdpEnabled!=first.GameUdpEnabled||r.InvertAddresses!=first.InvertAddresses||r.BuiltinPreset!=first.BuiltinPreset||r.ExcludeRussia!=first.ExcludeRussia||r.ExcludeTorrents!=first.ExcludeTorrents||!String.Equals(r.ExcludedText??"",first.ExcludedText??"",StringComparison.Ordinal)))throw new InvalidOperationException("Объединять можно только правила с одинаковым выходом, профилем, условием и состоянием.");
      var result=new RouteList{WardogsAutoPause=first.WardogsAutoPause,OriginalEngine=first.OriginalEngine,Dns=first.Dns,Name=String.Join(" + ",selected.Select(r=>r.Name).Distinct()),Target=first.Target,ZapretProfileId=first.ZapretProfileId,MatchMode=first.MatchMode,Enabled=first.Enabled,GameFilterTcp=first.GameTcpEnabled,GameFilterUdp=first.GameUdpEnabled,InvertAddresses=first.InvertAddresses,BuiltinPreset=first.BuiltinPreset,ExcludeRussia=first.ExcludeRussia,ExcludeTorrents=first.ExcludeTorrents,ExcludedText=first.ExcludedText,CustomText=String.Join("\n",selected.Select(r=>r.CustomText).Where(t=>!String.IsNullOrWhiteSpace(t)).Distinct()),Text=String.Join("\n",selected.SelectMany(r=>(r.Text??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)).Distinct(StringComparer.OrdinalIgnoreCase)),SourceUrls=selected.SelectMany(Urls).Distinct().ToList(),PresetNames=selected.SelectMany(r=>r.PresetNames??new List<string>()).Distinct().ToList()};result.PresetEntries=selected.Where(r=>r.PresetEntries!=null).SelectMany(r=>r.PresetEntries).GroupBy(p=>p.Key,StringComparer.OrdinalIgnoreCase).ToDictionary(g=>g.Key,g=>String.Join("\n",g.SelectMany(p=>(p.Value??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)).Distinct(StringComparer.OrdinalIgnoreCase)),StringComparer.OrdinalIgnoreCase);if(result.SourceUrls.Count==1)result.SourceUrl=result.SourceUrls[0];return result;
    }
  }
  public static class DefaultRouteRouting {
    static readonly string[] Allowed={"proxy","direct","byetube"};
    public static string Requested(ClientState state){var value=state==null?null:state.DefaultRouteTarget;if(value=="proxy"&&state!=null&&!state.VpnEnabled)return "direct";if(value=="byetube"&&state!=null&&!state.ByeTubeEnabled)return "direct";if(value!=null&&value.StartsWith("zapret:",StringComparison.Ordinal)&&state!=null&&state.ZapretProfiles!=null&&state.ZapretProfiles.Any(p=>p.Enabled&&p.Id==value.Substring(7)))return value;return Allowed.Contains(value)?value:state!=null&&state.VpnEnabled?"proxy":"direct";}
    public static string Effective(ClientState state,string proxy,bool vpnAvailable){var value=Requested(state);if(value=="proxy")return vpnAvailable?proxy:"direct";if(value=="byetube")return state.ByeTubeEnabled?"byetube":"direct";if(value.StartsWith("zapret:",StringComparison.Ordinal))return "direct";return value;}
  }
  public sealed class ClientState {
    public List<string> ExcludedApplications{get;set;}
    public List<SteamGameTemplate> SteamGameTemplates{get;set;}
    public List<RouteList> UserGameTemplates{get;set;}
    public Dictionary<string,string> GlobalHotkeys{get;set;}
    [ScriptIgnore]public bool VkWifiPaused{get;set;}
    public List<ExitDnsSettings> ExitDns{get;set;}
    public int TelegramPort{get;set;}public bool SetupSeen{get;set;}public List<string> SetupServices{get;set;}
    public List<ServerNode> Servers {get;set;} public List<RouteList> Lists {get;set;}
    public string SelectedServerId {get;set;} public string SubscriptionUrl {get;set;} public List<string> SubscriptionUrls {get;set;}
    public bool SimpleDesign{get;set;}public bool AdvancedMode{get;set;}public bool SettingsModeChosen{get;set;}
    public int InterfaceMode{get;set;}public bool MinimizeToTaskbar{get;set;}public bool CloseToTray{get;set;}
    public bool IndependentWarpOutputs{get;set;}public bool VpnEnabled{get;set;}public bool WarpEnabled{get;set;}
    public string DefaultRouteTarget{get;set;}
    public bool ByeTubeEnabled{get;set;}public string ByeTubeStrategy{get;set;}
    public string Mode {get;set;} public bool AutoSelect {get;set;}
    public bool AutoDetectBs{get;set;}public bool AutoBsActive{get;set;}public int AutoBsIntervalSeconds{get;set;}public string[] AutoBsSites{get;set;}public int SubscriptionUpdateHours{get;set;}
    public string LastVpnServerId{get;set;}public string LastBsServerId{get;set;}
    public bool CompactAnimations{get;set;}public bool CompactAdvancedFeatures{get;set;}public int CompactWallpaperOpacity{get;set;}public string CompactAccent{get;set;}public int CompactVisualVersion{get;set;}public string AdvancedOfferBuild{get;set;}
    public string CompactTheme{get;set;}public string CompactAccentColor{get;set;}public string CompactCardColor{get;set;}public string CompactBackgroundColor{get;set;}
    public string CompactDarkPrimary{get;set;}public string CompactDarkSecondary{get;set;}public string CompactLightPrimary{get;set;}public string CompactLightSecondary{get;set;}
    public string CompactConnectionStyle{get;set;}public string CompactDisconnectStyle{get;set;}
    public int LocalProxyPort{get;set;}public bool LocalProxyRandomPort{get;set;}public bool LocalProxyShareLan{get;set;}public bool LocalProxyAuth{get;set;}public string LocalProxyUsername{get;set;}public string LocalProxyPassword{get;set;}
    public int AutoSelectSettingsVersion{get;set;}public string AutoSelectMode{get;set;}public int AutoSelectToleranceMs{get;set;}public int AutoSelectIntervalSeconds{get;set;}public bool AutoSelectFailureOnly{get;set;}
    public Dictionary<string,List<string>> HostsSourceMultiSelections{get;set;}
    public Dictionary<string,string> HostsSourceSelections{get;set;}public Dictionary<string,string> HostsAppliedHashes{get;set;}
    public string DnsProvider {get;set;} public string DnsProtocol {get;set;} public string DnsCustom {get;set;}
    public List<string> RecentServerIds {get;set;}
    public ZapretSettings Zapret{get;set;}
    public List<ZapretProfile> ZapretProfiles{get;set;} public string SelectedZapretProfileId{get;set;}
    public bool ZapretRoutingMigrated{get;set;}
    public ClientState(){SubscriptionUpdateHours=6;VpnEnabled=true;Servers=new List<ServerNode>();Lists=new List<RouteList>();ZapretProfiles=new List<ZapretProfile>();RecentServerIds=new List<string>();SubscriptionUrls=new List<string>();SelectedServerId="";SubscriptionUrl="";Mode="tun";AutoSelect=true;AutoSelectMode="fast";AutoSelectToleranceMs=50;AutoSelectIntervalSeconds=60;AutoBsIntervalSeconds=60;CompactAnimations=true;CompactWallpaperOpacity=24;CompactAccent="balanced";CompactVisualVersion=1;CompactTheme="dark";CompactAccentColor="#65D6C7";CompactCardColor="#171C22";CompactBackgroundColor="#0E1217";CompactConnectionStyle="blueprint";CompactDisconnectStyle="lock";LocalProxyPort=10808;LocalProxyUsername="mcrf";LocalProxyPassword=Guid.NewGuid().ToString("N");DnsProvider="Cloudflare";DnsProtocol="DoH";DnsCustom="";}
    public static ClientState Initial(){var s=new ClientState{AutoSelectSettingsVersion=1};s.Lists.Add(new RouteList{Name="Telegram",Text="app:Telegram.exe\ntelegram.org\n*.telegram.org\nt.me",Target="proxy"});s.Lists.Add(new RouteList{Name="YouTube",Text="youtube.com\n*.youtube.com\ngooglevideo.com\n*.googlevideo.com",Target="proxy"});return s;}
  }
  public sealed class StateStore {
    static void NormalizeAutomation(ClientState s){if(!new[]{"fast","first","recent"}.Contains(s.AutoSelectMode))s.AutoSelectMode="fast";if(s.AutoSelectIntervalSeconds<5||s.AutoSelectIntervalSeconds>3600)s.AutoSelectIntervalSeconds=60;s.AutoSelectToleranceMs=Math.Max(15,Math.Min(150,s.AutoSelectToleranceMs));}
    public readonly string Root; public readonly string FilePath;
    readonly JavaScriptSerializer json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};
    readonly bool allowRecovery;
    public StateStore(string root=null):this(root,false){}
    internal static string NormalizeRoot(string path){string full=Path.GetFullPath(path),drive=Path.GetPathRoot(full);return full.Length>drive.Length?full.TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar):full;}
    internal StateStore(string root,bool recovery){allowRecovery=recovery;Root=NormalizeRoot(root??Environment.GetEnvironmentVariable("MCRF_DATA_DIR")??Environment.GetEnvironmentVariable("SPLIFY_WIN_DATA_DIR")??ProfileStorage.DefaultRoot());Directory.CreateDirectory(Root);FilePath=Path.Combine(Root,"client.json");}
    public ClientState Load(){
      if(!allowRecovery)ProfileRecovery.DemandHealthy(Root);
      try{
        if(!File.Exists(FilePath))return ClientState.Initial();
        string stored=File.ReadAllText(FilePath,Encoding.UTF8),decoded=Decode(stored);var s=json.Deserialize<ClientState>(decoded);
        if(s==null)throw new InvalidDataException("Пустой файл настроек");
        if(s.Servers==null)s.Servers=new List<ServerNode>();if(s.Lists==null)s.Lists=new List<RouteList>();s.Servers.RemoveAll(x=>x==null);s.Lists.RemoveAll(x=>x==null);PresetStorage.NormalizeNames(s.Lists);
        foreach(var node in s.Servers.Where(x=>x.Protocol=="csqtt"||x.Protocol=="wdtt"||x.Protocol=="qwdtt")){if(node.Protocol=="csqtt"&&String.IsNullOrEmpty(node.CsqttGeneration))node.CsqttGeneration=CsqttGenerations.Hint(node.Link)??"2.1.9";var parsed=Links.Read(node.Link??"").FirstOrDefault();if(parsed!=null){node.Host=parsed.Host;node.Port=parsed.Port;if(node.Name=="connect"||String.IsNullOrWhiteSpace(node.Name))node.Name=parsed.Name;}}
        var selectedNode=s.Servers.FirstOrDefault(n=>n.Id==s.SelectedServerId);if(s.AutoDetectBs&&selectedNode!=null)s.AutoBsActive=BsServers.Is(selectedNode);if(selectedNode!=null){if(BsServers.Is(selectedNode)&&String.IsNullOrEmpty(s.LastBsServerId))s.LastBsServerId=selectedNode.Id;if(!BsServers.Is(selectedNode)&&String.IsNullOrEmpty(s.LastVpnServerId))s.LastVpnServerId=selectedNode.Id;}
        if(String.IsNullOrEmpty(s.DnsProvider)||!DnsOptions.Providers.Contains(s.DnsProvider))s.DnsProvider="Cloudflare";if(String.IsNullOrEmpty(s.DnsProtocol)||!DnsOptions.Protocols(s.DnsProvider).Contains(s.DnsProtocol))s.DnsProtocol="DoH";
        if(s.TelegramPort<1024||s.TelegramPort>65535)s.TelegramPort=TelegramBridge.DefaultPort;
        if(s.AutoSelectSettingsVersion<1){if(s.AutoSelectIntervalSeconds==300||s.AutoSelectIntervalSeconds<5)s.AutoSelectIntervalSeconds=60;if(s.AutoSelectToleranceMs<0)s.AutoSelectToleranceMs=50;s.AutoSelectSettingsVersion=1;}if(s.AutoBsIntervalSeconds<15||s.AutoBsIntervalSeconds>3600)s.AutoBsIntervalSeconds=60;if(s.CompactVisualVersion<1){if(s.CompactWallpaperOpacity==72)s.CompactWallpaperOpacity=24;if(String.Equals(s.CompactAccentColor,"#78DCCF",StringComparison.OrdinalIgnoreCase))s.CompactAccentColor="#65D6C7";if(String.Equals(s.CompactCardColor,"#182B32",StringComparison.OrdinalIgnoreCase))s.CompactCardColor="#171C22";if(String.Equals(s.CompactBackgroundColor,"#091720",StringComparison.OrdinalIgnoreCase))s.CompactBackgroundColor="#0E1217";s.CompactVisualVersion=1;}if(s.CompactWallpaperOpacity<0||s.CompactWallpaperOpacity>100)s.CompactWallpaperOpacity=24;if(String.IsNullOrEmpty(s.CompactAccent))s.CompactAccent="balanced";if(s.CompactTheme!="light")s.CompactTheme="dark";if(!System.Text.RegularExpressions.Regex.IsMatch(s.CompactAccentColor??"",@"^#[0-9a-fA-F]{6}$"))s.CompactAccentColor="#65D6C7";if(!System.Text.RegularExpressions.Regex.IsMatch(s.CompactCardColor??"",@"^#[0-9a-fA-F]{6}$"))s.CompactCardColor="#171C22";if(!System.Text.RegularExpressions.Regex.IsMatch(s.CompactBackgroundColor??"",@"^#[0-9a-fA-F]{6}$"))s.CompactBackgroundColor="#0E1217";if(!new[]{"blueprint","classic","magnet"}.Contains(s.CompactConnectionStyle??""))s.CompactConnectionStyle="blueprint";if(s.CompactDisconnectStyle!="lock")s.CompactDisconnectStyle="lock";if(s.LocalProxyPort<1024||s.LocalProxyPort>65535)s.LocalProxyPort=10808;if(String.IsNullOrWhiteSpace(s.LocalProxyUsername))s.LocalProxyUsername="mcrf";if(String.IsNullOrWhiteSpace(s.LocalProxyPassword))s.LocalProxyPassword=Guid.NewGuid().ToString("N");NormalizeAutomation(s);if(!stored.StartsWith(ProtectedPrefix,StringComparison.Ordinal)||!String.Equals(json.Serialize(s),decoded,StringComparison.Ordinal))Save(s);
        return s;
      }catch(Exception ex){readFailed=true;throw new IOException("Настройки не прочитаны; исходный файл не изменён. "+ex.Message,ex);}
    }
    const string ProtectedPrefix="MCRF-DPAPI-1\n";
    bool readFailed;
    string Decode(string text){if(!text.StartsWith(ProtectedPrefix,StringComparison.Ordinal))return text;try{return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(text.Substring(ProtectedPrefix.Length)),Encoding.UTF8.GetBytes("MCRF settings v1"),DataProtectionScope.CurrentUser));}catch(Exception ex){readFailed=true;throw new IOException("Не удалось открыть защищённые настройки. Войдите под той же учётной записью Windows. Файл сохранён и не будет перезаписан.",ex);}}
    string Protect(string text){return ProtectedPrefix+Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(text),Encoding.UTF8.GetBytes("MCRF settings v1"),DataProtectionScope.CurrentUser));}
    public void Save(ClientState s){if(!allowRecovery)ProfileRecovery.DemandHealthy(Root);if(readFailed)throw new IOException("Сохранение запрещено: сначала восстановите чтение существующих настроек.");lock(json){var tmp=FilePath+"."+Guid.NewGuid().ToString("N")+".tmp";try{string encrypted=Protect(json.Serialize(s));File.WriteAllText(tmp,encrypted,new UTF8Encoding(false));if(File.Exists(FilePath)){string previous=File.ReadAllText(FilePath,Encoding.UTF8);if(previous.StartsWith(ProtectedPrefix,StringComparison.Ordinal)){File.Replace(tmp,FilePath,FilePath+".protected-backup");}else{File.WriteAllText(FilePath+".protected-backup",Protect(previous),new UTF8Encoding(false));File.Replace(tmp,FilePath,null);}}else File.Move(tmp,FilePath);}finally{if(File.Exists(tmp))File.Delete(tmp);}}}
  }
  public static class Links {
    static readonly HashSet<string> Accepted=new HashSet<string>(new[]{"mcrf-turn","vless","trojan","hysteria","hysteria2","hy2","tuic","ss","socks5","socks","http","https","anytls","vmess","naive","naive+https","ssh","awg","wg","wireguard","csqtt","wdtt","qwdtt","openflux"},StringComparer.OrdinalIgnoreCase);
    static readonly string[] AwgKeys={"Jc","Jmin","Jmax","S1","S2","S3","S4","H1","H2","H3","H4","I1","I2","I3","I4","I5","HeaderProtectionKey","ContentPaddingAddition","RekeyAfterTime","RekeyTimeout","RejectAfterTime","KeepaliveTimeout","MaxHandshakeAttempts","RandomTrailers","DisableCookies"};
    static string DecodeBase64(string value){var normalized=value.Replace('-','+').Replace('_','/');return Encoding.UTF8.GetString(Convert.FromBase64String(normalized.PadRight((normalized.Length+3)/4*4,'=')));}
    static string NormalizeShadowsocks(string link){var body=link.Substring(5);var fragment="";var hash=body.IndexOf('#');if(hash>=0){fragment=body.Substring(hash);body=body.Substring(0,hash);}if(body.Contains("@"))return link;try{var decoded=DecodeBase64(body);if(decoded.Contains("@")&&decoded.Contains(":"))return "ss://"+decoded+fragment;}catch{}return link;}
    public static bool IsAwg(Dictionary<string,string> fields){return AwgKeys.Any(fields.ContainsKey);}
    public static Dictionary<string,string> WireGuardFields(string text){var fields=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);string section="";foreach(var line in text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){var trimmed=line.Trim();if(trimmed.StartsWith("#")||trimmed.StartsWith(";"))continue;if(trimmed.StartsWith("[")&&trimmed.EndsWith("]")){section=trimmed.Substring(1,trimmed.Length-2);continue;}if(!trimmed.Contains("="))continue;var parts=trimmed.Split(new[]{'='},2);var key=parts[0].Trim();if(section.Equals("Peer",StringComparison.OrdinalIgnoreCase)||key.Equals("PrivateKey",StringComparison.OrdinalIgnoreCase)||key.Equals("Address",StringComparison.OrdinalIgnoreCase)||key.Equals("MTU",StringComparison.OrdinalIgnoreCase)||AwgKeys.Any(x=>x.Equals(key,StringComparison.OrdinalIgnoreCase)))fields[key]=parts[1].Trim();else if(section.Equals("Interface",StringComparison.OrdinalIgnoreCase))fields[key]=parts[1].Trim();}return fields;}
    public static List<ServerNode> Read(string input){if(input.TrimStart().StartsWith("{")||System.Text.RegularExpressions.Regex.IsMatch(input,@"^\s*\[\s*\{"))return VkTurnOptions.ReadProfiles(input.Trim());var text=input.Trim().Replace("qwdtt:config","qwdtt://config");if(text.Length==0)return new List<ServerNode>();if(text.StartsWith("[Interface]",StringComparison.OrdinalIgnoreCase)){var fields=WireGuardFields(text);if(!fields.ContainsKey("PrivateKey")||!fields.ContainsKey("PublicKey")||!fields.ContainsKey("Endpoint")||!fields.ContainsKey("Address"))return new List<ServerNode>();var endpoint=fields["Endpoint"];var split=endpoint.LastIndexOf(':');if(split<1)return new List<ServerNode>();int port;if(!Int32.TryParse(endpoint.Substring(split+1),out port))return new List<ServerNode>();var host=endpoint.Substring(0,split).Trim('[',']');var awg=IsAwg(fields);return new List<ServerNode>{new ServerNode{Name=awg?"AmneziaWG":"WireGuard / WARP",Protocol=awg?"awg":"wireguard",Host=host,Port=port,Link=text,Healthy=true,Latency=-1}};}if(!text.Contains("://")){try{var b=text.Replace('-','+').Replace('_','/');b=b.PadRight((b.Length+3)/4*4,'=');var decoded=Encoding.UTF8.GetString(Convert.FromBase64String(b));if(decoded.Contains("://"))text=decoded;}catch{}}
      var result=new List<ServerNode>();foreach(var raw in text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){var line=raw.Trim();if(line.StartsWith("#")||line.Length==0)continue;try{var scheme=line.Substring(0,line.IndexOf("://",StringComparison.Ordinal));if(!Accepted.Contains(scheme))continue;if(scheme.Equals("ss",StringComparison.OrdinalIgnoreCase))line=NormalizeShadowsocks(line);
        if(scheme.Equals("vmess",StringComparison.OrdinalIgnoreCase)){var encoded=line.Substring(8).Split('#')[0].Replace('-','+').Replace('_','/');encoded=encoded.PadRight((encoded.Length+3)/4*4,'=');var data=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Convert.FromBase64String(encoded)));var host=Convert.ToString(data["add"]);result.Add(new ServerNode{Name=data.ContainsKey("ps")?Convert.ToString(data["ps"]):host,Protocol="vmess",Host=host,Port=Convert.ToInt32(data["port"]),Link=line});continue;}
        if(scheme.Equals("mcrf-turn",StringComparison.OrdinalIgnoreCase)){result.AddRange(VkTurnOptions.ReadEnvelope(line));continue;}if(scheme.Equals("openflux",StringComparison.OrdinalIgnoreCase)){if(!line.StartsWith("openflux://v1/",StringComparison.OrdinalIgnoreCase))continue;result.Add(new ServerNode{Name="OpenFlux",Protocol="openflux",Host="локальный туннель",Port=0,Link=line,Healthy=false});continue;}
        if(scheme.Equals("qwdtt",StringComparison.OrdinalIgnoreCase)){var p=QwdttProfile.Parse(line);result.Add(new ServerNode{Name=p.Name,Protocol="qwdtt",Host=p.Host,Port=p.Port,Link=line,TurnPreferences=VkTurnOptions.FromLink(line)});continue;}
        if(scheme.Equals("csqtt",StringComparison.OrdinalIgnoreCase)||scheme.Equals("wdtt",StringComparison.OrdinalIgnoreCase)||scheme.Equals("qwdtt",StringComparison.OrdinalIgnoreCase)){var profile=TurnProfile.Parse(line);result.Add(new ServerNode{Name=profile.Name,Protocol=profile.Protocol,CsqttGeneration=profile.Protocol=="csqtt"?CsqttGenerations.Hint(line):null,Host=profile.Host,Port=profile.Port,Link=line,TurnPreferences=VkTurnOptions.FromLink(line)});continue;}
        var u=new Uri(line);var name=Uri.UnescapeDataString(u.Fragment.TrimStart('#'));
        if(scheme.Equals("awg",StringComparison.OrdinalIgnoreCase)||scheme.Equals("wg",StringComparison.OrdinalIgnoreCase)||scheme.Equals("wireguard",StringComparison.OrdinalIgnoreCase)){var q=HttpUtility.ParseQueryString(u.Query);var key=q["publickey"]??q["public_key"];var address=q["address"];if(String.IsNullOrEmpty(key)||String.IsNullOrEmpty(address))continue;var conf=new StringBuilder("[Interface]\nPrivateKey = ").Append(Uri.UnescapeDataString(u.UserInfo)).Append("\nAddress = ").Append(address).Append('\n');foreach(var field in new[]{"MTU","DNS"}.Concat(AwgKeys)){var value=q[field];if(!String.IsNullOrEmpty(value))conf.Append(field).Append(" = ").Append(value).Append('\n');}conf.Append("[Peer]\nPublicKey = ").Append(key).Append("\nEndpoint = ").Append(u.Host).Append(':').Append(u.Port).Append("\nAllowedIPs = ").Append(q["allowedips"]??"0.0.0.0/0, ::/0").Append('\n');if(!String.IsNullOrEmpty(q["presharedkey"]))conf.Append("PresharedKey = ").Append(q["presharedkey"]).Append('\n');if(!String.IsNullOrEmpty(q["persistentkeepalive"]))conf.Append("PersistentKeepalive = ").Append(q["persistentkeepalive"]).Append('\n');var awg=scheme.Equals("awg",StringComparison.OrdinalIgnoreCase)||IsAwg(WireGuardFields(conf.ToString()));result.Add(new ServerNode{Name=name.Length>0?name:awg?"AmneziaWG":"WireGuard / WARP",Protocol=awg?"awg":"wireguard",Host=u.Host,Port=u.Port,Link=conf.ToString(),Healthy=true});continue;}
        result.Add(new ServerNode{Name=name.Length>0?name:u.Host,Protocol=scheme.Equals("hy2",StringComparison.OrdinalIgnoreCase)?"hysteria2":scheme.Equals("naive+https",StringComparison.OrdinalIgnoreCase)?"naive":scheme.ToLowerInvariant(),Host=u.Host,Port=u.IsDefaultPort?(scheme.Equals("ssh",StringComparison.OrdinalIgnoreCase)?22:443):u.Port,Link=line});
      }catch{}}return result;}
  }
}
