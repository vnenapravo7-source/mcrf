using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Collections.Generic;
using System.Diagnostics;
using System.Web.Script.Serialization;
using SplifyWin;
class VkTurnPublishedChecks {
 static int count;static void Check(bool v,string n){if(!v)throw new Exception(n);count++;Console.WriteLine("PASS "+n);}
 static int Port(){var listener=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0);listener.Start();int port=((System.Net.IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();return port;}
 static int Main(string[] args){try{
  string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("fresh root required");var core=new NetworkCore(new StateStore(root));foreach(string id in new[]{"csqtt-bridge","qwdtt"}){core.Engines.Install(id).GetAwaiter().GetResult();Check(core.Engines.Installed(id),"public GitHub download verifies archive and every file: "+id);}
  Check(core.CsqttBridgeExecutable!=null&&core.WDTTExecutable==null,"verified independent IP adapter available without WDTT");Check(Encoding.ASCII.GetString(File.ReadAllBytes(core.QwdttExecutable)).Contains("MCRF_QWDTT_RAW_CONFIG"),"downloaded QWDTT has actual RAW relay capability, not UI-only switch");
  var node=Links.Read(QwdttProfile.Link("fixture","192.0.2.1:56000","fixture",new string('A',43))+"&mode=raw").Single();Check(core.HasEngine(node)&&CsqttGenerations.RequiredPackages(core,new[]{node}).Length==0,"RAW QWDTT dependencies satisfied without WDTT");
  var serializer=new JavaScriptSerializer();var embedded=new EngineCatalog{Version=1,Engines=core.Engines.Packages.ToList()};var old=serializer.Deserialize<EngineCatalog>(serializer.Serialize(embedded));old.Engines.RemoveAll(p=>p.Id=="csqtt-bridge");old.Engines.Single(p=>p.Id=="qwdtt").Version="engines-2026-10-13-1";var merged=OptionalEngines.MergeEmbeddedRequirements(old,embedded);Check(merged.Engines.Any(p=>p.Id=="csqtt-bridge")&&merged.Engines.Single(p=>p.Id=="qwdtt").Version=="vkturn-20261022-1","old active catalogue cannot hide bridge or keep obsolete QWDTT");
  var children=new List<Process>();using(var peer=new System.Net.Sockets.UdpClient(0)){TurnEngines.StartRawBridge(core.CsqttBridgeExecutable,((System.Net.IPEndPoint)peer.Client.LocalEndPoint).Port,"10.66.66.2","1.1.1.1",Port(),p=>children.Add(p),s=>{},CancellationToken.None,1280);}Check(children.Count==1,"published dedicated adapter starts through production runtime with server MTU 1280");foreach(var child in children){child.StandardInput.WriteLine("STOP");child.StandardInput.Flush();Check(child.WaitForExit(3000),"published adapter releases resources on private STOP");child.Dispose();}
  Check(!core.Running&&!Directory.GetFiles(root,"qwdtt-start-*.json").Any(),"verification leaves no VPN or plaintext QWDTT startup file");Console.WriteLine("RESULT "+count+" checks PASS");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
