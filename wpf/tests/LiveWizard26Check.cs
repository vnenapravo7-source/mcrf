using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
class LiveWizard26Check {
 const BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;
 static MainWindow owner;static ClientSession session;static string output,root;static int count;
 static object Get(string n){return typeof(MainWindow).GetField(n,F).GetValue(owner);}
 static void Set(string n,object value){typeof(MainWindow).GetField(n,F).SetValue(owner,value);}
 static object Call(string n,params object[] args){return typeof(MainWindow).GetMethods(F).Single(m=>m.Name==n&&m.GetParameters().Length==args.Length).Invoke(owner,args);}
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);count++;Console.WriteLine("PASS "+label);}
 static IEnumerable<T> All<T>(DependencyObject d)where T:DependencyObject{if(d is T)yield return (T)d;foreach(var child in LogicalTreeHelper.GetChildren(d).OfType<DependencyObject>())foreach(var item in All<T>(child))yield return item;}
 static T Named<T>(DependencyObject d,string name)where T:FrameworkElement{return All<T>(d).Single(x=>x.Name==name);}
 static void Click(Button button){button.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));}
 static void Pump(){var f=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>f.Continue=false));Dispatcher.PushFrame(f);}
 static void Until(Func<bool> done){var end=DateTime.UtcNow.AddSeconds(15);while(!done()&&DateTime.UtcNow<end){Pump();Thread.Sleep(5);}if(!done())throw new Exception("Timed out waiting for wizard");}
 static void Wait(Task task){Until(()=>task.IsCompleted);task.GetAwaiter().GetResult();}
 static ZapretCheckRow Row(string id,params string[] services){return new ZapretCheckRow{StrategyId=id,Name=id??"Direct",Family="Flowseal",Services=services.Select(s=>new ZapretServiceResult{Id=s,Access=ServiceAccess.Unknown}).ToList()};}
 static ZapretCheckReport Report(bool complete){return new ZapretCheckReport{Complete=complete,Settings=new ZapretSettings{CheckServices=new List<string>{"youtube","discord"}},ServiceIds=new List<string>{"youtube","discord"},Rows=new List<ZapretCheckRow>{Row("fixture","youtube","discord"),new ZapretCheckRow{StrategyId="interrupted",Name="Interrupted",Checking=true,Error="Interrupted"}}};}
 static void Shot(Window window,string name){window.UpdateLayout();var image=new RenderTargetBitmap((int)window.ActualWidth,(int)window.ActualHeight,96,96,PixelFormats.Pbgra32);image.Render(window);var png=new PngBitmapEncoder();png.Frames.Add(BitmapFrame.Create(image));using(var file=File.Create(Path.Combine(output,name+".png")))png.Save(file);}
 static void Scenario(string theme,bool resume,bool cancelled,bool detached,bool resultsEntry=false){
  var verified=new List<string>();int choices=0;Exception failure=null;bool started=false,finished=false;
  var saved=Report(!cancelled);ZapretChecks.Save(root,saved);Set("lastReport",null);
  owner.SetupTrialOfflineForTests=true;
  owner.SetupVerifyForTests=async(report,item,token)=>{Check(!token.IsCancellationRequested,theme+" manual stage uses live token");Check(item.Row.StrategyId=="fixture",theme+" interrupted row excluded");verified.Add(item.Service.Id);var trial=(Task<int>)Call("ShowSetupServiceTrial",item,null,token);int answer=await trial;Check(answer==-1,theme+" skip closes actual "+item.Service.Id+" trial without recording success");return (bool?)null;};
  owner.SetupScanForTests=async(report,token)=>{foreach(var row in saved.Rows)report.Rows.Add(row);await Task.Delay(10);if(cancelled){Click(Named<Button>(detached?(DependencyObject)Get("detachedWorkspaceWindow"):owner,"setupStop"));token.ThrowIfCancellationRequested();}};
  var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(25)};
  timer.Tick+=(s,e)=>{try{
   var trial=Application.Current.Windows.Cast<Window>().FirstOrDefault(w=>w.Name=="setupTrialWindow"&&w.IsVisible);if(trial!=null){Check(trial.Owner==(detached?(Window)Get("detachedWorkspaceWindow"):owner),theme+" trial owned by active workspace");Shot(trial,theme+"-trial-"+verified.Last());Click(Named<Button>(trial,"setupTrialSkip"));return;}
   var picker=Application.Current.Windows.Cast<Window>().FirstOrDefault(w=>w.Name=="setupConfigurationWindow"&&w.IsVisible);
   if(picker!=null){choices++;Check(choices==1,theme+" configuration chooser opened once");Check(picker.Owner==(detached?(Window)Get("detachedWorkspaceWindow"):owner),theme+" chooser belongs to active workspace");Check(All<ComboBox>(picker).Count()==2,theme+" both selected services rendered");Check(All<ComboBox>(picker).All(c=>c.Items.Count==1),theme+" only completed candidates offered");Shot(picker,theme+"-"+(resume?"resume":"scan")+(cancelled?"-partial":"-complete")+(detached?"-detached":""));Click(Named<Button>(picker,"setupVerifySelected"));return;}
   var workspace=detached?(Window)Get("detachedWorkspaceWindow"):owner;if(workspace==null)return;
   if(!started){started=true;var button=Named<Button>(workspace,resultsEntry?"resultManualChecks":resume?"setupResume":"setupStart");Dispatcher.CurrentDispatcher.BeginInvoke(new Action(()=>Click(button)));}
   else if(!(bool)Get("busy")){finished=true;timer.Stop();if(detached)workspace.Close();}
  }catch(Exception ex){failure=ex;timer.Stop();foreach(var w in Application.Current.Windows.Cast<Window>().Where(w=>w!=owner).ToArray())w.Close();}};
  timer.Start();try{if(detached)Call("OpenDetachedWorkspace","Мастер настройки",(Action)(()=>Call("SetupPage")));else{((Grid)Get("body")).Children.Clear();if(resultsEntry){Set("lastReport",saved);Call("Page","Результаты");}else Call("SetupPage");}Until(()=>finished||failure!=null);}finally{timer.Stop();}
  if(failure!=null)throw failure;
  Check(choices==1&&verified.SequenceEqual(new[]{"youtube","discord"}),theme+" "+(resume?"saved resume":"automatic handoff")+(cancelled?" after stop":" after full scan")+" verifies services sequentially");
  var storedReport=ZapretChecks.Load(root);Check(storedReport.Complete==!cancelled,theme+" partial completion flag preserved");Check(Get("scanCancellation")==null&&!(bool)Get("busy"),theme+" operation releases cancellation and busy state");
 }
 static void ClosingAndErrors(){
  bool started=false;int choices=0;Exception failure=null;
  owner.SetupScanForTests=async(report,token)=>{report.Rows.Add(Row("fixture","youtube","discord"));await Task.Delay(10);((Window)Get("detachedWorkspaceWindow")).Close();token.ThrowIfCancellationRequested();};
  var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(25)};
  timer.Tick+=(s,e)=>{try{var picker=Application.Current.Windows.Cast<Window>().FirstOrDefault(w=>w.Name=="setupConfigurationWindow"&&w.IsVisible);if(picker!=null){choices++;picker.Close();}var workspace=(Window)Get("detachedWorkspaceWindow");if(workspace!=null&&!started){started=true;var start=Named<Button>(workspace,"setupStart");Dispatcher.CurrentDispatcher.BeginInvoke(new Action(()=>Click(start)));}}catch(Exception ex){failure=ex;}};
  timer.Start();try{Call("OpenDetachedWorkspace","Мастер настройки",(Action)(()=>Call("SetupPage")));}finally{timer.Stop();}
  if(failure!=null)throw failure;Check(started&&choices==0,"closing running wizard does not reopen manual configuration windows");Check(Get("scanCancellation")==null&&!(bool)Get("busy")&&!(bool)Get("detachedWorkspaceOpen"),"close cancels scan and releases detached workspace");Check(!ZapretChecks.Load(root).Complete,"closing scan keeps partial report incomplete");
  Set("lastReport",Report(false));((Grid)Get("body")).Children.Clear();Call("SetupPage");bool clicked=false;owner.SetupVerifyForTests=(report,item,token)=>{throw new InvalidOperationException("Fixture: trial cannot open");};
  timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(25)};timer.Tick+=(s,e)=>{var picker=Application.Current.Windows.Cast<Window>().FirstOrDefault(w=>w.Name=="setupConfigurationWindow"&&w.IsVisible);if(picker!=null&&!clicked){clicked=true;Click(Named<Button>(picker,"setupVerifySelected"));}};
  timer.Start();try{Click(Named<Button>(owner,"setupResume"));Until(()=>clicked&&!(bool)Get("busy"));}finally{timer.Stop();}Check(Named<TextBlock>(owner,"setupProgress").Text.Contains("Fixture: trial cannot open"),"trial opening failures are visible inside wizard");
 }
 [STAThread]static int Main(string[] args){try{
  output=Path.GetFullPath(args[0]);Directory.CreateDirectory(output);root=Path.Combine(output,"private-profile");Mcrf.Wpf.Program.ConfigureDpi();var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};Mcrf.Wpf.Program.LoadTheme(app);SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
  var opening=ClientSession.Open(root);Wait(opening);session=opening.Result;session.State.CompactAdvancedFeatures=true;session.State.CompactAnimations=false;session.State.SetupSeen=true;
  owner=new MainWindow(new[]{"--data",root},p=>Task.FromResult(session)){ShowActivated=false,ShowInTaskbar=false,Left=-30000,Top=-30000,WindowStartupLocation=WindowStartupLocation.Manual};owner.Show();Pump();((DispatcherTimer)Get("health")).Stop();
  foreach(var theme in new[]{"dark","light"}){
   session.State.CompactTheme=theme;Call("ApplyWorkspacePalette",false);
   Scenario(theme,false,false,false);Scenario(theme,false,true,true);Scenario(theme,true,false,false);Scenario(theme,true,true,false);Scenario(theme,true,true,false,true);
   Set("lastReport",null);Call("Page","Zapret & Byetube");Pump();owner.UpdateLayout();var results=Named<Button>(owner,"zapretCheckResults");var scroll=Named<ScrollViewer>(owner,"compactPageScroll");Check(results.TransformToAncestor(owner).Transform(new Point()).Y>=scroll.TransformToAncestor(owner).Transform(new Point(0,scroll.ActualHeight)).Y-1,theme+" results button fixed below profile scroll");Click(results);Until(()=>All<DataGrid>(owner).Any(g=>g.Name=="strategyResultsTable"));Check(Object.ReferenceEquals(Get("lastReport"),null)==false,theme+" results button loads saved report");Check((bool)Get("reportFromEditor")==false,theme+" results not scoped as editor copy");Shot(owner,theme+"-saved-results");
  }
  ClosingAndErrors();var empty=Report(false);empty.Rows.Clear();Check(!SetupWizardFlow.CanContinue(empty),"empty scan cannot start manual checks");empty.Rows.Add(new ZapretCheckRow{StrategyId="unfinished",Checking=true,Services=new List<ZapretServiceResult>{new ZapretServiceResult{Id="youtube"}}});Check(!SetupWizardFlow.CanContinue(empty),"in-progress row alone cannot continue");
  Set("lastReport",empty);((Grid)Get("body")).Children.Clear();Call("SetupPage");Click(Named<Button>(owner,"setupResume"));Until(()=>!(bool)Get("busy"));Check(Named<TextBlock>(owner,"setupProgress").Text.Contains("Нет проверенных"),"empty resume explains problem inside wizard without rebuilding screen");
  Check(!session.Core.Running&&!session.Zapret.Running&&!session.Telegram.Running,"fixtures never start network modules");
  owner.Close();app.Shutdown();Console.WriteLine("LIVE_WIZARD_PASS "+count);return 0;
 }catch(Exception ex){Console.WriteLine("LIVE_WIZARD_FAIL "+ex);return 1;}}
}
