using System;
using System.IO;
using System.Linq;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Security.Principal;
using System.Threading;
using System.Web.Script.Serialization;
using SplifyWin;
class TunAdapterChecks {
 static int Main(string[] args){string result=null;try{
  if(!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator))return 3;
  string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh isolated profile required");Directory.CreateDirectory(root);result=Path.Combine(root,"result.txt");var core=new NetworkCore(new StateStore(root),49141,49142,49143);var state=new ClientState{Mode="tun",AutoSelect=false,VpnEnabled=false,DnsProvider="Системный",DnsProtocol="Системный"};var config=core.Build(state);var inbound=((IEnumerable)config["inbounds"]).Cast<Dictionary<string,object>>().Single(x=>Convert.ToString(x["type"])=="tun");string name=TunIdentity.ForProfile(root);inbound["auto_route"]=false;inbound["strict_route"]=false;inbound["address"]=new[]{"172.30.252.1/30"};inbound["dns_mode"]="disabled";
  // A real Wintun session, deliberately no auto-route, DNS hijack or external peer.
  string file=Path.Combine(root,"test.json");File.WriteAllText(file,new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue}.Serialize(config));
  for(int i=0;i<3;i++){using(var p=Process.Start(CoreProcessHost.StartInfo(core.Executable,file))){var stderr=p.StandardError.ReadToEndAsync();var stdout=p.StandardOutput.ReadToEndAsync();try{bool ready=false;for(int n=0;n<150&&!p.HasExited;n++){ready=NetworkInterface.GetAllNetworkInterfaces().Any(a=>a.Name==name&&a.OperationalStatus==OperationalStatus.Up&&a.GetIPProperties().UnicastAddresses.Any(u=>u.Address.ToString()=="172.30.252.1"));if(ready)break;Thread.Sleep(100);}if(!ready)throw new Exception("Owned adapter not ready in cycle "+i);p.StandardInput.WriteLine("STOP");p.StandardInput.Flush();if(!p.WaitForExit(CoreProcessHost.StopTimeout)||p.ExitCode!=0)throw new Exception("Core did not stop normally in cycle "+i);if(stderr.Result.Contains("FATAL"))throw new Exception("Core reported FATAL in cycle "+i);File.AppendAllText(result,"PASS actual Wintun create/reopen + graceful close cycle "+i+Environment.NewLine);}finally{if(!p.HasExited){try{p.StandardInput.WriteLine("STOP");p.StandardInput.Flush();p.WaitForExit(CoreProcessHost.StopTimeout);}finally{if(!p.HasExited)p.Kill();}}}}}
  File.AppendAllText(result,"RESULT 3 real Wintun cycles PASS; no auto-route or global adapter removal"+Environment.NewLine);return 0;
 }catch(Exception e){if(result!=null)File.AppendAllText(result,"FAIL "+e+Environment.NewLine);return 1;}}
}
