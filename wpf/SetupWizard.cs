using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Task<SetupWizardSelection[]> ChooseSetupConfigurations(ZapretCheckReport report){
   var window=Dialog("Мастер · конфигурации и очная проверка",1000,720);var panel=new DockPanel{Margin=new Thickness(20)};
   var note=Text("Выберите конфигурацию для каждого сервиса. Жёлтые и красные варианты можно проверить вручную. Настройки будут предложены к применению только после подтверждения работы.",13);note.TextWrapping=TextWrapping.Wrap;DockPanel.SetDock(note,Dock.Top);panel.Children.Add(note);
   Func<SetupWizardSelection[]> read;var rows=SetupConfigurationFields(report,out read);
   SetupWizardSelection[] result=null;var next=new Button{Name="setupVerifySelected",Content="Проверить выбранные"};next.Click+=(s,e)=>{result=read();window.DialogResult=true;};
   var cancel=new Button{Content="Вернуться к результатам"};cancel.Click+=(s,e)=>window.Close();var actions=Toolbar(next,cancel);DockPanel.SetDock(actions,Dock.Bottom);panel.Children.Add(actions);panel.Children.Add(new ScrollViewer{Content=rows,VerticalScrollBarVisibility=ScrollBarVisibility.Auto});window.Content=panel;window.ShowDialog();return Task.FromResult(result);
  }
  StackPanel SetupConfigurationFields(ZapretCheckReport report,out Func<SetupWizardSelection[]> read){
   var entries=new List<Tuple<CheckBox,ComboBox,ZapretService,ZapretCheckRow[]>>();var rows=new StackPanel();
   foreach(var service in ZapretChecks.Selected(report.WizardServiceIds??report.ServiceIds)){
    var candidates=SetupWizardFlow.Candidates(report,service.Id);int preferred=Array.FindIndex(candidates,r=>!String.IsNullOrEmpty(r.StrategyId));var enabled=new CheckBox{Content=service.CheckName,IsChecked=preferred>=0,IsEnabled=candidates.Length>0,Margin=new Thickness(0,14,0,6)};
    var choice=new ComboBox{ItemsSource=candidates.Select(r=>SetupWizardFlow.OutputName(r)+" · "+r.Name+" · "+(r.Access(service.Id)==ServiceAccess.Available?"работа подтверждена":r.Access(service.Id)==ServiceAccess.Unknown?"нужна очная проверка":"автотест не прошёл — можно проверить вручную")).ToArray(),SelectedIndex=preferred,IsEnabled=candidates.Length>0};
    rows.Children.Add(enabled);rows.Children.Add(choice);entries.Add(Tuple.Create(enabled,choice,service,candidates));
   }
   read=()=>entries.Where(x=>x.Item1.IsChecked==true&&x.Item2.SelectedIndex>=0).Select(x=>new SetupWizardSelection{Service=x.Item3,Row=x.Item4[x.Item2.SelectedIndex]}).ToArray();return rows;
  }
  async Task ContinueSetupWizard(ZapretCheckReport report,CancellationToken token,bool repeatManual=false){
   lastReport=report;
   try{await SetupWizardFlow.RunCore(report,()=>ChooseSetupConfigurations(report),(item,ct)=>VerifySetupSelection(report,item,ct),items=>ApplySetupSelections(report,items,token),()=>ZapretChecks.Save(root,report),token,repeatManual);}finally{lastReport=report;}
  }
  async Task<bool?> VerifySetupSelection(ZapretCheckReport report,SetupWizardSelection selection,CancellationToken token){
   var ordered=SetupWizardFlow.TrialCandidates(report,selection);
   foreach(var row in ordered){
    token.ThrowIfCancellationRequested();selection.Row=row;ZapretSettings trial=null;Process bye=null;
    try{
     string proxy=null;
     if(selection.Output=="zapret"){
      var strategy=(await session.Strategies()).FirstOrDefault(s=>s.Id==row.StrategyId);if(strategy==null)continue;
      trial=ManualServiceChecks.Trial(report,selection.Service,strategy);if(selection.Service.Id=="discord"){trial.DiscordVoiceEnabled=true;trial.DiscordInterfaceStrategy=strategy.Id;}
      await session.Zapret.Start(trial,strategy,token);
     }else if(selection.Output=="byetube"){
      int index;if(!Int32.TryParse(row.StrategyId.Substring("byetube:".Length),out index)||index<0||index>=ByeTube.Strategies.Length)continue;
      var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
      bye=await Task.Run(()=>ByeTube.Start(Path.Combine(session.Core.DataRoot,"core","ciadpi.exe"),ByeTube.Strategies[index],message=>{},token,port));proxy="socks5://127.0.0.1:"+port;
     }
     VideoBrowserLibraries.Install(root);int answer=await ShowSetupServiceTrial(selection,proxy,token);token.ThrowIfCancellationRequested();if(answer<0)return null;
     if(selection.Output=="zapret"&&!session.Zapret.ProfileApplied(null,row.StrategyId))throw new InvalidOperationException("Стратегия остановлена: результат не будет сохранён.");
     SetupWizardFlow.Record(report,selection,answer==1,trial);ZapretChecks.Save(root,report);if(answer==1)return true;
    }catch(OperationCanceledException){throw;}catch(Exception error){RecordEvent("Очная проверка "+selection.Service.Name+": "+error.Message);status.Text="Не удалось проверить "+row.Name+": "+error.Message+". Переходим к следующему варианту.";}
    finally{
     if(selection.Output=="zapret")session.Zapret.Stop();
     if(bye!=null){try{if(!bye.HasExited){bye.Kill();bye.WaitForExit(3000);}}finally{bye.Dispose();}}
    }
   }
   return false;
  }
  async Task ApplySetupSelections(ZapretCheckReport report,SetupWizardSelection[] selections,CancellationToken token){
   token.ThrowIfCancellationRequested();
   if(!Confirm("Сохранить назначения?\n\n"+String.Join("\n",selections.Select(x=>x.Service.CheckName+" → "+SetupWizardFlow.OutputName(x.Row)+" · "+x.Row.Name))+"\n\nПрямой выход не создаёт профиль Zapret. Правила этого мастера будут обновлены, а не продублированы. Другие правила сохранятся ниже новых назначений по приоритету.","Мастер · применить конфигурации"))return;
   var profiles=new List<ZapretProfile>();var routes=new List<RouteList>();int? byeIndex=null;
   foreach(var item in selections){token.ThrowIfCancellationRequested();string target=item.Output,id=null;
    if(target=="zapret"){
     var settings=ManualServiceChecks.AppliedSettings(report,item.Service.Id,item.Row.StrategyId);if(item.Service.Id=="discord")DiscordVoiceScores.Configure(settings,item.Row);
     var profile=new ZapretProfile{Name=item.Service.Name,WizardServiceId=item.Service.Id,Enabled=true,Settings=settings};id=profile.Id;profiles.Add(profile);
    }else if(target=="byetube"){
     int index=Int32.Parse(item.Row.StrategyId.Substring("byetube:".Length));if(byeIndex.HasValue&&byeIndex.Value!=index)throw new InvalidOperationException("Для ByeTube выбраны разные стратегии. Назначьте одну общую стратегию или примените сервисы отдельно.");byeIndex=index;
    }
    var route=await ServicePresetRoutes.Create(item.Service,target,id,session.Core.DownloadTextAsync);route.BuiltinPreset="wpf-setup:"+item.Service.Id;routes.Add(route);
   }
   token.ThrowIfCancellationRequested();await session.ChangeConfiguration(()=>{token.ThrowIfCancellationRequested();SetupWizardFlow.MergePlan(session.State,profiles.ToArray(),routes.ToArray(),byeIndex.HasValue?ByeTube.Strategies[byeIndex.Value]:null);});
   status.Text="Мастер завершён. Сохранены подтверждённые маршруты: "+routes.Count+". Подключение включается отдельно.";
  }
 }
}
