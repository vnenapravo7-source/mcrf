using System;
using System.IO;
using System.Linq;
using System.Reflection;
using Expression=System.Linq.Expressions.Expression;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
class PublishedOnboarding12Check {
 static Assembly production;static string output;static int checks;const BindingFlags F=BindingFlags.Instance|BindingFlags.Static|BindingFlags.Public|BindingFlags.NonPublic;
 static object Value(object target,string name){var p=target.GetType().GetProperty(name,F);return p!=null?p.GetValue(target,null):target.GetType().GetField(name,F).GetValue(target);}
 static void Set(object target,string name,object value){var p=target.GetType().GetProperty(name,F);if(p!=null)p.SetValue(target,value,null);else target.GetType().GetField(name,F).SetValue(target,value);}
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 static void Pump(){var frame=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>frame.Continue=false));Dispatcher.PushFrame(frame);}
 static void Until(Func<bool> ready){var end=DateTime.UtcNow.AddSeconds(15);while(!ready()&&DateTime.UtcNow<end){Pump();Thread.Sleep(5);}if(!ready())throw new Exception("onboarding timeout");}
 static IEnumerable<T> All<T>(DependencyObject root)where T:DependencyObject{if(root is T)yield return (T)root;for(int i=0;i<VisualTreeHelper.GetChildrenCount(root);i++)foreach(var item in All<T>(VisualTreeHelper.GetChild(root,i)))yield return item;}
 static void Shot(Window window,string name){window.UpdateLayout();var bitmap=new RenderTargetBitmap((int)Math.Ceiling(window.ActualWidth),(int)Math.Ceiling(window.ActualHeight),96,96,PixelFormats.Pbgra32);bitmap.Render(window);var png=new PngBitmapEncoder();png.Frames.Add(BitmapFrame.Create(bitmap));using(var file=File.Create(Path.Combine(output,name+".png")))png.Save(file);}
 static void RunCase(string theme,bool advanced,bool wizard){
  string name=theme+"-advanced"+advanced+"-wizard"+wizard;string root=Path.Combine(output,name);int passes=0;
  for(int launch=0;launch<2;launch++){
   var sessionType=production.GetType("Mcrf.Wpf.ClientSession");var opening=(Task)sessionType.GetMethod("Open",F).Invoke(null,new object[]{root});Until(()=>opening.IsCompleted);opening.GetAwaiter().GetResult();var session=Value(opening,"Result");var state=Value(session,"State");
   Set(Value(Value(session,"Updates"),"Preferences"),"CheckOnStart",false);Set(state,"CompactTheme",theme);Set(state,"CompactAnimations",false);
   if(launch==0)Check(!(bool)Value(state,"CompactAdvancedFeatures")&&!(bool)Value(state,"SetupSeen"),name+" truly fresh basic profile");
   Type taskType=typeof(Task<>).MakeGenericType(sessionType),factoryType=typeof(Func<,>).MakeGenericType(typeof(string),taskType);var parameter=Expression.Parameter(typeof(string),"root");var factory=Expression.Lambda(factoryType,Expression.Constant(opening,taskType),parameter).Compile();
   var ownerType=production.GetType("Mcrf.Wpf.MainWindow");var ctor=ownerType.GetConstructor(F,null,new[]{typeof(string[]),factoryType},null);var owner=(Window)ctor.Invoke(new object[]{new[]{"--data",root,"--interactive"},factory});owner.ShowInTaskbar=false;owner.ShowActivated=false;owner.WindowStartupLocation=WindowStartupLocation.Manual;owner.Left=owner.Top=-30000;
   Set(owner,"FirstSetupActiveForTests",true);Set(owner,"StartupStateReadForTests",new Func<Task<bool?>>(()=>Task.FromResult((bool?)false)));
   var seen=new HashSet<Window>();var order=new List<string>();Exception failure=null;bool done=false;DateTime start=DateTime.UtcNow;
   var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(20)};
   timer.Tick+=(s,e)=>{try{
    ((DispatcherTimer)Value(owner,"health")).Stop();
    if(DateTime.UtcNow-start>TimeSpan.FromSeconds(12))throw new Exception(name+" modal timeout");
    foreach(var dialog in Application.Current.Windows.Cast<Window>().Where(w=>w!=owner&&w.IsVisible).ToArray()){
     if(!seen.Add(dialog))continue;dialog.UpdateLayout();
     var accept=All<Button>(dialog).FirstOrDefault(b=>b.Name=="sharedConfirmProceed");var cancel=All<Button>(dialog).FirstOrDefault(b=>b.Name=="sharedConfirmCancel");
     if(accept!=null){
      bool first=dialog.Title=="Возможности MCRF";Check(first?order.Count==0:dialog.Title=="Мастер настройки"&&order.SequenceEqual(new[]{"advanced"}),name+" offers in required order");order.Add(first?"advanced":"wizard-offer");
      foreach(var choice in new[]{accept,cancel}){var bounds=choice.TransformToAncestor(dialog).TransformBounds(new Rect(0,0,choice.ActualWidth,choice.ActualHeight));Check(bounds.Top>=0&&bounds.Bottom<=dialog.ActualHeight-5&&bounds.Width>100&&bounds.Height>20,name+" both choices fit visible window");}var selected=(first?advanced:wizard)?accept:cancel;Check(selected!=null&&selected.IsVisible,name+" visible choice button");if(theme=="dark"&&advanced&&wizard)Shot(dialog,name+"-"+order.Last());selected.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
     }else{
      Check(dialog.Title=="Мастер настройки"&&order.SequenceEqual(new[]{"advanced","wizard-offer"}),name+" actual wizard opens after both offers");Check(All<Button>(dialog).Any(b=>Convert.ToString(b.Content)=="Проверить подключение и стратегии"),name+" real wizard content present");order.Add("wizard-open");if(theme=="dark"&&advanced&&wizard)Shot(dialog,name+"-wizard");dialog.Close();
     }
    }
    bool finished=(bool)Value(Value(session,"State"),"SetupSeen")&&!(bool)Value(owner,"advancedOfferSaving");
    if(launch==0&&finished)done=true;
    if(launch==1&&DateTime.UtcNow-start>TimeSpan.FromMilliseconds(650)){Check(order.Count==0,name+" relaunch does not repeat onboarding");done=true;}
   }catch(Exception ex){failure=ex;done=true;foreach(var w in Application.Current.Windows.Cast<Window>().Where(w=>w!=owner).ToArray())w.Close();}};
   timer.Start();owner.Show();try{Until(()=>done);}finally{timer.Stop();Set(owner,"forceExit",true);owner.Close();Until(()=>!owner.IsVisible);}
   if(failure!=null)throw failure;
   if(launch==0){Check(order.SequenceEqual(wizard?new[]{"advanced","wizard-offer","wizard-open"}:new[]{"advanced","wizard-offer"}),name+" complete first-run chain");var finalState=Value(session,"State");Check((bool)Value(finalState,"CompactAdvancedFeatures")==advanced&&(bool)Value(finalState,"SetupSeen"),name+" both choices retained");passes++;}
  }
  Check(passes==1,name+" first launch and restart completed");
 }
 [STAThread]static int Main(string[] args){try{
  production=Assembly.LoadFile(Path.GetFullPath(args[0]));output=Path.GetFullPath(args[1]);Directory.CreateDirectory(output);var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
  var program=production.GetType("Mcrf.Wpf.Program");program.GetMethod("ConfigureDpi",F).Invoke(null,new object[0]);program.GetMethod("LoadTheme",F).Invoke(null,new object[]{app});
  foreach(string theme in new[]{"dark","light"})foreach(bool advanced in new[]{false,true})foreach(bool wizard in new[]{false,true})RunCase(theme,advanced,wizard);
  app.Shutdown();Console.WriteLine("RESULT "+checks+" checks PASS on exact ordinary production EXE; normal --interactive Loaded path, fresh profiles, real choice dialogs and wizard; no network-test start or working-profile change");return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);if(Application.Current!=null)foreach(var w in Application.Current.Windows.Cast<Window>().ToArray())w.Close();return 1;}}
}
