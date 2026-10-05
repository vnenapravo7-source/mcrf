using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace SplifyWin {
  public enum ServiceAccess {Unknown, Available, Unavailable}
  public sealed class ZapretService {
    public string Id,Name,Group,Marker,Domains;public bool Primary;public string[] Urls;
    public string CheckName{get{return Id=="rutor"?"Rutor+Rutracker":Name;}}
  }
  public sealed class ZapretEndpointResult {
public string Url{get;set;}public ServiceAccess Access{get;set;}public string Detail{get;set;}public int Milliseconds{get;set;}
    public List<HostsCandidate> Hosts{get;set;}
  }
  public sealed class ZapretServiceResult {
    public long? SuccessfulMilliseconds{get;set;}
public string PingQuality{get;set;}public string InterfaceStrategyId{get;set;}
    public List<HostsCandidate> Hosts{get;set;}
    public int SuccessfulRequests{get;set;}
    public string Id{get;set;}public ServiceAccess Access{get;set;}public string Detail{get;set;}public int Milliseconds{get;set;}
  }
  public sealed class ZapretCheckRow {
    public string StrategyId{get;set;}public string Name{get;set;}public string Family{get;set;}public string Error{get;set;}
    public DateTime CheckedAt{get;set;}public List<ZapretServiceResult> Services{get;set;}
    public bool Checking{get;set;}
    public int StartupMilliseconds{get;set;}public int PrimaryMilliseconds{get;set;}public int AdditionalMilliseconds{get;set;}public int TotalMilliseconds{get;set;}
    public ZapretCheckRow(){Services=new List<ZapretServiceResult>();}
    public long? SuccessfulMilliseconds{get{return !Services.Any(x=>x.SuccessfulMilliseconds>0)?(long?)null:Services.Where(x=>x.SuccessfulMilliseconds.HasValue).Sum(x=>x.SuccessfulMilliseconds.Value);}}
    public ServiceAccess Access(string id){var item=Services.FirstOrDefault(x=>x.Id==id);return item==null?ServiceAccess.Unknown:item.Access;}
    public bool PrimaryPassed{get{return Access("youtube")==ServiceAccess.Available&&Access("discord")==ServiceAccess.Available;}}
    public int AdditionalPassed{get{return Services.Count(x=>x.Id!="youtube"&&x.Id!="discord"&&x.Access==ServiceAccess.Available);}}
    public int PrimaryCount{get{return Services.Count(x=>(x.Id=="youtube"||x.Id=="discord")&&x.Access==ServiceAccess.Available);}}
  }
  public sealed class ZapretCheckReport {
    public int Version{get;set;}public int MediaProbeRevision{get;set;}public DateTime StartedAt{get;set;}public DateTime FinishedAt{get;set;}public bool Complete{get;set;}
    public List<string> ServiceIds{get;set;}
    public List<string> WizardServiceIds{get;set;}
    public int Planned{get;set;}public string StopReason{get;set;}public ZapretSettings Settings{get;set;}public List<ZapretCheckRow> Rows{get;set;}
    public string LastAppliedId{get;set;}public DateTime LastAppliedAt{get;set;}
    public string ProfileId{get;set;}public ZapretSettings ApplySettings{get;set;}
    public List<ServiceManualResult> ManualResults{get;set;}
    public List<SetupAssignment> WizardAssignments{get;set;}
    public bool WizardVoiceCompleted{get;set;}
    public List<string> WizardManualCompleted{get;set;}
    public bool WizardSkipHosts{get;set;}
    public ZapretCheckReport(){Version=1;Rows=new List<ZapretCheckRow>();}
  }
  public static class ZapretChecks {
    static readonly object saveLock=new object();
    public static bool WarpPreferred(string id){return id=="whatsapp";}
    public static bool CanCheckZapret(ZapretService service){return service!=null&&service.Id!="spotify"&&service.Id!="rutracker"&&service.Id!="pornhub"&&!WarpPreferred(service.Id);}
    public static readonly ZapretService[] Services=new[]{
      new ZapretService{Id="youtube",Name="YouTube",Group="Основные",Primary=true,Marker="",Domains="youtube.com\ngooglevideo.com\nytimg.com\nyoutubei.googleapis.com\nggpht.com\ngoogleusercontent.com\ngstatic.com",Urls=new[]{VideoProbe.Target}},
      new ZapretService{Id="discord",Name="Discord: интерфейс",Group="Основные",Primary=true,Marker="gateway.discord.gg",Domains="discord.com\ndiscord.gg\ndiscord.media\ndiscordapp.com\ndiscordapp.net",Urls=new[]{"https://discord.com/api/v10/gateway",DiscordInterfaceProbe.Target}},
      new ZapretService{Id="chatgpt",Name="ChatGPT",Group="AI",Marker="ChatGPT",Domains="chatgpt.com\nopenai.com\noaistatic.com\noaiusercontent.com",Urls=new[]{"https://chatgpt.com/"}},
      new ZapretService{Id="facebook",Name="Facebook",Group="Meta",Marker="Facebook",Domains="facebook.com\nfbcdn.net\nfbsbx.com",Urls=new[]{"https://www.facebook.com/"}},
      new ZapretService{Id="instagram",Name="Instagram",Group="Meta",Marker="Instagram",Domains="instagram.com\ncdninstagram.com",Urls=new[]{"https://www.instagram.com/"}},
      new ZapretService{Id="rutor",Name="Rutor",Group="Другое",Marker="rutor",Domains="rutor.info",Urls=new[]{"https://rutor.info/"}},
      new ZapretService{Id="rutracker",Name="RuTracker",Group="Другое",Marker="rutracker",Domains="rutracker.cc\nrutracker.net\nrutracker.org\nrutracker.ru\nrutracker.wiki\nrutrk.org\nt-ru.org\n104.21.0.111\n104.21.1.151\n104.21.112.1\n104.21.16.1\n104.21.32.1\n104.21.32.127\n104.21.32.39\n104.21.48.1\n104.21.50.150\n104.21.64.1\n104.21.7.164\n104.21.80.1\n104.21.96.1\n162.159.140.104\n162.159.140.160\n172.66.0.102\n172.66.0.158\n172.67.128.13\n172.67.136.246\n172.67.151.249\n172.67.163.237\n172.67.182.196\n172.67.185.253\n185.81.128.108\n188.114.96.0\n188.114.96.1\n188.114.96.10\n188.114.96.11\n188.114.96.12\n188.114.96.2\n188.114.96.3\n188.114.96.7\n188.114.96.8\n188.114.96.9\n188.114.97.0\n188.114.97.1\n188.114.97.10\n188.114.97.11\n188.114.97.12\n188.114.97.2\n188.114.97.3\n188.114.97.7\n188.114.97.8\n188.114.97.9",Urls=new[]{"https://rutracker.org/forum/index.php"}},
      new ZapretService{Id="pornhub",Name="Pornhub",Group="Другое",Marker="pornhub",Domains="pornhub.org\npornhub.com\nphncdn.com",Urls=new[]{VideoProbe.PornhubTarget}},
      new ZapretService{Id="spotify",Name="Spotify",Group="Другое",Marker="Spotify",Domains="spotify.com\nscdn.co",Urls=new[]{"https://open.spotify.com/"}}
    };
    public static readonly string[] DefaultServices={"youtube","discord","instagram","rutor"};
    public static ZapretService[] Selected(IEnumerable<string> ids){var set=new HashSet<string>(ids??DefaultServices,StringComparer.OrdinalIgnoreCase);var selected=Services.Where(x=>set.Contains(x.Id)).ToArray();if(selected.Length==0)throw new InvalidOperationException("Выберите хотя бы один сервис для проверки.");return selected;}
    public static string Scope{get{return String.Join("\n",Services.SelectMany(x=>x.Domains.Split('\n')).Distinct(StringComparer.OrdinalIgnoreCase));}}
    public static string Targets{get{return String.Join("\n",Services.SelectMany(x=>x.Urls));}}
    public static ZapretStrategy[] SelectFamilies(IEnumerable<ZapretStrategy> choices,IEnumerable<string> families){var selected=new HashSet<string>(families??new[]{"Flowseal","V","YouTube"},StringComparer.OrdinalIgnoreCase);if(selected.Count==0)throw new InvalidOperationException("Отметьте хотя бы один набор стратегий");if(selected.Any(x=>x!="Flowseal"&&x!="V"&&x!="YouTube"))throw new InvalidOperationException("Неизвестный набор стратегий");return choices.Where(x=>x.Available&&selected.Contains(x.Family)).ToArray();}
    public static ZapretSettings CopySettings(ZapretSettings settings){var json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};var copy=json.Deserialize<ZapretSettings>(json.Serialize(settings));if(settings.ScopeRules!=null)copy.ScopeRules=json.Deserialize<List<RouteList>>(json.Serialize(settings.ScopeRules));return copy;}
    public static ZapretCheckRow[] Rank(IEnumerable<ZapretCheckRow> rows){return rows.Where(x=>!String.IsNullOrEmpty(x.StrategyId)).OrderByDescending(x=>x.PrimaryPassed).ThenByDescending(x=>x.AdditionalPassed).ThenBy(x=>x.SuccessfulMilliseconds??Int64.MaxValue).ThenBy(x=>x.Name,StringComparer.OrdinalIgnoreCase).ToArray();}
    public static string Label(ServiceAccess access){return access==ServiceAccess.Available?"Да":access==ServiceAccess.Unavailable?"Нет":"?";}
    public static ZapretEndpointResult Classify(string url,int exitCode,string output,string error,int elapsed){
      var result=new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unknown,Milliseconds=elapsed};
      int footer=(output??"").LastIndexOf("\nMCRF_HTTP:",StringComparison.Ordinal);int status=0;
      if(footer>=0)Int32.TryParse(output.Substring(footer+11).Trim(),out status);
      string body=footer>=0?output.Substring(0,footer):output??"";
      string visible=System.Text.RegularExpressions.Regex.Replace(body,@"(?is)<script\b[^>]*>.*?(?:</script>|$)|<style\b[^>]*>.*?(?:</style>|$)|<!--.*?-->","");
      if(new[]{6,7,28,35,51,52,56,60}.Contains(exitCode)){
        result.Access=ServiceAccess.Unavailable;result.Detail=exitCode==6?"Не разрешилось имя сервера":exitCode==28?"Истекло время ожидания":exitCode==60||exitCode==51?"Не прошла проверка TLS-сертификата":"Соединение не установлено или оборвалось (curl "+exitCode+")";return result;
      }
      if(status==451){result.Access=ServiceAccess.Unavailable;result.Detail="HTTP 451 — доступ ограничен сервером";return result;}
      if(status==401||status==403||status==429||body.IndexOf("cf-chl-",StringComparison.OrdinalIgnoreCase)>=0||body.IndexOf("challenge-platform",StringComparison.OrdinalIgnoreCase)>=0||visible.IndexOf("captcha",StringComparison.OrdinalIgnoreCase)>=0){result.Detail="HTTP "+status+" / авторизация или защита от ботов — доступность не подтверждена";return result;}
      if(exitCode!=0&&!(exitCode==63&&status==200&&body.Length>=131072)||status!=200&&status!=206&&status!=204){result.Detail="Неоднозначный ответ (HTTP "+status+", curl "+exitCode+")";return result;}
      var service=Services.FirstOrDefault(x=>x.Urls.Contains(url));string host=new Uri(url).Host;
      bool content=host=="i.ytimg.com"?body.Length>100:host=="www.youtube.com"?body.Contains("ytInitialData")||body.Contains("INNERTUBE_API_KEY")||body.IndexOf("<title>YouTube",StringComparison.OrdinalIgnoreCase)>=0||body.Contains("ytcfg")||body.Contains("ytInitialPlayerResponse"):service!=null?body.IndexOf(service.Marker,StringComparison.OrdinalIgnoreCase)>=0:body.Length>0;
      if(status==204)content=false;
      if(service!=null&&service.Id=="discord")content=System.Text.RegularExpressions.Regex.IsMatch(body,@"\x22url\x22\s*:\s*\x22wss:(?:\\/|/){2}gateway\.discord\.gg(?:/|\x22)",System.Text.RegularExpressions.RegexOptions.IgnoreCase);
      if(service!=null&&service.Id!="discord"&&host!="i.ytimg.com")content=content&&body.Length>=256&&(body.IndexOf("<html",StringComparison.OrdinalIgnoreCase)>=0||body.IndexOf("<!doctype",StringComparison.OrdinalIgnoreCase)>=0);
      if(new[]{"access denied","request blocked","доступ ограничен","доступ заблокирован","доступ запрещён"}.Any(marker=>body.IndexOf(marker,StringComparison.OrdinalIgnoreCase)>=0)){result.Detail="Получена заглушка ограничения доступа";return result;}
      if(content&&(host=="www.youtube.com"||host=="i.ytimg.com"||service!=null&&(service.Id=="pornhub"||service.Id=="instagram"||service.Id=="chatgpt"))){result.Detail=host=="www.youtube.com"||host=="i.ytimg.com"?"Страница/обложка доступны; загрузка видео не проверена — работа YouTube не подтверждена":service!=null&&service.Id=="pornhub"?"Веб-страница доступна; воспроизведение видео не проверено — работа Pornhub не подтверждена":"Веб-страница доступна; лента, медиа и вход не проверены — работа сервиса не подтверждена";return result;}
      if(!content){result.Detail="Ответ получен, но содержимое сервиса не подтверждено";return result;}
      result.Access=ServiceAccess.Available;result.Detail="HTTP "+status+"; содержимое подтверждено";return result;
    }
    public static ZapretServiceResult Combine(ZapretService service,ZapretEndpointResult[] first,ZapretEndpointResult[] second){
bool video=service.Urls.Length==1&&(service.Urls[0]==VideoProbe.Target||service.Urls[0]==VideoProbe.PornhubTarget||ServiceBrowserProbe.Supports(service.Urls[0]))&&second.Length==0;
      if(first.Length!=service.Urls.Length||!video&&second.Length!=first.Length)throw new ArgumentException("Несовпадающее число проверок");
      bool yes=first.Concat(second).All(x=>x.Access==ServiceAccess.Available);
      bool no=first.Where((x,index)=>x.Access==ServiceAccess.Unavailable&&(video||second[index].Access==ServiceAccess.Unavailable)).Any();
return new ZapretServiceResult{Id=service.Id,Hosts=first.SelectMany(x=>x.Hosts??new List<HostsCandidate>()).ToList(),Access=yes?ServiceAccess.Available:no?ServiceAccess.Unavailable:ServiceAccess.Unknown,SuccessfulRequests=first.Concat(second).Count(x=>x.Access==ServiceAccess.Available),SuccessfulMilliseconds=first.Concat(second).Where(x=>x.Access==ServiceAccess.Available).Sum(x=>(long)Math.Max(0,x.Milliseconds)),Milliseconds=first.Concat(second).Sum(x=>x.Milliseconds)/Math.Max(1,first.Length+second.Length),Detail=String.Join(Environment.NewLine,service.Urls.Select((url,index)=>url+Environment.NewLine+"1: "+first[index].Detail+Environment.NewLine+(video?(ServiceBrowserProbe.Supports(url)?"Hosts + Zapret: один браузерный тест":"Видео: один полноценный тест воспроизведения"):"2: "+second[index].Detail)))};
    }
    public static void Save(string root,ZapretCheckReport report){lock(saveLock){var serializer=new JavaScriptSerializer{MaxJsonLength=16*1024*1024};string file=Path.Combine(root,"zapret-results.json"),temp=file+"."+Guid.NewGuid().ToString("N")+".tmp";Directory.CreateDirectory(root);try{File.WriteAllText(temp,serializer.Serialize(report),new UTF8Encoding(false));if(File.Exists(file))File.Replace(temp,file,file+".bak");else File.Move(temp,file);}finally{if(File.Exists(temp))File.Delete(temp);}}}
    public static ZapretCheckReport Load(string root){string file=Path.Combine(root,"zapret-results.json");if(!File.Exists(file))return null;var report=new JavaScriptSerializer{MaxJsonLength=16*1024*1024}.Deserialize<ZapretCheckReport>(File.ReadAllText(file));if(report==null||report.Version!=1||report.Rows==null||report.Settings==null)throw new FormatException("Формат сохранённых результатов Zapret не поддерживается");return report;}
  }
  public sealed partial class ZapretRuntime {
    readonly NetworkCore hostsCore;
    async Task<List<HostsCandidate>> PrepareScanHosts(string service,CancellationToken token,Action<string> progress){
      string source=service=="chatgpt"?HostsCandidates.MalwSource:HostsRepair.Source;progress(service=="chatgpt"?"ChatGPT: hosts + Zapret через стороннее SNI-реле; TLS проверяется":"Instagram: hosts + Zapret, подбираем адреса один раз");
      var download=hostsCore.DownloadTextAsync(source);if(await Task.WhenAny(download,Task.Delay(6000,token))!=download){token.ThrowIfCancellationRequested();throw new TimeoutException("Источник hosts не ответил за 6 с");}string text=await download;token.ThrowIfCancellationRequested();return HostsCandidates.SelectMappings(service=="chatgpt"?HostsCandidates.ParseLines(text,service,source):HostsCandidates.ParseScript(text,service));
    }
    async Task<ZapretEndpointResult> ProbeService(string target,CancellationToken cancellation){
      if(target==DiscordInterfaceProbe.Target)return await DiscordInterfaceProbe.Run(false,cancellation);
      if(ServiceBrowserProbe.Supports(target))return await ServiceBrowserProbe.Run(Path.GetDirectoryName(curl),target,null,cancellation);
      var args="--silent --show-error --location --max-redirs 3 --proto =https --proto-redir =https --http1.1 --compressed --dump-header - --header \"Connection: close\" --user-agent \"Mozilla/5.0\" --noproxy \"*\" --connect-timeout 3 --max-time 5 --cacert "+Quote(cert)+" --write-out \"\\nMCRF_HTTP:%{http_code}\" "+Quote(target);
      return await BoundedProbe.Run(curl,args,target,cancellation);
    }
    public async Task Scan(ZapretCheckReport report,IEnumerable<ZapretStrategy> candidates,CancellationToken cancellation,Action<string> progress,Action<ZapretCheckReport> save){
      cancellation.ThrowIfCancellationRequested();if(!Administrator())throw new UnauthorizedAccessException("Для проверки Zapret нужны права администратора Windows.");
      TestTargets(report.Settings);var choices=candidates.Where(x=>x.Available).ToArray();if(choices.Length==0)throw new InvalidOperationException("Нет доступных стратегий");
      if(Interlocked.CompareExchange(ref operation,1,0)!=0)throw new InvalidOperationException("Проверка Zapret уже выполняется");
try{var preparation=Stopwatch.StartNew();await CheckEngine(cancellation);Record("Подготовка Zapret: "+preparation.ElapsedMilliseconds+" мс");if(ZapretChecks.Selected(report.Settings.CheckServices).Any(s=>s.Id=="discord")){progress("Discord: читаем локальные домены и IP…");report.Settings.DiscordScopeText=await DiscordLists.Load(hostsCore.DownloadTextAsync);cancellation.ThrowIfCancellationRequested();report.Settings.ScopeText+="\n"+report.Settings.DiscordScopeText;if(report.Settings.ScopeRules!=null)report.Settings.ScopeRules.Add(new RouteList{Text=report.Settings.DiscordScopeText,MatchMode="addresses",Target="zapret"});if(report.ApplySettings!=null)report.ApplySettings.DiscordScopeText=report.Settings.DiscordScopeText;}if(ZapretChecks.Selected(report.Settings.CheckServices).Any(s=>s.Id=="rutor"||s.Id=="rutracker")){progress("Rutor+RuTracker: загружаем домены и IP…");string trackerScope=await TrackerLists.Load(hostsCore.DownloadTextAsync);TrackerLists.Use(trackerScope);report.Settings.ScopeText+="\n"+trackerScope;if(report.Settings.ScopeRules!=null)report.Settings.ScopeRules.Add(new RouteList{Text=trackerScope,MatchMode="addresses",Target="zapret"});}foreach(var service in ZapretChecks.Selected(report.Settings.CheckServices).Where(s=>s.Id!="discord"&&s.Id!="rutor"&&s.Id!="rutracker")){service.Domains=ServicePresetRoutes.LocalScope(hostsCore,service);report.Settings.ScopeText+="\n"+service.Domains;if(report.Settings.ScopeRules!=null)report.Settings.ScopeRules.Add(new RouteList{Text=service.Domains,MatchMode="addresses",Target="zapret"});}var mappings=new Dictionary<string,List<HostsCandidate>>();var errors=new Dictionary<string,string>();var wanted=ZapretChecks.Selected(report.Settings.CheckServices).Where(s=>s.Id=="instagram"||s.Id=="chatgpt").ToArray();var prepareJobs=wanted.Select(async service=>{try{var prepared=await PrepareScanHosts(service.Id,cancellation,progress);lock(mappings)mappings[service.Id]=prepared;}catch(OperationCanceledException){throw;}catch(Exception ex){lock(mappings){errors[service.Id]=JournalStyle.Redact(ex.Message);mappings[service.Id]=new List<HostsCandidate>();}}}).ToArray();await Task.WhenAll(prepareJobs);bool active=false;
      using(var video=new VideoProbe(curl,"--noproxy \"*\" --cacert "+Quote(cert))){Func<ZapretSettings,ZapretStrategy,CancellationToken,Task> startMapped=async(settings,strategy,ct)=>{active=true;await Start(HostsCandidates.WithScope(settings,mappings.Values.SelectMany(h=>h)),strategy,ct);};Func<string,CancellationToken,Task<ZapretEndpointResult>> probeMapped=async(target,ct)=>{if(!ServiceBrowserProbe.Supports(target))return target==VideoProbe.Target||target==VideoProbe.PornhubTarget?await video.RunMediaFor(target,ct):await ProbeService(target,ct);string id=target.Contains("chatgpt")?"chatgpt":"instagram";List<HostsCandidate> hosts;if(!active)return new ZapretEndpointResult{Url=target,Access=ServiceAccess.Unknown,Detail="Комбинация hosts + Zapret проверяется на строках стратегий, не без обхода."};if(!mappings.TryGetValue(id,out hosts)||hosts.Count==0)return new ZapretEndpointResult{Url=target,Access=ServiceAccess.Unknown,Detail="Hosts + Zapret: нет адресов для проверки. "+(errors.ContainsKey(id)?errors[id]:"Источник не содержит нужных доменов")};var measured=await ServiceBrowserProbe.Run(root,target,null,ct,hosts,HostsCandidates.StrategyBudgetMs);measured.Hosts=hosts;measured.Detail="Hosts + Zapret · "+hosts.Count+" доменов · "+measured.Detail;return measured;};var runner=new ZapretCheckRunner(startMapped,()=>{active=false;Stop();},probeMapped,Record);await runner.Run(report,choices,cancellation,progress,save);}}
      finally{Stop();Interlocked.Exchange(ref operation,0);}
    }
  }
  // The same coordinator is exercised with deterministic network/process
  // implementations in regression tests; production supplies the real runtime.
  public sealed class ZapretCheckRunner {
    readonly Func<ZapretSettings,ZapretStrategy,CancellationToken,Task> start;
    readonly Action stop;readonly Action<string> record;
    readonly Func<string,CancellationToken,Task<ZapretEndpointResult>> probe;
    public ZapretCheckRunner(Func<ZapretSettings,ZapretStrategy,CancellationToken,Task> start,Action stop,Func<string,CancellationToken,Task<ZapretEndpointResult>> probe,Action<string> record){this.start=start;this.stop=stop;this.probe=probe;this.record=record;}
    sealed class EndpointPair {public ZapretEndpointResult First,Second;}
    async Task<ZapretEndpointResult> SafeProbe(string url,CancellationToken cancellation){
      var clock=Stopwatch.StartNew();try{return await probe(url,cancellation);}catch(OperationCanceledException){throw;}catch(Exception ex){return new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unknown,Milliseconds=(int)clock.ElapsedMilliseconds,Detail="Тест не завершён: "+JournalStyle.Redact(ex.Message)};}
    }
