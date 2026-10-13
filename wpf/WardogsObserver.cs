using System;
using System.IO;
using System.Linq;
using System.Diagnostics;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed class WardogsObserver : IDisposable {
  readonly ClientSession session;readonly WardogsMatchTracker tracker=new WardogsMatchTracker();readonly WardogsLogReader logs=new WardogsLogReader();readonly SemaphoreSlim gate=new SemaphoreSlim(1,1);readonly CancellationTokenSource lifetime=new CancellationTokenSource();GameFlowObserver observer;Task cleanup;DateTime retryAfter;string identity="";bool disposed;
  public WardogsObserver(ClientSession client){session=client;}
  public async Task Poll(){if(disposed||DateTime.UtcNow<retryAfter||!await gate.WaitAsync(0))return;try{
   if(cleanup!=null){await cleanup;cleanup=null;}
   var eligible=session.State.Lists.Where(r=>r.Enabled&&r.Target=="zapret"&&r.OriginalEngine&&r.WardogsAutoPause&&session.State.ZapretProfiles.Any(p=>p.Id==r.ZapretProfileId&&p.Enabled)).Select(r=>r.ZapretProfileId).Distinct().ToArray();
   if(!session.Zapret.Running||eligible.Length==0){if(observer!=null){var old=observer;observer=null;await Task.Run(()=>old.Dispose());}tracker.Reset();logs.Reset();identity="";if(session.Zapret.Running&&session.State.ZapretProfiles.Any(p=>session.Zapret.IsAutoPaused(p.Id)))await session.Zapret.UpdateWardogsPause(ZapretRoutes.Active(session.State),new string[0],lifetime.Token);return;}
   if(observer==null){var next=new GameFlowObserver{IncludeClosedEvents=true};try{await Task.Run(()=>{session.Zapret.Prepare();next.Start(session.Zapret.DirectoryPath,new GamePreset{Name="Wardogs",Processes=new[]{"WardogsClient-Win64-Shipping.exe"}});},lifetime.Token);observer=next;}catch{next.Dispose();throw;}}
   DateTime born=DateTime.MaxValue;var identities=new List<string>();foreach(var process in Process.GetProcessesByName("WardogsClient-Win64-Shipping"))using(process){try{var started=process.StartTime.ToUniversalTime();if(started<born)born=started;identities.Add(process.Id+":"+started.Ticks);}catch(InvalidOperationException){}catch(System.ComponentModel.Win32Exception){}}
   string current=String.Join("|",identities.OrderBy(s=>s));if(current!=identity){tracker.Reset();logs.Reset();identity=current;}if(identities.Count==0)tracker.Reset();else{tracker.Observe(observer.Drain(),DateTime.UtcNow);foreach(string line in await Task.Run(()=>logs.Read(born),lifetime.Token))tracker.LogLine(line);}if(!observer.Running)throw new IOException(observer.Error??"Наблюдение Wardogs остановлено");await session.Zapret.UpdateWardogsPause(ZapretRoutes.Active(session.State),tracker.InMatch(DateTime.UtcNow)?eligible:new string[0],lifetime.Token);
  }catch(OperationCanceledException){}catch(Exception ex){retryAfter=DateTime.UtcNow.AddSeconds(15);if(observer!=null){var old=observer;observer=null;cleanup=Task.Run(()=>old.Dispose());}try{File.AppendAllText(Path.Combine(session.Store.Root,"wpf-events.log"),DateTime.Now+" Wardogs: "+ex.Message+Environment.NewLine);}catch(IOException){}catch(UnauthorizedAccessException){}}
   finally{gate.Release();}}
  public void Dispose(){disposed=true;lifetime.Cancel();gate.Wait();try{if(cleanup!=null)cleanup.GetAwaiter().GetResult();if(observer!=null)observer.Dispose();}finally{gate.Release();lifetime.Dispose();}}
 }
}
