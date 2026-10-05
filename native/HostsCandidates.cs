using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
namespace SplifyWin {
  public sealed class HostsCandidate {
    public string Service{get;set;}public string Domain{get;set;}public string Address{get;set;}public string Source{get;set;}
    public bool Relay{get{return Service=="chatgpt";}}
  }
  public sealed class HostsCandidateResult {
    public HostsCandidate Candidate{get;set;}public bool TlsVerified{get;set;}public int HttpStatus{get;set;}public int Milliseconds{get;set;}public string Detail{get;set;}
    public bool Usable{get{return TlsVerified&&HttpStatus>=200&&HttpStatus<500&&HttpStatus!=451;}}
  }
  public static class HostsCandidates {
    public const string GeoHideSource="https://raw.githubusercontent.com/Internet-Helper/GeoHideDNS/main/hosts/hosts";
    public static string[] SourceUrls(string service){return service=="instagram"?new[]{HostsRepair.Source,GeoHideSource}:new[]{MalwSource,GeoHideSource};}
    public static string[] SourceNames(string service){return service=="instagram"?new[]{"Zapret-Manager · StressOzz","GeoHideDNS · Internet-Helper"}:new[]{"dns.malw.link · ImMALWARE","GeoHideDNS · Internet-Helper"};}
    public static List<HostsCandidate> ParseSource(string service,string source,string text){return SelectMappings(source==HostsRepair.Source?ParseScript(text,service):ParseLines(text,service,source));}
    public sealed class SourceChoice {public string Service,Url,Name;}
    public static SourceChoice[] ConstructorSources(){var items=new List<SourceChoice>();foreach(var service in new[]{"instagram","chatgpt"}){var urls=SourceUrls(service);var names=SourceNames(service);for(int i=0;i<urls.Length;i++)items.Add(new SourceChoice{Service=service,Url=urls[i],Name=(service=="instagram"?"Instagram":"ChatGPT")+" · "+names[i]});items.Add(new SourceChoice{Service=service,Url="",Name=(service=="instagram"?"Instagram":"ChatGPT")+" · сохранённые записи"});}return items.ToArray();}
    public static HashSet<int> SelectedSources(IEnumerable<HostsCandidate> hosts,SourceChoice[] options){var result=new HashSet<int>();foreach(var group in hosts.Where(h=>h.Service=="instagram"||h.Service=="chatgpt").GroupBy(h=>h.Service)){int index=Array.FindIndex(options,o=>o.Service==group.Key&&!String.IsNullOrEmpty(o.Url)&&group.All(h=>h.Source==o.Url));if(index<0)index=Array.FindIndex(options,o=>o.Service==group.Key&&o.Url=="");if(index>=0)result.Add(index);}return result;}
    public const int StrategyBudgetMs=9000;
    public static List<HostsCandidate> ForResult(ZapretCheckRow row,string service){var result=row==null?null:row.Services.FirstOrDefault(r=>r.Id==service);return result==null||result.Hosts==null?new List<HostsCandidate>():result.Hosts.ToList();}
public static ZapretSettings WithScope(ZapretSettings settings,IEnumerable<HostsCandidate> hosts){var copy=ZapretChecks.CopySettings(settings);var values=hosts.ToArray();Entries(values);copy.ScopeText=(copy.ScopeText??"")+"\n"+String.Join("\n",values.SelectMany(h=>new[]{h.Domain,h.Address}).Distinct());if(copy.ScopeRules!=null)copy.ScopeRules.Add(new RouteList{Text=String.Join("\n",values.SelectMany(h=>new[]{h.Domain,h.Address}).Distinct()),MatchMode="addresses"});return copy;}
    public static List<HostsCandidate> SelectMappings(IEnumerable<HostsCandidate> candidates){return candidates.GroupBy(c=>c.Service+"|"+c.Domain).Select(g=>g.First()).ToList();}
    public const string MalwSource="https://raw.githubusercontent.com/ImMALWARE/dns.malw.link/master/hosts";
    public static string[] AllowedDomains(string service){
      if(service=="instagram")return new[]{"instagram.com","cdninstagram.com","facebook.com","fbcdn.net","fbsbx.com","fb.com"};
      if(service=="rutor")return new[]{"rutor.info"};
      if(service=="chatgpt")return new[]{"chatgpt.com","auth.openai.com","auth0.openai.com","chat.openai.com","api.openai.com","cdn.oaistatic.com","files.oaiusercontent.com","cdn.auth0.com"};
      throw new FormatException("Неизвестный сервис hosts.");
    }
    public static bool AllowedDomain(string service,string domain){return Regex.IsMatch(domain??"",@"^(?=.{1,253}$)[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?$")&&!domain.Contains("..")&&AllowedDomains(service).Any(s=>domain==s||domain.EndsWith("."+s,StringComparison.OrdinalIgnoreCase));}
    public static bool PublicV4(string text){IPAddress ip;if(!IPAddress.TryParse(text,out ip)||ip.AddressFamily!=System.Net.Sockets.AddressFamily.InterNetwork)return false;var b=ip.GetAddressBytes();return ip.ToString()==text&&b[0]>0&&b[0]!=10&&b[0]!=127&&b[0]<224&&!(b[0]==100&&b[1]>=64&&b[1]<=127)&&!(b[0]==169&&b[1]==254)&&!(b[0]==172&&b[1]>=16&&b[1]<=31)&&!(b[0]==192&&(b[1]==168||b[1]==0||b[1]==2))&&!(b[0]==198&&(b[1]==18||b[1]==19||b[1]==51&&b[2]==100))&&!(b[0]==203&&b[1]==0&&b[2]==113);}
    public static List<HostsCandidate> ParseScript(string script,string service){
      string variable=service=="instagram"?"INSTAGRAM":service=="rutor"?"RUTOR":"AI";AllowedDomains(service);
      var match=Regex.Match(script??"",@"(?ms)(?:^|;\s*)"+variable+@"=""(.*?)""");if(!match.Success)throw new FormatException("В источнике нет блока "+variable+".");
      return ParseLines(match.Groups[1].Value.Replace("\\n","\n"),service,HostsRepair.Source,service!="chatgpt");
    }
    public static List<HostsCandidate> ParseLines(string text,string service,string source,bool strict=false){
      if(text==null||text.Length>10*1024*1024)throw new FormatException("Некорректный размер источника hosts.");AllowedDomains(service);var result=new List<HostsCandidate>();var seen=new HashSet<string>();
      foreach(string raw in text.Split('\n')){var fields=raw.Split('#')[0].Trim().Split(new[]{' ','\t','\r'},StringSplitOptions.RemoveEmptyEntries);if(fields.Length<2)continue;
        foreach(string name in fields.Skip(1)){string domain=name.ToLowerInvariant();if(!AllowedDomain(service,domain)){if(strict)throw new FormatException("Посторонний домен в выбранном блоке hosts.");continue;}if(!PublicV4(fields[0]))throw new FormatException("Непубличный или некорректный IP для "+domain+".");if(seen.Add(domain+"|"+fields[0]))result.Add(new HostsCandidate{Service=service,Domain=domain,Address=fields[0],Source=source});}
      }if(result.Count==0||result.Count>160)throw new FormatException("Некорректное число записей выбранного сервиса.");return result;
    }
    public static List<HostsCandidate> Merge(IEnumerable<HostsCandidate> candidates){return candidates.GroupBy(c=>c.Service+"|"+c.Domain+"|"+c.Address).Select(g=>g.First()).ToList();}
    public static string Entries(IEnumerable<HostsCandidate> candidates){var all=candidates.ToArray();if(all.Any(c=>!AllowedDomain(c.Service,c.Domain)||!PublicV4(c.Address)))throw new FormatException("Некорректные записи hosts.");if(all.GroupBy(c=>c.Domain).Any(g=>g.Select(c=>c.Address).Distinct().Count()>1))throw new FormatException("Для одного домена выбран более чем один IP.");return String.Join("\r\n",all.GroupBy(c=>c.Domain).Select(g=>g.First()).OrderBy(c=>c.Domain).Select(c=>c.Address+" "+c.Domain));}
    public static HostsCandidateResult Classify(HostsCandidate c,int exit,string text,int elapsed){
      var r=new HostsCandidateResult{Candidate=c,Milliseconds=elapsed};var m=Regex.Match(text??"",@"\nMCRF_HOST:(\d{3}):(\d+)\s*$");if(m.Success){r.HttpStatus=Int32.Parse(m.Groups[1].Value);r.TlsVerified=exit==0&&m.Groups[2].Value=="0"&&r.HttpStatus>0;}
      if(!r.TlsVerified){r.Detail=exit==60||exit==51?"TLS-сертификат отклонён":exit==28?"Нет ответа за 5 с":"Соединение/TLS не подтверждены (curl "+exit+")";return r;}
      if(Regex.IsMatch(text??"",@"unsupported_country|unsupported_region|country.{0,30}not supported|not available in your country|доступ.{0,30}ограничен",RegexOptions.IgnoreCase)){r.HttpStatus=451;r.Detail="Сервер сообщил об ограничении региона";return r;}
      r.Detail="TLS проверен · HTTP "+r.HttpStatus+(r.HttpStatus==401||r.HttpStatus==403||r.HttpStatus==429?" · вход/антибот, нужна проверка браузером":" · это проверка соединения, не аккаунта и медиа");return r;
    }
    public static async Task<HostsCandidateResult> Probe(NetworkCore core,HostsCandidate c,CancellationToken token){
      Entries(new[]{c});string q="\"";string args="--silent --show-error --ipv4 --http1.1 --compressed --noproxy \"*\" --connect-timeout 3 --max-time 5 --head --proto =https --cacert "+q+Path.Combine(core.DataRoot,"core","curl-ca-bundle.crt")+q+" --resolve "+q+c.Domain+":443:"+c.Address+q+" --dump-header - --user-agent \"Mozilla/5.0\" --write-out \"\\nMCRF_HOST:%{http_code}:%{ssl_verify_result}\" "+q+"https://"+c.Domain+"/"+q;
      using(var p=OwnedJob.Start(new ProcessStartInfo(core.CurlExecutable,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){var watch=Stopwatch.StartNew();var read=p.StandardOutput.ReadToEndAsync();var errors=p.StandardError.ReadToEndAsync();try{while(!p.HasExited){token.ThrowIfCancellationRequested();if(watch.ElapsedMilliseconds>6500){p.Kill();p.WaitForExit(1000);break;}await Task.Delay(30,token);}token.ThrowIfCancellationRequested();await errors;return Classify(c,p.ExitCode,await read,(int)watch.ElapsedMilliseconds);}finally{if(!p.HasExited)try{p.Kill();}catch{}}}
    }
    public static async Task<List<HostsCandidateResult>> ProbeAll(NetworkCore core,IEnumerable<HostsCandidate> candidates,CancellationToken token,Action<HostsCandidateResult> progress){
      using(var gate=new SemaphoreSlim(4)){var jobs=Merge(candidates).Select(async c=>{await gate.WaitAsync(token);try{var first=await Probe(core,c,token);var result=first;if(first.Usable){var second=await Probe(core,c,token);if(!second.Usable)result=second;else result.Milliseconds=(first.Milliseconds+second.Milliseconds)/2;}if(progress!=null)progress(result);return result;}finally{gate.Release();}}).ToArray();return (await Task.WhenAll(jobs)).ToList();}
    }
  }
}
