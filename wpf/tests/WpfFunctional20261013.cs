using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using SplifyWin;
class WpfFunctional20261013 {
 static int count;static string root;static NetworkCore core;
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);count++;Console.WriteLine("PASS "+label);}
 static int FreePort(){var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int value=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();return value;}
 static byte[] Read(NetworkStream stream,int count){var data=new byte[count];int read=0;while(read<count){int next=stream.Read(data,read,count-read);if(next==0)throw new IOException("Unexpected EOF");read+=next;}return data;}
 sealed class LocalHttp : IDisposable {
  readonly TcpListener listener=new TcpListener(IPAddress.Loopback,0);readonly Task server;public int Port,Requests;
  public LocalHttp(){listener.Start();Port=((IPEndPoint)listener.LocalEndpoint).Port;server=Task.Run(()=>{while(true){TcpClient client;try{client=listener.AcceptTcpClient();}catch(SocketException){break;}catch(ObjectDisposedException){break;}using(client){var stream=client.GetStream();stream.ReadTimeout=stream.WriteTimeout=3000;var data=new StringBuilder();try{while(!data.ToString().Contains("\r\n\r\n")){int b=stream.ReadByte();if(b<0||data.Length>16000)break;data.Append((char)b);}if(data.Length==0)continue;Interlocked.Increment(ref Requests);byte[] reply=Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\nMCRF loopback");stream.Write(reply,0,reply.Length);}catch(IOException){}}}});}
  public void Dispose(){listener.Stop();server.Wait(4000);}
 }
 static void Proxy(int proxyPort){
  using(var http=new LocalHttp()){
   using(var socket=new TcpClient("127.0.0.1",proxyPort)){var stream=socket.GetStream();stream.ReadTimeout=5000;byte[] get=Encoding.ASCII.GetBytes("GET http://127.0.0.1:"+http.Port+"/health HTTP/1.1\r\nHost: 127.0.0.1:"+http.Port+"\r\nConnection: close\r\n\r\n");stream.Write(get,0,get.Length);using(var reader=new StreamReader(stream))Check(reader.ReadToEnd().Contains("MCRF loopback"),"real HTTP proxy forwards only to local fixture");}
   using(var socket=new TcpClient("127.0.0.1",proxyPort)){var stream=socket.GetStream();stream.ReadTimeout=5000;stream.Write(new byte[]{5,1,0},0,3);Check(Read(stream,2).SequenceEqual(new byte[]{5,0}),"real SOCKS5 negotiates authentication");var connect=new byte[]{5,1,0,1,127,0,0,1,(byte)(http.Port>>8),(byte)http.Port};stream.Write(connect,0,connect.Length);var header=Read(stream,4);Check(header[1]==0,"real SOCKS5 connects to loopback destination");int address=header[3]==1?4:header[3]==4?16:Read(stream,1)[0];Read(stream,address+2);byte[] get=Encoding.ASCII.GetBytes("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");stream.Write(get,0,get.Length);using(var reader=new StreamReader(stream))Check(reader.ReadToEnd().Contains("MCRF loopback"),"real SOCKS5 forwards local HTTP");}
   Check(http.Requests==2,"both transports reached only controlled loopback fixture");
  }
 }
 static void ByeTubeChecks(){int index=0;foreach(string strategy in ByeTube.Strategies){int port=FreePort();using(var process=ByeTube.Start(Path.Combine(core.DataRoot,"core","ciadpi.exe"),strategy,m=>{},CancellationToken.None,port)){try{using(var socket=new TcpClient("127.0.0.1",port)){var stream=socket.GetStream();stream.ReadTimeout=3000;stream.Write(new byte[]{5,1,0},0,3);Check(Read(stream,2).SequenceEqual(new byte[]{5,0}),"ByeTube strategy "+(++index)+" launches and accepts SOCKS5");}}finally{if(!process.HasExited){process.Kill();process.WaitForExit(1500);}}}}}
 static void WarpAndTunChecks(){
  string config="[Interface]\nPrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAddress=172.16.0.2/32\n[Peer]\nPublicKey="+WarpScout.PeerPublicKey+"\nEndpoint=198.51.100.77:2408";var warp=Links.Read(config).Single();warp.Name="WARP diagnostic";
  var state=new ClientState{VpnEnabled=false,IndependentWarpOutputs=true,WarpEnabled=true,Mode="proxy",AutoSelect=false,DnsProvider="Системный",DnsProtocol="Системный",Servers=new List<ServerNode>{warp},Lists=new List<RouteList>{new RouteList{Name="Scoped WARP",Text="warp.example",Target=WarpOutputs.Tag(warp),MatchMode="addresses"}}};
  core.Check(core.WriteConfig(state));Check(!core.Running,"WARP WireGuard configuration passes actual engine preflight without connecting");
  var generated=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue}.Serialize(core.Build(state));Check(generated.Contains("\"final\":\"direct\"")&&!generated.Contains("chess.com"),"WARP keeps direct default and has only assigned route");
  state.Mode="tun";core.Check(core.WriteConfig(state));Check(!core.Running,"TUN plus WARP configuration passes actual preflight without adapter creation");
  state.Mode="proxy";warp.Protocol="awg";warp.Link=warp.Link.Replace("[Interface]","[Interface]\nJc=4\nJmin=40\nJmax=70\nS1=0\nS2=0\nH1=1\nH2=2\nH3=3\nH4=4");core.Check(core.WriteConfig(state));Check(!core.Running,"AmneziaWG configuration accepted by bundled sing-box fork");
  Check(WarpScout.CountryArguments("foreign").Contains("RU")&&WarpScout.CountryArguments("DE").Contains("DE"),"WARP country controls bind to scout arguments");
 }
 static ZapretStrategy[] Catalog(ZapretRuntime runtime){runtime.Prepare();var catalog=ZapretCatalog.Load(runtime.DirectoryPath).Where(s=>s.Available).ToArray();foreach(string family in new[]{"Flowseal","V","YouTube"})Check(catalog.Any(s=>s.Family==family),"catalog includes usable "+family);return catalog;}
 static void ZapretChecksAll(ZapretRuntime runtime,ZapretStrategy[] catalog){var errors=new List<string>();foreach(var strategy in catalog.Where(s=>s.Family!="Свои")){
   var settings=new ZapretSettings{Strategy=strategy.Id,Family=strategy.Family,ScopeText="example.test\n198.51.100.23/32\napp:fixture.exe",MatchMode="any"};string dir=Path.Combine(root,"strategy-preflight",count.ToString());Directory.CreateDirectory(dir);string filter=Path.Combine(dir,"filter.bin");
   try{string options=runtime.BuildArguments(settings,strategy,dir)+" --wf-save="+ZapretRuntime.Quote(filter);using(var process=OwnedJob.Start(new ProcessStartInfo(runtime.AppExecutable(),options){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=runtime.DirectoryPath})){var output=process.StandardOutput.ReadToEndAsync();var error=process.StandardError.ReadToEndAsync();if(!process.WaitForExit(8000)){process.Kill();throw new TimeoutException("argument check");}Task.WaitAll(output,error);if(process.ExitCode!=0||!File.Exists(filter))throw new Exception("exit="+process.ExitCode+" ("+process.ExitCode.ToString("X")+") filter="+File.Exists(filter)+" stdout="+output.Result+" stderr="+error.Result);Check(true,"Zapret "+strategy.ShortName+" arguments accepted with capture disabled");}}
   catch(Exception ex){errors.Add(strategy.Id+": "+ex.Message);}
   // Cygwin startup under a tight subprocess batch can fail before main with
   // STATUS_DLL_INIT_FAILED. Pace the diagnostic batch; never retry or hide a failure.
   Thread.Sleep(120);
  }Check(errors.Count==0,"every advertised Zapret strategy preflight passed: "+String.Join(" | ",errors));
 }
 static void GeneratorChecks(ZapretStrategy[] catalog,ZapretRuntime runtime){
  var bases=catalog.Where(s=>s.Family!="Свои"&&!String.IsNullOrEmpty(s.Tcp)).ToArray();var definitions=AdaptiveStrategySearch.Initial(bases,"Diagnostic generation",12,bases[0].Id);var report=new ZapretCheckReport{Settings=new ZapretSettings{CheckServices=new List<string>{"youtube"}},ServiceIds=new List<string>{"youtube"}};
  Func<ZapretCheckReport,ZapretStrategy[],CancellationToken,Action<string>,Action<ZapretCheckReport>,Task> scan=(r,variants,token,say,save)=>{foreach(var v in variants){token.ThrowIfCancellationRequested();r.Rows.Add(new ZapretCheckRow{StrategyId=v.Id,Name=v.Name,Family=v.Family,CheckedAt=DateTime.Now,Services=new List<ZapretServiceResult>{new ZapretServiceResult{Id="youtube",Access=ServiceAccess.Available,Milliseconds=100,SuccessfulRequests=2}}});}save(r);return Task.FromResult(0);};
  AdaptiveStrategySearch.Run(report,definitions,bases,"Diagnostic generation",12,CancellationToken.None,scan,m=>{},r=>ZapretChecks.Save(Path.Combine(root,"generator"),r)).GetAwaiter().GetResult();
  Check(report.Complete&&report.Adaptive&&report.Rows.Count<=12,"generator respects budget and marks completed search");var winner=AdaptiveStrategySearch.BestForService(report,"youtube",true);Check(winner!=null&&report.Rows.Count(r=>r.StrategyId==winner.StrategyId)>=3,"generator requires repeated confirmation");
  var checkedDefs=definitions.Where(d=>d.Id==winner.StrategyId).ToArray();CustomStrategies.SaveChecked(runtime.DirectoryPath,checkedDefs,bases,report);Check(CustomStrategies.Read(runtime.DirectoryPath).Any(d=>d.Id==winner.StrategyId),"generator saves confirmed copy, not active routes");
  var cancelled=new CancellationTokenSource();cancelled.Cancel();bool stopped=false;try{AdaptiveStrategySearch.Run(new ZapretCheckReport{Settings=report.Settings},definitions,bases,"Cancelled",12,cancelled.Token,scan,m=>{},r=>{}).GetAwaiter().GetResult();}catch(OperationCanceledException){stopped=true;}Check(stopped,"generator cancellation prevents further trials");
  Check(ZapretChecks.Load(Path.Combine(root,"generator")).Rows.Count==report.Rows.Count,"wizard result model saves and restores every trial");
 }
 static void WizardChecks(ZapretStrategy[] catalog){
  var settings=new ZapretSettings{CheckServices=new List<string>{"facebook"},TestUrls="https://www.facebook.com/",ScopeText="facebook.com",MatchMode="addresses"};var report=new ZapretCheckReport{Settings=settings};int starts=0,stops=0,requests=0;var runner=new ZapretCheckRunner((s,v,t)=>{starts++;return Task.FromResult(0);},()=>stops++, (url,t)=>{requests++;return Task.FromResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Available,Milliseconds=10,Detail="Controlled fixture, not real service"});},m=>{});
  runner.Run(report,catalog.Take(2),CancellationToken.None,m=>{},r=>ZapretChecks.Save(Path.Combine(root,"wizard"),r)).GetAwaiter().GetResult();Check(report.Complete&&report.Rows.Count==3&&starts==2&&stops>=3,"wizard checks baseline and selected strategies and always stops runtime");Check(requests==6&&report.Rows.All(r=>r.Access("facebook")==ServiceAccess.Available),"wizard checks HTTP twice per result");Check(ZapretChecks.Load(Path.Combine(root,"wizard")).Rows.Count==3,"wizard results survive restart");
  var cancel=new CancellationTokenSource();var partial=new ZapretCheckReport{Settings=settings};var cancelledRunner=new ZapretCheckRunner((s,v,t)=>{cancel.Cancel();t.ThrowIfCancellationRequested();return Task.FromResult(0);},()=>stops++, (url,t)=>Task.FromResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Available,Detail="Fixture"}),m=>{});bool cancelled=false;try{cancelledRunner.Run(partial,catalog.Take(2),cancel.Token,m=>{},r=>ZapretChecks.Save(Path.Combine(root,"wizard-cancel"),r)).GetAwaiter().GetResult();}catch(OperationCanceledException){cancelled=true;}Check(cancelled&&!partial.Complete&&partial.Rows.All(r=>!r.Checking)&&ZapretChecks.Load(Path.Combine(root,"wizard-cancel")).Rows.Count==2,"wizard cancellation retains partial results and clears running flags");
  var ambiguous=ZapretChecks.Combine(ZapretChecks.Services.First(s=>s.Id=="facebook"),new[]{new ZapretEndpointResult{Access=ServiceAccess.Available}},new[]{new ZapretEndpointResult{Access=ServiceAccess.Unavailable}});Check(ambiguous.Access==ServiceAccess.Unknown,"unstable one-of-two response never marked successful");
 }
 static void DnsAndByeTubeConfigChecks(){
  var state=new ClientState{VpnEnabled=false,IndependentWarpOutputs=true,Mode="proxy",AutoSelect=false,Lists=new List<RouteList>()};foreach(string provider in DnsOptions.Providers.Where(p=>p!="Свой"))foreach(string protocol in DnsOptions.Protocols(provider)){state.DnsProvider=provider;state.DnsProtocol=protocol;core.Check(core.WriteConfig(state));Check(true,"DNS preflight "+provider+" "+protocol);}
  state.DnsProvider="Системный";state.DnsProtocol="Системный";state.ByeTubeEnabled=true;state.ByeTubeStrategy=ByeTube.Strategies[0];state.Lists.Add(new RouteList{Name="Scoped ByeTube",Text="youtube.example",MatchMode="addresses",Target="byetube"});core.Check(core.WriteConfig(state));string json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue}.Serialize(core.Build(state));Check(json.Contains("\"final\":\"direct\"")&&json.Contains("\"network\":\"udp\"")&&json.Contains("\"tag\":\"byetube\""),"ByeTube proxy configuration keeps default direct and blocks QUIC only for its route");
 }
 static int Main(string[] args){try{root=Path.GetFullPath(args[0]);Directory.CreateDirectory(root);int proxy=FreePort();core=new NetworkCore(new StateStore(root),proxy,FreePort(),FreePort());
   if(args.Length>1&&args[1]=="--live-updates"){using(var updater=new AppUpdates(core)){updater.Check(new ClientState{Lists=new List<RouteList>()}).GetAwaiter().GetResult();foreach(var row in updater.Items)Console.WriteLine(row.Id+" | "+(row.CurrentLabel??row.Current)+" | "+(row.VersionLabel??row.Version)+" | "+row.Status);Check(updater.Items[0].Id=="app"&&!updater.Items.Any(i=>i.Kind=="catalog"),"live update sources preserve ordering and hide catalogs");Check(updater.Items.Count(i=>i.Id.StartsWith("engine:"))==4,"live catalog contains four optional engines exactly once");}return 0;}
   var state=new ClientState{VpnEnabled=false,IndependentWarpOutputs=true,Mode="proxy",AutoSelect=false,DnsProvider="Системный",DnsProtocol="Системный",Lists=new List<RouteList>()};core.Start(state);try{Check(core.Running,"actual sing-box launched on isolated loopback ports");Proxy(proxy);}finally{core.Stop();}
   WarpAndTunChecks();using(var runtime=new ZapretRuntime(core)){var catalog=Catalog(runtime);if(args.Length>1&&args[1]=="--yt05"){for(int replay=0;replay<12;replay++)ZapretChecksAll(runtime,catalog.Where(s=>s.Id=="YouTube:05").ToArray());return 0;}ZapretChecksAll(runtime,catalog);GeneratorChecks(catalog,runtime);WizardChecks(catalog);}DnsAndByeTubeConfigChecks();ByeTubeChecks();Check(!core.Running,"all test-owned engines stopped");Console.WriteLine("OK "+count+" functional checks; no TUN adapter or packet capture started");return 0;
  }catch(Exception ex){Console.WriteLine(ex);return 1;}finally{if(core!=null)core.Stop();}}
}
