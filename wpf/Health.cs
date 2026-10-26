using System;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
using SplifyWin;
using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  VpnTraffic traffic;
  DateTime lastBsDetection=DateTime.MinValue;bool bsDetectionSwitching;
  static string Rate(float kib){return kib>=1024?(kib/1024).ToString("0.0")+" МБ/с":kib.ToString("0")+" КБ/с";}
  internal static int AutoBsTransition(bool bsActive,int directResponses){if(directResponses<0)return 0;if(!bsActive&&directResponses==0)return 1;if(bsActive&&directResponses>0)return -1;return 0;}
  DispatcherTimer firstSetupTimer;bool firstSetupScheduled;internal Func<bool> AdvancedOfferForTests;internal bool FirstSetupActiveForTests;bool advancedOfferSaving;
  static bool ShouldOfferFirstSetup(bool seen,bool diagnostic){return !seen&&!diagnostic;}
  static bool FirstSetupWindowReady(bool busy,bool visible,bool active,bool modal,bool closing){return !busy&&visible&&active&&!modal&&!closing;}
  void ScheduleFirstSetup(){
   if(firstSetupScheduled||session==null||!ShouldOfferFirstSetup(session.State.SetupSeen,diagnostic))return;
   string build=typeof(MainWindow).Assembly.ManifestModule.ModuleVersionId.ToString("N");
   firstSetupScheduled=true;firstSetupTimer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(200)};
   firstSetupTimer.Tick+=async(s,e)=>{
    if(closing||closeRequested||session==null){firstSetupTimer.Stop();return;}
    if(!FirstSetupWindowReady(busy,IsVisible,IsActive||FirstSetupActiveForTests||AdvancedOfferForTests!=null,OwnedWindows.Cast<Window>().Any(w=>w.IsVisible),closing))return;
    firstSetupTimer.Stop();advancedOfferSaving=true;
    try{
     if(session.State.AdvancedOfferBuild!=build){
      bool wanted=AdvancedOfferForTests!=null?AdvancedOfferForTests():ConfirmWithLabel("Включить расширенные настройки? Они добавляют сохранённые правила, захват процессов, Zapret, WARP и автодеплой. Выбор можно изменить в настройках.","Возможности MCRF","Включить");
      if(closing||closeRequested)return;
      await session.ChangeConfiguration(()=>{session.State.CompactAdvancedFeatures=wanted;session.State.InterfaceMode=wanted?0:1;session.State.AdvancedOfferBuild=build;});
      if(closing||closeRequested)return;
      ApplyInterfaceMode();Page(DefaultInterfacePage);
     }
     bool openWizard=ConfirmWithLabel("Открыть мастер настройки? Он поможет проверить подключение и выбрать подходящие стратегии. Проверка начнётся только по вашему нажатию.","Мастер настройки","Открыть мастер");
     if(closing||closeRequested)return;
     await session.ChangeConfiguration(()=>session.State.SetupSeen=true);
     if(openWizard&&!closing&&!closeRequested)OpenDetachedWorkspace("Мастер настройки",SetupPage);
    }catch(Exception ex){status.Text="Не удалось завершить первый запуск: "+FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));}
    finally{advancedOfferSaving=false;}
   };
   Closed+=(s,e)=>firstSetupTimer.Stop();firstSetupTimer.Start();
  }
  async void CancelCoreStartup(){try{await Task.Run(()=>session.Core.Stop());}catch(Exception ex){status.Text=ex.Message;}}
  void RebuildAfterRollback(string message){Dispatcher.BeginInvoke(new Action(()=>{if(closing||closeRequested)return;Page(current);status.Text=message;}),System.Windows.Threading.DispatcherPriority.Background);}
  async Task PollHealth(){if(session==null||closing||healthPolling)return;healthPolling=true;try{if(!busy)await session.PollWardogs();if(session.Core.Running)await Task.Run(()=>session.Core.PollActiveServer());await PollSubscriptionUpdates();await AutoProbeHealth();await PollBsDetection();await PollVkWifi();if(traffic==null)traffic=new VpnTraffic(session.Core.TrafficApiPort,session.Core.TrafficApiSecret);if(!diagnostic)await Task.Run(()=>traffic.Sample(session.Core.Running&&!session.Core.Starting));if(closing)return;SyncModules();if(refreshServer!=null)refreshServer();if(refreshHome!=null)refreshHome();if(refreshLog!=null&&!busy)await refreshLog();}catch(Exception ex){if(!closing)status.Text="Проверка состояния: "+ex.Message;}finally{healthPolling=false;}}
  async Task PollBsDetection(){
   if(diagnostic||closing||closeRequested||busy||bsDetectionSwitching||session==null||!session.State.AutoDetectBs||!session.Core.Running||session.Core.Starting||session.State.Mode!="tun"||!session.State.VpnEnabled)return;
   var interval=TimeSpan.FromSeconds(Math.Max(15,session.State.AutoBsIntervalSeconds));if(DateTime.UtcNow-lastBsDetection<interval)return;lastBsDetection=DateTime.UtcNow;
   var sites=BsDetectionSites.Load(session.State.AutoBsSites);var context=new BsProbeContext(session.State,session.Core.StartGeneration);int direct=await Task.Run(()=>session.Core.ProbeDirectAccess(sites,!context.BsActive));if(!sites.SequenceEqual(BsDetectionSites.Load(session.State.AutoBsSites))||!context.CanApply(session.State,session.Core.StartGeneration,session.Core.Running,session.Core.Starting,busy,closing||closeRequested))return;int transition=AutoBsTransition(session.State.AutoBsActive,direct);bool toBs=transition>0,toVpn=transition<0;if(!toBs&&!toVpn)return;
   var candidates=AutomaticServers.TransitionCandidates(session.State,toBs,session.Core.HasEngine);if(candidates.Length==0){status.Text=toBs?"Автоопределение БС: нет доступных серверов белых списков.":"Автовозврат VPN: нет доступного обычного VPN-сервера.";return;}
   string preferred=toBs?session.State.LastBsServerId:session.State.LastVpnServerId;var target=candidates.FirstOrDefault(n=>n.Id==preferred)??candidates[0];string currentId=!String.IsNullOrEmpty(session.Core.ActiveServerId)?session.Core.ActiveServerId:session.State.SelectedServerId;
   bsDetectionSwitching=true;try{bool applied=await session.ChangeConfigurationIf(()=>context.CanApply(session.State,session.Core.StartGeneration,session.Core.Running,session.Core.Starting,busy,closing||closeRequested)&&session.State.Servers.Any(n=>n.Id==target.Id&&n.UseInAutoSelection&&AutomaticServers.Allowed(n)&&session.Core.HasEngine(n)&&BsServers.Is(n)==toBs),()=>{if(toBs)session.State.LastVpnServerId=currentId;else session.State.LastBsServerId=currentId;session.State.AutoBsActive=toBs;session.State.SelectedServerId=target.Id;});if(!applied||closing||closeRequested)return;RecordEvent(toBs?"Автоопределение БС: прямой доступ пропал, включены белые списки.":"Автоопределение БС: прямой доступ восстановлен, возвращён VPN.");status.Text=toBs?"Автоопределение БС · белые списки":"Автоопределение БС · VPN восстановлен";}finally{bsDetectionSwitching=false;}
  }
  Func<Task> refreshLog;
 }
}
