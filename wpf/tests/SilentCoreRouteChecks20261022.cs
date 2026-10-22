using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
class SilentCoreRouteChecks {
 static readonly BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;static MainWindow main;static Application app;static int count;static string root;
 [DllImport("kernel32.dll")]static extern bool FreeConsole();
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool AttachConsole(uint pid);
 static object Get(string n){return typeof(MainWindow).GetField(n,F).GetValue(main);}static object Call(string n,params object[] a){return typeof(MainWindow).GetMethod(n,F).Invoke(main,a);}
 static void Check(bool v,string n){if(!v)throw new Exception(n);Console.WriteLine("PASS "+n);count++;}
 static void Pump(){var f=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle,new Action(()=>f.Continue=false));Dispatcher.PushFrame(f);}
 static void Wait(Func<bool> ready){var end=DateTime.UtcNow.AddSeconds(30);while(!ready()&&DateTime.UtcNow<end){Pump();Thread.Sleep(5);}if(!ready())throw new Exception("Timeout");}
 static void Finish(Task t){Wait(()=>t.IsCompleted);t.GetAwaiter().GetResult();}
 static System.Collections.Generic.IEnumerable<T> All<T>(DependencyObject r)where T:DependencyObject{for(int i=0;i<VisualTreeHelper.GetChildrenCount(r);i++){var c=VisualTreeHelper.GetChild(r,i);if(c is T)yield return (T)c;foreach(var x in All<T>(c))yield return x;}}
 static int Port(){var l=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0);l.Start();int port=((System.Net.IPEndPoint)l.LocalEndpoint).Port;l.Stop();return port;}
 static void NoConsole(Process p){using(var check=Process.Start(new ProcessStartInfo(Assembly.GetExecutingAssembly().Location,"--check-console "+p.Id){UseShellExecute=false,CreateNoWindow=true})){if(!check.WaitForExit(5000))throw new Exception("Console checker timeout");Check(check.ExitCode==0,"actual core has no console allocated (checker="+check.ExitCode+")");}}
 [STAThread]static int Main(string[] args){if(args.Length==2&&args[0]=="--check-console"){FreeConsole();bool attached=AttachConsole(UInt32.Parse(args[1]));int error=Marshal.GetLastWin32Error();if(attached)FreeConsole();return !attached&&error==6?0:attached?100:1000+error;}ClientSession session=null;try{
  root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh profile required");var program=typeof(MainWindow).Assembly.GetType("Mcrf.Wpf.Program");app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};program.GetMethod("LoadTheme",BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,new object[]{app});SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());main=new MainWindow(new[]{"--data",root}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000};main.Show();Wait(()=>Get("session")!=null);session=(ClientSession)Get("session");((DispatcherTimer)Get("health")).Stop();typeof(ClientSession).GetField("catalogTask",F).SetValue(session,Task.FromResult(new ZapretStrategy[0]));
  var core=new NetworkCore(session.Store,Port(),Port(),Port());typeof(ClientSession).GetProperty("Core",BindingFlags.Instance|BindingFlags.Public).SetValue(session,core);session.State.Mode="proxy";session.State.AutoSelect=false;session.State.VpnEnabled=true;session.State.DnsProvider="Системный";session.State.DnsProtocol="Системный";session.State.Lists.Clear();var node=Links.Read("socks5://127.0.0.1:9#Local-fixture").Single();node.Healthy=true;session.State.Servers.Add(node);session.State.SelectedServerId=node.Id;
  for(int i=0;i<3;i++){Finish(Task.Run(()=>core.Start(session.State)));var child=(Process)typeof(NetworkCore).GetField("process",F).GetValue(core);NoConsole(child);int pid=child.Id;Finish(Task.Run(()=>core.Stop()));Check(!core.Running,"actual core STOP closes on first request cycle "+i);bool gone;try{using(var old=Process.GetProcessById(pid))gone=old.HasExited;}catch(ArgumentException){gone=true;}Check(gone,"no owned core orphan after STOP cycle "+i);}
  Finish(Task.Run(()=>core.Start(session.State)));Call("Page","Маршрутизация");main.UpdateLayout();long generation=core.StartGeneration;Exception editError=null;var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(20)};timer.Tick+=(s,e)=>{var edit=app.Windows.Cast<Window>().FirstOrDefault(w=>w!=main&&All<TextBox>(w).Any(t=>t.Name=="routeEditorEntries"));if(edit==null)return;timer.Stop();try{edit.Left=-10000;edit.Top=-10000;All<TextBox>(edit).Single(t=>t.Name=="routeEditorEntries").Text="app:discord.exe";var match=All<ComboBox>(edit).First(c=>c.Items.Cast<object>().Any(x=>Convert.ToString(x)=="Только приложения"));match.SelectedItem=match.Items.Cast<object>().Single(x=>Convert.ToString(x)=="Только приложения");All<Button>(edit).Single(b=>Convert.ToString(b.Content)=="Сохранить").RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));}catch(Exception ex){editError=ex;edit.Close();}};timer.Start();Finish((Task)Call("Run",(Func<Task>)(()=>(Task)Call("RouteEditor",(object)null)),null));timer.Stop();if(editError!=null)throw editError;Pump();main.UpdateLayout();Check(core.Running&&core.StartGeneration>generation,"actual rule save restarts running real core successfully");NoConsole((Process)typeof(NetworkCore).GetField("process",F).GetValue(core));var grid=All<DataGrid>(main).Single(g=>g.Name=="routeGrid");typeof(ItemContainerGenerator).GetMethod("Verify",F).Invoke(grid.ItemContainerGenerator,null);Check(session.State.Lists.Any(r=>r.Text=="app:discord.exe")&&grid.ItemsSource is RouteList[],"running-core Discord rule save retains consistent snapshot and WPF generator");
  var held=new TaskCompletionSource<bool>();var operation=(Task)Call("Run",(Func<Task>)(()=>held.Task),null);Finish((Task)Call("ChangeModule","@tun",(bool?)false));Check((bool?)Get("queuedTun")==false,"first OFF click is retained during another busy operation");held.SetResult(true);Finish(operation);Wait(()=>!core.Running&&session.State.VpnEnabled==false&&!(bool)Get("busy"));Check(!core.Running&&!session.State.VpnEnabled,"queued first OFF click stops actual core without a second click");
  main.Close();Wait(()=>!main.IsVisible);app.Shutdown();Console.WriteLine("RESULT "+count+" checks PASS; no TUN driver or external peer used");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);if(session!=null)session.Core.Stop();if(main!=null)main.Close();return 1;}}
}
