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
  Task<SetupWizardSelection[]> ChooseSetupConfigurations(ZapretCheckReport report){return ChooseSetupConfigurationsWithLifetime(report,CancellationToken.None);}
  Task<SetupWizardSelection[]> ChooseSetupConfigurationsWithLifetime(ZapretCheckReport report,CancellationToken token){
   token.ThrowIfCancellationRequested();if(closing||closeRequested)return Task.FromResult<SetupWizardSelection[]>(null);
   var window=Dialog("Мастер · конфигурации и очная проверка",1000,720);window.Name="setupConfigurationWindow";var panel=new DockPanel{Margin=new Thickness(20)};
   var note=Text("Выберите конфигурацию для каждого сервиса. Жёлтые и красные варианты можно проверить вручную. Настройки будут предложены к применению только после подтверждения работы.",13);note.TextWrapping=TextWrapping.Wrap;DockPanel.SetDock(note,Dock.Top);panel.Children.Add(note);
   Func<SetupWizardSelection[]> read;var rows=SetupConfigurationFields(report,out read);
   SetupWizardSelection[] result=null;var next=new Button{Name="setupVerifySelected",Content="Проверить выбранные",IsEnabled=read().Length>0};
   Action sync=()=>next.IsEnabled=read().Length>0;
   foreach(var check in rows.Children.OfType<CheckBox>()){check.Checked+=(s,e)=>sync();check.Unchecked+=(s,e)=>sync();}
   foreach(var choice in rows.Children.OfType<ComboBox>())choice.SelectionChanged+=(s,e)=>sync();
   next.Click+=(s,e)=>{if(token.IsCancellationRequested||closing||closeRequested){window.Close();return;}var selected=read();if(selected.Length==0)return;result=selected;window.DialogResult=true;};
   var cancel=new Button{Name="setupConfigurationsCancel",Content="Вернуться к результатам"};cancel.Click+=(s,e)=>window.Close();var actions=Toolbar(next,cancel);DockPanel.SetDock(actions,Dock.Bottom);panel.Children.Add(actions);panel.Children.Add(new ScrollViewer{Content=rows,MaxHeight=Math.Max(100,window.Height-300),VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled});window.MaxHeight=window.Height;window.SizeToContent=SizeToContent.Height;window.Content=panel;
   var lifetime=new System.Windows.Threading.DispatcherTimer{Interval=TimeSpan.FromMilliseconds(100)};lifetime.Tick+=(s,e)=>{if(token.IsCancellationRequested||closing||closeRequested)window.Close();};lifetime.Start();
   try{window.ShowDialog();}finally{lifetime.Stop();}
   token.ThrowIfCancellationRequested();return Task.FromResult(closing||closeRequested?null:result);
  }
  StackPanel SetupConfigurationFields(ZapretCheckReport report,out Func<SetupWizardSelection[]> read){
   var entries=new List<Tuple<CheckBox,ComboBox,ZapretService,ZapretCheckRow[]>>();var rows=new StackPanel();
   foreach(var service in ZapretChecks.Selected(SetupWizardFlow.ServiceIds(report))){
    var candidates=SetupWizardFlow.Candidates(report,service.Id);int preferred=Array.FindIndex(candidates,r=>!String.IsNullOrEmpty(r.StrategyId));
    var enabled=new CheckBox{Name="setupService_"+service.Id.Replace("-","_"),Content=service.CheckName,IsChecked=preferred>=0,IsEnabled=candidates.Length>0,Margin=new Thickness(0,14,0,6)};
    var choice=new ComboBox{Name="setupConfiguration_"+service.Id.Replace("-","_"),ItemsSource=candidates.Select(r=>SetupWizardFlow.OutputName(r)+" · "+r.Name+" · "+(r.Access(service.Id)==ServiceAccess.Available?"работа подтверждена":r.Access(service.Id)==ServiceAccess.Unknown?"нужна очная проверка":"автотест не прошёл — можно проверить вручную")).ToArray(),SelectedIndex=preferred,IsEnabled=preferred>=0};
    var label=new FrameworkElementFactory(typeof(TextBlock));label.SetBinding(TextBlock.TextProperty,new System.Windows.Data.Binding());label.SetValue(TextBlock.TextTrimmingProperty,TextTrimming.CharacterEllipsis);label.SetValue(FrameworkElement.MaxWidthProperty,250d);choice.ItemTemplate=new DataTemplate{VisualTree=label};
    var itemStyle=new Style(typeof(ComboBoxItem),(Style)TryFindResource(typeof(ComboBoxItem)));itemStyle.Setters.Add(new Setter(FrameworkElement.ToolTipProperty,new System.Windows.Data.Binding()));choice.ItemContainerStyle=itemStyle;
    Action selection=()=>{choice.ToolTip=choice.SelectedItem;};choice.SelectionChanged+=(s,e)=>selection();selection();
    enabled.Checked+=(s,e)=>choice.IsEnabled=candidates.Length>0;enabled.Unchecked+=(s,e)=>choice.IsEnabled=false;
    rows.Children.Add(enabled);rows.Children.Add(choice);
    if(candidates.Length==0)rows.Children.Add(Text("Нет доступных конфигураций. Завершите поиск или измените источники стратегий.",11));
    else if(preferred<0)rows.Children.Add(Text("Есть только прямой выход. Включите сервис и явно выберите конфигурацию, если хотите проверить подключение без Zapret.",11));
    entries.Add(Tuple.Create(enabled,choice,service,candidates));
   }
   read=()=>entries.Where(x=>x.Item1.IsChecked==true&&x.Item2.SelectedIndex>=0).Select(x=>new SetupWizardSelection{Service=x.Item3,Row=x.Item4[x.Item2.SelectedIndex]}).ToArray();return rows;
  }
  async Task ContinueSetupWizard(ZapretCheckReport report,CancellationToken token,bool repeatManual=false){
   lastReport=report;
   try{await SetupWizardFlow.RunCore(report,()=>ChooseSetupConfigurationsWithLifetime(report,token),(item,ct)=>VerifySetupSelection(report,item,ct),items=>ApplySetupSelections(report,items,token),()=>ZapretChecks.Save(root,report),token,repeatManual);}finally{lastReport=report;}
  }
  async Task<bool?> VerifySetupSelection(ZapretCheckReport report,SetupWizardSelection selection,CancellationToken token){
   if(SetupVerifyForTests!=null)return await SetupVerifyForTests(report,selection,token);
   var ordered=SetupWizardFlow.TrialCandidates(report,selection);bool opened=false;var errors=new List<string>();
   foreach(var row in ordered){
    token.ThrowIfCancellationRequested();selection.Row=row;ZapretSettings trial=null;Process bye=null;
    try{
     string proxy=null;
     if(selection.Output=="zapret"){
      var strategy=(await session.Strategies()).FirstOrDefault(s=>s.Id==row.StrategyId);if(strategy==null)throw new InvalidOperationException("Сохранённая стратегия отсутствует в каталоге. Повторите поиск стратегий.");
      trial=ManualServiceChecks.Trial(report,selection.Service,strategy);if(selection.Service.Id=="discord"){trial.DiscordVoiceEnabled=true;trial.DiscordInterfaceStrategy=strategy.Id;}
      await session.Zapret.Start(trial,strategy,token);
     }else if(selection.Output=="byetube"){
      int index;if(!Int32.TryParse(row.StrategyId.Substring("byetube:".Length),out index)||index<0||index>=ByeTube.Strategies.Length)throw new InvalidOperationException("Сохранённая стратегия ByeTube недоступна. Повторите поиск стратегий.");
      var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
      bye=await Task.Run(()=>ByeTube.Start(Path.Combine(session.Core.DataRoot,"core","ciadpi.exe"),ByeTube.Strategies[index],message=>{},token,port));proxy="socks5://127.0.0.1:"+port;
     }
     if(SetupTrialNeedsBrowser(selection.Service.Id))VideoBrowserLibraries.Install(root);int answer=await ShowSetupServiceTrial(selection,proxy,token);opened=true;token.ThrowIfCancellationRequested();if(answer<0)return null;
     if(selection.Output=="zapret"&&!session.Zapret.ProfileApplied(null,row.StrategyId))throw new InvalidOperationException("Стратегия остановлена: результат не будет сохранён.");
     SetupWizardFlow.Record(report,selection,answer==1,trial);ZapretChecks.Save(root,report);if(answer==1)return true;
    }catch(OperationCanceledException){throw;}catch(Exception error){errors.Add(error.Message);RecordEvent("Очная проверка "+selection.Service.Name+": "+error.Message);status.Text="Не удалось проверить "+row.Name+": "+error.Message+". Переходим к следующему варианту.";}
    finally{
     if(selection.Output=="zapret")session.Zapret.Stop();
     if(bye!=null){try{if(!bye.HasExited){bye.Kill();bye.WaitForExit(3000);}}finally{bye.Dispose();}}
    }
   }
   if(!opened&&errors.Count>0)throw new InvalidOperationException("Не удалось открыть очную проверку «"+selection.Service.CheckName+"»: "+String.Join("; ",errors.Distinct()));return false;
  }
  async Task ApplySetupSelections(ZapretCheckReport report,SetupWizardSelection[] selections,CancellationToken token){
   token.ThrowIfCancellationRequested();if(closing||closeRequested)throw new OperationCanceledException(token);
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
   token.ThrowIfCancellationRequested();if(closing||closeRequested)throw new OperationCanceledException(token);await session.ChangeConfiguration(()=>{token.ThrowIfCancellationRequested();if(closing||closeRequested)throw new OperationCanceledException(token);SetupWizardFlow.MergePlan(session.State,profiles.ToArray(),routes.ToArray(),byeIndex.HasValue?ByeTube.Strategies[byeIndex.Value]:null);});
   status.Text="Мастер завершён. Сохранены подтверждённые маршруты: "+routes.Count+". Подключение включается отдельно.";
  }
 }
}
