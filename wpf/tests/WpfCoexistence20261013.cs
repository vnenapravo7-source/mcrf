using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Diagnostics;
using System.Reflection;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using SplifyWin;

// Controlled coexistence test. No system proxy, hosts changes, real WARP
// account, external service requests or elevation. --tun-zapret is an explicit
// administrator-only follow-up: changes system routes temporarily via TUN.
class WpfCoexistence20261013 {
 static int checks;
 static readonly JavaScriptSerializer Json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};
 static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 static int FreePort(){var l=new TcpListener(IPAddress.Loopback,0);l.Start();int p=((IPEndPoint)l.LocalEndpoint).Port;l.Stop();return p;}
 static byte[] Read(NetworkStream s,int size){var bytes=new byte[size];int n=0;while(n<size){int next=s.Read(bytes,n,size-n);if(next==0)throw new IOException("Unexpected EOF");n+=next;}return bytes;}
 static void SocksGreeting(int port){using(var c=new TcpClient("127.0.0.1",port)){var s=c.GetStream();s.ReadTimeout=3000;s.Write(new byte[]{5,1,0},0,3);Check(Read(s,2).SequenceEqual(new byte[]{5,0}),"live SOCKS5 listener "+port);}}
 sealed class HttpFixture:IDisposable {
  readonly TcpListener listener;readonly Task task;public int Port,Requests;
  public HttpFixture(string address){listener=new TcpListener(IPAddress.Parse(address),0);listener.Start();Port=((IPEndPoint)listener.LocalEndpoint).Port;task=Task.Run(()=>{while(true){TcpClient c;try{c=listener.AcceptTcpClient();}catch(SocketException){break;}catch(ObjectDisposedException){break;}using(c){var s=c.GetStream();s.ReadTimeout=3000;try{var request=new StringBuilder();while(!request.ToString().Contains("\r\n\r\n")){int b=s.ReadByte();if(b<0||request.Length>16000)break;request.Append((char)b);}if(request.Length==0)continue;Interlocked.Increment(ref Requests);byte[] reply=Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\nMCRF loopback");s.Write(reply,0,reply.Length);}catch(IOException){}}}});}
  public void Dispose(){listener.Stop();task.Wait(4000);}
 }
 static void Request(int proxy,string host,int port){using(var c=new TcpClient("127.0.0.1",proxy)){var s=c.GetStream();s.ReadTimeout=5000;byte[] request=Encoding.ASCII.GetBytes("GET http://"+host+":"+port+"/health HTTP/1.1\r\nHost: "+host+":"+port+"\r\nConnection: close\r\n\r\n");s.Write(request,0,request.Length);using(var r=new StreamReader(s))Check(r.ReadToEnd().Contains("MCRF loopback"),"actual HTTP forwarding to "+host+" through combined runtime");}}
 static Process[] Sidecars(NetworkCore core){return ((List<Process>)typeof(NetworkCore).GetField("sidecars",BindingFlags.Instance|BindingFlags.NonPublic).GetValue(core)).ToArray();}
 static string ReadLog(NetworkCore core){using(var stream=new FileStream(core.LogPath,FileMode.Open,FileAccess.Read,FileShare.ReadWrite))using(var reader=new StreamReader(stream))return reader.ReadToEnd();}
 static void AssertProcesses(NetworkCore core,TelegramBridge bridge){var children=Sidecars(core);Check(core.Running&&bridge.Running&&children.Length==2&&children.All(p=>!p.HasExited),"sing-box, ByeTube, routed TG WS and separate Telegram proxy alive together");Check(children.Select(p=>Path.GetFileName(p.StartInfo.FileName)).OrderBy(n=>n).SequenceEqual(new[]{"ciadpi.exe","tgws.exe"}),"sidecars are real bundled engines, not mocks");}
 static void UpdatedByeTubeBypass(NetworkCore core,string root){
  string folder=Path.Combine(root,"updates","installed","byetube-test");Directory.CreateDirectory(folder);string updated=Path.Combine(folder,"ciadpi.exe");File.Copy(core.ByeTubeExecutable,updated,false);
  using(var updates=new AppUpdates(core)){updates.Preferences.Installed["byetube"]=new UpdateInstallation{Directory=folder,File="ciadpi.exe",Sha256=AppUpdates.HashFile(updated),Version="test"};updates.Save();}
  Check(core.ByeTubeExecutable==updated,"updated ByeTube path used for launch");var state=new ClientState{IndependentWarpOutputs=true,VpnEnabled=false,Mode="tun",Lists=new List<RouteList>()};string config=Json.Serialize(core.Build(state));Check(config.Contains(updated.Replace("\\","\\\\"))&&!config.Contains(Path.Combine(root,"core","ciadpi.exe").Replace("\\","\\\\")),"TUN helper bypass follows updated ByeTube executable, not stale path");
 }
 static int Main(string[] args){NetworkCore core=null;TelegramBridge bridge=null;ZapretRuntime zapret=null;try{
  if(args.Length==0)throw new ArgumentException("Pass a NEW isolated fixture directory, never a user profile.");string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new ArgumentException("Fixture already exists; choose a new path.");bool actualTun=args.Length>1&&args[1]=="--tun-zapret";
  bool admin=new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);if(actualTun&&!admin)throw new UnauthorizedAccessException("Run this explicit follow-up as administrator; test will temporarily create TUN and scoped WinDivert.");
  Directory.CreateDirectory(root);int proxy=FreePort(),tg=FreePort();core=new NetworkCore(new StateStore(root),proxy,FreePort(),tg);bridge=new TelegramBridge();bridge.SetPort(FreePort());zapret=new ZapretRuntime(core);zapret.Prepare();var catalog=ZapretCatalog.Load(zapret.DirectoryPath);var strategy=catalog.First(s=>s.Available&&s.Family=="Flowseal");
  // The core binds WireGuard to the default interface. A 127.0.0.1 fake peer
  // would fail Windows interface binding, so bind OUR local NIC address.
  // No packet is sent to any remote WARP service or other network host.
  var nic=NetworkInterface.GetAllNetworkInterfaces().First(n=>n.OperationalStatus==OperationalStatus.Up&&n.GetIPProperties().GatewayAddresses.Any(g=>g.Address.AddressFamily==AddressFamily.InterNetwork));var local=nic.GetIPProperties().UnicastAddresses.First(a=>a.Address.AddressFamily==AddressFamily.InterNetwork).Address;
  using(var peer=new UdpClient(new IPEndPoint(local,0)))using(var byeHttp=new HttpFixture("127.0.0.1"))using(var directHttp=new HttpFixture("127.0.0.2")){
   int endpoint=((IPEndPoint)peer.Client.LocalEndPoint).Port;var warp=Links.Read("[Interface]\nPrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAddress=172.16.0.2/32\n[Peer]\nPublicKey="+WarpScout.PeerPublicKey+"\nEndpoint="+local+":"+endpoint).Single();
   var profile=new ZapretProfile{Name="Only other domains and IPs",Settings=new ZapretSettings{Family=strategy.Family,Strategy=strategy.Id}};
   var state=new ClientState{IndependentWarpOutputs=true,VpnEnabled=false,WarpEnabled=true,ByeTubeEnabled=true,ByeTubeStrategy=ByeTube.Strategies[0],Mode="proxy",AutoSelect=false,DnsProvider="Системный",DnsProtocol="Системный",Servers=new List<ServerNode>{warp},ZapretProfiles=new List<ZapretProfile>{profile},Lists=new List<RouteList>{
    new RouteList{Name="ByeTube fixture",Target="byetube",MatchMode="addresses",Text="127.0.0.1/32\nbyetube.example"},
    new RouteList{Name="WARP fixture",Target=WarpOutputs.Tag(warp),MatchMode="addresses",Text="198.51.100.22/32\nwarp.example"},
    new RouteList{Name="Telegram fixture",Target="tgws",MatchMode="addresses",Text="149.154.167.0/24\ntgws.example"},
    new RouteList{Name="Zapret fixture",Target="zapret",ZapretProfileId=profile.Id,MatchMode="addresses",Text="203.0.113.27/32\nzapret.example"}}};
   var active=ZapretRoutes.Active(state);Check(active.Length==1&&active[0].Settings.ScopeText.Contains("zapret.example")&&!active[0].Settings.ScopeText.Contains("byetube.example")&&!active[0].Settings.ScopeText.Contains("warp.example")&&!active[0].Settings.ScopeText.Contains("tgws.example"),"Zapret resolves only its separate domains and IPs");
   string text=Json.Serialize(core.Build(state));Check(text.Contains("\"tag\":\"byetube\"")&&text.Contains("\"tag\":\"tgws\"")&&text.Contains("\"tag\":\""+WarpOutputs.Tag(warp)+"\"")&&text.Contains("\"final\":\"direct\""),"all outputs present together; unassigned traffic stays direct");core.Check(core.WriteConfig(state));Check(true,"actual sing-box preflight for simultaneous proxy outputs");state.Mode="tun";core.Check(core.WriteConfig(state));Check(true,"actual sing-box preflight for TUN plus all outputs");state.Mode="proxy";
   bridge.Start(core.TelegramExecutable);core.Start(state);AssertProcesses(core,bridge);SocksGreeting(proxy);SocksGreeting(ByeTube.Port);SocksGreeting(tg);SocksGreeting(bridge.Port);
   for(int i=0;i<3;i++){Request(proxy,"127.0.0.1",byeHttp.Port);Request(proxy,"127.0.0.2",directHttp.Port);}Check(byeHttp.Requests==3&&directHttp.Requests==3,"ByeTube scope and separate direct scope both forward repeatedly");Check(ReadLog(core).Contains("outbound/socks[byetube]"),"real fixture traffic routed into ByeTube, not accidentally sent direct");
   using(var kick=new TcpClient("127.0.0.1",proxy)){var s=kick.GetStream();s.ReadTimeout=3000;s.Write(new byte[]{5,1,0},0,3);Read(s,2);byte[] connect=new byte[]{5,1,0,1,198,51,100,22,1,187};var received=peer.ReceiveAsync();s.Write(connect,0,connect.Length);Check(received.Wait(4000)&&received.Result.Buffer.Length>0,"WARP endpoint emits real WireGuard handshake to local fake peer");Request(proxy,"127.0.0.1",byeHttp.Port);AssertProcesses(core,bridge);}
   string dir=Path.Combine(root,"coexistence-zapret");Directory.CreateDirectory(dir);string filter=Path.Combine(dir,"capture-disabled.bin");string arguments=zapret.BuildProfileArguments(active,catalog,dir)+" --wf-save="+ZapretRuntime.Quote(filter);
   using(var p=OwnedJob.Start(new ProcessStartInfo(zapret.AppExecutable(),arguments){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=zapret.DirectoryPath})){var stdout=p.StandardOutput.ReadToEndAsync();var stderr=p.StandardError.ReadToEndAsync();if(!p.WaitForExit(10000)){p.Kill();throw new TimeoutException("Zapret filter export");}Task.WaitAll(stdout,stderr);Check(p.ExitCode==0&&File.Exists(filter),"real scoped Zapret parameters accepted while other runtimes live; interception NOT started");}
   AssertProcesses(core,bridge);core.Stop();Check(!core.Running&&bridge.Running,"stopping routed core leaves standalone Telegram proxy alive");core.Start(state);AssertProcesses(core,bridge);Request(proxy,"127.0.0.1",byeHttp.Port);
   if(actualTun){core.Stop();state.Mode="tun";core.Start(state);zapret.StartProfiles(active,CancellationToken.None).GetAwaiter().GetResult();Check(core.Running&&zapret.Running&&bridge.Running&&Sidecars(core).All(p=>!p.HasExited),"ACTUAL TUN, WARP endpoint, ByeTube, both TG WS proxies and scoped Zapret alive together");SocksGreeting(ByeTube.Port);SocksGreeting(tg);Thread.Sleep(1500);Check(core.Running&&zapret.Running&&bridge.Running,"combined administrator runtime remains alive");zapret.Stop();core.Stop();}
   core.Stop();bridge.Stop();Check(!core.Running&&!bridge.Running&&!zapret.Running,"all test-owned network processes stopped");UpdatedByeTubeBypass(core,root);
  }
  Console.WriteLine("OK "+checks+" coexistence checks. WARP peer is fake: no successful Cloudflare handshake or service availability claimed. Actual TUN/WinDivert: "+actualTun);return 0;
 }catch(Exception ex){Console.WriteLine(ex);return 1;}finally{if(zapret!=null)zapret.Dispose();if(core!=null)core.Stop();if(bridge!=null)bridge.Dispose();}}
}
