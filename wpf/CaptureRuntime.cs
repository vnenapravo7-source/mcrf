using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 internal abstract class CaptureObserver {
  internal abstract Task Start(GamePreset game,CancellationToken cancellation);
  internal abstract Task Stop();
  internal abstract Task Dispose();
  internal abstract Dictionary<string,string>[] Drain();
  internal abstract string Error{get;}
  internal abstract bool Running{get;}
 }
 internal sealed class NativeCaptureObserver : CaptureObserver {
  readonly GameFlowObserver observer=new GameFlowObserver();readonly ZapretRuntime runtime;
  internal NativeCaptureObserver(ZapretRuntime runtime){this.runtime=runtime;}
  internal override Task Start(GamePreset game,CancellationToken cancellation){return Task.Run(()=>{cancellation.ThrowIfCancellationRequested();runtime.Prepare();cancellation.ThrowIfCancellationRequested();observer.Start(runtime.DirectoryPath,game);cancellation.ThrowIfCancellationRequested();});}
  internal override Task Stop(){return Task.Run(()=>observer.Stop());}
  internal override Task Dispose(){return Task.Run(()=>observer.Dispose());}
  internal override Dictionary<string,string>[] Drain(){return observer.Drain();}
  internal override string Error{get{return observer.Error;}}
  internal override bool Running{get{return observer.Running;}}
 }
 internal sealed class CaptureServices {
  internal Func<Task<string[]>> Processes;
  internal Func<CaptureObserver> CreateObserver;
 }
}
