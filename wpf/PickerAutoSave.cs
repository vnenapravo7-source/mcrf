using System;
using System.Threading.Tasks;
using System.Windows.Threading;
namespace Mcrf.Wpf {
 // Coalesce selection cascades and serialize writes; never discard a newer draft.
 internal sealed class PickerAutoSave {
  readonly DispatcherTimer timer=new DispatcherTimer();readonly Func<Task> save;readonly Action<Exception> failure;int revision,completed;bool running,stopped,stopAfterSave;
  internal bool Pending{get{return running||revision!=completed;}}
  internal PickerAutoSave(Func<Task> save,Action<Exception> failure){this.save=save;this.failure=failure;timer.Tick+=(s,e)=>{timer.Stop();Drain();};}
  internal void Request(int delay=30){if(stopped||stopAfterSave)return;revision++;timer.Stop();timer.Interval=TimeSpan.FromMilliseconds(Math.Max(1,delay));timer.Start();}
  internal void Flush(){timer.Stop();if(!stopped&&revision!=completed)Drain();}
  internal void Stop(){timer.Stop();stopAfterSave=true;Flush();if(!running)stopped=true;}
  async void Drain(){if(stopped||running)return;running=true;try{while(!stopped&&completed!=revision){int attempt=revision;try{await save();}catch(Exception ex){if(attempt==revision)failure(ex);}completed=attempt;}}finally{running=false;if(stopAfterSave)stopped=true;}}
 }
}
