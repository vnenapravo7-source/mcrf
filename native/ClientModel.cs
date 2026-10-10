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
  public static class SecretStorage {
    const string Prefix="MCRF-SECRET-1\n";
    public static string Read(string path){string text=File.ReadAllText(path,Encoding.UTF8);if(text.StartsWith(Prefix,StringComparison.Ordinal))return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(text.Substring(Prefix.Length)),Encoding.UTF8.GetBytes("MCRF secret v1"),DataProtectionScope.CurrentUser));Write(path,text);return text;}
    public static void Write(string path,string text){string encrypted=Prefix+Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(text),Encoding.UTF8.GetBytes("MCRF secret v1"),DataProtectionScope.CurrentUser));string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temp,encrypted,new UTF8Encoding(false));if(File.Exists(path))File.Replace(temp,path,null);else File.Move(temp,path);}finally{if(File.Exists(temp))File.Delete(temp);}}
    // External engines need plaintext while running. Restrict access before writing any secret bytes.
    public static void WriteRuntime(string path,string text){using(var file=new FileStream(path,FileMode.Create,FileAccess.Write,FileShare.Read)){var security=new FileSecurity();security.SetAccessRuleProtection(true,false);security.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User,FileSystemRights.FullControl,AccessControlType.Allow));security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid,null),FileSystemRights.FullControl,AccessControlType.Allow));File.SetAccessControl(path,security);using(var writer=new StreamWriter(file,new UTF8Encoding(false)))writer.Write(text);}}
  }
  public sealed class ServerNode {
    public string Id {get;set;} public string Name {get;set;} public string Protocol {get;set;}
    public string Link {get;set;} public string Host {get;set;} public int Port {get;set;} public string Source {get;set;}
    public int Latency {get;set;} public bool Healthy {get;set;}
    public ServerNode(){Id=Guid.NewGuid().ToString("N");Name="";Protocol="";Link="";Host="";Port=443;Latency=-1;}
    public string TransportLabel {get{if(Protocol=="wireguard"||Protocol=="awg"||Protocol=="csqtt"||Protocol=="wdtt"||Protocol=="qwdtt")return Protocol.ToUpperInvariant();try{var u=new Uri(Link);var q=HttpUtility.ParseQueryString(u.Query);var transport=q["type"]??q["network"]??"tcp";var security=q["security"];return Protocol.ToUpperInvariant()+" · "+transport.ToUpperInvariant()+(String.IsNullOrEmpty(security)||security=="none"?"":" · "+security.ToUpperInvariant());}catch{return Protocol.ToUpperInvariant();}}}
  }
  public static class SubscriptionMerge {
    static string Identity(ServerNode node){try{var uri=new Uri(node.Link);var query=HttpUtility.ParseQueryString(uri.Query);return node.Protocol+"|"+node.Host.ToLowerInvariant()+"|"+node.Port+"|"+(query["type"]??query["network"]??"tcp");}catch{return node.Protocol+"|"+node.Host+"|"+node.Port;}}
    public static void Apply(ClientState state,List<ServerNode> imported,string source){
      var previous=state.Servers.ToList();
      foreach(var node in imported){
        var old=previous.FirstOrDefault(x=>x.Link==node.Link);
        if(old==null&&source!=null){var candidates=previous.Where(x=>x.Source==source&&Identity(x)==Identity(node)).ToArray();if(candidates.Length==1&&imported.Count(x=>Identity(x)==Identity(node))==1)old=candidates[0];}
        if(old!=null){node.Id=old.Id;node.Latency=old.Latency;node.Healthy=old.Healthy;}
        node.Source=source??(old==null?null:old.Source);
      }
      if(source!=null){state.Servers.RemoveAll(x=>x.Source==source);state.SubscriptionUrl=source;if(state.SubscriptionUrls==null)state.SubscriptionUrls=new List<string>();if(!state.SubscriptionUrls.Contains(source))state.SubscriptionUrls.Add(source);}
      foreach(var node in imported){state.Servers.RemoveAll(x=>x.Link==node.Link||x.Id==node.Id);state.Servers.Add(node);}
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
    public ExitDnsSettings Dns{get;set;}
    public bool? GameFilterTcp{get;set;} public bool? GameFilterUdp{get;set;}
    [System.Web.Script.Serialization.ScriptIgnore] public bool GameTcpEnabled{get{return GameFilterTcp??GameFilter;}}
    [System.Web.Script.Serialization.ScriptIgnore] public bool GameUdpEnabled{get{return GameFilterUdp??GameFilter;}}
    public string Id {get;set;} public string Name {get;set;} public string Text {get;set;}
    public string Target {get;set;} public bool Enabled {get;set;} public string SourceUrl {get;set;} public string ZapretProfileId{get;set;} public bool GameFilter{get;set;}
    public bool ExcludeRussia{get;set;}public bool ExcludeTorrents{get;set;}public string ExcludedText{get;set;}public string CustomText{get;set;}public bool InvertAddresses{get;set;}public string BuiltinPreset{get;set;}public string MatchMode{get;set;}public List<string> SourceUrls{get;set;}public List<string> PresetNames{get;set;}
    public RouteList(){Id=Guid.NewGuid().ToString("N");Name="Новый список";Text="";Target="proxy";Enabled=true;}
  }
  public static class RouteSources {
    public static string OwnerLabel(string owner){return owner=="vnenapravo7-source"?"7":owner=="itdoginfo"?"itdog":owner=="splify2"?"splify2":owner;}
    public static string SourceLabel(RouteList route){var urls=Urls(route);return urls.Length==0?"Вручную":String.Join(", ",urls.Select(url=>{Uri uri;if(!Uri.TryCreate(url,UriKind.Absolute,out uri))return "Неизвестно";var parts=uri.AbsolutePath.Trim('/').Split('/');return OwnerLabel((uri.Host=="raw.githubusercontent.com"||uri.Host=="github.com")&&parts.Length>0?parts[0]:uri.Host);}).Distinct());}
    public static string UpdatedText(RouteList route,string downloaded){string custom=route.CustomText;if(String.IsNullOrWhiteSpace(custom)&&(route.BuiltinPreset=="setup-service:discord"||route.BuiltinPreset=="service-constructor:discord"||route.BuiltinPreset=="setup-service:discord-voice"||route.BuiltinPreset=="service-constructor:discord-voice"))custom=ZapretChecks.Services.First(s=>s.Id=="discord").Domains;return OutsideRussiaPreset.Update(route,downloaded)+(String.IsNullOrWhiteSpace(custom)?"":"\n"+custom);}
    public static string[] Urls(RouteList route){return (route.SourceUrls??new List<string>()).Concat(String.IsNullOrWhiteSpace(route.SourceUrl)?new string[0]:new[]{route.SourceUrl}).Distinct(StringComparer.Ordinal).ToArray();}
    public static string Identity(RouteList route){return String.Join("\n",Urls(route));}
    public static RouteList Merge(IEnumerable<RouteList> routes){var selected=routes.ToArray();if(selected.Length<1)throw new InvalidOperationException("Выберите списки");var first=selected[0];if(selected.Any(r=>!String.Equals(new JavaScriptSerializer().Serialize(r.Dns),new JavaScriptSerializer().Serialize(first.Dns),StringComparison.Ordinal)||r.Target!=first.Target||r.ZapretProfileId!=first.ZapretProfileId||r.MatchMode!=first.MatchMode||r.Enabled!=first.Enabled||r.GameTcpEnabled!=first.GameTcpEnabled||r.GameUdpEnabled!=first.GameUdpEnabled||r.InvertAddresses!=first.InvertAddresses||r.BuiltinPreset!=first.BuiltinPreset||r.ExcludeRussia!=first.ExcludeRussia||r.ExcludeTorrents!=first.ExcludeTorrents||!String.Equals(r.ExcludedText??"",first.ExcludedText??"",StringComparison.Ordinal)))throw new InvalidOperationException("Объединять можно только правила с одинаковым выходом, профилем, условием и состоянием.");
      var result=new RouteList{Dns=first.Dns,Name=String.Join(" + ",selected.Select(r=>r.Name).Distinct()),Target=first.Target,ZapretProfileId=first.ZapretProfileId,MatchMode=first.MatchMode,Enabled=first.Enabled,GameFilterTcp=first.GameTcpEnabled,GameFilterUdp=first.GameUdpEnabled,InvertAddresses=first.InvertAddresses,BuiltinPreset=first.BuiltinPreset,ExcludeRussia=first.ExcludeRussia,ExcludeTorrents=first.ExcludeTorrents,ExcludedText=first.ExcludedText,CustomText=String.Join("\n",selected.Select(r=>r.CustomText).Where(t=>!String.IsNullOrWhiteSpace(t)).Distinct()),Text=String.Join("\n",selected.SelectMany(r=>(r.Text??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)).Distinct(StringComparer.OrdinalIgnoreCase)),SourceUrls=selected.SelectMany(Urls).Distinct().ToList(),PresetNames=selected.SelectMany(r=>r.PresetNames??new List<string>()).Distinct().ToList()};if(result.SourceUrls.Count==1)result.SourceUrl=result.SourceUrls[0];return result;
    }
  }
  public sealed class ClientState {
    public List<string> ExcludedApplications{get;set;}
    public List<SteamGameTemplate> SteamGameTemplates{get;set;}
    public List<RouteList> UserGameTemplates{get;set;}
    public Dictionary<string,string> GlobalHotkeys{get;set;}
    public List<ExitDnsSettings> ExitDns{get;set;}
    public int TelegramPort{get;set;}public bool SetupSeen{get;set;}public List<string> SetupServices{get;set;}
    public List<ServerNode> Servers {get;set;} public List<RouteList> Lists {get;set;}
    public string SelectedServerId {get;set;} public string SubscriptionUrl {get;set;} public List<string> SubscriptionUrls {get;set;}
    public bool SimpleDesign{get;set;}public bool AdvancedMode{get;set;}public bool SettingsModeChosen{get;set;}
    public bool IndependentWarpOutputs{get;set;}public bool VpnEnabled{get;set;}public bool WarpEnabled{get;set;}
    public bool ByeTubeEnabled{get;set;}public string ByeTubeStrategy{get;set;}
    public string Mode {get;set;} public bool AutoSelect {get;set;}
    public string DnsProvider {get;set;} public string DnsProtocol {get;set;} public string DnsCustom {get;set;}
    public List<string> RecentServerIds {get;set;}
    public ZapretSettings Zapret{get;set;}
    public List<ZapretProfile> ZapretProfiles{get;set;} public string SelectedZapretProfileId{get;set;}
    public bool ZapretRoutingMigrated{get;set;}
    public ClientState(){VpnEnabled=true;Servers=new List<ServerNode>();Lists=new List<RouteList>();RecentServerIds=new List<string>();SubscriptionUrls=new List<string>();SelectedServerId="";SubscriptionUrl="";Mode="tun";AutoSelect=true;DnsProvider="Cloudflare";DnsProtocol="DoH";DnsCustom="";}
    public static ClientState Initial(){var s=new ClientState();s.Lists.Add(new RouteList{Name="Telegram",Text="app:Telegram.exe\ntelegram.org\n*.telegram.org\nt.me",Target="tgws"});s.Lists.Add(new RouteList{Name="YouTube",Text="youtube.com\n*.youtube.com\ngooglevideo.com\n*.googlevideo.com",Target="proxy"});return s;}
  }
  public sealed class StateStore {
    public readonly string Root; public readonly string FilePath;
    readonly JavaScriptSerializer json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};
    public StateStore(string root=null){Root=Path.GetFullPath(root??Environment.GetEnvironmentVariable("MCRF_DATA_DIR")??Environment.GetEnvironmentVariable("SPLIFY_WIN_DATA_DIR")??ProfileStorage.DefaultRoot());Directory.CreateDirectory(Root);FilePath=Path.Combine(Root,"client.json");}
    public ClientState Load(){
      try{
        if(!File.Exists(FilePath))return ClientState.Initial();
        string stored=File.ReadAllText(FilePath,Encoding.UTF8);var s=json.Deserialize<ClientState>(Decode(stored));
        if(s==null)throw new InvalidDataException("Пустой файл настроек");
        if(s.Servers==null)s.Servers=new List<ServerNode>();if(s.Lists==null)s.Lists=new List<RouteList>();s.Servers.RemoveAll(x=>x==null);s.Lists.RemoveAll(x=>x==null);PresetStorage.NormalizeNames(s.Lists);
        foreach(var node in s.Servers.Where(x=>x.Protocol=="csqtt"||x.Protocol=="wdtt"||x.Protocol=="qwdtt")){var parsed=Links.Read(node.Link??"").FirstOrDefault();if(parsed!=null){node.Host=parsed.Host;node.Port=parsed.Port;if(node.Name=="connect"||String.IsNullOrWhiteSpace(node.Name))node.Name=parsed.Name;}}
        if(String.IsNullOrEmpty(s.DnsProvider)||!DnsOptions.Providers.Contains(s.DnsProvider))s.DnsProvider="Cloudflare";if(String.IsNullOrEmpty(s.DnsProtocol)||!DnsOptions.Protocols(s.DnsProvider).Contains(s.DnsProtocol))s.DnsProtocol="DoH";
        if(s.TelegramPort<1024||s.TelegramPort>65535)s.TelegramPort=TelegramBridge.DefaultPort;
        if(!stored.StartsWith(ProtectedPrefix,StringComparison.Ordinal))Save(s);
        return s;
      }catch(Exception ex){readFailed=true;throw new IOException("Настройки не прочитаны; исходный файл не изменён. "+ex.Message,ex);}
    }
    const string ProtectedPrefix="MCRF-DPAPI-1\n";
    bool readFailed;
    string Decode(string text){if(!text.StartsWith(ProtectedPrefix,StringComparison.Ordinal))return text;try{return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(text.Substring(ProtectedPrefix.Length)),Encoding.UTF8.GetBytes("MCRF settings v1"),DataProtectionScope.CurrentUser));}catch(Exception ex){readFailed=true;throw new IOException("Не удалось открыть защищённые настройки. Войдите под той же учётной записью Windows. Файл сохранён и не будет перезаписан.",ex);}}
    string Protect(string text){return ProtectedPrefix+Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(text),Encoding.UTF8.GetBytes("MCRF settings v1"),DataProtectionScope.CurrentUser));}
    public void Save(ClientState s){if(readFailed)throw new IOException("Сохранение запрещено: сначала восстановите чтение существующих настроек.");lock(json){var tmp=FilePath+"."+Guid.NewGuid().ToString("N")+".tmp";try{string encrypted=Protect(json.Serialize(s));File.WriteAllText(tmp,encrypted,new UTF8Encoding(false));if(File.Exists(FilePath)){string previous=File.ReadAllText(FilePath,Encoding.UTF8);if(previous.StartsWith(ProtectedPrefix,StringComparison.Ordinal)){File.Replace(tmp,FilePath,FilePath+".protected-backup");}else{File.WriteAllText(FilePath+".protected-backup",Protect(previous),new UTF8Encoding(false));File.Replace(tmp,FilePath,null);}}else File.Move(tmp,FilePath);}finally{if(File.Exists(tmp))File.Delete(tmp);}}}
  }
  public static class Links {
    static readonly HashSet<string> Accepted=new HashSet<string>(new[]{"vless","trojan","hysteria","hysteria2","hy2","tuic","ss","socks5","socks","http","https","anytls","vmess","naive","naive+https","ssh","awg","wg","wireguard","csqtt","wdtt","qwdtt","openflux"},StringComparer.OrdinalIgnoreCase);
    static readonly string[] AwgKeys={"Jc","Jmin","Jmax","S1","S2","S3","S4","H1","H2","H3","H4","I1","I2","I3","I4","I5","HeaderProtectionKey","ContentPaddingAddition","RekeyAfterTime","RekeyTimeout","RejectAfterTime","KeepaliveTimeout","MaxHandshakeAttempts","RandomTrailers","DisableCookies"};
    static string DecodeBase64(string value){var normalized=value.Replace('-','+').Replace('_','/');return Encoding.UTF8.GetString(Convert.FromBase64String(normalized.PadRight((normalized.Length+3)/4*4,'=')));}
    static string NormalizeShadowsocks(string link){var body=link.Substring(5);var fragment="";var hash=body.IndexOf('#');if(hash>=0){fragment=body.Substring(hash);body=body.Substring(0,hash);}if(body.Contains("@"))return link;try{var decoded=DecodeBase64(body);if(decoded.Contains("@")&&decoded.Contains(":"))return "ss://"+decoded+fragment;}catch{}return link;}
    public static bool IsAwg(Dictionary<string,string> fields){return AwgKeys.Any(fields.ContainsKey);}
    public static Dictionary<string,string> WireGuardFields(string text){var fields=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);string section="";foreach(var line in text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){var trimmed=line.Trim();if(trimmed.StartsWith("#")||trimmed.StartsWith(";"))continue;if(trimmed.StartsWith("[")&&trimmed.EndsWith("]")){section=trimmed.Substring(1,trimmed.Length-2);continue;}if(!trimmed.Contains("="))continue;var parts=trimmed.Split(new[]{'='},2);var key=parts[0].Trim();if(section.Equals("Peer",StringComparison.OrdinalIgnoreCase)||key.Equals("PrivateKey",StringComparison.OrdinalIgnoreCase)||key.Equals("Address",StringComparison.OrdinalIgnoreCase)||key.Equals("MTU",StringComparison.OrdinalIgnoreCase)||AwgKeys.Any(x=>x.Equals(key,StringComparison.OrdinalIgnoreCase)))fields[key]=parts[1].Trim();else if(section.Equals("Interface",StringComparison.OrdinalIgnoreCase))fields[key]=parts[1].Trim();}return fields;}
    public static List<ServerNode> Read(string input){var text=input.Trim();if(text.Length==0)return new List<ServerNode>();if(text.StartsWith("[Interface]",StringComparison.OrdinalIgnoreCase)){var fields=WireGuardFields(text);if(!fields.ContainsKey("PrivateKey")||!fields.ContainsKey("PublicKey")||!fields.ContainsKey("Endpoint")||!fields.ContainsKey("Address"))return new List<ServerNode>();var endpoint=fields["Endpoint"];var split=endpoint.LastIndexOf(':');if(split<1)return new List<ServerNode>();int port;if(!Int32.TryParse(endpoint.Substring(split+1),out port))return new List<ServerNode>();var host=endpoint.Substring(0,split).Trim('[',']');var awg=IsAwg(fields);return new List<ServerNode>{new ServerNode{Name=awg?"AmneziaWG":"WireGuard / WARP",Protocol=awg?"awg":"wireguard",Host=host,Port=port,Link=text,Healthy=true,Latency=-1}};}if(!text.Contains("://")){try{var b=text.Replace('-','+').Replace('_','/');b=b.PadRight((b.Length+3)/4*4,'=');var decoded=Encoding.UTF8.GetString(Convert.FromBase64String(b));if(decoded.Contains("://"))text=decoded;}catch{}}
      var result=new List<ServerNode>();foreach(var raw in text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){var line=raw.Trim();if(line.StartsWith("#")||line.Length==0)continue;try{var scheme=line.Substring(0,line.IndexOf("://",StringComparison.Ordinal));if(!Accepted.Contains(scheme))continue;if(scheme.Equals("ss",StringComparison.OrdinalIgnoreCase))line=NormalizeShadowsocks(line);
        if(scheme.Equals("vmess",StringComparison.OrdinalIgnoreCase)){var encoded=line.Substring(8).Split('#')[0].Replace('-','+').Replace('_','/');encoded=encoded.PadRight((encoded.Length+3)/4*4,'=');var data=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Convert.FromBase64String(encoded)));var host=Convert.ToString(data["add"]);result.Add(new ServerNode{Name=data.ContainsKey("ps")?Convert.ToString(data["ps"]):host,Protocol="vmess",Host=host,Port=Convert.ToInt32(data["port"]),Link=line});continue;}
        if(scheme.Equals("openflux",StringComparison.OrdinalIgnoreCase)){if(!line.StartsWith("openflux://v1/",StringComparison.OrdinalIgnoreCase))continue;result.Add(new ServerNode{Name="OpenFlux",Protocol="openflux",Host="локальный туннель",Port=0,Link=line,Healthy=true});continue;}
        if(scheme.Equals("csqtt",StringComparison.OrdinalIgnoreCase)||scheme.Equals("wdtt",StringComparison.OrdinalIgnoreCase)||scheme.Equals("qwdtt",StringComparison.OrdinalIgnoreCase)){var profile=TurnProfile.Parse(line);result.Add(new ServerNode{Name=profile.Name,Protocol=profile.Protocol,Host=profile.Host,Port=profile.Port,Link=line});continue;}
        var u=new Uri(line);var name=Uri.UnescapeDataString(u.Fragment.TrimStart('#'));
        if(scheme.Equals("awg",StringComparison.OrdinalIgnoreCase)||scheme.Equals("wg",StringComparison.OrdinalIgnoreCase)||scheme.Equals("wireguard",StringComparison.OrdinalIgnoreCase)){var q=HttpUtility.ParseQueryString(u.Query);var key=q["publickey"]??q["public_key"];var address=q["address"];if(String.IsNullOrEmpty(key)||String.IsNullOrEmpty(address))continue;var conf=new StringBuilder("[Interface]\nPrivateKey = ").Append(Uri.UnescapeDataString(u.UserInfo)).Append("\nAddress = ").Append(address).Append('\n');foreach(var field in new[]{"MTU","DNS"}.Concat(AwgKeys)){var value=q[field];if(!String.IsNullOrEmpty(value))conf.Append(field).Append(" = ").Append(value).Append('\n');}conf.Append("[Peer]\nPublicKey = ").Append(key).Append("\nEndpoint = ").Append(u.Host).Append(':').Append(u.Port).Append("\nAllowedIPs = ").Append(q["allowedips"]??"0.0.0.0/0, ::/0").Append('\n');if(!String.IsNullOrEmpty(q["presharedkey"]))conf.Append("PresharedKey = ").Append(q["presharedkey"]).Append('\n');if(!String.IsNullOrEmpty(q["persistentkeepalive"]))conf.Append("PersistentKeepalive = ").Append(q["persistentkeepalive"]).Append('\n');var awg=scheme.Equals("awg",StringComparison.OrdinalIgnoreCase)||IsAwg(WireGuardFields(conf.ToString()));result.Add(new ServerNode{Name=name.Length>0?name:awg?"AmneziaWG":"WireGuard / WARP",Protocol=awg?"awg":"wireguard",Host=u.Host,Port=u.Port,Link=conf.ToString(),Healthy=true});continue;}
        result.Add(new ServerNode{Name=name.Length>0?name:u.Host,Protocol=scheme.Equals("hy2",StringComparison.OrdinalIgnoreCase)?"hysteria2":scheme.Equals("naive+https",StringComparison.OrdinalIgnoreCase)?"naive":scheme.ToLowerInvariant(),Host=u.Host,Port=u.IsDefaultPort?(scheme.Equals("ssh",StringComparison.OrdinalIgnoreCase)?22:443):u.Port,Link=line});
      }catch{}}return result;}
  }
}
