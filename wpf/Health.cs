using System;
using SplifyWin;
using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  VpnTraffic traffic;
  static string Rate(float kib){return kib>=1024?(kib/1024).ToString("0.0")+" МБ/с":kib.ToString("0")+" КБ/с";}
  void ScheduleFirstSetup(){Dispatcher.BeginInvoke(new Action(()=>Page("Мастер настройки")),System.Windows.Threading.DispatcherPriority.Background);}
  async void CancelCoreStartup(){try{await Task.Run(()=>session.Core.Stop());}catch(Exception ex){status.Text=ex.Message;}}
  void RebuildAfterRollback(string message){Dispatcher.BeginInvoke(new Action(()=>{Page(current);status.Text=message;}),System.Windows.Threading.DispatcherPriority.Background);}
  async Task PollHealth(){if(session==null||closing||healthPolling)return;healthPolling=true;try{if(!busy)await session.PollWardogs();if(session.Core.Running)await Task.Run(()=>session.Core.PollActiveServer());await AutoProbeHealth();if(traffic==null)traffic=new VpnTraffic(session.Core.TrafficApiPort,session.Core.TrafficApiSecret);if(!diagnostic)await Task.Run(()=>traffic.Sample(session.Core.Running&&!session.Core.Starting));if(closing)return;SyncModules();if(refreshServer!=null)refreshServer();if(refreshHome!=null)refreshHome();if(refreshLog!=null&&!busy)await refreshLog();}catch(Exception ex){if(!closing)status.Text="Проверка состояния: "+ex.Message;}finally{healthPolling=false;}}
  Func<Task> refreshLog;
 }
}
