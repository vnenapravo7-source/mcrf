using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Security.Cryptography;
using System.Threading;
using System.Web.Script.Serialization;

namespace SplifyWin {
  // Both protocols terminate at loopback SOCKS5. Sing-box remains responsible
  // for application/domain routing and optional TUN; these sidecars never
  // change Windows routes, DNS, adapters or system proxy settings themselves.
  public static class TurnEngines {
    static long lastGeneration;
    public static long NextGeneration(){while(true){long previous=Interlocked.Read(ref lastGeneration),next=Math.Max(previous+1,(DateTime.UtcNow.Ticks-621355968000000000L)/10000);if(Interlocked.CompareExchange(ref lastGeneration,next,previous)==previous)return next;}}
    // A transport credential is often bound on its first handshake. Re-imports,
    // health probes and normal connections must all identify the same device.
    public static string DeviceId(){using(var hash=SHA256.Create()){var identity=Environment.MachineName+"\n"+Environment.UserDomainName+"\n"+Environment.UserName;return "mcrf-"+BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(identity))).Replace("-","").Substring(0,32).ToLowerInvariant();}}
    public static bool TryTunnelConfig(string config,out string ipText,out string dnsText){
      ipText=dnsText=null;if(config==null||!config.StartsWith("TUNCONF:",StringComparison.Ordinal))return false;
      var fields=config.Substring(8).Split(':');IPAddress ip;if(fields.Length<2||!IPAddress.TryParse(fields[0].Split('/')[0],out ip)||ip.AddressFamily!=AddressFamily.InterNetwork)return false;
      var servers=fields[1].Split(',');IPAddress dns;
      // The server sends a comma-separated DNS list, not necessarily one IP.
      if(servers.Length==0||servers.Any(server=>!IPAddress.TryParse(server.Trim(),out dns)||dns.AddressFamily!=AddressFamily.InterNetwork))return false;
      ipText=ip.ToString();dnsText=servers[0].Trim();return true;
    }
    public static bool IsTurn(ServerNode node){return node!=null&&(node.Protocol=="csqtt"||node.Protocol=="wdtt");}
    public static string Redact(string line,TurnProfile profile){
      string value=line??"";foreach(var secret in profile.Hashes.Concat(new[]{profile.Password,profile.VkToken}).Where(x=>!String.IsNullOrEmpty(x)))value=value.Replace(secret,"<скрыто>");
      value=Regex.Replace(value,@"[a-zA-Z0-9_+/=-]{32,}","<скрыто>");
      return value.Length>500?value.Substring(0,500):value;
    }
    static string Address(string host,int port){return (host.Contains(":")?"["+host+"]":host)+":"+port;}
    static int UdpPort(){using(var socket=new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp)){socket.Bind(new IPEndPoint(IPAddress.Loopback,0));return ((IPEndPoint)socket.LocalEndPoint).Port;}}
    static void SendPrivate(Process process,string command){var bytes=Encoding.UTF8.GetBytes(command+"\n");try{lock(process){process.StandardInput.BaseStream.Write(bytes,0,bytes.Length);process.StandardInput.BaseStream.Flush();}}finally{Array.Clear(bytes,0,bytes.Length);}}
    public static bool HasWorkerControl(string executable){if(String.IsNullOrEmpty(executable)||!File.Exists(executable))return false;return Encoding.ASCII.GetString(File.ReadAllBytes(executable)).Contains("MCRF_CSQTT_WORKERS");}
    static Process Spawn(string executable,string arguments,Action<string> line,Action<Process> register,int workers=0,string captcha=null){
      if(!File.Exists(executable))throw new FileNotFoundException("Не найден встроенный клиентский движок туннеля");
      var info=new ProcessStartInfo(executable,arguments){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(executable),RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8};
      info.EnvironmentVariables["CSQTT_EVENTS"]="1";
      if(workers>0)info.EnvironmentVariables["MCRF_CSQTT_WORKERS"]=workers.ToString(System.Globalization.CultureInfo.InvariantCulture);
      if(!String.IsNullOrEmpty(captcha))info.EnvironmentVariables["MCRF_CSQTT_CAPTCHA_MODE"]=captcha;
      var process=new Process{StartInfo=info};process.OutputDataReceived+=(s,e)=>{if(e.Data!=null)line(e.Data);};process.ErrorDataReceived+=(s,e)=>{if(e.Data!=null)line(e.Data);};
      try{if(!process.Start())throw new IOException("Движок туннеля не запустился");OwnedJob.Attach(process);register(process);process.BeginOutputReadLine();process.BeginErrorReadLine();return process;}
      catch{try{if(!process.HasExited)process.Kill();}catch{}process.Dispose();throw;}
    }
    static void Wait(Process process,Func<bool> ready,Func<string> error,CancellationToken cancellation,string protocol){
      var clock=Stopwatch.StartNew();while(!ready()){
        var reason=error();if(reason.StartsWith("FATAL_AUTH",StringComparison.OrdinalIgnoreCase)||reason.IndexOf("DENIED",StringComparison.OrdinalIgnoreCase)>=0)throw new IOException(protocol+": сервер отклонил авторизацию. "+reason);if(reason.StartsWith("FATAL",StringComparison.OrdinalIgnoreCase))throw new IOException(protocol+": "+reason);
        cancellation.ThrowIfCancellationRequested();if(process.HasExited)throw new IOException(protocol+": движок завершился до готовности. "+error());
        if(clock.ElapsedMilliseconds>75000)throw new TimeoutException(protocol+": транспорт не подтвердил готовность за 75 секунд. "+error());Thread.Sleep(50);
      }
      cancellation.ThrowIfCancellationRequested();
    }
    public static void Start(ServerNode node,string csqttExecutable,string wdttExecutable,int port,Action<Process> register,Action<string> log,CancellationToken cancellation,Func<string,CancellationToken,System.Threading.Tasks.Task<string>> manualCaptcha=null,VkTurnPreferences vkSettings=null,string dataRoot=null,bool probeOnly=false){
      var json=new JavaScriptSerializer{MaxJsonLength=32768};
      cancellation.ThrowIfCancellationRequested();if(port<1024||port>65535)throw new FormatException("Некорректный локальный порт туннеля");var settingsForNode=VkTurnOptions.ForNode(vkSettings??new VkTurnPreferences(),node);var nodeOptions=VkTurnSettings.Server(settingsForNode,node.Id);var profile=VkTurnOptions.Apply(node,TurnProfile.Parse(node.Link),nodeOptions);if(node.Protocol=="csqtt")profile.Csqtt25=CsqttGenerations.For(node).Id=="2.5";bool autoJs=VkTurnOptions.Value(nodeOptions,"vk_hash_mode")=="auto_js";if(autoJs&&!profile.Csqtt25&&String.IsNullOrEmpty(profile.VkToken)){if(!settingsForNode.Linked)throw new InvalidOperationException("Для Auto VK привяжите аккаунт в разделе VK TURN.");profile.VkToken=settingsForNode.Token;}if(autoJs&&probeOnly)throw new InvalidOperationException("Фоновый замер не создаёт сессию Auto JS; сначала выполните ручное подключение.");if(String.IsNullOrEmpty(profile.Password))throw new InvalidOperationException("Добавьте пароль подключения CSQTT перед запуском.");if(!autoJs){if(dataRoot!=null)profile.Hashes=VkTurnSettings.ResolveCalls(dataRoot,node.Id,node.Protocol,profile.Hashes,probeOnly,manualCaptcha,cancellation,profile.VkToken,nodeOptions).GetAwaiter().GetResult();else VkTurnSettings.Apply(profile,settingsForNode);}if(profile.Hashes.Length==0&&!autoJs)throw new InvalidOperationException("Добавьте звонки VK или включите автоматические звонки для этого сервера в разделе VK TURN.");string lastError="";int ready=0,captchaWaiting=0;string configIP=null,configDNS=null;object gate=new object();Process transport=null;
      Action<string> output=line=>{
        if(profile.Protocol=="csqtt"&&line.StartsWith("CAPTCHA_SOLVE|",StringComparison.Ordinal)){
          var parts=line.Split(new[]{'|'},4);
          if(parts.Length!=4||!VkCalls.AllowedPage(parts[2])||manualCaptcha==null){lock(gate)lastError="VK требует ручную капчу. Откройте подключение из главного окна MCRF.";return;}
          if(Interlocked.CompareExchange(ref captchaWaiting,1,0)!=0)return;
          var owner=transport;
          System.Threading.Tasks.Task.Run(async()=>{using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellation)){
            timeout.CancelAfter(55000);
            try{string proof=await manualCaptcha(parts[2],timeout.Token);if(!timeout.IsCancellationRequested&&owner!=null&&!owner.HasExited)SendPrivate(owner,"CAPTCHA_RESULT|"+(VkCalls.ValidProof(proof)?proof:"error:cancelled"));}
            catch{}finally{Interlocked.Exchange(ref captchaWaiting,0);}
          }});return;
        }
        if(line.Contains("PROXY_READY|")){Interlocked.Exchange(ref ready,1);log(profile.Protocol.ToUpperInvariant()+": локальный SOCKS5 готов");return;}
        if(line.StartsWith("__CSQTT_EVENT__|")){
          var parts=line.Split(new[]{'|'},3);if(parts.Length!=3)return;
          try{var fields=json.Deserialize<Dictionary<string,object>>(parts[2]);object value;
            if(parts[1]=="CONFIG"&&fields.TryGetValue("config",out value)){string ip,dns;if(TryTunnelConfig(Convert.ToString(value),out ip,out dns)){lock(gate){configIP=ip;configDNS=dns;}log("CSQTT: сервер выдал параметры локального сетевого стека");}else{lock(gate)lastError="FATAL_CONFIG: сервер выдал некорректные IP/DNS туннеля";}}
            else if(parts[1]=="ERROR"&&fields.TryGetValue("message",out value)){object fatal;var message=Redact(Convert.ToString(value),profile);if(fields.TryGetValue("fatal",out fatal)&&Convert.ToBoolean(fatal)&&!message.StartsWith("FATAL",StringComparison.OrdinalIgnoreCase))message="FATAL: "+message;lock(gate)lastError=message;log("CSQTT: "+message);}
            else if(parts[1]=="READY")log("CSQTT: транспортный канал готов");
          }catch{}return;
        }
        // Never forward VK response dumps, cookies or generated WG configs.
        if(line.IndexOf("PROXY_ERROR|",StringComparison.Ordinal)>=0||line.IndexOf("DENIED",StringComparison.OrdinalIgnoreCase)>=0||line.IndexOf("CSQTT_ERROR|",StringComparison.Ordinal)>=0){var safe=Redact(line,profile);lock(gate)lastError=safe;log(profile.Protocol.ToUpperInvariant()+": "+safe);}
        else if(line.IndexOf("CAPTCHA",StringComparison.OrdinalIgnoreCase)>=0||line.IndexOf("КАПЧА",StringComparison.OrdinalIgnoreCase)>=0){lock(gate)lastError="VK требует капчу; автоматический вход ещё не подтверждён.";}
      };
      Func<string> error=()=>{lock(gate)return lastError;};
      if(profile.Protocol=="wdtt"){
        var settings=settingsForNode;var server=VkTurnSettings.Server(settings,node.Id);VkMasqueRuntime masque=null;
        string args="-startup-config-stdin -mode socks5 -socks-listen 127.0.0.1:"+port+" -listen 127.0.0.1:0 -peer "+ZapretRuntime.Quote(Address(profile.Host,profile.Port))+" -device-id "+ZapretRuntime.Quote(VkTurnOptions.Value(server,"device_id",DeviceId()))+" -n "+VkTurnSettings.ServerWorkers(settings,node.Id,"wdtt",profile.Hashes.Length,18)+" -hash-fallback=true -captcha-mode "+(server.CaptchaMode=="manual"?"wv":server.CaptchaMode=="rjs"?"rjs":"auto")+VkTurnOptions.Arguments(server,"wdtt");
        if(!String.IsNullOrEmpty(server.TurnSni))args+=" -turn-sni "+ZapretRuntime.Quote(server.TurnSni);if(server.Masque){if(!settings.MasqueAccepted||String.IsNullOrEmpty(dataRoot))throw new InvalidOperationException("Примите условия MASQUE в разделе VK TURN.");if(Uri.CheckHostName(server.TurnSni??"")!=UriHostNameType.Dns)throw new FormatException("Для MASQUE укажите внешний SNI в настройках WDTT-сервера.");masque=new VkMasqueRuntime(dataRoot,probeOnly);args+=" -rt-masque=true -rt-masque-config "+ZapretRuntime.Quote(masque.Config)+" -rt-masque-accept-tos="+(masque.AllowEnrollment?"true":"false");}
        Process process;try{process=Spawn(wdttExecutable,args,output,register);if(masque!=null)masque.Attach(process);}catch{if(masque!=null)masque.Dispose();throw;}string payload=json.Serialize(new{vk_hashes=String.Join(",",profile.Hashes),connection_password=profile.Password,custom_vk_client_id=VkTurnOptions.Value(server,"custom_vk_client_id"),custom_vk_client_secret=VkTurnOptions.Value(server,"custom_vk_client_secret")});
        SendPrivate(process,"START_CONFIG|"+Convert.ToBase64String(Encoding.UTF8.GetBytes(payload)).TrimEnd('=').Replace('+','-').Replace('/','_'));
        Wait(process,()=>Volatile.Read(ref ready)!=0,error,cancellation,"WDTT");if(masque!=null)masque.Checkpoint();return;
      }
      if(profile.Protocol=="csqtt"){var generation=CsqttGenerations.For(node);if(String.IsNullOrEmpty(csqttExecutable)||!String.Equals(Path.GetFileName(csqttExecutable),generation.Executable,StringComparison.OrdinalIgnoreCase))throw new FileNotFoundException("Скачайте движок "+generation.Label+". Другое поколение для этого подключения не используется.");}
      int workers=VkTurnSettings.ServerWorkers(settingsForNode,node.Id,profile.Csqtt25?"csqtt25":"csqtt",autoJs?6:profile.Hashes.Length,profile.Workers);
      if(workers!=18&&!HasWorkerControl(csqttExecutable))throw new InvalidOperationException("Для изменения потоков CSQTT обновите его Windows-модуль; старый движок настройку не поддерживает.");
      string captcha=VkTurnSettings.Server(vkSettings??new VkTurnPreferences(),node.Id).CaptchaMode;if(!String.IsNullOrEmpty(captcha)&&!Encoding.ASCII.GetString(File.ReadAllBytes(csqttExecutable)).Contains("MCRF_CSQTT_CAPTCHA_MODE"))throw new InvalidOperationException("Для выбора режима капчи обновите Windows-модуль CSQTT.");
      bool advanced=VkTurnOptions.NativeOptions(nodeOptions);if(advanced)VkTurnOptions.RequireModule(csqttExecutable);int rawPort=UdpPort();var csqtt=Spawn(csqttExecutable,CsqttGenerations.For(node).Id=="2.1.9"?"":"--mcrf-config-stdin",output,register,workers,String.IsNullOrEmpty(captcha)?"auto":captcha);transport=csqtt;
      var startup=new Dictionary<string,object>{{"peer",Address(profile.Host,profile.Port)},{"listen","127.0.0.1:"+rawPort},{"password",profile.Password},{"hashes",profile.Hashes},{"device_id",VkTurnOptions.Value(nodeOptions,"device_id",DeviceId())},{"generation",NextGeneration()},{"salt",Guid.NewGuid().ToString("N")}};var privateOptions=new Dictionary<string,string>(nodeOptions.Options);if(autoJs)privateOptions["vk_token"]=profile.VkToken;if(advanced)startup["options"]=privateOptions.Where(p=>!new[]{"server","server_port","password","hashes","device_id"}.Contains(p.Key)).ToDictionary(p=>p.Key,p=>p.Value);SendPrivate(csqtt,json.Serialize(startup));
      if(autoJs&&profile.Csqtt25)SendPrivate(csqtt,"VK_JS_BOOTSTRAP:"+Convert.ToBase64String(Encoding.UTF8.GetBytes(json.Serialize(new{token=profile.VkToken}))));
      Wait(csqtt,()=>{lock(gate)return configIP!=null;},error,cancellation,"CSQTT");
      string ipText,dnsText;lock(gate){ipText=configIP;dnsText=configDNS;}
      StartRawBridge(wdttExecutable,rawPort,ipText,dnsText,port,register,log,cancellation);
    }
    public static void StartRawBridge(string executable,int rawPort,string ip,string dns,int socksPort,Action<Process> register,Action<string> log,CancellationToken cancellation,int mtu=1300){
      if(String.IsNullOrEmpty(executable)||!String.Equals(Path.GetFileName(executable),"csqtt-netstack.exe",StringComparison.OrdinalIgnoreCase))throw new FileNotFoundException("Скачайте компонент «Локальный IP-адаптер». Пакет WDTT не требуется.");
      int ready=0;string error="";var child=Spawn(executable,"-csqtt-raw-peer 127.0.0.1:"+rawPort+" -csqtt-ip "+ZapretRuntime.Quote(ip)+" -csqtt-dns "+ZapretRuntime.Quote(dns)+" -socks-listen 127.0.0.1:"+socksPort+" -mtu "+mtu,line=>{if(line.Contains("PROXY_READY|"))Interlocked.Exchange(ref ready,1);else if(line.Contains("IP_ADAPTER_CAPACITY|queued"))log("Локальный IP-адаптер: достигнут предел соединений; новые подключения ожидают освобождения слота, а не сбрасываются.");else if(line.Contains("FATAL")){error="FATAL: локальный IP-адаптер не запустился";log("Локальный IP-адаптер: "+JournalStyle.Redact(line));}},register);
      Wait(child,()=>Volatile.Read(ref ready)!=0,()=>error,cancellation,"Локальный IP-адаптер");log("Локальный IP-адаптер: SOCKS5 готов");
    }
  }
}
