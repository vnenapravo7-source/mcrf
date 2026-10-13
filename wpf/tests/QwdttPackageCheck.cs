using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Web.Script.Serialization;
using SplifyWin;
class QwdttPackageCheck {
 static int count;
 static void Check(bool condition,string message){if(!condition)throw new Exception(message);count++;Console.WriteLine("PASS "+message);}
 static string Stage(OptionalEngines engines){string path=Path.Combine(engines.StorageRoot,".download-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(path);return path;}
 static void Rejected(Action operation,string message){bool fail=false;try{operation();}catch(InvalidDataException){fail=true;}catch(OperationCanceledException){fail=true;}Check(fail,message);}
 static int Main(string[] args){try{
  string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Use a new isolated profile.");
  var core=new NetworkCore(new StateStore(root));var engines=core.Engines;var q=engines.Package("qwdtt");
  Check(engines.Packages.Count()==4,"four optional engines including QWDTT");
  Check(q.Url=="https://github.com/vnenapravo7-source/mcrf/releases/download/engines-2026-10-13-1/mcrf-qwdtt-windows-amd64.zip","QWDTT uses own GitHub release");
  OptionalEngines.ValidateCatalog(new EngineCatalog{Version=1,Engines=engines.Packages.ToList()});Check(true,"published catalog validation");
  var resources=Assembly.GetExecutingAssembly().GetManifestResourceNames();
  Check(!resources.Any(n=>n.EndsWith("qwdtt-client.exe")||n.EndsWith("QWDTT-source.zip")),"QWDTT binary and source NOT embedded in EXE");
  Check(!engines.Installed("qwdtt")&&core.QwdttExecutable==null,"startup does not unpack an optional engine");
  string link=QwdttProfile.Link("QWDTT test","192.0.2.7:56000","test-secret","abcdef_test_hash");var node=Links.Read(link).Single();
  Check(node.Protocol=="qwdtt"&&!core.HasEngine(node),"profile import works before engine download");
  var updates=new AppUpdates(core);var row=updates.LocalItems(new ClientState()).Single(p=>p.Id=="engine:qwdtt");
  Check(row.Kind=="optional"&&row.Package.Id=="qwdtt"&&row.Group=="Дополнительные движки","download offered before update check");
  var old=new EngineCatalog{Version=1,Engines=engines.Packages.Where(p=>p.Id!="qwdtt").ToList()};var migrated=new OptionalEngines(Path.Combine(root,"old-profile"),old);
  Check(migrated.Packages.Count()==4&&migrated.Package("qwdtt").Sha256==q.Sha256,"old three-engine catalog gains QWDTT");
  if(args[1]=="download"){
   engines.Install("qwdtt").GetAwaiter().GetResult();
   Check(engines.State("qwdtt").Percent==100&&!engines.State("qwdtt").Busy&&!engines.State("qwdtt").Error,"real GitHub download completes and verifies");
   Check(Directory.GetDirectories(engines.StorageRoot,".download-*").Length==0,"download staging cleaned");
  } else {
   string archive=Path.GetFullPath(args[1]);engines.InstallArchive(q,archive,Stage(engines),CancellationToken.None);
   string corrupt=Path.Combine(root,"corrupt.zip");File.Copy(archive,corrupt);using(var stream=File.Open(corrupt,FileMode.Open,FileAccess.Write))stream.WriteByte(0);
   Rejected(()=>engines.InstallArchive(q,corrupt,Stage(engines),CancellationToken.None),"tampered archive rejected by SHA-256");
   var cancel=new CancellationTokenSource();cancel.Cancel();Rejected(()=>engines.InstallArchive(q,archive,Stage(engines),cancel.Token),"cancelled extraction rejected");
  }
  Check(engines.Installed("qwdtt")&&core.HasEngine(node),"verified QWDTT ready after installation");
  string exe=core.QwdttExecutable;Check(exe.StartsWith(engines.StorageRoot+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase),"engine uses program data directory");
  Check(File.Exists(Path.Combine(Path.GetDirectoryName(exe),"LICENSE.qwdtt"))&&File.Exists(Path.Combine(Path.GetDirectoryName(exe),"NOTICES.txt")),"license and notices unpacked with engine");
  AppUpdates.ValidatePe(exe);Check(true,"engine is Windows x64 PE");
  using(var process=System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe,"-help"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){
   var output=process.StandardOutput.ReadToEndAsync();var error=process.StandardError.ReadToEndAsync();if(!process.WaitForExit(10000)){process.Kill();throw new Exception("QWDTT help timeout");}
   Check(process.ExitCode==0&&(output.Result+error.Result).Contains("startup-config"),"actual QWDTT executable responds without opening a network session");
  }
  var state=new ClientState{Mode="proxy",AutoSelect=false,SelectedServerId=node.Id,Servers=new System.Collections.Generic.List<ServerNode>{node},Lists=new System.Collections.Generic.List<RouteList>(),IndependentWarpOutputs=true,VpnEnabled=true};
  core.Check(core.WriteConfig(state));Check(true,"sing-box QWDTT SOCKS outbound validates");state.Mode="tun";core.Check(core.WriteConfig(state));Check(true,"QWDTT TUN config validates with helper bypass");
  string original=File.ReadAllText(Path.Combine(Path.GetDirectoryName(exe),"NOTICES.txt"));File.AppendAllText(Path.Combine(Path.GetDirectoryName(exe),"NOTICES.txt"),"tamper");Check(!engines.Installed("qwdtt")&&!core.HasEngine(node),"tampered installed engine not used");
  File.WriteAllText(Path.Combine(Path.GetDirectoryName(exe),"NOTICES.txt"),original,new System.Text.UTF8Encoding(false));Check(engines.Installed("qwdtt"),"original notices restore verification");
  Check(!core.Running,"checks leave VPN and TUN stopped");updates.Dispose();Console.WriteLine("OK "+count+" QWDTT checks");return 0;
 }catch(Exception ex){Console.WriteLine(ex);return 1;}}
}
