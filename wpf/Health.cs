using System;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
using SplifyWin;
using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  VpnTraffic traffic;
  static string Rate(float kib){return kib>=1024?(kib/1024).ToString("0.0")+" МБ/с":kib.ToString("0")+" КБ/с";}
  DispatcherTimer firstSetupTimer;bool firstSetupScheduled;
  static bool ShouldOfferFirstSetup(bool seen,bool diagnostic){return !seen&&!diagnostic;}
  static bool FirstSetupWindowReady(bool busy,bool visible,bool active,bool modal,bool closing){return !busy&&visible&&active&&!modal&&!closing;}
  void ScheduleFirstSetup(){
   if(firstSetupScheduled||session==null||!ShouldOfferFirstSetup(session.State.SetupSeen,diagnostic))return;
   firstSetupScheduled=true;firstSetupTimer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(200)};
   firstSetupTimer.Tick+=async(s,e)=>{
    if(closing||session==null||session.State.SetupSeen||InterfaceMode==1){firstSetupTimer.Stop();firstSetupScheduled=false;return;}
    if(!FirstSetupWindowReady(busy,IsVisible,IsActive,OwnedWindows.Cast<Window>().Any(w=>w.IsVisible),closing))return;
    firstSetupTimer.Stop();
    if(Confirm("Запустить мастер настройки? Он подберёт конфигурации для выбранных сервисов и предложит очные проверки. Настройки применяются только после вашего подтверждения.","Добро пожаловать в MCRF"))Page("Мастер настройки");
    else{session.State.SetupSeen=true;try{await session.Save();}catch(Exception error){session.State.SetupSeen=false;status.Text="Не удалось сохранить решение о мастере: "+error.Message;}}
   };
   Closed+=(s,e)=>firstSetupTimer.Stop();firstSetupTimer.Start();
  }
  async void CancelCoreStartup(){try{await Task.Run(()=>session.Core.Stop());}catch(Exception ex){status.Text=ex.Message;}}
  void RebuildAfterRollback(string message){Dispatcher.BeginInvoke(new Action(()=>{Page(current);status.Text=message;}),System.Windows.Threading.DispatcherPriority.Background);}
  async Task PollHealth(){if(session==null||closing||healthPolling)return;healthPolling=true;try{if(!busy)await session.PollWardogs();if(session.Core.Running)await Task.Run(()=>session.Core.PollActiveServer());await AutoProbeHealth();await PollVkWifi();if(traffic==null)traffic=new VpnTraffic(session.Core.TrafficApiPort,session.Core.TrafficApiSecret);if(!diagnostic)await Task.Run(()=>traffic.Sample(session.Core.Running&&!session.Core.Starting));if(closing)return;SyncModules();if(refreshServer!=null)refreshServer();if(refreshHome!=null)refreshHome();if(refreshLog!=null&&!busy)await refreshLog();}catch(Exception ex){if(!closing)status.Text="Проверка состояния: "+ex.Message;}finally{healthPolling=false;}}
  Func<Task> refreshLog;
 }
}
