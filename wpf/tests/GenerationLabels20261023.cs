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
using SplifyWin;
using Mcrf.Wpf;
class GenerationLabels20261023 {
 static readonly BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;static MainWindow main;static Application app;static int count;
 static object Get(string n){return typeof(MainWindow).GetField(n,F).GetValue(main);}static object Call(string n,params object[] a){return typeof(MainWindow).GetMethod(n,F).Invoke(main,a);}
 static void Check(bool value,string label){if(!value)throw new Exception(label);count++;Console.WriteLine("PASS "+label);}
 static void Wait(Func<bool> ready){var end=DateTime.UtcNow.AddSeconds(15);while(!ready()&&DateTime.UtcNow<end){var f=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle,new Action(()=>f.Continue=false));Dispatcher.PushFrame(f);Thread.Sleep(5);}if(!ready())throw new Exception("UI timeout");}
 static IEnumerable<T> All<T>(DependencyObject r)where T:DependencyObject{for(int i=0;i<VisualTreeHelper.GetChildrenCount(r);i++){var c=VisualTreeHelper.GetChild(r,i);if(c is T)yield return (T)c;foreach(var x in All<T>(c))yield return x;}}
 static void Modal(Action open,Action<Window> inspect){Exception error=null;var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(20)};timer.Tick+=(s,e)=>{var w=app.Windows.Cast<Window>().FirstOrDefault(x=>x!=main);if(w==null)return;timer.Stop();try{w.Left=-10000;w.Top=-10000;w.UpdateLayout();inspect(w);}catch(Exception ex){error=ex;w.Close();}};timer.Start();open();Wait(()=>!timer.IsEnabled);if(error!=null)throw error;}
 static void Save(FrameworkElement view,string path){view.UpdateLayout();var b=new RenderTargetBitmap((int)view.ActualWidth,(int)view.ActualHeight,96,96,PixelFormats.Pbgra32);b.Render(view);var png=new PngBitmapEncoder();png.Frames.Add(BitmapFrame.Create(b));using(var s=File.Create(path))png.Save(s);}
 [STAThread]static int Main(string[] args){try{
 app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};typeof(MainWindow).Assembly.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,new object[]{app});SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());main=new MainWindow(new[]{"--data",args[0]}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000,Width=1050,Height=680};main.Show();Wait(()=>Get("session")!=null);var session=(ClientSession)Get("session");((DispatcherTimer)Get("health")).Stop();typeof(ClientSession).GetField("catalogTask",F).SetValue(session,Task.FromResult(new ZapretStrategy[0]));
 Wait(()=>!(bool)Get("busy"));var node=Links.Read("csqtt://connect?v=2&host=192.0.2.1&peer=56000&password=fixture-only&hashes="+new string('A',43)+"&mcrf_generation=2.1.9").Single();node.CsqttGeneration="2.1.9";session.State.Servers.Add(node);
 Modal(()=>Call("VkTurnServerEditor",node),w=>{var c=All<ComboBox>(w).Single(x=>x.Name=="vkCsqttGeneration");c.IsDropDownOpen=true;Wait(()=>c.ItemContainerGenerator.ContainerFromIndex(2)!=null);w.UpdateLayout();foreach(CsqttGeneration g in c.Items){var item=(ComboBoxItem)c.ItemContainerGenerator.ContainerFromItem(g);item.ApplyTemplate();item.UpdateLayout();Check(All<TextBlock>(item).Any(t=>t.Text==g.Label&&t.ActualWidth>0&&t.ActualHeight>0),"actual popup text rendered: "+g.Id);}var popup=All<Popup>(c).Single();Save((FrameworkElement)popup.Child,args[1]);c.IsDropDownOpen=false;foreach(CsqttGeneration g in c.Items){c.SelectedItem=g;w.UpdateLayout();Check(All<TextBlock>(c).Any(t=>t.Text==g.Label&&t.ActualWidth>0),"actual selected text rendered: "+g.Id);}c.SelectedItem=CsqttGenerations.Get("2.1.5");All<Button>(w).Single(b=>b.Name=="vkSaveConnection").RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));});
 Check(node.CsqttGeneration=="2.1.5"&&CsqttGenerations.Hint(node.Link)=="2.1.5","selection saves generation and link consistently");
 Modal(()=>Call("VkTurnServerEditor",node),w=>{var c=All<ComboBox>(w).Single(x=>x.Name=="vkCsqttGeneration");Check(((CsqttGeneration)c.SelectedItem).Id=="2.1.5"&&All<TextBlock>(c).Any(t=>t.Text==CsqttGenerations.Get("2.1.5").Label),"reopened editor displays saved selection");w.Close();});
 var fresh=Links.Read("csqtt://connect?v=2&host=192.0.2.2&peer=56000&password=fixture-only&hashes="+new string('B',43)).Single();
 Modal(()=>Call("ChooseCsqttGenerations",new object[]{new[]{fresh}}),w=>{var c=All<ComboBox>(w).Single(x=>x.Name=="csqttImportGeneration");c.IsDropDownOpen=true;Wait(()=>c.ItemContainerGenerator.ContainerFromIndex(4)!=null);foreach(var choice in c.Items){var item=(ComboBoxItem)c.ItemContainerGenerator.ContainerFromItem(choice);item.ApplyTemplate();item.UpdateLayout();Check(All<TextBlock>(item).Any(t=>!String.IsNullOrWhiteSpace(t.Text)&&t.ActualWidth>0),"import option visible: "+choice);}c.IsDropDownOpen=false;w.Close();});
 Check(!session.Core.Running&&!session.Zapret.Running,"no VPN or provider connection started");Call("RequestExit");Wait(()=>!main.IsVisible);app.Shutdown();Console.WriteLine("RESULT "+count+" UI text/selection checks PASS");return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);if(main!=null)Call("RequestExit");return 1;}}
}
