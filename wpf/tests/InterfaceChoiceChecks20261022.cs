using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
class InterfaceChoiceChecks {
 static BindingFlags flags=BindingFlags.NonPublic|BindingFlags.Instance;static MainWindow window;static int checks;static ClientSession session;
 static object Get(string n){return typeof(MainWindow).GetField(n,flags).GetValue(window);}static object Call(string n,params object[] a){return typeof(MainWindow).GetMethod(n,flags).Invoke(window,a);}
 static IEnumerable<T> All<T>(DependencyObject r)where T:DependencyObject{for(int i=0;i<VisualTreeHelper.GetChildrenCount(r);i++){var c=VisualTreeHelper.GetChild(r,i);if(c is T)yield return (T)c;foreach(var x in All<T>(c))yield return x;}}
 static void Wait(Func<bool> test){var end=DateTime.UtcNow.AddSeconds(20);while(!test()&&DateTime.UtcNow<end){var f=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle,new Action(()=>f.Continue=false));Dispatcher.PushFrame(f);Thread.Sleep(5);}if(!test())throw new Exception("Timeout");}
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 static void Save(string file){window.UpdateLayout();var b=new RenderTargetBitmap((int)window.ActualWidth,(int)window.ActualHeight,96,96,PixelFormats.Pbgra32);b.Render(window);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(b));using(var stream=File.Create(file))encoder.Save(stream);}
 static ComboBox Choice(){return All<ComboBox>(window).Single(c=>c.Name=="interfaceModeChoice");}
 static void Open(string root){window=new MainWindow(new[]{"--data",root}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000,Width=1050,Height=680};window.Show();Wait(()=>Get("session")!=null);((DispatcherTimer)Get("health")).Stop();session=(ClientSession)Get("session");typeof(ClientSession).GetField("catalogTask",flags).SetValue(session,Task.FromResult(new ZapretStrategy[0]));Call("Page","Серверы");window.UpdateLayout();}
 static void Close(){Call("RequestExit");Wait(()=>(bool)Get("closing")&&!window.IsVisible);}
 [STAThread]static int Main(string[] args){try{var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};typeof(MainWindow).Assembly.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",BindingFlags.NonPublic|BindingFlags.Static).Invoke(null,new object[]{app});SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());Open(args[0]);
 var mode=Choice();var minimize=All<ComboBox>(window).Single(c=>c.Name=="minimizeBehavior");Check(mode.Items.Cast<string>().SequenceEqual(new[]{"Весь функционал","Только VPN"}),"dropdown has exactly the requested two names");Check(All<TextBlock>((Panel)Get("side")).Any(t=>t.Text=="Интерфейс"),"interface field has visible label");
 Check(mode.TranslatePoint(new Point(),window).Y<minimize.TranslatePoint(new Point(),window).Y&&Math.Abs(mode.TranslatePoint(new Point(),window).X-minimize.TranslatePoint(new Point(),window).X)<=1,"interface dropdown above minimize, aligned to same sidebar field edge");Check(!All<Slider>(window).Any(s=>s.Name=="interfaceModeSlider"),"old slider removed");
 mode.SelectedIndex=1;Wait(()=>session.State.InterfaceMode==1&&!(bool)Get("busy")&&!(bool)Get("drainingInterfaceMode"));window.UpdateLayout();Check(((StackPanel)Get("navigation")).Children.OfType<Button>().Select(b=>Convert.ToString(b.Content)).SequenceEqual(new[]{"Серверы","Конструктор","Обновления"}),"dropdown actually changes navigation and constructor order");Check(mode.SelectedIndex==1&&mode.IsVisible,"only-VPN choice stays visible after changing mode");Save(Path.Combine(args[1],"interface-only-vpn-final.png"));
 typeof(MainWindow).GetField("busy",flags).SetValue(window,true);mode.SelectedIndex=0;mode.SelectedIndex=1;mode.SelectedIndex=0;typeof(MainWindow).GetField("busy",flags).SetValue(window,false);Call("ScheduleInterfaceMode");Wait(()=>session.State.InterfaceMode==0&&!(bool)Get("busy")&&!(bool)Get("drainingInterfaceMode"));Check(mode.SelectedIndex==0,"last dropdown intent retained while another operation busy");Save(Path.Combine(args[1],"interface-full-final.png"));
 mode.SelectedIndex=1;Wait(()=>session.State.InterfaceMode==1&&!(bool)Get("busy")&&!(bool)Get("drainingInterfaceMode"));Close();Open(args[0]);Check(session.State.InterfaceMode==1&&Choice().SelectedIndex==1,"selected dropdown choice persists across window/session restart");Check(All<ComboBox>(window).Count(c=>c.Name=="interfaceModeChoice")==1,"one sidebar dropdown, no duplicate titlebar instance");Close();app.Shutdown();Console.WriteLine("RESULT "+checks+" differential checks PASS");return 0;}catch(Exception ex){Console.Error.WriteLine(ex);if(window!=null)Call("RequestExit");return 1;}}
}
