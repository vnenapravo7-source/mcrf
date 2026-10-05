using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace SplifyWin {
  public sealed class GamePreset {
    public string Name;public string[] Processes;
    public RouteList CreateRoute(){return new RouteList{Name="Игра · "+Name,Text=String.Join("\n",Processes.Select(x=>"app:"+x)),Target="proxy",MatchMode="apps"};}
  }
  public static class GameCatalog {
    static GamePreset G(string name,params string[] processes){return new GamePreset{Name=name,Processes=processes};}
    public static readonly GamePreset[] Items={
      G("WARDOGS","WardogsClient-Win64-Shipping.exe"),G("Counter-Strike 2","cs2.exe"),G("Dota 2","dota2.exe"),G("Deadlock","project8.exe","deadlock.exe"),
      G("VALORANT","VALORANT-Win64-Shipping.exe"),G("PUBG: BATTLEGROUNDS","TslGame.exe"),
      G("Fortnite","FortniteClient-Win64-Shipping.exe"),G("Marvel Rivals","Marvel-Win64-Shipping.exe")
    };
  }
  public sealed class GameRecording {
    public GamePreset Game{get;private set;}public bool Running{get;private set;}
    readonly HashSet<string> domains=new HashSet<string>(StringComparer.OrdinalIgnoreCase),ips=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    public int DomainCount{get{return domains.Count;}}public int IpCount{get{return ips.Count;}}
    public void Start(GamePreset game){Game=game;domains.Clear();ips.Clear();Running=true;}
    public void Stop(){Running=false;}
    public void Observe(IEnumerable<Dictionary<string,string>> connections){if(!Running||Game==null)return;
      foreach(var meta in connections){string process;meta.TryGetValue("processPath",out process);if(String.IsNullOrEmpty(process))meta.TryGetValue("process",out process);if(String.IsNullOrEmpty(process))continue;
        string executable=process.Replace('/','\\').Split('\\').Last();if(!Game.Processes.Contains(executable,StringComparer.OrdinalIgnoreCase))continue;
        string value;if(meta.TryGetValue("destinationIP",out value)){IPAddress address;if(IPAddress.TryParse(value,out address)&&!IPAddress.IsLoopback(address)&&!address.Equals(IPAddress.Any)&&!address.Equals(IPAddress.IPv6Any))ips.Add(address.ToString()+(address.AddressFamily==AddressFamily.InterNetwork?"/32":"/128"));}
        if(meta.TryGetValue("host",out value)&&Regex.IsMatch(value??"",@"^(?=.{1,253}$)[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)+$"))domains.Add(value.ToLowerInvariant());
      }
    }
    public RouteList CreateRoute(){if(Game==null||IpCount+DomainCount==0)throw new InvalidOperationException("Пока не записано адресов выбранного процесса. Используйте приложение и повторите запись.");return new RouteList{Name="Запись · "+Game.Name,Target="proxy",MatchMode="in-apps",Text=String.Join("\n",Game.Processes.Select(x=>"app:"+x).Concat(domains.OrderBy(x=>x)).Concat(ips.OrderBy(x=>x)))};}
  }
  public sealed class TelegramBridge : IDisposable {
    Process process;public const int DefaultPort=19071;public int Port{get;private set;}public TelegramBridge(){Port=DefaultPort;}
    public string SetupLink{get{return "tg://socks?server=127.0.0.1&port="+Port;}}
    public void SetPort(int port){if(Running)throw new InvalidOperationException("Сначала выключите Telegram WS.");if(port<1024||port>65535)throw new ArgumentOutOfRangeException("port","Порт должен быть от 1024 до 65535.");Port=port;}
    public bool Running{get{try{return process!=null&&!process.HasExited;}catch{return false;}}}
    public void Start(string executable){if(Running)return;Stop();var test=new TcpListener(IPAddress.Loopback,Port);try{test.ExclusiveAddressUse=true;test.Start();}catch(SocketException ex){throw new TelegramPortException("Порт "+Port+" занят или недоступен. Чужие процессы не остановлены.",ex);}finally{test.Stop();}
      process=OwnedJob.Start(new ProcessStartInfo(executable,"-host 127.0.0.1 -port "+Port){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(executable)});
      try{for(int i=0;i<30;i++){if(!Running)throw new InvalidOperationException("Telegram WS завершился при запуске. Смена порта не предлагается без подтверждения проблемы с портом.");try{using(var socket=new TcpClient())if(socket.ConnectAsync("127.0.0.1",Port).Wait(100)&&socket.Connected)return;}catch{}Thread.Sleep(100);}throw new TelegramPortException("Telegram WS не открыл порт "+Port+".",null);}catch{Stop();throw;}
    }
    public void Stop(){if(process==null)return;try{if(!process.HasExited){process.Kill();process.WaitForExit(1000);}}finally{process.Dispose();process=null;}}
    public void Dispose(){Stop();}
  }
  public sealed class TelegramPortException : IOException {public TelegramPortException(string message,Exception inner):base(message,inner){}}
  public sealed class WarpPickResult {public ServerNode Node;public int Milliseconds;}
  public static class WarpPicker {
    public static bool IsWarp(ServerNode node){if(node==null||(node.Protocol!="wireguard"&&node.Protocol!="awg"))return false;try{var fields=Links.WireGuardFields(node.Link);string key;return fields.TryGetValue("PublicKey",out key)&&key.Trim()==WarpScout.PeerPublicKey;}catch{return false;}}
    public static async Task<WarpPickResult> Pick(NetworkCore core,ServerNode source,CancellationToken cancellation,Action<string> progress){
      if(!IsWarp(source))throw new InvalidOperationException("Выберите импортированный WARP-профиль Cloudflare, а не обычный WireGuard-сервер.");
      var hosts=new List<string>{source.Host};try{hosts.AddRange((await Dns.GetHostAddressesAsync(source.Host)).Select(x=>x.ToString()));}catch{}var candidates=hosts.Distinct().Take(8).ToArray();WarpPickResult best=null;
      foreach(var host in candidates){cancellation.ThrowIfCancellationRequested();if(progress!=null)progress("Проверяем WARP "+host+"…");
        string endpoint=(host.Contains(":")?"["+host+"]":host)+":"+source.Port;
        var link=Regex.Replace(source.Link,@"(?im)^\s*Endpoint\s*=.*$","Endpoint = "+endpoint);var nodes=Links.Read(link);if(nodes.Count!=1)throw new FormatException("Не удалось подготовить WARP-профиль.");var node=nodes[0];node.Name="WARP · "+host;node.Id=source.Id;node.Source=source.Source;
        var watch=Stopwatch.StartNew();string response=null;try{response=await core.ProbeIsolatedAsync(node,"https://www.cloudflare.com/cdn-cgi/trace",cancellation);}catch(OperationCanceledException){throw;}catch{continue;}
        if(!Regex.IsMatch(response??"",@"(?m)^warp=(?:on|plus)\s*$"))continue;
        if(best==null||watch.ElapsedMilliseconds<best.Milliseconds)best=new WarpPickResult{Node=node,Milliseconds=(int)watch.ElapsedMilliseconds};
      }
      if(best==null)throw new InvalidOperationException("Ни один проверенный выход не подтвердил warp=on. Текущий профиль не изменён.");return best;
    }
  }
  // Application conditions are kept per rule; merging is for display/check targets only.
  public static class ZapretScope {
    public static string Merge(IEnumerable<RouteList> scopes){
      var lines=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
      foreach(var scope in scopes){var fields=Validate(scope);foreach(var value in fields["domain"])lines.Add("DOMAIN,"+value);foreach(var value in fields["domain_suffix"])lines.Add(value);foreach(var value in fields["ip_cidr"])lines.Add(value);foreach(var value in fields["process_name"])lines.Add("app:"+value);foreach(var value in fields["process_path"])lines.Add("path:"+value);}
      return String.Join("\n",lines.OrderBy(x=>x,StringComparer.OrdinalIgnoreCase));
    }
    public static Dictionary<string,List<string>> Validate(RouteList scope){var fields=RouteCompiler.Parse(scope.Text);string mode=scope.MatchMode??"any";
      if(!new[]{"any","apps","addresses","in-apps","except-apps"}.Contains(mode))throw new FormatException("Неизвестное условие Zapret");
      bool apps=fields["process_name"].Count+fields["process_path"].Count>0,addresses=fields["domain"].Count+fields["domain_suffix"].Count+fields["ip_cidr"].Count>0;
      if((mode=="apps"&&!apps)||(mode=="addresses"&&!addresses)||((mode=="in-apps"||mode=="except-apps")&&(!apps||!addresses))||(!apps&&!addresses))throw new InvalidOperationException("Укажите записи для выбранного условия Zapret. Пустой глобальный запуск запрещён.");
      if(fields["process_name"].Count+fields["process_path"].Count>256)throw new FormatException("Допускается до 256 приложений в одном списке Zapret");
      foreach(var value in fields["process_name"])if(!Regex.IsMatch(value,@"^[^\\/:*?\""<>|\r\n\x00]+\.exe$",RegexOptions.IgnoreCase))throw new FormatException("Приложение задаётся как app:program.exe без пути и масок");
      foreach(var value in fields["process_path"])if(value.Length>2040||!Regex.IsMatch(value,@"^[a-zA-Z]:\\[^*?\""<>|\r\n\x00]+\.exe$",RegexOptions.IgnoreCase))throw new FormatException("Укажите полный путь path:C:\\…\\program.exe без масок");
      return fields;
    }
  }
}
