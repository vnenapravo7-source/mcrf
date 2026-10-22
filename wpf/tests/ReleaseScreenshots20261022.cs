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
using SplifyWin;
using Mcrf.Wpf;
class ReleaseScreenshots20261022 {
 static BindingFlags F=BindingFlags.NonPublic|BindingFlags.Instance; static Application app; static MainWindow window; static ClientSession session; static string root;
 static object Get(string n){return typeof(MainWindow).GetField(n,F).GetValue(window);}
 static object Call(string n,params object[] a){return typeof(MainWindow).GetMethod(n,F).Invoke(window,a);}
 static void Wait(Func<bool> done){var end=DateTime.UtcNow.AddSeconds(30);while(!done()&&DateTime.UtcNow<end){var f=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle,new Action(()=>f.Continue=false));Dispatcher.PushFrame(f);Thread.Sleep(5);}if(!done())throw new Exception("UI timeout");window.UpdateLayout();}
 static void Finish(Task task){Wait(()=>task.IsCompleted);task.GetAwaiter().GetResult();}
 static IEnumerable<T> All<T>(DependencyObject p)where T:DependencyObject{for(int i=0;i<VisualTreeHelper.GetChildrenCount(p);i++){var c=VisualTreeHelper.GetChild(p,i);if(c is T)yield return (T)c;foreach(var x in All<T>(c))yield return x;}}
 static void Save(FrameworkElement target,string name){target.UpdateLayout();var b=new RenderTargetBitmap((int)target.ActualWidth,(int)target.ActualHeight,96,96,PixelFormats.Pbgra32);b.Render(target);var png=new PngBitmapEncoder();png.Frames.Add(BitmapFrame.Create(b));using(var s=File.Create(Path.Combine(root,name+"-windows-1.1.png")))png.Save(s);Console.WriteLine("SCREENSHOT "+name);}
 static void Page(string name){Call("Page",name);Wait(()=>!(bool)Get("busy"));Save(window,name=="Главная"?"home":name=="Серверы"?"servers":name=="Маршрутизация"?"routes":name=="VK TURN"?"vk-turn":name=="Автодеплой"?"autodeploy":"updates");}
 [STAThread]static int Main(string[] args){try{
 root=args[0];if(Directory.Exists(root))throw new Exception("Fresh screenshot directory required");Directory.CreateDirectory(root);
 app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};typeof(MainWindow).Assembly.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",BindingFlags.NonPublic|BindingFlags.Static).Invoke(null,new object[]{app});SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
 window=new MainWindow(new[]{"--data",Path.Combine(root,"profile")}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000,Width=1300,Height=820};window.Show();Wait(()=>Get("session")!=null);session=(ClientSession)Get("session");((DispatcherTimer)Get("health")).Stop();typeof(ClientSession).GetField("catalogTask",F).SetValue(session,Task.FromResult(new ZapretStrategy[0]));
 var vpn=Links.Read("socks5://192.0.2.10:1080#VPN%20%2F%20%D0%BF%D1%80%D0%BE%D0%BA%D1%81%D0%B8%20%C2%B7%20%D0%B4%D0%B5%D0%BC%D0%BE").Single();
 var q=Links.Read(QwdttProfile.Link("QWDTT · демо","192.0.2.20:56000","demo-password",new string('A',43))).Single();
 q.TurnPreferences=new VkTurnServerPreferences{Options=new Dictionary<string,string>{{"fingerprint","chrome"},{"go_dns","doh-cloudflare"},{"raw_port","56003"}}};
 session.State.Servers=new List<ServerNode>{vpn,q};session.State.SelectedServerId=vpn.Id;session.State.AutoSelect=false;
 session.State.Lists=new List<RouteList>{new RouteList{Name="Популярные сервисы 7",Target="proxy",Text="youtube.com\ndiscord.com\ninstagram.com\nfacebook.com\nx.com\nchatgpt.com\ntelegram.org",Enabled=true},new RouteList{Name="РФ — напрямую",Target="direct",Text="geosite:category-ru\ngeoip:ru",Enabled=true}};
 Finish(session.Save());Page("Главная");Page("Серверы");Page("Маршрутизация");Page("VK TURN");
 Exception modalError=null;var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(30)};timer.Tick+=(s,e)=>{var dialog=app.Windows.Cast<Window>().FirstOrDefault(x=>x!=window);if(dialog==null)return;timer.Stop();try{dialog.Left=-10000;dialog.Top=-10000;dialog.UpdateLayout();Save(dialog,"vk-turn-editor");dialog.Close();}catch(Exception ex){modalError=ex;dialog.Close();}};timer.Start();Call("VkTurnServerEditor",q);if(modalError!=null)throw modalError;
 Page("Автодеплой");Page("Обновления");Finish((Task)Call("ChangeInterfaceMode",1));Call("Page","Конструктор");window.UpdateLayout();Save(window,"constructor");window.Width=1050;window.Height=680;window.UpdateLayout();Save(window,"constructor-minimum");window.Width=1500;window.Height=950;window.UpdateLayout();Save(window,"constructor-large");
 if(session.Core.Running||session.Zapret.Running||session.Telegram.Running)throw new Exception("Unexpected networking");
 Call("RequestExit");Wait(()=>!window.IsVisible);app.Shutdown();Console.WriteLine("PASS 1.1 startup and 10 demo screenshots; no connections started");return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);if(window!=null)Call("RequestExit");return 1;}}
}
