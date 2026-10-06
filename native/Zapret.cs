using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace SplifyWin {
  public sealed class ZapretSettings {
    public static string ServiceScope{get{return ZapretChecks.Scope;}}
    public static string ServiceTests{get{return ZapretChecks.Targets;}}
    public string Family{get;set;} public string Strategy{get;set;} public string ScopeText{get;set;}
    public string MatchMode{get;set;} public string TestUrls{get;set;}
    public List<string> ScopeSources{get;set;} public string CustomScopeText{get;set;}
    public List<string> CheckServices{get;set;} public List<string> CheckFamilies{get;set;} public bool DetailedLogs{get;set;}
public bool DiscordVoiceEnabled{get;set;}public string DiscordInterfaceStrategy{get;set;}public string DiscordScopeText{get;set;}
    public List<HostsCandidate> Hosts{get;set;}
    public bool GameFilter{get;set;}
    public bool? GameFilterTcp{get;set;} public bool? GameFilterUdp{get;set;}
    [System.Web.Script.Serialization.ScriptIgnore] public bool GameTcpEnabled{get{return GameFilterTcp??GameFilter;}}
    [System.Web.Script.Serialization.ScriptIgnore] public bool GameUdpEnabled{get{return GameFilterUdp??GameFilter;}}
    [System.Web.Script.Serialization.ScriptIgnore]
    public List<RouteList> ScopeRules{get;set;}
    public ZapretSettings(){Family="Flowseal";Strategy="";MatchMode="addresses";ScopeText="youtube.com\ngooglevideo.com\nytimg.com\nyoutubei.googleapis.com";TestUrls="https://www.youtube.com/\nhttps://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg";}
  }
  public sealed class ZapretStrategy {
    public string Id,Name,Family,Tcp,Udp,VoiceUdp,UnavailableReason;
    public string GameTcp,GameUdp,GameUnavailableReason;
    public bool GameAvailable{get{return Available&&String.IsNullOrEmpty(GameUnavailableReason)&&(!String.IsNullOrEmpty(GameTcp)||!String.IsNullOrEmpty(GameUdp));}}
    public string ShortName{get{return CompactName(Name,Family);}}
    public static string CompactName(string name,string family){
      name=name??"";var number=Regex.Match(name,@"(?:Yv|v)(\d+)$",RegexOptions.IgnoreCase);
      if(number.Success)return family=="YouTube"?"YT "+number.Groups[1].Value.PadLeft(2,'0'):"v"+number.Groups[1].Value;
      var variant=Regex.Match(name,@"\(([^)]+)\)");if(variant.Success)return variant.Groups[1].Value;
      return name.Replace("Flowseal · ","").Replace("Базовая","BASE");
    }
    public static string CleanLabel(string name){return Regex.Replace(name??"",@"^(?:Список:\s*)?(?:Мастер\s*·\s*)?","");}
    public bool Available{get{return String.IsNullOrEmpty(UnavailableReason);}}
  }
  public sealed class ZapretProfile {
    public string Id{get;set;} public string Name{get;set;} public bool Enabled{get;set;} public ZapretSettings Settings{get;set;}
    public ZapretProfile(){Id=Guid.NewGuid().ToString("N");Name="Новый профиль";Enabled=true;Settings=new ZapretSettings();}
  }
  // BAT files are source data only. No service installers, hosts edits or upstream scripts are executed.
  public static class ZapretCatalog {
    static string DataRoot(string directory){var p=System.IO.Directory.GetParent(directory);if(p!=null&&p.Name=="core")return p.Parent.FullName;if(p!=null&&p.Name=="installed"&&p.Parent.Name=="updates")return p.Parent.Parent.FullName;return null;}
    static Stream Flowseal(string directory){string root=DataRoot(directory);if(root!=null){var prefs=AppUpdates.ReadPreferences(root);var candidates=new List<UpdateInstallation>();foreach(var id in new[]{"flowseal-config","zapret"}){UpdateInstallation item;if(prefs.Installed.TryGetValue(id,out item)&&AppUpdates.ActiveFile(root,id,null)!=null)candidates.Add(item);}var latest=candidates.OrderByDescending(p=>p.Version,Comparer<string>.Create(AppUpdates.CompareVersion)).FirstOrDefault();if(latest!=null)return File.OpenRead(Path.Combine(latest.Directory,latest.File));}return Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret.flowseal.zip");}
    static string Config(string directory,string id,string resource){string root=DataRoot(directory),file=root==null?null:AppUpdates.ActiveFile(root,id,null);return file==null?Resource(resource):File.ReadAllText(file);}
    static string Resource(string name){using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret."+name)){if(stream==null)throw new FileNotFoundException("Нет встроенного ресурса Zapret: "+name);using(var reader=new StreamReader(stream,Encoding.UTF8))return reader.ReadToEnd();}}
    static string Options(string text,string directory,out string reason,bool game=false){
      reason=null;var result=new List<string>();
      foreach(Match match in Regex.Matches(text,@"--(?:dpi-desync[-a-z0-9]*|dup[-a-z0-9]*|ip-id)(?:=(?:""[^""]*""|[^\s]+)|\s+[0-9][^\s]*)?")){
        var option=match.Value.Trim().TrimEnd('^').Replace("^!","!");if((option.Contains("any-protocol")&&!game)||option.Contains("fake-syn")||option.Contains("skip-nosni")){reason="Стратегия содержит обработку без определённого адреса";return "";}
        option=Regex.Replace(option,@"(?:%BIN%|/opt/zapret/files/fake/)([a-zA-Z0-9_.-]+)",m=>Path.Combine(directory,m.Groups[1].Value=="4pda.bin"?"tls_clienthello_4pda_to.bin":m.Groups[1].Value));
        foreach(Match file in Regex.Matches(option,@"[a-zA-Z0-9_.-]+\.bin"))if(!File.Exists(Path.Combine(directory,file.Value)))reason="Нет встроенного payload: "+file.Value;
        // Cygwin getopt needs complete Windows arguments, including paths containing spaces.
        if(option.Contains(directory)){int equal=option.IndexOf('=');option=option.Substring(0,equal+1)+ZapretRuntime.Quote(option.Substring(equal+1).Trim('"'));}
        result.Add(option);
      }
      if(!result.Any(x=>x.StartsWith("--dpi-desync=")))reason="Нет DPI-стратегии";
      return String.Join(" ",result);
    }
    public static List<ZapretStrategy> Load(string directory){
      var result=new List<ZapretStrategy>();
      using(var input=Flowseal(directory))using(var zip=new ZipArchive(input,ZipArchiveMode.Read)){
        foreach(var entry in zip.Entries.Where(x=>Path.GetFileName(x.FullName).StartsWith("general",StringComparison.OrdinalIgnoreCase)&&x.FullName.EndsWith(".bat")).OrderBy(x=>x.FullName)){
          string script;using(var reader=new StreamReader(entry.Open(),Encoding.UTF8))script=reader.ReadToEnd();
          var profiles=Regex.Split(script.Replace("^\r\n"," ").Replace("^\n"," "),@"--new\s*");
          var tcp=profiles.FirstOrDefault(x=>x.Contains("--filter-tcp=80,443")&&x.Contains("list-general.txt"));
          var udp=profiles.FirstOrDefault(x=>x.Contains("--filter-udp=443 ")&&x.Contains("list-general.txt"));
          var voice=profiles.FirstOrDefault(x=>x.Contains("--filter-l7=discord,stun"));string voiceReason;var voiceOptions=voice==null?"--dpi-desync=fake --dpi-desync-repeats=6":Options(voice,directory,out voiceReason);
          if(tcp==null)continue;string tcpReason,udpReason=null;var tcpOptions=Options(tcp,directory,out tcpReason);var udpOptions=udp==null?"":Options(udp,directory,out udpReason);
          var gameTcp=profiles.FirstOrDefault(x=>Regex.IsMatch(x,@"--filter-tcp=%GameFilter(?:TCP)?%"));
          var gameUdp=profiles.FirstOrDefault(x=>Regex.IsMatch(x,@"--filter-udp=%GameFilter(?:UDP)?%"));
          string gameTcpReason=null,gameUdpReason=null;
          var gameTcpOptions=gameTcp==null?"":Options(gameTcp,directory,out gameTcpReason,true);
          var gameUdpOptions=gameUdp==null?"":Options(gameUdp,directory,out gameUdpReason,true);
          var name=Path.GetFileNameWithoutExtension(entry.FullName).Replace("general","Базовая");
          result.Add(new ZapretStrategy{Family="Flowseal",Id="flowseal:"+Path.GetFileName(entry.FullName),Name="Flowseal · "+name,Tcp=tcpOptions,Udp=udpOptions,VoiceUdp=voiceOptions,UnavailableReason=tcpReason??udpReason,GameTcp=gameTcpOptions,GameUdp=gameUdpOptions,GameUnavailableReason=gameTcpReason??gameUdpReason});
        }
      }
      AddMarkdown(result,"V",Config(directory,"v-config","v.md"),directory);AddMarkdown(result,"YouTube",Config(directory,"youtube-config","youtube.md"),directory);return result;
    }
    static void AddMarkdown(List<ZapretStrategy> result,string family,string text,string directory){
      var pattern=family=="V"?@"(?m)^# v([0-9]+)\s*\r?\n```\s*\r?\n([\s\S]*?)```":@"(?m)^#Yv([0-9]+)\s*\r?\n([\s\S]*?)(?=```|^#Yv[0-9]+|\z)";
      foreach(Match block in Regex.Matches(text,pattern)){string reason;var tcp=Options(block.Groups[2].Value,directory,out reason);var number=block.Groups[1].Value;
        result.Add(new ZapretStrategy{Family=family,Id=family+":"+number,Name=family+" · "+(family=="V"?"v":"Yv")+number,Tcp=tcp,Udp="--dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fake-quic="+ZapretRuntime.Quote(Path.Combine(directory,"quic_initial_www_google_com.bin")),UnavailableReason=reason});}
    }
  }
  public sealed class ZapretPickResult {public ZapretStrategy Strategy;public int Milliseconds,PassedServices,TotalServices;public bool BaselinePassed;public string Summary;}
  public sealed class ZapretEngineException : Exception {public ZapretEngineException(string message,Exception inner=null):base(message,inner){}}
  public sealed partial class ZapretRuntime : IDisposable {
    readonly string root,curl,cert;readonly object sync=new object(),logSync=new object();readonly StringBuilder pendingLog=new StringBuilder();readonly System.Threading.Timer logTimer;Process process;bool disposed,engineValidated,appEngineValidated,prepared;int operation;
    readonly Dictionary<string,string> activeProfiles=new Dictionary<string,string>();
    public string DirectoryPath{get;private set;} public string ActiveStrategy{get;private set;}public string Status{get;private set;}
    public bool Running{get{lock(sync){try{return process!=null&&!process.HasExited;}catch{return false;}}}}
    public bool ProfileApplied(string profileId,string strategyId){lock(sync){string current;return Running&&(String.IsNullOrEmpty(profileId)?ActiveStrategy==strategyId:activeProfiles.TryGetValue(profileId,out current)&&current==strategyId);}}
public ZapretRuntime(NetworkCore core){hostsCore=core;root=core.DataRoot;curl=core.CurlExecutable;cert=Path.Combine(root,"core","curl-ca-bundle.crt");DirectoryPath=Path.Combine(root,"core","zapret-1.10.3");Status="Выключен";logTimer=new System.Threading.Timer(s=>FlushLog(),null,250,250);}
    public static string Quote(string value){if(value==null||value.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new FormatException("Недопустимый аргумент");return "\""+value.Replace("\"","\\\"")+"\"";}
    public void ReloadUpdates(){lock(sync){if(Running)throw new InvalidOperationException("Сначала выключите Zapret");string archive=AppUpdates.ActiveFile(root,"zapret",null);DirectoryPath=archive==null?Path.Combine(root,"core","zapret-1.10.3"):Path.GetDirectoryName(archive);prepared=false;engineValidated=false;}Prepare();}
    public void Prepare(){lock(sync){if(disposed)throw new ObjectDisposedException("Zapret");if(prepared&&File.Exists(Path.Combine(DirectoryPath,"winws.exe")))return;Directory.CreateDirectory(DirectoryPath);
      string active=AppUpdates.ActiveFile(root,"zapret",null);if(active!=null){DirectoryPath=Path.GetDirectoryName(active);foreach(var name in new[]{"tls_clienthello_vk_com.bin","tls_clienthello_gosuslugi_ru.bin"})if(!File.Exists(Path.Combine(DirectoryPath,name)))using(var source=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret."+name))using(var output=File.Create(Path.Combine(DirectoryPath,name)))source.CopyTo(output);prepared=true;return;}
      using(var input=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret.flowseal.zip"))using(var zip=new ZipArchive(input,ZipArchiveMode.Read))foreach(var entry in zip.Entries){
        string name=Path.GetFileName(entry.FullName);if(!entry.FullName.Contains("/bin/")&&!name.StartsWith("LICENSE",StringComparison.OrdinalIgnoreCase))continue;
        if(!Regex.IsMatch(name,@"^[a-zA-Z0-9_.-]+$")||name.Length==0)continue;var target=Path.Combine(DirectoryPath,name);
        using(var source=entry.Open()){var memory=new MemoryStream();source.CopyTo(memory);var bytes=memory.ToArray();bool same=false;if(File.Exists(target))using(var sha=SHA256.Create())using(var previous=File.OpenRead(target))same=sha.ComputeHash(bytes).SequenceEqual(sha.ComputeHash(previous));if(same)continue;File.WriteAllBytes(target+".new",bytes);if(File.Exists(target))File.Delete(target);File.Move(target+".new",target);}
      }
      using(var license=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret.LICENSE.WinDivert"))using(var output=File.Create(Path.Combine(DirectoryPath,"LICENSE.WinDivert")))license.CopyTo(output);
      using(var license=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret.LICENSE.zapret"))using(var output=File.Create(Path.Combine(DirectoryPath,"LICENSE.zapret")))license.CopyTo(output);
      foreach(var name in new[]{"tls_clienthello_vk_com.bin","tls_clienthello_gosuslugi_ru.bin"})using(var source=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Zapret."+name))using(var output=File.Create(Path.Combine(DirectoryPath,name)))source.CopyTo(output);
      prepared=true;
    }}
    public string BuildArguments(ZapretSettings settings,ZapretStrategy strategy,string runDirectory){
      if(settings==null||strategy==null)throw new ArgumentNullException();runDirectory=Path.GetFullPath(runDirectory);if(!strategy.Available)throw new NotSupportedException(strategy.UnavailableReason);
      if(settings.DiscordVoiceEnabled){
        var normal=ZapretChecks.CopySettings(settings);normal.DiscordVoiceEnabled=false;
        var baseline=String.IsNullOrEmpty(settings.DiscordInterfaceStrategy)?strategy:ZapretCatalog.Load(DirectoryPath).FirstOrDefault(s=>s.Id==settings.DiscordInterfaceStrategy&&s.Available);
        if(baseline==null)throw new InvalidOperationException("Стратегия интерфейса Discord недоступна");
        var args=BuildArguments(normal,baseline,runDirectory);var appsFile=Path.Combine(runDirectory,"discord-voice-apps.txt");File.WriteAllLines(appsFile,new[]{"app:Discord.exe","app:DiscordPTB.exe","app:DiscordCanary.exe"},new UTF8Encoding(false));
        var voiceOptions=String.IsNullOrEmpty(strategy.VoiceUdp)?"--dpi-desync=fake --dpi-desync-repeats=6":strategy.VoiceUdp;
        var voiceAddresses=ZapretScope.Validate(new RouteList{Text=String.IsNullOrWhiteSpace(settings.DiscordScopeText)?settings.ScopeText:settings.DiscordScopeText,MatchMode="addresses"})["ip_cidr"].ToArray();string voiceScope="";
        if(voiceAddresses.Any(x=>x.EndsWith("/0")))throw new InvalidOperationException("Глобальная сеть /0 в голосовом пресете запрещена");
        if(voiceAddresses.Length>0){string voiceIps=Path.Combine(runDirectory,"discord-voice-ips.txt");File.WriteAllLines(voiceIps,voiceAddresses,new UTF8Encoding(false));voiceScope=" --ipset="+Quote(voiceIps);}
        // With the user's IP preset, upstream address-scoped UDP does not depend on
        // observing a newly created Discord socket before its first voice packet.
        string voiceApplications=voiceAddresses.Length>0?"":" --mcrf-apps="+Quote(appsFile);
        return args.Replace("--wf-udp=443 ","--wf-udp=443,1024-65535 ")+" --new --filter-udp=1024-65535 --filter-l7=discord,stun"+voiceScope+voiceApplications+" "+voiceOptions;
      }
      if(settings.ScopeRules!=null){if(settings.ScopeRules.Count==0)throw new InvalidOperationException("Пустая область Zapret запрещена");var rules=new List<string>();int number=0;foreach(var rule in settings.ScopeRules){var part=new ZapretSettings{ScopeText=rule.Text,MatchMode=rule.MatchMode,DetailedLogs=settings.DetailedLogs,GameFilterTcp=rule.GameTcpEnabled,GameFilterUdp=rule.GameUdpEnabled};var arg=BuildArguments(part,strategy,Path.Combine(runDirectory,"rule-"+(++number)));rules.Add(arg.Substring(arg.IndexOf(" --filter-",StringComparison.Ordinal)+1));}return CaptureHeader(settings.ScopeRules.Any(r=>r.GameTcpEnabled),settings.ScopeRules.Any(r=>r.GameUdpEnabled),false)+"--debug="+(settings.DetailedLogs?"1":"0")+" "+String.Join(" --new ",rules);}
      var fields=ZapretScope.Validate(new RouteList{Text=settings.ScopeText,MatchMode=settings.MatchMode});
      if(fields["ip_cidr"].Any(x=>x.EndsWith("/0")))throw new InvalidOperationException("Сеть /0 применяет Zapret глобально. Укажите конкретные адреса.");
      Directory.CreateDirectory(runDirectory);var profiles=new List<string>();
      string mode=settings.MatchMode??"any",apps="";bool hasApps=fields["process_name"].Count+fields["process_path"].Count>0;
      if(hasApps&&mode!="addresses"){string file=Path.Combine(runDirectory,"apps.txt");File.WriteAllLines(file,fields["process_name"].Select(x=>"app:"+x).Concat(fields["process_path"].Select(x=>"path:"+x)),new UTF8Encoding(false));apps=(mode=="except-apps"?"--mcrf-except-apps=":"--mcrf-apps=")+Quote(file);}
      var domains=fields["domain"].Select(x=>"^"+x).Concat(fields["domain_suffix"]).Distinct().ToArray();var ips=fields["ip_cidr"].ToArray();
      string gate=mode=="in-apps"||mode=="except-apps"?" "+apps:""; if(settings.GameTcpEnabled||settings.GameUdpEnabled){if(!strategy.GameAvailable||(settings.GameTcpEnabled&&String.IsNullOrEmpty(strategy.GameTcp))||(settings.GameUdpEnabled&&String.IsNullOrEmpty(strategy.GameUdp)))throw new InvalidOperationException("В выбранной стратегии нет доступного Game Filter. Выберите Flowseal с игровой частью.");if(ips.Length==0&&(!hasApps||mode=="addresses"||mode=="in-apps"||mode=="except-apps"))throw new InvalidOperationException("Game Filter требует IP или процесс игры; одних доменов недостаточно.");}
      if(mode!="apps"&&domains.Length>0){var file=Path.Combine(runDirectory,"hosts.txt");File.WriteAllLines(file,domains,new UTF8Encoding(false));AddProfiles(profiles,"--hostlist="+Quote(file)+gate,strategy,true,false,false);}
      if(mode!="apps"&&ips.Length>0){var file=Path.Combine(runDirectory,"ips.txt");File.WriteAllLines(file,ips,new UTF8Encoding(false));AddProfiles(profiles,"--ipset="+Quote(file)+gate,strategy,false,settings.GameTcpEnabled,settings.GameUdpEnabled);}
      if(hasApps&&(mode=="apps"||mode=="any"))AddProfiles(profiles,apps,strategy,false,settings.GameTcpEnabled,settings.GameUdpEnabled);
      if(profiles.Count==0)throw new InvalidOperationException("Пустая область Zapret запрещена");
      // Every profile is explicitly scoped. No auto-hostlist, catch-all, SYN fake or service installation.
      return CaptureHeader(settings.GameTcpEnabled,settings.GameUdpEnabled,false)+"--debug="+(settings.DetailedLogs?"1":"0")+" "+String.Join(" --new ",profiles);
    }
    public string BuildProfileArguments(IEnumerable<ZapretProfile> profiles,IEnumerable<ZapretStrategy> catalog,string runDirectory){
      var enabled=profiles.Where(x=>x!=null&&x.Enabled).ToArray();if(enabled.Length==0)throw new InvalidOperationException("Нет включённых профилей Zapret");
      var choices=catalog.ToArray();var arguments=new List<string>();int index=0;
      foreach(var profile in enabled){if(profile.Settings==null)throw new InvalidOperationException("Пустой профиль: "+profile.Name);var strategy=choices.FirstOrDefault(x=>x.Id==profile.Settings.Strategy&&x.Available);if(strategy==null)throw new InvalidOperationException("Выберите стратегию для профиля «"+profile.Name+"»");var arg=BuildArguments(profile.Settings,strategy,Path.Combine(runDirectory,"profile-"+(++index)));arguments.Add(arg.Substring(arg.IndexOf(" --filter-",StringComparison.Ordinal)+1));}
      return CaptureHeader(enabled.Any(p=>p.Settings.GameTcpEnabled||(p.Settings.ScopeRules!=null&&p.Settings.ScopeRules.Any(r=>r.GameTcpEnabled))),enabled.Any(p=>p.Settings.GameUdpEnabled||(p.Settings.ScopeRules!=null&&p.Settings.ScopeRules.Any(r=>r.GameUdpEnabled))),enabled.Any(p=>p.Settings.DiscordVoiceEnabled))+"--debug="+(enabled.Any(x=>x.Settings.DetailedLogs)?"1":"0")+" "+String.Join(" --new ",arguments);
    }
    static string CaptureHeader(bool tcp,bool udp,bool voice){return "--wf-tcp=80,443"+(tcp?",1024-65535":"")+" --wf-udp=443"+(udp||voice?",1024-65535":"")+" ";}
    static void AddProfiles(List<string> profiles,string scope,ZapretStrategy strategy,bool hosts,bool tcp,bool udp){
      profiles.Add("--filter-tcp=80,443 "+(hosts?"--filter-l7=http,tls ":"")+scope+" "+strategy.Tcp);
      if(!String.IsNullOrEmpty(strategy.Udp))profiles.Add("--filter-udp=443 "+(hosts?"--filter-l7=quic ":"")+scope+" "+strategy.Udp);
      if(tcp)profiles.Add("--filter-tcp=1024-65535 "+scope+" "+strategy.GameTcp);
      if(udp)profiles.Add("--filter-udp=1024-65535 "+scope+" "+strategy.GameUdp);
    }
    static bool Administrator(){using(var identity=WindowsIdentity.GetCurrent())return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);}
    void Record(string line){if(String.IsNullOrWhiteSpace(line))return;lock(logSync){pendingLog.AppendLine(ClientJournal.Stamp(line));if(pendingLog.Length>65536)FlushLog();}}
    void FlushLog(){lock(logSync){if(pendingLog.Length==0)return;try{File.AppendAllText(Path.Combine(root,"zapret.log"),pendingLog.ToString(),Encoding.UTF8);pendingLog.Clear();}catch{if(pendingLog.Length>131072)pendingLog.Clear();}}}
    public static bool ValidHelpResult(int exitCode,string stdout,string stderr){
      // Upstream exithelp() intentionally exits with 1. Accept that only with
      // identifiable help, never an arbitrary error or a Windows loader failure.
      string help=(stdout??"")+"\n"+(stderr??"");
      return (exitCode==0||exitCode==1)&&help.Contains("--dpi-desync")&&help.Contains("--hostlist")&&help.Contains("--wf-tcp");
    }
    public string AppExecutable(){Prepare();string directory=Path.Combine(root,"core","zapret-apps-v1");Directory.CreateDirectory(directory);foreach(var name in new[]{"winws.exe","cygwin1.dll","WinDivert.dll","WinDivert64.sys","NOTICES.md","LICENSE.MCRF"})BundledResources.Install("SplifyWin.Zapret.apps."+name,Path.Combine(directory,name));foreach(var name in new[]{"LICENSE.zapret","LICENSE.WinDivert"})BundledResources.Install("SplifyWin.Zapret."+name,Path.Combine(directory,name));return Path.Combine(directory,"winws.exe");}
    public async Task CheckEngine(CancellationToken cancellation,bool applications=false){
      cancellation.ThrowIfCancellationRequested();if(applications?appEngineValidated:engineValidated)return;Prepare();string executable=applications?AppExecutable():Path.Combine(DirectoryPath,"winws.exe");
      var info=new ProcessStartInfo(executable,"--help"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=Path.GetDirectoryName(executable)};
      Process validator;try{validator=OwnedJob.Start(info);}catch(Win32Exception ex){Record("Инициализация движка: "+ex);throw new ZapretEngineException("Windows не смог запустить движок Zapret. Это не ошибка выбранной стратегии. Подробности в журнале.",ex);}
      using(validator){var output=validator.StandardOutput.ReadToEndAsync();var error=validator.StandardError.ReadToEndAsync();var watch=Stopwatch.StartNew();
        try{while(!validator.HasExited){cancellation.ThrowIfCancellationRequested();if(watch.ElapsedMilliseconds>10000)throw new ZapretEngineException("Движок Zapret не ответил на проверку запуска.");await Task.Delay(40,cancellation);}
          var text=await output;var detail=await error;if(!ValidHelpResult(validator.ExitCode,text,detail)){Record("Проверка запуска: 0x"+validator.ExitCode.ToString("X8")+" "+detail);throw new ZapretEngineException("Движок Zapret не подтвердил готовность (0x"+validator.ExitCode.ToString("X8")+"). Перебор стратегий остановлен; ничего не включено.");}
          if(applications){if(!text.Contains("--mcrf-apps")||!text.Contains("--mcrf-except-apps"))throw new ZapretEngineException("Движок не подтвердил поддержку приложений. Запуск запрещён.");appEngineValidated=true;}else engineValidated=true;
        }finally{if(!validator.HasExited)validator.Kill();}
      }
    }
    public async Task Start(ZapretSettings settings,ZapretStrategy strategy,CancellationToken cancellation){
      cancellation.ThrowIfCancellationRequested();Prepare();var run=Path.Combine(DirectoryPath,"run-"+Guid.NewGuid().ToString("N"));string args=BuildArguments(settings,strategy,run);
      await StartArguments(args,strategy.Id,strategy.Name,cancellation);
    }
    public async Task StartProfiles(IEnumerable<ZapretProfile> profiles,CancellationToken cancellation){
      cancellation.ThrowIfCancellationRequested();Prepare();var enabled=profiles.Where(x=>x!=null&&x.Enabled).ToArray();var run=Path.Combine(DirectoryPath,"run-"+Guid.NewGuid().ToString("N"));string args=BuildProfileArguments(enabled,ZapretCatalog.Load(DirectoryPath),run);
      await StartArguments(args,"profiles",enabled.Length+" профилей",cancellation);
      lock(sync)foreach(var profile in enabled)activeProfiles[profile.Id]=profile.Settings.Strategy;
    }
    async Task StartArguments(string args,string id,string name,CancellationToken cancellation){
      if(!Administrator())throw new UnauthorizedAccessException("Zapret требует права администратора Windows. Нажмите «Запросить права».");
      bool applications=args.Contains("--mcrf-apps=")||args.Contains("--mcrf-except-apps=");await CheckEngine(cancellation,applications);
      Stop();var executable=applications?AppExecutable():Path.Combine(DirectoryPath,"winws.exe");
      var check=new ProcessStartInfo(executable,args+" --dry-run"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=Path.GetDirectoryName(executable)};
      using(var validator=OwnedJob.Start(check)){
        var stdout=validator.StandardOutput.ReadToEndAsync();var stderr=validator.StandardError.ReadToEndAsync();var watch=Stopwatch.StartNew();
        try{while(!validator.HasExited){cancellation.ThrowIfCancellationRequested();if(watch.ElapsedMilliseconds>10000)throw new TimeoutException("Проверка параметров Zapret зависла");await Task.Delay(50,cancellation);}var error=await stderr;var output=await stdout;if(validator.ExitCode!=0)throw new InvalidOperationException("Движок отверг стратегию (0x"+validator.ExitCode.ToString("X8")+"): "+(error.Length>0?error:output));}
        finally{if(!validator.HasExited)validator.Kill();}
      }
      cancellation.ThrowIfCancellationRequested();
      lock(sync){if(disposed)throw new ObjectDisposedException("Zapret");process=OwnedJob.Start(new ProcessStartInfo(executable,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=Path.GetDirectoryName(executable)});process.OutputDataReceived+=(s,e)=>Record(e.Data);process.ErrorDataReceived+=(s,e)=>Record(e.Data);process.BeginOutputReadLine();process.BeginErrorReadLine();ActiveStrategy=id;Status="Запуск "+name;}
      try{await Task.Delay(650,cancellation);if(!Running)throw new InvalidOperationException("Zapret завершился при запуске. Подробности в zapret.log");Status="Включён · "+name;Record(Status);}catch{Stop();throw;}
    }
    public void Stop(){Process previous;lock(sync){previous=process;process=null;activeProfiles.Clear();ActiveStrategy=null;Status="Выключен";}if(previous!=null){try{if(!previous.HasExited){previous.Kill();previous.WaitForExit(1500);}}catch{}previous.Dispose();}FlushLog();}
    public static string DefaultTargets(ZapretSettings settings){
      var fields=ZapretScope.Validate(new RouteList{Text=settings.ScopeText,MatchMode=settings.MatchMode});
      try{TestTargets(settings);return settings.TestUrls;}catch(FormatException){}catch(InvalidOperationException){}
      var targets=fields["domain"].Concat(fields["domain_suffix"]).Distinct().Take(4).Select(x=>"https://"+x+"/").ToArray();
      if(targets.Length==0)throw new InvalidOperationException("Для автоматической проверки IP-сетей откройте ручные настройки и укажите HTTPS-сайт из этой сети.");
      return String.Join("\n",targets);
    }
    public static string[] TestTargets(ZapretSettings settings){
      var fields=ZapretScope.Validate(new RouteList{Text=settings.ScopeText,MatchMode=settings.MatchMode});var targets=new List<string>();
      foreach(var line in (settings.TestUrls??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){Uri uri;if(!Uri.TryCreate(line.Trim(),UriKind.Absolute,out uri)||(uri.Scheme!="https"&&line.Trim()!=DiscordInterfaceProbe.Target)||uri.UserInfo.Length>0||uri.Fragment.Length>0||uri.Port!=443)throw new FormatException("Проверка требует HTTPS-адрес на порту 443");
        bool allowed=fields["domain"].Contains(uri.Host,StringComparer.OrdinalIgnoreCase)||fields["domain_suffix"].Any(x=>uri.Host.Equals(x,StringComparison.OrdinalIgnoreCase)||uri.Host.EndsWith("."+x,StringComparison.OrdinalIgnoreCase));
        if(!allowed&&fields["ip_cidr"].Count>0){System.Net.IPAddress literal;allowed=System.Net.IPAddress.TryParse(uri.Host.Trim('[',']'),out literal)&&fields["ip_cidr"].Any(cidr=>ContainsIp(cidr,literal));}
        if(!allowed)throw new InvalidOperationException("Тестовый адрес не входит в область Zapret: "+uri.Host);targets.Add(uri.AbsoluteUri);
      }
      if(targets.Count==0||targets.Count>32)throw new InvalidOperationException("Укажите от 1 до 32 HTTPS-адресов для проверки");return targets.Distinct().ToArray();
    }
    static bool ContainsIp(string cidr,System.Net.IPAddress address){var parts=cidr.Split('/');var subnet=System.Net.IPAddress.Parse(parts[0]);var net=subnet.GetAddressBytes();var host=address.GetAddressBytes();if(net.Length!=host.Length)return false;int bits=Int32.Parse(parts[1]);for(int i=0;i<net.Length&&bits>0;i++,bits-=8){int mask=255<<(8-Math.Min(8,bits));if((net[i]&mask)!=(host[i]&mask))return false;}return true;}
    async Task<int> Probe(string target,CancellationToken cancellation){
      var args="--silent --show-error --fail --location --max-redirs 3 --proto =https --proto-redir =https --http1.1 --noproxy \"*\" --connect-timeout 3 --max-time 9 --max-filesize 2097152 --range 0-131071 --cacert "+Quote(cert)+" --write-out \"\\nMCRF_HTTP:%{http_code}\" "+Quote(target);
      using(var p=OwnedJob.Start(new ProcessStartInfo(curl,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true})){var error=p.StandardError.ReadToEndAsync();var output=p.StandardOutput.ReadToEndAsync();var watch=Stopwatch.StartNew();try{while(!p.HasExited){cancellation.ThrowIfCancellationRequested();if(watch.ElapsedMilliseconds>11000)throw new TimeoutException();await Task.Delay(40,cancellation);}await error;string body=await output;return ValidProbeResponse(target,p.ExitCode,body)?(int)watch.ElapsedMilliseconds:-1;}finally{if(!p.HasExited)p.Kill();}}
    }
    public static bool ValidProbeResponse(string target,int exitCode,string output){
      if(exitCode!=0||String.IsNullOrEmpty(output))return false;
      int footer=output.LastIndexOf("\nMCRF_HTTP:",StringComparison.Ordinal);if(footer<0)return false;
      string code=output.Substring(footer+11).Trim();if(code!="200"&&code!="206"&&code!="204")return false;
      string body=output.Substring(0,footer);var host=new Uri(target).Host.ToLowerInvariant();
      if(body.IndexOf("cf-chl-",StringComparison.OrdinalIgnoreCase)>=0||body.IndexOf("challenge-platform",StringComparison.OrdinalIgnoreCase)>=0)return false;
      if(host=="chatgpt.com")return body.IndexOf("ChatGPT",StringComparison.OrdinalIgnoreCase)>=0;
      if(host=="www.youtube.com")return body.Contains("ytInitialData")||body.Contains("INNERTUBE_API_KEY");
      if(host=="discord.com")return body.Contains("wss://gateway.discord.gg");
      if(host=="www.instagram.com")return body.IndexOf("Instagram",StringComparison.OrdinalIgnoreCase)>=0;
      if(host=="i.ytimg.com")return body.Length>100;
      return true;
    }
    static string ServiceName(string target){string host=new Uri(target).Host;return host.Contains("chatgpt")?"ChatGPT":host.Contains("youtube")||host.Contains("ytimg")?"YouTube":host.Contains("discord")?"Discord":host.Contains("instagram")?"Instagram":host;}
    public static string ProbeSummary(string[] targets,int[] results){return String.Join(" · ",targets.Select((target,index)=>new{Service=ServiceName(target),Passed=results[index]>=0}).GroupBy(x=>x.Service).Select(group=>group.Key+": "+(group.All(x=>x.Passed)?"OK":"не прошёл")));}
    public static int PassedServices(string[] targets,int[] first,int[] second){if(targets.Length!=first.Length||targets.Length!=second.Length)throw new ArgumentException("Несовпадающие результаты проверок");return targets.Select((target,index)=>new{Service=ServiceName(target),Passed=first[index]>=0&&second[index]>=0}).GroupBy(x=>x.Service).Count(group=>group.All(x=>x.Passed));}
    public static ZapretPickResult[] RankedResults(IEnumerable<ZapretPickResult> results){return results.Where(result=>result.PassedServices>0).OrderByDescending(result=>result.PassedServices).ThenBy(result=>result.Milliseconds).ToArray();}
    public void Dispose(){lock(sync){disposed=true;}Stop();logTimer.Dispose();FlushLog();}
  }
}
