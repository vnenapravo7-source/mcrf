using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal Func<ZapretCheckReport,CancellationToken,Task> SetupScanForTests;
  internal Func<ZapretCheckReport,SetupWizardSelection,CancellationToken,Task<bool?>> SetupVerifyForTests;
  void SetupPage(){
   reportFromEditor=false;
   var panel=new DockPanel();var header=new StackPanel();header.Children.Add(Text("Проверка подключения и стратегий",20));header.Children.Add(Text("Проверим без обхода, затем выбранные стратегии. После поиска мастер предложит конфигурации и последовательно откроет очные проверки выбранных сервисов, включая YouTube и голос Discord. Настройки применяются только после вашего подтверждения."));
   var checks=ServiceChoices(null);var families=new[]{"Flowseal","V","YouTube","Свои","ByeTube"}.Select(f=>new CheckBox{Content=f,Tag=f,IsChecked=f=="Flowseal"}).ToArray();header.Children.Add(Columns(Field("Сервисы",ServicePicker(checks)),Field("Источники стратегий",ServicePicker(families,"Источников"))));DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);
   var content=new Grid();var progress=Text("Выберите сервисы и начните проверку.");progress.Name="setupProgress";var footer=new StackPanel();footer.Children.Add(progress);bool checking=false;
   var start=new Button{Name="setupStart",Content="Проверить подключение и стратегии"};Accent(start);
   var stop=Button("Остановить",()=>{if(scanCancellation!=null)scanCancellation.Cancel();},true);stop.Name="setupStop";stop.Visibility=Visibility.Collapsed;
   var result=AsyncButton("Результаты / назначить",async()=>{if(lastReport==null)lastReport=await Task.Run(()=>ZapretChecks.Load(root));ShowWorkspaceResults();});
   var resume=new Button{Name="setupResume",Content="Очные проверки по сохранённым результатам"};footer.Children.Add(Toolbar(start,stop,result));footer.Children.Add(resume);DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(content);body.Children.Add(Card(panel));
   Action<bool> setChecking=value=>{checking=value;header.IsEnabled=start.IsEnabled=resume.IsEnabled=!value;stop.Visibility=value?Visibility.Visible:Visibility.Collapsed;};
   Func<ZapretCheckReport,bool,Task> manual=async(report,repeat)=>{
    if(!SetupWizardFlow.CanContinue(report)){progress.Text="Нет проверенных конфигураций для очной проверки. Запустите поиск стратегий.";return;}
    if(closing||closeRequested||detachedWorkspaceClosing)return;
    // Stop ends the scan; the separate manual stage needs a fresh token.
    if(scanCancellation!=null)scanCancellation.Dispose();scanCancellation=new CancellationTokenSource();
    progress.Text=report.Complete?"Поиск завершён. Выберите конфигурации для очной проверки.":"Поиск остановлен. Можно очно проверить уже проверенные конфигурации.";
    await ContinueSetupWizard(report,scanCancellation.Token,repeat);
    progress.Text="Результаты сохранены. Очные проверки можно повторить кнопкой ниже.";
   };
   Func<Task> run=async()=>{
    setChecking(true);scanCancellation=new CancellationTokenSource();ZapretCheckReport report=null;DataGrid table=null;
    try{
     await PrepareTests();var settings=TestSettings(checks);var profile=session.State.ZapretProfiles.FirstOrDefault();
     report=new ZapretCheckReport{Settings=settings,ServiceIds=settings.CheckServices.ToList(),WizardServiceIds=settings.CheckServices.ToList(),ProfileId=profile==null?null:profile.Id,ApplySettings=profile==null?settings:ZapretChecks.CopySettings(profile.Settings)};
     lastReport=report;reportFromEditor=false;table=CheckTable(report);content.Children.Clear();content.Children.Add(table);
     Action<string> show=message=>Dispatcher.BeginInvoke(new Action(()=>progress.Text=message));
     Action<ZapretCheckReport> save=r=>{ZapretChecks.Save(root,r);var snapshot=ReportSnapshot(r);Dispatcher.BeginInvoke(new Action(()=>RefreshReport(table,snapshot)));};
     try{
      if(SetupScanForTests!=null)await SetupScanForTests(report,scanCancellation.Token);
      else{
       var catalog=await session.Strategies();var selected=families.Where(c=>c.IsChecked==true).Select(c=>(string)c.Tag).ToArray();var strategies=catalog.Where(c=>selected.Contains(c.Family)).ToArray();if(strategies.Length==0&&!selected.Contains("ByeTube"))throw new InvalidOperationException("Выберите источники стратегий.");
       var direct=await Task.Run(()=>SetupServiceProbe.Measure(session.Core,ZapretChecks.Selected(settings.CheckServices),false,scanCancellation.Token,show));report.Rows.Add(direct);RefreshReport(table,report);
       if(strategies.Length>0)await ScanStrategies(report,strategies,scanCancellation.Token,show,save);
       if(selected.Contains("ByeTube")){report.Complete=false;await Task.Run(()=>ByeTubeScan.Run(session.Core,report,scanCancellation.Token,show,save));}
      }
      scanCancellation.Token.ThrowIfCancellationRequested();report.Complete=true;
     }catch(OperationCanceledException){report.Complete=false;report.StopReason="Проверка остановлена; завершённые результаты сохранены.";}
     ZapretChecks.Save(root,report);RefreshReport(table,report);
     await manual(report,false);RefreshReport(table,report);
    }catch(OperationCanceledException){progress.Text="Очная проверка отменена; результаты сохранены.";}
    catch(Exception ex){progress.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));}
    finally{setChecking(false);if(scanCancellation!=null){scanCancellation.Dispose();scanCancellation=null;}}
   };
   start.Click+=async(s,e)=>{if(checking||busy)return;if(TestSettings(checks).CheckServices.Count==0){progress.Text="Выберите сервисы.";return;}await Run(run);};
   resume.Click+=async(s,e)=>{
    if(checking||busy)return;
    await Run(async()=>{
     setChecking(true);
     try{var saved=lastReport??await Task.Run(()=>ZapretChecks.Load(root));if(!SetupWizardFlow.CanContinue(saved)){progress.Text="Нет проверенных конфигураций для очной проверки. Запустите поиск стратегий.";return;}await PrepareTests();await manual(saved,true);}
     catch(OperationCanceledException){progress.Text="Очная проверка отменена; результаты сохранены.";}
     catch(Exception ex){progress.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));}
     finally{setChecking(false);if(scanCancellation!=null){scanCancellation.Dispose();scanCancellation=null;}}
    });
   };
   session.State.SetupSeen=true;Dispatcher.BeginInvoke(new Action(()=>LoadBasicJournal(session.Save)));
  }
 }
}

