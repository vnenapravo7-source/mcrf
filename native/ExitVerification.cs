using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
namespace SplifyWin {
  public static class ExitVerification {
    sealed class Attempt {public string Url,Error;public bool Passed;}
    static async Task<Attempt> Run(string url,Func<string,CancellationToken,Task<bool>> probe,CancellationToken token){try{return new Attempt{Url=url,Passed=await probe(url,token)};}catch(OperationCanceledException){return new Attempt{Url=url};}catch(Exception ex){return new Attempt{Url=url,Error=JournalStyle.Redact(ex.Message)};}}
    public static async Task<string> FirstSuccess(IEnumerable<string> urls,Func<string,CancellationToken,Task<bool>> probe){
      using(var cancellation=new CancellationTokenSource()){
        var all=urls.Select(url=>Run(url,probe,cancellation.Token)).ToArray();var pending=all.ToList();var errors=new List<string>();string winner=null;
        try{while(pending.Count>0){var done=await Task.WhenAny(pending);pending.Remove(done);var attempt=await done;if(attempt.Passed){winner=attempt.Url;break;}if(attempt.Error!=null)errors.Add(attempt.Error);}}
        finally{cancellation.Cancel();}await Task.WhenAll(all);
        if(winner!=null)return winner;throw new InvalidOperationException("Проверочные сайты не ответили через профиль. Это не доказывает неисправность сервера. "+String.Join("; ",errors));
      }
    }
  }
  public sealed partial class MainForm {
    async Task<bool> VerifyExitTarget(string url,CancellationToken token){
      token.ThrowIfCancellationRequested();string args="--silent --show-error --ipv4 --connect-timeout 3 --max-time 5 --noproxy \"\" --proxy http://127.0.0.1:10809 --cacert "+ZapretRuntime.Quote(System.IO.Path.Combine(store.Root,"core","curl-ca-bundle.crt"))+" --output NUL --write-out \"%{http_code}\" "+ZapretRuntime.Quote(url);
      using(var process=OwnedJob.Start(new ProcessStartInfo(core.CurlExecutable,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){
        var stdout=process.StandardOutput.ReadToEndAsync();var stderr=process.StandardError.ReadToEndAsync();
        try{while(!process.HasExited){token.ThrowIfCancellationRequested();await Task.Delay(30,token);}string code=(await stdout).Trim();if(process.ExitCode!=0)throw new InvalidOperationException(await stderr);return code=="204"||code=="200";}
        finally{if(!process.HasExited)try{process.Kill();process.WaitForExit(1000);}catch{}}
      }
    }
  }
}
