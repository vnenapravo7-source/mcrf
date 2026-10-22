using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using SplifyWin;
using Mcrf.Wpf;
class TurnPublicDownloadChecks {
 static int Main(string[] args){ClientSession session=null;try{if(Directory.Exists(args[0]))throw new Exception("Fresh download fixture required");session=ClientSession.Open(args[0]).GetAwaiter().GetResult();int count=0;foreach(string id in new[]{"csqtt20","csqtt215","csqtt","csqtt25","qwdtt"}){session.Core.Engines.Install(id).GetAwaiter().GetResult();var package=session.Core.Engines.Package(id);var path=session.Core.Engines.Executable(id,id+"-client.exe");if(!session.Core.Engines.Installed(id)||path==null||package.Version!="vkturn-options-20261022-1")throw new Exception("Published package missing "+id);VkTurnOptions.RequireModule(path);Console.WriteLine("PASS real application downloader, SHA-256, installed files and options marker: "+id);count++;}Console.WriteLine("RESULT "+count+" published package checks PASS; installed into isolated fixture, no peer contacted");return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}finally{if(session!=null)session.Dispose();}}
}
