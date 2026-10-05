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
      string value=line??"";foreach(var secret in profile.Hashes.Concat(new[]{profile.Password}).Where(x=>!String.IsNullOrEmpty(x)))value=value.Replace(secret,"<скрыто>");
      value=Regex.Replace(value,@"[a-zA-Z0-9_+/=-]{32,}","<скрыто>");
      return value.Length>500?value.Substring(0,500):value;
    }
    static string Address(string host,int port){return (host.Contains(":")?"["+host+"]":host)+":"+port;}
    static int UdpPort(){using(var socket=new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp)){socket.Bind(new IPEndPoint(IPAddress.Loopback,0));return ((IPEndPoint)socket.LocalEndPoint).Port;}}
    static Process Spawn(string executable,string arguments,Action<string> line,Action<Process> register){
      if(!File.Exists(executable))throw new FileNotFoundException("Не найден встроенный клиентский движок туннеля");
      var info=new ProcessStartInfo(executable,arguments){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(executable),RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8};
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
    public static void Start(ServerNode node,string csqttExecutable,string wdttExecutable,int port,Action<Process> register,Action<string> log,CancellationToken cancellation){
      var json=new JavaScriptSerializer{MaxJsonLength=32768};
      cancellation.ThrowIfCancellationRequested();if(port<1024||port>65535)throw new FormatException("Некорректный локальный порт туннеля");var profile=TurnProfile.Parse(node.Link);string lastError="";int ready=0;string configIP=null,configDNS=null;object gate=new object();
      Action<string> output=line=>{
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
        string args="-startup-config-stdin -mode socks5 -socks-listen 127.0.0.1:"+port+" -listen 127.0.0.1:0 -peer "+ZapretRuntime.Quote(Address(profile.Host,profile.Port))+" -device-id "+ZapretRuntime.Quote(DeviceId())+" -n 18 -hash-fallback=true";
        var process=Spawn(wdttExecutable,args,output,register);string payload=json.Serialize(new{vk_hashes=String.Join(",",profile.Hashes),connection_password=profile.Password});
        process.StandardInput.WriteLine("START_CONFIG|"+Convert.ToBase64String(Encoding.UTF8.GetBytes(payload)).TrimEnd('=').Replace('+','-').Replace('/','_'));process.StandardInput.Flush();
        Wait(process,()=>Volatile.Read(ref ready)!=0,error,cancellation,"WDTT");return;
      }
      int rawPort=UdpPort();var csqtt=Spawn(csqttExecutable,"",output,register);
      csqtt.StandardInput.WriteLine(json.Serialize(new{peer=Address(profile.Host,profile.Port),listen="127.0.0.1:"+rawPort,password=profile.Password,hashes=profile.Hashes,device_id=DeviceId(),generation=NextGeneration(),salt=Guid.NewGuid().ToString("N")}));csqtt.StandardInput.Flush();
      Wait(csqtt,()=>{lock(gate)return configIP!=null;},error,cancellation,"CSQTT");
      string ipText,dnsText;lock(gate){ipText=configIP;dnsText=configDNS;}
      var proxy=Spawn(wdttExecutable,"-csqtt-raw-peer 127.0.0.1:"+rawPort+" -csqtt-ip "+ZapretRuntime.Quote(ipText)+" -csqtt-dns "+ZapretRuntime.Quote(dnsText)+" -socks-listen 127.0.0.1:"+port,output,register);
      Wait(proxy,()=>Volatile.Read(ref ready)!=0,error,cancellation,"CSQTT");
    }
  }
}
