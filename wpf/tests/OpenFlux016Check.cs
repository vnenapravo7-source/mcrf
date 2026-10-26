using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Collections.Generic;
using System.Threading;
using System.Web.Script.Serialization;
using SplifyWin;
class OpenFlux016Check {
 static int count;
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);Console.WriteLine("PASS "+label);count++;}
 static int Main(string[] args){try{
  var json=new JavaScriptSerializer();var catalog=json.Deserialize<EngineCatalog>(File.ReadAllText(args[0]));OptionalEngines.ValidateCatalog(catalog);
  var package=catalog.Engines.Single(p=>p.Id=="openflux");Check(package.Version=="0.16.0","pinned version");
  var old=json.Deserialize<EngineCatalog>(json.Serialize(catalog));old.Engines.Single(p=>p.Id=="openflux").Version="engines-2026-10-02-1";old.Engines.Single(p=>p.Id=="openflux").Url="https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/mcrf-openflux-windows-amd64.zip";var unaffected=old.Engines.Single(p=>p.Id=="qwdtt");
  var merged=OptionalEngines.MergeEmbeddedRequirements(old,catalog);Check(merged.Engines.Single(p=>p.Id=="openflux").Version=="0.16.0","cached catalog upgrades OpenFlux");Check(Object.ReferenceEquals(unaffected,merged.Engines.Single(p=>p.Id=="qwdtt")),"QWDTT remains unchanged");
  var engines=new OptionalEngines(args[2],catalog);var staging=Path.Combine(engines.StorageRoot,".download-fixture");Directory.CreateDirectory(staging);engines.InstallArchive(package,args[1],staging,CancellationToken.None);Check(engines.Installed("openflux"),"upstream executable verified and installed");
  var bad=json.Deserialize<EngineCatalog>(json.Serialize(catalog));bad.Engines.Single(p=>p.Id=="openflux").Url=package.Url+"?untrusted=1";bool rejected=false;try{OptionalEngines.ValidateCatalog(bad);}catch(InvalidDataException){rejected=true;}Check(rejected,"untrusted URL rejected");
  foreach(var type in new[]{"fluxfast","direct","telemost","yandex"}){
   var cfg=new Dictionary<string,object>{{"name","Fixture"},{"negotiate",true},{"secret","fixture-only-secret-0123456789"},{"context","fixture-context"},{"transports",new[]{new Dictionary<string,object>{{"type",type},{"name","carrier"},{"dial","127.0.0.1:28445"},{"url","https://example.invalid/document"},{"priority",100}}}}};
   byte[] packed;using(var buffer=new MemoryStream()){using(var compressor=new DeflateStream(buffer,CompressionMode.Compress,true)){var bytes=Encoding.UTF8.GetBytes(json.Serialize(cfg));compressor.Write(bytes,0,bytes.Length);}packed=buffer.ToArray();}var link="openflux://v1/"+Convert.ToBase64String(packed).TrimEnd('=').Replace('+','-').Replace('/','_');
   var psi=new ProcessStartInfo(args[1],"--parse-link -"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};
   using(var p=Process.Start(psi)){p.StandardInput.WriteLine(link);p.StandardInput.Close();var output=p.StandardOutput.ReadToEnd();var error=p.StandardError.ReadToEnd();if(!p.WaitForExit(5000)){p.Kill();throw new Exception("parser timeout");}Check(p.ExitCode==0&&output.Contains("\"config\""),type+" share link accepted by actual 0.16 CLI");}
   var ini=NetworkCore.OpenFluxConfig(cfg,"fixture-secret.txt",19081);Check(ini.Contains("Type = "+type)&&ini.Contains("Dial = 127.0.0.1:28445")&&ini.Contains("Priority = 100"),type+" config preserves transport fields");Check(ini.Contains("SessionContext = fixture-context")&&!ini.Contains("fixture-only-secret"),type+" context preserved and secret absent from config");
  }
  var minimal=new Dictionary<string,object>{{"transports",new[]{new Dictionary<string,object>{{"type","fluxfast"},{"dial","127.0.0.1:28445"}}}}};Check(NetworkCore.OpenFluxConfig(minimal,"secret.txt",19081).Contains("SessionContext = \n"),"omitted optional context handled");
  var secretPath=Path.Combine(args[2],"fixture-secret.txt");File.WriteAllText(secretPath,"fixture-only-secret-0123456789");
  var reservation=new TcpListener(IPAddress.Loopback,0);reservation.Start();int port=((IPEndPoint)reservation.LocalEndpoint).Port;reservation.Stop();
  var local=new Dictionary<string,object>{{"secret","fixture-only-secret-0123456789"},{"context","fixture-context"},{"transports",new[]{new Dictionary<string,object>{{"type","fluxfast"},{"dial","127.0.0.1:28445"}}}}};var confPath=Path.Combine(args[2],"fixture.conf");File.WriteAllText(confPath,NetworkCore.OpenFluxConfig(local,secretPath,port));
  using(var client=Process.Start(new ProcessStartInfo(args[1],"--config=\""+confPath+"\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){
   client.BeginOutputReadLine();client.BeginErrorReadLine();bool ready=false;try{for(int attempt=0;attempt<60&&!client.HasExited;attempt++){try{using(var socket=new TcpClient()){var pending=socket.ConnectAsync(IPAddress.Loopback,port);if(pending.Wait(50)&&socket.Connected){ready=true;break;}}}catch{}Thread.Sleep(50);}Check(ready,"actual FluxFast client accepts generated config and opens loopback SOCKS");}finally{if(!client.HasExited)client.Kill();client.WaitForExit(3000);}
  }
  Console.WriteLine("RESULT "+count+" checks PASS; no working profile or external connection");return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
