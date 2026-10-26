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
  void SetupPage(){reportFromEditor=false;
   var panel=new DockPanel();var header=new StackPanel();header.Children.Add(Text("Проверка подключения и стратегий",20));header.Children.Add(Text("Проверим без обхода, затем выбранные стратегии. После поиска мастер предложит конфигурации и последовательно откроет очные проверки выбранных сервисов, включая YouTube и голос Discord. Настройки применяются только после вашего подтверждения."));
   var checks=ServiceChoices(null);var families=new[]{"Flowseal","V","YouTube","Свои","ByeTube"}.Select(f=>new CheckBox{Content=f,Tag=f,IsChecked=f=="Flowseal"}).ToArray();header.Children.Add(Columns(Field("Сервисы",ServicePicker(checks)),Field("Источники стратегий",ServicePicker(families,"Источников"))));DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);var content=new Grid();var progress=Text("Выберите сервисы и начните проверку.");var footer=new StackPanel();footer.Children.Add(progress);var start=new Button{Content="Проверить подключение и стратегии"};Accent(start);var stop=Button("Остановить",()=>{if(scanCancellation!=null)scanCancellation.Cancel();},true);stop.Visibility=Visibility.Collapsed;var result=AsyncButton("Результаты / назначить",async()=>{if(lastReport==null)lastReport=await Task.Run(()=>ZapretChecks.Load(root));ShowWorkspaceResults();});footer.Children.Add(Toolbar(start,stop,result));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(content);body.Children.Add(Card(panel));bool checking=false;
   Func<Task> run=async()=>{
    checking=true;header.IsEnabled=start.IsEnabled=false;stop.Visibility=Visibility.Visible;scanCancellation=new CancellationTokenSource();try{await PrepareTests();reportFromEditor=false;var settings=TestSettings(checks);var profile=session.State.ZapretProfiles.FirstOrDefault();var report=new ZapretCheckReport{Settings=settings,ServiceIds=settings.CheckServices,ProfileId=profile==null?null:profile.Id,ApplySettings=profile==null?settings:ZapretChecks.CopySettings(profile.Settings)};var table=CheckTable(report);content.Children.Clear();content.Children.Add(table);var catalog=await session.Strategies();var selected=families.Where(c=>c.IsChecked==true).Select(c=>(string)c.Tag).ToArray();var strategies=catalog.Where(c=>selected.Contains(c.Family)).ToArray();if(strategies.Length==0&&!selected.Contains("ByeTube"))throw new InvalidOperationException("Выберите источники стратегий.");
     var direct=await Task.Run(()=>SetupServiceProbe.Measure(session.Core,ZapretChecks.Selected(settings.CheckServices),false,scanCancellation.Token,message=>Dispatcher.BeginInvoke(new Action(()=>progress.Text=message))));report.Rows.Add(direct);RefreshReport(table,report);if(strategies.Length>0)await ScanStrategies(report,strategies,scanCancellation.Token,message=>Dispatcher.BeginInvoke(new Action(()=>progress.Text=message)),r=>{ZapretChecks.Save(root,r);var snapshot=ReportSnapshot(r);Dispatcher.BeginInvoke(new Action(()=>RefreshReport(table,snapshot)));});if(selected.Contains("ByeTube"))await Task.Run(()=>ByeTubeScan.Run(session.Core,report,scanCancellation.Token,message=>Dispatcher.BeginInvoke(new Action(()=>progress.Text=message)),r=>{ZapretChecks.Save(root,r);var snapshot=ReportSnapshot(r);Dispatcher.BeginInvoke(new Action(()=>RefreshReport(table,snapshot)));}));scanCancellation.Token.ThrowIfCancellationRequested();report.Complete=true;report.WizardServiceIds=settings.CheckServices.ToList();lastReport=report;ZapretChecks.Save(root,report);progress.Text="Поиск завершён. Выбираем конфигурации и проверяем сервисы очно.";result.Visibility=Visibility.Visible;await ContinueSetupWizard(report,scanCancellation.Token);RefreshReport(table,report);progress.Text="Мастер завершён. Результаты и очные проверки сохранены.";
    }catch(OperationCanceledException){progress.Text="Проверка остановлена; завершённые результаты сохранены.";}catch(Exception ex){progress.Text=ex.Message;}finally{checking=false;header.IsEnabled=start.IsEnabled=true;stop.Visibility=Visibility.Collapsed;scanCancellation.Dispose();scanCancellation=null;}
   };
   start.Click+=async(s,e)=>{if(checking)return;if(TestSettings(checks).CheckServices.Count==0){progress.Text="Выберите сервисы.";return;}await Run(run);};
   var resume=new Button{Name="setupResume",Content="Очные проверки по сохранённым результатам"};resume.Click+=async(s,e)=>{if(checking||busy)return;await Run(async()=>{var saved=lastReport??ZapretChecks.Load(root);if(saved==null||!saved.Complete)throw new InvalidOperationException("Нет завершённой проверки для продолжения.");await PrepareTests();scanCancellation=new CancellationTokenSource();try{await ContinueSetupWizard(saved,scanCancellation.Token,true);}finally{scanCancellation.Dispose();scanCancellation=null;}});};footer.Children.Add(resume);
   session.State.SetupSeen=true;Dispatcher.BeginInvoke(new Action(()=>LoadBasicJournal(session.Save)));
  }

 }
}

