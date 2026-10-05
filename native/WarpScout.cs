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
using System.Web.Script.Serialization;

namespace SplifyWin {
  public sealed class WarpScanExhaustedException : InvalidOperationException { public int Attempts {get;private set;} public WarpScanExhaustedException(int attempts):base("WarpScout не нашёл подтверждённый выход. Текущий профиль сохранён."){Attempts=attempts;} }
  public static class WarpScout {
    public static string RegistrationFailure(string output,int exitCode){
      string reason=Regex.IsMatch(output??"",@"(?i)timeout|timed out|deadline exceeded")?"Cloudflare не ответил вовремя":Regex.IsMatch(output??"",@"(?i)no such host|name resolution|resolve host|dns")?"не удалось определить адрес Cloudflare":Regex.IsMatch(output??"",@"(?i)tls|certificate|handshake")?"ошибка защищённого соединения с Cloudflare":Regex.IsMatch(output??"",@"(?i)429|rate.limit|too many requests")?"Cloudflare ограничил частоту создания аккаунтов":Regex.IsMatch(output??"",@"(?i)network is unreachable|connection refused|connection reset|unreachable")?"нет соединения с Cloudflare":"регистрация в Cloudflare завершилась с ошибкой";
      return "Не удалось создать аккаунт WARP: "+reason+" (код "+exitCode+"). Подбор выхода и проверка сервисов ещё не запускались.";
    }
    public const string PeerPublicKey="bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";
    static string Q(string value){if(value.Contains("\"")||value.Contains("\n"))throw new ArgumentException("Некорректный аргумент сканера.");return "\""+value+"\"";}
    public static Dictionary<string,object> Account(ServerNode source){
      if(!WarpPicker.IsWarp(source))throw new InvalidOperationException("Для подбора нужен WARP-профиль Cloudflare.");
      var fields=Links.WireGuardFields(source.Link);var result=new Dictionary<string,object>{{"private_key",fields["PrivateKey"]},{"peer_public_key",fields["PublicKey"]}};
      if(fields.ContainsKey("PresharedKey"))throw new NotSupportedException("WARP-профиль с дополнительным ключом не поддерживается сканером.");
      foreach(string item in fields["Address"].Split(',')){IPAddress address;if(IPAddress.TryParse(item.Trim().Split('/')[0],out address))result[address.AddressFamily==System.Net.Sockets.AddressFamily.InterNetwork?"ipv4":"ipv6"]=address.ToString();}
      return result;
    }
    public static ServerNode ImportAccount(string text){
      var fields=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(text);object priv,pub;
      if(!fields.TryGetValue("private_key",out priv)||!fields.TryGetValue("peer_public_key",out pub)||Convert.ToString(pub)!=PeerPublicKey)throw new FormatException("Файл не содержит поддерживаемый аккаунт WARP.");
      try{if(Convert.FromBase64String(Convert.ToString(priv)).Length!=32)throw new FormatException();}catch{throw new FormatException("Некорректный ключ аккаунта WARP.");}
      var addresses=new List<string>();
      foreach(string key in new[]{"ipv4","ipv6"}){object value;IPAddress ip;if(!fields.TryGetValue(key,out value)||String.IsNullOrWhiteSpace(Convert.ToString(value)))continue;if(!IPAddress.TryParse(Convert.ToString(value),out ip)||(key=="ipv4")!=(ip.AddressFamily==System.Net.Sockets.AddressFamily.InterNetwork))throw new FormatException("Некорректный адрес аккаунта WARP.");addresses.Add(ip.ToString()+(key=="ipv4"?"/32":"/128"));}
      if(addresses.Count==0)throw new FormatException("В аккаунте нет адреса WARP. Импортируйте готовый .conf.");
      var config="[Interface]\nPrivateKey = "+Convert.ToString(priv)+"\nAddress = "+String.Join(", ",addresses)+"\nMTU = 1280\n[Peer]\nPublicKey = "+Convert.ToString(pub)+"\nEndpoint = engage.cloudflareclient.com:2408\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n";
      var node=Links.Read(config).Single();node.Name="WARP";return node;
    }
    public static string AccountPath(NetworkCore core){return Path.Combine(core.DataRoot,"warp-account.json");}
    public static Task<ServerNode> GetOrCreate(NetworkCore core,CancellationToken cancellation,Action<string> progress,string accountId=null){
      if(accountId!=null&&!Regex.IsMatch(accountId,@"^[a-f0-9]{32}$"))throw new ArgumentException("Некорректный аккаунт WARP");var context=SynchronizationContext.Current;
      Action<string> report=message=>{if(progress==null)return;if(context!=null)context.Post(_=>progress(message),null);else progress(message);};
      return Task.Run(()=>{
        cancellation.ThrowIfCancellationRequested();string cached=accountId==null?AccountPath(core):Path.Combine(core.DataRoot,"warp-account-"+accountId+".json");
        // Never re-register an existing account: the CLI rotates its keys on register.
        if(File.Exists(cached)){report("Используем сохранённый аккаунт WARP…");return ImportAccount(SecretStorage.Read(cached));}
        string pending=Path.Combine(core.DataRoot,"warp-account-pending-"+Guid.NewGuid().ToString("N")+".json");Process owned=null;
        try{
          report("Создаём аккаунт WARP в Cloudflare…");
          owned=OwnedJob.Start(new ProcessStartInfo(core.WarpScoutExecutable,"register -account "+Q(pending)+" -plain -relay none -timeout 4 -gen-i1 quic"){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=core.DataRoot,RedirectStandardOutput=true,RedirectStandardError=true});
          var stdout=owned.StandardOutput.ReadToEndAsync();var stderr=owned.StandardError.ReadToEndAsync();var watch=Stopwatch.StartNew();long next=1000;
          while(!owned.WaitForExit(100)){cancellation.ThrowIfCancellationRequested();if(watch.ElapsedMilliseconds>180000)throw new TimeoutException("Cloudflare не ответил за три минуты. Повторите создание WARP.");if(watch.ElapsedMilliseconds>=next){next+=1000;report("Создание WARP · "+watch.ElapsedMilliseconds/1000+" с · пробуем прямой доступ и туннель");}}
          cancellation.ThrowIfCancellationRequested();string registrationOutput=stdout.GetAwaiter().GetResult()+"\n"+stderr.GetAwaiter().GetResult();
          if(owned.ExitCode!=0||!File.Exists(pending))throw new InvalidOperationException(RegistrationFailure(registrationOutput,owned.ExitCode));
          var node=ImportAccount(File.ReadAllText(pending));cancellation.ThrowIfCancellationRequested();
          if(File.Exists(cached))return ImportAccount(SecretStorage.Read(cached));
          SecretStorage.Write(cached,File.ReadAllText(pending));report("Аккаунт создан. Подбираем выход…");return node;
        }finally{
          if(owned!=null){try{if(!owned.HasExited){owned.Kill();owned.WaitForExit(1000);}}catch{}owned.Dispose();}
          if(File.Exists(pending))File.Delete(pending);
        }
      },cancellation);
    }
    public static string CountryArguments(string country){if(country=="any")return "";if(country=="foreign")return " -exclude-country RU";if(!Regex.IsMatch(country??"",@"^[A-Z]{2}$"))throw new ArgumentException("Выберите страну WARP");return " -country "+country;}
    public static async Task<WarpPickResult> Pick(NetworkCore core,ServerNode source,string protocol,bool excludeDme,CancellationToken cancellation,Action<string> progress,string country="any"){
      string countryArguments=CountryArguments(country);
      if(country=="RU")excludeDme=false;
      if(protocol!="auto"&&protocol!="awg"&&protocol!="wg")throw new ArgumentException("Неизвестный протокол подбора.");
      var account=Account(source);var fields=Links.WireGuardFields(source.Link);var context=SynchronizationContext.Current;
      Action<string> report=message=>{if(progress==null)return;if(context!=null)context.Post(_=>progress(message),null);else progress(message);};
      return await Task.Run(async()=>{
        string dir=Path.Combine(core.DataRoot,"warp-scan-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(dir);
        string accountFile=Path.Combine(dir,"account.json"),candidate=Path.Combine(dir,"candidate.conf");Process owned=null;int failedPasses=0;
        try{
          SecretStorage.WriteRuntime(accountFile,new JavaScriptSerializer().Serialize(account));
          foreach(bool quick in new[]{true,false}){
            foreach(string transport in protocol=="auto"?new[]{"awg","wg"}:new[]{protocol}){
              cancellation.ThrowIfCancellationRequested();if(File.Exists(candidate))File.Delete(candidate);
              // Try a small, parallel 2408 pass first. Only then expand to other ports.
              var args="scan -account "+Q(accountFile)+" -plain -no-report -best -P -tun-ping-count 10 -sample "+(quick?"2":"3")+" -timeout 2 -tunnel-jobs 4 -mtu 1280 -conf "+Q(candidate)+" -proto "+transport+(quick?" -port 2408":"")+(transport=="awg"?" -gen-i1 quic":"")+(excludeDme?" -exclude-node DME":"")+countryArguments;
              report("WarpScout: "+transport.ToUpperInvariant()+(quick?" · быстрый поиск":" · расширенный поиск")+" · проверяем передачу данных и потери…");
              owned=OwnedJob.Start(new ProcessStartInfo(core.WarpScoutExecutable,args){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=dir,RedirectStandardOutput=true,RedirectStandardError=true});
              var stdout=owned.StandardOutput.ReadToEndAsync();var stderr=owned.StandardError.ReadToEndAsync();var watch=Stopwatch.StartNew();long next=1000;bool timeout=false;
              while(!owned.WaitForExit(100)){
                cancellation.ThrowIfCancellationRequested();
                if(watch.ElapsedMilliseconds>(quick?90000:240000)){timeout=true;owned.Kill();owned.WaitForExit(2000);break;}
                if(watch.ElapsedMilliseconds>=next){next+=1000;report("WarpScout · "+transport.ToUpperInvariant()+" · "+(quick?"быстрый":"расширенный")+" поиск · "+watch.ElapsedMilliseconds/1000+" с");}
              }
              cancellation.ThrowIfCancellationRequested();bool found=!timeout&&owned.ExitCode==0&&File.Exists(candidate);owned.Dispose();owned=null;
              // Raw output may contain account paths; never send it to the client log or UI.
              stdout.GetAwaiter().GetResult();stderr.GetAwaiter().GetResult();
              if(found){try{return await ValidateCandidate(core,source,fields,File.ReadAllText(candidate),country,excludeDme,cancellation,report);}catch(OperationCanceledException){throw;}catch(Exception ex){if(!(ex is IOException||ex is TimeoutException||ex is InvalidOperationException||ex is FormatException))throw;report("Кандидат "+transport.ToUpperInvariant()+" не прошёл проверку в приложении: "+JournalStyle.Redact(ex.Message)+". Продолжаем подбор; прежний профиль сохранён.");}}
              failedPasses++;report("Неуспешных поисков WARP: "+failedPasses);if(country!="RU"&&country!="any"&&failedPasses>=3)throw new WarpScanExhaustedException(failedPasses);
            }
          }
          throw new WarpScanExhaustedException(failedPasses);
        }finally{
          if(owned!=null){try{if(!owned.HasExited){owned.Kill();owned.WaitForExit(1000);}}catch{}owned.Dispose();}
          foreach(var file in new[]{candidate,accountFile})if(File.Exists(file))File.Delete(file);
          // This directory was freshly created by this operation; no recursive deletion.
          if(Directory.Exists(dir)&&Directory.GetFileSystemEntries(dir).Length==0)Directory.Delete(dir);
        }
      },cancellation);
    }
    static async Task<WarpPickResult> ValidateCandidate(NetworkCore core,ServerNode source,Dictionary<string,string> fields,string config,string country,bool excludeDme,CancellationToken cancellation,Action<string> report){
      // Do not accept a scanner-only result: validate the exported config in the actual app core.
      config=Regex.Replace(config,@"(?im)^\s*Address\s*=.*$","Address = "+fields["Address"]);
      var nodes=Links.Read(config);if(nodes.Count!=1)throw new FormatException("Сканер вернул неподдерживаемую конфигурацию.");var node=nodes[0];
      var imported=Links.WireGuardFields(node.Link);if(imported["PrivateKey"]!=fields["PrivateKey"]||imported["PublicKey"]!=fields["PublicKey"])throw new InvalidOperationException("Сканер изменил ключи. Профиль не применён.");
      node.Id=source.Id;node.Source=source.Source;report("Перепроверяем выбранный выход через движок приложения…");var elapsed=Stopwatch.StartNew();
      string trace=await core.ProbeIsolatedAsync(node,"https://www.cloudflare.com/cdn-cgi/trace",cancellation);
      if(!Regex.IsMatch(trace??"",@"(?m)^warp=(?:on|plus)\s*$"))throw new InvalidOperationException("Выход не подтвердил warp=on в приложении. Профиль не изменён.");
      var samples=new List<long>{elapsed.ElapsedMilliseconds};
      for(int i=0;i<2;i++){elapsed.Restart();trace=await core.ProbeIsolatedAsync(node,"https://www.cloudflare.com/cdn-cgi/trace",cancellation);if(!Regex.IsMatch(trace??"",@"(?m)^warp=(?:on|plus)\s*$"))throw new InvalidOperationException("Выход оборвался при повторной проверке. Профиль не применён.");samples.Add(elapsed.ElapsedMilliseconds);}
      string colo=Regex.Match(trace,@"(?m)^colo=([A-Z]{3})").Groups[1].Value,region=Regex.Match(trace,@"(?m)^loc=([A-Z]{2})").Groups[1].Value;
      if(country=="foreign"&&(region.Length==0||region=="RU"))throw new InvalidOperationException("Нероссийский выход не подтверждён. Профиль не применён.");if(country!="any"&&country!="foreign"&&region!=country)throw new InvalidOperationException("Страна выхода "+(region.Length==0?"не определена":region)+" вместо "+country+". Профиль не применён.");
      if(excludeDme&&colo=="DME")throw new InvalidOperationException("Повторная проверка обнаружила узел DME. Профиль не применён.");
      node.Name="WARP · "+(region.Length>0?region:"выход")+(colo.Length>0?" · "+colo:"")+" · "+node.Protocol.ToUpperInvariant();
      samples.Sort();return new WarpPickResult{Node=node,Milliseconds=(int)samples[1]};
    }
  }
}
