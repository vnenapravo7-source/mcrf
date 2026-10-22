using System;
using System.IO;
using System.Linq;
using SplifyWin;
class CsqttPublishedDownloadChecks {
 static int Main(string[] args){try{string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh root required");var core=new NetworkCore(new StateStore(root));int count=0;foreach(var generation in CsqttGenerations.All){core.Engines.Install(generation.Package).GetAwaiter().GetResult();if(core.Engines.Executable(generation.Package,generation.Executable)==null)throw new Exception("Published module not verified");Console.WriteLine("PASS actual public GitHub download + archive/file SHA256 "+generation.Id);count++;}
  string good="csqtt://connect?v=2&host=192.0.2.1&peer=56000&password=fixture-only&hashes="+new string('A',43);bool failed=false;try{CsqttGenerations.ValidateBatch(good+"\n"+good+"&mcrf_generation=unknown");}catch(FormatException){failed=true;}if(!failed)throw new Exception("Invalid generation silently dropped from mixed batch");Console.WriteLine("PASS mixed CSQTT batch rejects unknown generation atomically");count++;CsqttGenerations.ValidateBatch(good);Console.WriteLine("PASS unlabelled valid legacy link accepted for explicit generation choice");count++;if(core.Running)throw new Exception("Download unexpectedly started a VPN");Console.WriteLine("RESULT "+count+" checks PASS; no VPN/transport launched");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
