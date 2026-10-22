using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
class RouteTableSnapshotChecks20261022 {
 static readonly BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;
 static MainWindow main;static ClientSession session;static int checks;static Application app;
 static void Check(bool condition,string label){if(!condition)throw new Exception("FAIL "+label);checks++;Console.WriteLine("PASS "+label);}
 static object Call(string name,params object[] args){return typeof(MainWindow).GetMethod(name,F).Invoke(main,args);}
 static void Pump(){var frame=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>frame.Continue=false));Dispatcher.PushFrame(frame);}
 static void Wait(Func<bool> done){var end=DateTime.UtcNow.AddSeconds(30);while(!done()&&DateTime.UtcNow<end){Pump();Thread.Sleep(5);}if(!done())throw new Exception("Timeout");Pump();}
 static void Finish(Task task){Wait(()=>task.IsCompleted);task.GetAwaiter().GetResult();}
 static IEnumerable<T> All<T>(DependencyObject root)where T:DependencyObject{for(int i=0;i<VisualTreeHelper.GetChildrenCount(root);i++){var c=VisualTreeHelper.GetChild(root,i);if(c is T)yield return (T)c;foreach(var d in All<T>(c))yield return d;}}
 static DataGrid Table(){return All<DataGrid>(main).Single(t=>t.Name=="routeGrid");}
 static void Page(){Call("Page","Маршрутизация");Pump();main.UpdateLayout();}
 static RouteList Route(string name){return new RouteList{Name=name,Target="direct",MatchMode="apps",Text="app:speedtest.exe",Enabled=true};}
 static void Verify(DataGrid table){table.UpdateLayout();var verify=typeof(ItemContainerGenerator).GetMethod("Verify",F);if(verify==null)throw new Exception("WPF generator verification unavailable");verify.Invoke(table.ItemContainerGenerator,null);}
 static void Editor(RouteList original,string name,bool save){
  Exception error=null;var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(15)};
  timer.Tick+=(s,e)=>{var w=app.Windows.Cast<Window>().FirstOrDefault(x=>x!=main&&All<TextBox>(x).Any(t=>t.Name=="routeEditorEntries"));if(w==null)return;timer.Stop();try{w.ShowActivated=false;w.Left=-10000;w.Top=-10000;All<TextBox>(w).First(t=>t.Name=="").Text=name;All<TextBox>(w).Single(t=>t.Name=="routeEditorEntries").Text="app:speedtest.exe";var match=All<ComboBox>(w).First(c=>c.Items.Cast<object>().Any(x=>Convert.ToString(x)=="Только приложения"));match.SelectedItem=match.Items.Cast<object>().Single(x=>Convert.ToString(x)=="Только приложения");All<Button>(w).Single(b=>Convert.ToString(b.Content)==(save?"Сохранить":"Отмена")).RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));}catch(Exception ex){error=ex;w.Close();}};
  timer.Start();try{Finish((Task)Call("RouteEditor",original));}finally{timer.Stop();}if(error!=null)throw error;Pump();main.UpdateLayout();Verify(Table());
 }
 [STAThread]static int Main(string[] args){try{
  string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh isolated profile required");Directory.CreateDirectory(root);
  var program=typeof(MainWindow).Assembly.GetType("Mcrf.Wpf.Program",true);program.GetMethod("ConfigureDpi",BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,null);
  app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};program.GetMethod("LoadTheme",BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,new object[]{app});
  SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
  main=new MainWindow(new[]{"--data",Path.Combine(root,"profile")}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000};main.Show();Wait(()=>typeof(MainWindow).GetField("session",F).GetValue(main)!=null);session=(ClientSession)typeof(MainWindow).GetField("session",F).GetValue(main);
  ((DispatcherTimer)typeof(MainWindow).GetField("health",F).GetValue(main)).Stop();typeof(ClientSession).GetField("catalogTask",F).SetValue(session,Task.FromResult(new ZapretStrategy[0]));
  session.State.Lists=Enumerable.Range(0,30).Select(i=>Route("Fixture "+i)).ToList();Page();var table=Table();
  Check(table.ItemsSource is RouteList[]&&!Object.ReferenceEquals(table.ItemsSource,session.State.Lists),"grid binds stable snapshot, not mutable model list");
  var held=new TaskCompletionSource<bool>();var addition=Route("Held speedtest");var saveTask=session.ChangeConfiguration(async()=>{session.State.Lists.Add(addition);await held.Task;});
  Verify(table);Check(table.Items.Count==30&&session.State.Lists.Count==31&&!saveTask.IsCompleted,"pending asynchronous save cannot change rendered membership");held.SetResult(true);Finish(saveTask);Page();Check(Table().Items.Count==31,"completed addition appears after refresh");
  Editor(null,"Speedtest rule",true);Check(session.State.Lists.Count==32&&session.State.Lists.Last().Text=="app:speedtest.exe","actual new-rule dialog saves speedtest.exe");Check(new StateStore(Path.Combine(root,"profile")).Load().Lists.Any(r=>r.Name=="Speedtest rule"),"new rule persisted to isolated profile");
  var original=session.State.Lists.Last();Editor(original,"Speedtest edited",true);Check(session.State.Lists.Count==32&&session.State.Lists.Last().Id==original.Id&&session.State.Lists.Last().Name=="Speedtest edited","actual edit replaces row without changing count or identity");
  Editor(session.State.Lists.Last(),"Cancelled edit",false);Check(session.State.Lists.Last().Name=="Speedtest edited","cancel editing preserves saved rule");Editor(null,"Cancelled add",false);Check(session.State.Lists.Count==32,"cancel adding preserves membership");
  table=Table();table.SelectedItem=table.Items[3];string selected=((RouteList)table.SelectedItem).Id;bool rejected=false;try{Finish(session.ChangeConfiguration((Action)(()=>{session.State.Lists.Add(Route("Rejected"));throw new InvalidOperationException("fixture rejection");})));}catch(InvalidOperationException){rejected=true;}
  Call("RestoreRouteTable",table);Pump();Verify(table);Check(rejected&&session.State.Lists.Count==32&&table.Items.Count==32,"failed transaction restores membership and generator");Check(table.ItemsSource is RouteList[]&&((RouteList)table.SelectedItem).Id==selected,"rollback keeps snapshot binding and selection");
  var removed=session.State.Lists.Last();Finish((Task)Call("RouteChange",(Action)(()=>session.State.Lists.Remove(removed)),false));Verify(table);Check(table.Items.Count==32&&session.State.Lists.Count==31,"deletion cannot invalidate existing table snapshot");Page();Check(Table().Items.Count==31,"deleted rule disappears after refresh");
  table=Table();var first=session.State.Lists[0];typeof(MainWindow).GetMethod("ReorderRoute",BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,new object[]{session.State.Lists,first,session.State.Lists[2],true});Verify(table);Check(((RouteList)table.Items[0]).Id==first.Id,"reordering model leaves current generator order stable");Page();Check(((RouteList)Table().Items[2]).Id==first.Id,"reordered priority appears after refresh");
  Check(!session.Core.Running&&!session.Zapret.Running,"no VPN or Zapret engine started");main.Close();Wait(()=>!main.IsVisible);app.Shutdown();Console.WriteLine("RESULT "+checks+" checks PASS");return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);if(main!=null)main.Close();return 1;}}
}
