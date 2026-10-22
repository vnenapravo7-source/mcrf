using System;
using System.IO;
using System.Text;
using System.Diagnostics;
using SplifyWin;
using Mcrf.Wpf;
class WdttPublishedCapabilityChecks {
 static int Main(string[] a){ClientSession session=null;try{session=ClientSession.Open(a[0]).GetAwaiter().GetResult();session.Core.Engines.Install("wdtt").GetAwaiter().GetResult();string exe=session.Core.WDTTExecutable;var info=new ProcessStartInfo(exe,"-h"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};using(var p=Process.Start(info)){var o=p.StandardOutput.ReadToEndAsync();var e=p.StandardError.ReadToEndAsync();if(!p.WaitForExit(15000)){p.Kill();throw new Exception("Help timeout");}string help=o.Result+e.Result;foreach(string flag in new[]{"vkcalls-preflight","turn-stream-first","fingerprint","client-ids","turn-sni","rt-masque-config","startup-config-stdin"}){if(!help.Contains("-"+flag))throw new Exception("Published WDTT missing "+flag);Console.WriteLine("PASS actual published WDTT CLI "+flag);}}string bytes=Encoding.UTF8.GetString(File.ReadAllBytes(exe));if(!bytes.Contains("custom_vk_client_secret")||!bytes.Contains("custom_vk_client_id"))throw new Exception("Published WDTT private client credential fields missing");Console.WriteLine("PASS actual published WDTT private startup client credentials");return 0;}catch(Exception ex){Console.Error.WriteLine(ex);return 1;}finally{if(session!=null)session.Dispose();}}
}