async Task<EndpointPair> Repeat(string url,Task<ZapretEndpointResult> first,CancellationToken cancellation){var value=await first;cancellation.ThrowIfCancellationRequested();return new EndpointPair{First=value,Second=url==VideoProbe.Target||url==VideoProbe.PornhubTarget||ServiceBrowserProbe.Supports(url)?null:await SafeProbe(url,cancellation)};}
    async Task FinishGroup(ZapretCheckRow row,ZapretService[] services,Dictionary<string,Task<EndpointPair>> tasks,Stopwatch clock,bool primary,CancellationToken cancellation){
      var results=services.Select(async service=>{var pairs=await Task.WhenAll(service.Urls.Select(url=>tasks[url]));cancellation.ThrowIfCancellationRequested();var result=ZapretChecks.Combine(service,pairs.Select(x=>x.First).ToArray(),pairs.Where(x=>x.Second!=null).Select(x=>x.Second).ToArray());if(service.Id=="rutor")result.Detail+="\nПроверен Rutor. RuTracker назначается тому же профилю; антифрод RuTracker этим тестом не проверяется.";lock(row.Services)row.Services.Add(result);}).ToArray();
      await Task.WhenAll(results);if(primary)row.PrimaryMilliseconds=(int)clock.ElapsedMilliseconds;else row.AdditionalMilliseconds=(int)clock.ElapsedMilliseconds;
    }
    async Task MeasureServices(ZapretCheckRow row,CancellationToken cancellation,Action<string> progress,ZapretService[] selected){
      var primary=selected.Where(x=>x.Primary).ToArray();var additional=selected.Where(x=>!x.Primary).ToArray();
      progress("Видео: воспроизведение; Discord: интерфейс");var primaryClock=Stopwatch.StartNew();
      var all=selected.SelectMany(x=>x.Urls).ToDictionary(x=>x,x=>SafeProbe(x,cancellation));
      var pairs=all.ToDictionary(x=>x.Key,x=>Repeat(x.Key,x.Value,cancellation));
      progress(selected.All(s=>s.Urls.All(u=>u==VideoProbe.Target||u==VideoProbe.PornhubTarget))?"Проверяем воспроизведение: "+String.Join(", ",selected.Select(s=>s.Name)):"Сервисы проверяются параллельно; HTTP — дважды, видео — воспроизведение");
      await Task.WhenAll(FinishGroup(row,primary,pairs,primaryClock,true,cancellation),FinishGroup(row,additional,pairs,primaryClock,false,cancellation));
    }
    public async Task Run(ZapretCheckReport report,IEnumerable<ZapretStrategy> candidates,CancellationToken cancellation,Action<string> progress,Action<ZapretCheckReport> save){
      cancellation.ThrowIfCancellationRequested();ZapretRuntime.TestTargets(report.Settings);var choices=candidates.Where(x=>x.Available).ToArray();if(choices.Length==0)throw new InvalidOperationException("Нет доступных стратегий");
      try{
        stop();report.MediaProbeRevision=2;report.Planned=choices.Length;report.StartedAt=DateTime.Now;save(report);
        var selected=ZapretChecks.Selected(report.Settings.CheckServices);report.ServiceIds=selected.Select(x=>x.Id).ToList();
        var baseline=report.Rows.FirstOrDefault(r=>String.IsNullOrEmpty(r.StrategyId)&&!r.Checking&&r.CheckedAt>=DateTime.Now.AddMinutes(-5)&&r.Services.Count>=selected.Length&&selected.All(service=>r.Services.Any(result=>result.Id==service.Id)));
        if(baseline==null){baseline=new ZapretCheckRow{Name="Без обхода",CheckedAt=DateTime.Now,Checking=true};report.Rows.Add(baseline);save(report);progress("Без Zapret · исходная доступность");var baselineClock=Stopwatch.StartNew();await MeasureServices(baseline,cancellation,progress,selected);baseline.TotalMilliseconds=(int)baselineClock.ElapsedMilliseconds;baseline.Checking=false;save(report);}
        else{baseline.Name="Без обхода";progress("Исходная доступность уже проверена мастером — повтор не нужен");save(report);}
        for(int i=0;i<choices.Length;i++){
cancellation.ThrowIfCancellationRequested();var choice=choices[i];var row=new ZapretCheckRow{StrategyId=choice.Id,Name=choice.Name,Family=choice.Family,CheckedAt=DateTime.Now};row.Checking=true;report.Rows.Add(row);save(report);string prefix=(i+1)+" / "+choices.Length+" · Осталось: "+(choices.Length-i-1)+" · "+choice.Name;
          progress(prefix);
          var rowClock=Stopwatch.StartNew();try{var startup=Stopwatch.StartNew();await start(report.Settings,choice,cancellation);row.StartupMilliseconds=(int)startup.ElapsedMilliseconds;await MeasureServices(row,cancellation,message=>progress(prefix+" · "+message),selected);}
          catch(OperationCanceledException){row.Error="Проверка этой стратегии прервана";save(report);throw;}
          catch(ZapretEngineException){throw;}
          catch(Exception ex){row.Error=ex.Message;record(prefix+" · ошибка: "+ex.Message);}
          finally{stop();row.Checking=false;row.TotalMilliseconds=(int)rowClock.ElapsedMilliseconds;}
          save(report);var slowest=row.Services.OrderByDescending(x=>x.Milliseconds).Take(3).Select(x=>x.Id+" "+x.Milliseconds+" мс");record(prefix+" · YouTube/Discord: "+row.PrimaryCount+"/2 · остальные: "+row.AdditionalPassed+"/"+selected.Count(x=>!x.Primary)+" · всего "+row.TotalMilliseconds+" мс; запуск "+row.StartupMilliseconds+" мс; основные "+row.PrimaryMilliseconds+" мс; дополнительные "+row.AdditionalMilliseconds+" мс (группы перекрываются); медленные: "+String.Join(", ",slowest));
        }
        report.Complete=true;
      }catch(OperationCanceledException){report.StopReason="Проверка отменена. Проверенные результаты сохранены.";throw;}
      catch(Exception ex){report.StopReason=ex.Message;throw;}
      finally{stop();foreach(var item in report.Rows)item.Checking=false;report.FinishedAt=DateTime.Now;if(report.StartedAt!=default(DateTime))save(report);}
    }
  }
}
