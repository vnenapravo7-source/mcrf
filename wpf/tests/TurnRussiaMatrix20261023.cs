using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Diagnostics;
using System.Security.Principal;
using System.Threading;
using System.Web.Script.Serialization;
using SplifyWin;
class TurnRussiaMatrix20261023 {
 static string Option(string[] args,string name){int i=Array.IndexOf(args,name);return i>=0&&i+1<args.Length?args[i+1]:null;}
 static void CopyTree(string src,string dst){Directory.CreateDirectory(dst);foreach(string file in Directory.GetFiles(src))File.Copy(file,Path.Combine(dst,Path.GetFileName(file)),false);foreach(string dir in Directory.GetDirectories(src))CopyTree(dir,Path.Combine(dst,Path.GetFileName(dir)));}
 static int Port(){var l=new TcpListener(IPAddress.Loopback,0);l.Start();int p=((IPEndPoint)l.LocalEndpoint).Port;l.Stop();return p;}
 static string RemoteA(NetworkCore core,string host){
  var info=new ProcessStartInfo(core.CurlExecutable,"--silent --show-error --noproxy \"\" --socks5-hostname 127.0.0.1:19081 --connect-timeout 10 --max-time 15 --cacert \""+Path.Combine(core.DataRoot,"core","curl-ca-bundle.crt")+"\" \"https://dns.google/resolve?name="+host+"&type=A\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
  using(var p=Process.Start(info)){var output=p.StandardOutput.ReadToEndAsync();var error=p.StandardError.ReadToEndAsync();if(!p.WaitForExit(18000)){p.Kill();p.WaitForExit(2000);}System.Threading.Tasks.Task.WaitAll(output,error);if(p.ExitCode!=0)throw new Exception("remote DoH lookup failed, code "+p.ExitCode);var result=new JavaScriptSerializer().Deserialize<System.Collections.Generic.Dictionary<string,object>>(output.Result);var answers=(System.Collections.IEnumerable)result["Answer"];foreach(var answer in answers.Cast<System.Collections.Generic.Dictionary<string,object>>()){string address=Convert.ToString(answer["data"]);IPAddress ip;if(Convert.ToInt32(answer["type"])==1&&IPAddress.TryParse(address,out ip)&&!address.StartsWith("198.18.")&&!address.StartsWith("198.19."))return address;}throw new Exception("remote DNS did not return a real IPv4");}
 }
 static void Page(NetworkCore core,string host,bool fake,string realIp=null){
  string ip=fake?"198.18.1.99":realIp;
  var info=new ProcessStartInfo(core.CurlExecutable,(ip!=null?" --resolve "+host+":443:"+ip:"")+" --silent --show-error --noproxy \"*\" --head --connect-timeout 10 --max-time 15 --cacert \""+Path.Combine(core.DataRoot,"core","curl-ca-bundle.crt")+"\" --output NUL --write-out %{http_code} \"https://"+host+"/\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
  using(var p=Process.Start(info)){var output=p.StandardOutput.ReadToEndAsync();var error=p.StandardError.ReadToEndAsync();if(!p.WaitForExit(18000)){p.Kill();p.WaitForExit(2000);}System.Threading.Tasks.Task.WaitAll(output,error);Console.WriteLine("HTTPS "+host+" fake="+fake+" realAddress="+(realIp??"automatic")+" exit="+p.ExitCode+" HTTP="+output.Result+" "+JournalStyle.Redact(error.Result.Trim()));}
 }
 static int Main(string[] args){
  string root=Path.GetFullPath(args[1]);Directory.CreateDirectory(root);string input=Path.Combine(root,"private-input.dat");
  if(args[0]=="--prepare"){var link=Console.ReadLine();var nodes=Links.Read(link);if(nodes.Count!=1||nodes[0].TurnPreferences==null)throw new Exception("Expected one complete TURN envelope");SecretStorage.Write(input,link);Console.WriteLine("Prepared one encrypted isolated envelope; parameters validated");return 0;}
  using(var log=new StreamWriter(new FileStream(Path.Combine(root,"matrix.log"),FileMode.Create,FileAccess.Write,FileShare.ReadWrite)){AutoFlush=true}){
   Console.SetOut(log);Console.SetError(log);
   if(!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)){Console.WriteLine("Administrator required");return 1;}
   var link=SecretStorage.Read(input);
   foreach(bool rf in new[]{false,true})foreach(bool fake in new[]{false,true}){
    string label=(rf?"rf-direct":"no-rules")+(fake?"-fake":"-normal"),profile=Path.Combine(root,label),tun=TunIdentity.ForProfile(profile);NetworkCore core=null;Timer watchdog=null;
    if(Option(args,"--only")!=null&&Option(args,"--only")!=label)continue;
    try{
     Console.WriteLine("CASE "+label+" START");
     if(NetworkInterface.GetAllNetworkInterfaces().Any(n=>n.GetIPProperties().UnicastAddresses.Any(a=>a.Address.Equals(IPAddress.Parse("172.19.0.1")))))throw new Exception("Existing TUN active; refusing");
     var busy=new TcpListener(IPAddress.Loopback,19081);try{busy.Start();}finally{busy.Stop();}
     Directory.CreateDirectory(profile);RuntimeResources.Prepare(profile);
     foreach(string engine in new[]{"csqtt25","csqtt-bridge"}){if(engine=="csqtt-bridge"&&Option(args,"--catalog")!=null)continue;string src=Path.Combine(args[2],"optional-engines-v2",engine),dst=Path.Combine(profile,"optional-engines-v2",engine);CopyTree(src,dst);}
     if(Option(args,"--catalog")!=null)File.Copy(Option(args,"--catalog"),Path.Combine(profile,"optional-engines-v2","active-catalog.json"));
     var node=Links.Read(link).Single();
     var state=new ClientState{Mode="tun",DnsProvider=Array.IndexOf(args,"--public-dns")>=0?"Cloudflare":"Системный",DnsProtocol=Array.IndexOf(args,"--public-dns")>=0?"DoH":"Системный",AutoSelect=false,SelectedServerId=node.Id,Servers=new System.Collections.Generic.List<ServerNode>{node},IndependentWarpOutputs=true,VpnEnabled=true};
     if(Array.IndexOf(args,"--exclude-curl")>=0)state.ExcludedApplications=new System.Collections.Generic.List<string>{"curl.exe"};
     if(rf)state.Lists.Add(new RouteList{Name="Всё кроме РФ",Text="0.0.0.0/0\n::/0",Target="proxy",MatchMode="addresses",ExcludeRussia=true});
     core=new NetworkCore(new StateStore(profile),Port(),Port(),Port());if(args.Length>3&&args[3]!="--bundled")File.Copy(args[3],Path.Combine(profile,"core","sing-box.exe"),true);if(Option(args,"--catalog")!=null){var p=core.Engines.Package("csqtt-bridge");core.Engines.InstallArchive(p,Option(args,"--bridge"),Path.Combine(profile,"optional-engines-v2",".download-fixture"),CancellationToken.None);Console.WriteLine("BRIDGE verified "+p.Version);}var owned=core;watchdog=new Timer(_=>owned.Stop(),null,Array.IndexOf(args,"--soak")>=0?180000:80000,Timeout.Infinite);core.Start(state);Console.WriteLine("READY "+core.Running);
     foreach(string host in new[]{"ya.ru","www.wildberries.ru","www.youtube.com"})Page(core,host,fake);
     if(!fake&&Array.IndexOf(args,"--real-address")>=0){string ip=RemoteA(core,"ya.ru");Page(core,"ya.ru",false,ip);}
     if(Array.IndexOf(args,"--soak")>=0){var until=DateTime.UtcNow.AddSeconds(120);while(DateTime.UtcNow<until){if(!core.Running)throw new Exception("core or sidecar exited during stability check");Page(core,"www.youtube.com",false);Thread.Sleep(10000);}Console.WriteLine("STABILITY 120 seconds; all owned processes running="+core.Running);}
    }catch(Exception ex){Console.WriteLine("CASE FAIL "+ex.GetType().Name+" "+JournalStyle.Redact(ex.Message));}
    finally{if(watchdog!=null)watchdog.Dispose();if(core!=null)core.Stop();for(int i=0;i<40&&NetworkInterface.GetAllNetworkInterfaces().Any(n=>n.Name==tun);i++)Thread.Sleep(100);Console.WriteLine("CLEANUP "+label+" adapterRemoved="+!NetworkInterface.GetAllNetworkInterfaces().Any(n=>n.Name==tun));}
   }
   Console.WriteLine("MATRIX FINISHED");return 0;
  }
 }
}
