using System;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace SplifyWin {
  // Read beyond the usual initial DPI window, but never download a whole video
  // or keep arbitrarily large responses in memory. Each process is a fresh TLS connection.
  internal static class BoundedProbe {
    public static async Task<ZapretEndpointResult> Run(string executable,string args,string url,CancellationToken token){
      using(var p=OwnedJob.Start(new ProcessStartInfo(executable,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){
        var stderr=p.StandardError.ReadToEndAsync();var body=new StringBuilder();var buffer=new char[8192];var clock=Stopwatch.StartNew();bool bounded=false;
        try{
          while(body.Length<131072){var pending=p.StandardOutput.ReadAsync(buffer,0,Math.Min(buffer.Length,131072-body.Length));while(!pending.IsCompleted){token.ThrowIfCancellationRequested();if(clock.ElapsedMilliseconds>7000){if(!p.HasExited)p.Kill();return new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unavailable,Detail="Истекло время ожидания",Milliseconds=(int)clock.ElapsedMilliseconds};}await Task.Delay(30,token);}int read=await pending;if(read==0)break;body.Append(buffer,0,read);}
          token.ThrowIfCancellationRequested();if(body.Length>=131072){bounded=true;if(!p.HasExited)p.Kill();}
          while(!p.HasExited){token.ThrowIfCancellationRequested();if(clock.ElapsedMilliseconds>7000){p.Kill();break;}await Task.Delay(30,token);}
          var text=body.ToString();if(bounded){var matches=Regex.Matches(text,@"(?m)^HTTP/[0-9.]+\s+(\d{3})");int status=matches.Count==0?0:Int32.Parse(matches[matches.Count-1].Groups[1].Value);text+="\nMCRF_HTTP:"+status;}
          return ZapretChecks.Classify(url,bounded?0:p.ExitCode,text,await stderr,(int)clock.ElapsedMilliseconds);
        }finally{if(!p.HasExited)try{p.Kill();p.WaitForExit(1000);}catch{}}
      }
    }
  }
}
