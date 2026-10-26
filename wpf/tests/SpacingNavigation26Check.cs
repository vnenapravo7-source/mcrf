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
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
class SpacingNavigation26Check {
 static MainWindow owner;static ClientSession session;static string output;static int count;
 const BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;
 static object Get(string n){return typeof(MainWindow).GetField(n,F).GetValue(owner);}
 static object Call(string n,params object[] args){return typeof(MainWindow).GetMethods(F).Single(m=>m.Name==n&&m.GetParameters().Length==args.Length).Invoke(owner,args);}
 static void Pump(){var frame=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>frame.Continue=false));Dispatcher.PushFrame(frame);}
 static void Until(Func<bool> done){var end=DateTime.UtcNow.AddSeconds(15);while(!done()&&DateTime.UtcNow<end){Pump();Thread.Sleep(5);}if(!done())throw new Exception("timeout");}
 static void Wait(Task t){Until(()=>t.IsCompleted);t.GetAwaiter().GetResult();}
 static IEnumerable<T> All<T>(DependencyObject root)where T:DependencyObject{var visited=new HashSet<DependencyObject>();return Walk<T>(root,visited);}
 static IEnumerable<T> Walk<T>(DependencyObject root,HashSet<DependencyObject> visited)where T:DependencyObject{if(root==null||!visited.Add(root))yield break;if(root is T)yield return (T)root;if(root is Visual)for(int i=0;i<VisualTreeHelper.GetChildrenCount(root);i++)foreach(var item in Walk<T>(VisualTreeHelper.GetChild(root,i),visited))yield return item;foreach(var child in LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>())foreach(var item in Walk<T>(child,visited))yield return item;}
 static T Named<T>(DependencyObject root,string name)where T:FrameworkElement{return All<T>(root).Single(x=>x.Name==name);}
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);Console.WriteLine("PASS "+label);count++;}
 static void Click(Button b){b.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));Pump();}
 static Rect Bounds(FrameworkElement e,Visual parent){return e.TransformToAncestor(parent).TransformBounds(new Rect(0,0,e.ActualWidth,e.ActualHeight));}
 static void Shot(FrameworkElement e,string name){e.UpdateLayout();var bmp=new RenderTargetBitmap((int)Math.Ceiling(e.ActualWidth),(int)Math.Ceiling(e.ActualHeight),96,96,PixelFormats.Pbgra32);bmp.Render(e);var png=new PngBitmapEncoder();png.Frames.Add(BitmapFrame.Create(bmp));using(var f=File.Create(Path.Combine(output,name+".png")))png.Save(f);}
 static void Modal(Action open,Action<Window> inspect){Exception failure=null;bool seen=false;Window dialog=null;var start=DateTime.UtcNow;var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(25)};timer.Tick+=(s,e)=>{if(seen){if(dialog!=null&&dialog.IsVisible&&DateTime.UtcNow-start>TimeSpan.FromSeconds(10)){failure=new Exception("modal did not close");dialog.Close();}return;}dialog=Application.Current.Windows.Cast<Window>().FirstOrDefault(w=>w!=owner&&w.IsVisible);if(dialog==null)return;seen=true;try{dialog.UpdateLayout();inspect(dialog);}catch(Exception ex){failure=ex;dialog.Close();}};timer.Start();try{open();Until(()=>seen);Pump();}finally{timer.Stop();}if(failure!=null)throw failure;Check(seen,"modal opens without exception");}
 static void Editor(RouteList original,string theme,bool save){Modal(()=>Wait((Task)Call("RouteEditor",original)),dialog=>{
  var name=Named<TextBox>(dialog,"routeEditorName");var sources=Named<Expander>(dialog,"routeEditorSources");var accept=Named<Button>(dialog,"routeEditorSave");var cancel=Named<Button>(dialog,"routeEditorCancel");
  Check(!sources.IsExpanded,theme+" source fields initially collapsed");Check(!All<CheckBox>(dialog).Any(c=>c.Name=="routeEditorExcludeRussia"),theme+" Russia standalone checkbox removed");
  Shot(dialog,theme+"-editor-before-check");var a=Bounds(accept,dialog);var b=Bounds(cancel,dialog);Check(a.Width>90&&b.Width>90&&a.Right<=b.Left+1&&Math.Abs(a.Top-b.Top)<1&&b.Bottom<=dialog.ActualHeight,theme+" Save and Cancel visible equal row "+a+" / "+b+" window="+dialog.ActualWidth+"x"+dialog.ActualHeight);
  var presets=Named<ComboBox>(dialog,"routeEditorPresets");var apps=Named<Button>(dialog,"routeApplicationsPicker");Check(Math.Abs(Bounds(presets,dialog).Top-Bounds(apps,dialog).Top)<1,theme+" presets and apps share row");
  var target=Named<ComboBox>(dialog,"routeEditorTarget");Check(Bounds(target,dialog).Top>Bounds(apps,dialog).Bottom,theme+" destination below picker row");Shot(dialog,theme+(original==null?"-add":"-edit")+"-collapsed");
  sources.IsExpanded=true;dialog.UpdateLayout();var text=Named<TextBox>(dialog,"routeEditorEntries");name.Text="Fixture "+theme;text.Text="example.invalid";target.SelectedItem=target.Items.Cast<object>().Single(x=>Convert.ToString(x.GetType().GetProperty("Key").GetValue(x,null))=="direct");
  Shot(dialog,theme+(original==null?"-add":"-edit")+"-expanded");if(save)Click(accept);else dialog.Close();
 });}
 [STAThread]static int Main(string[] args){try{
  output=Path.GetFullPath(args[0]);Directory.CreateDirectory(output);Mcrf.Wpf.Program.ConfigureDpi();var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};Mcrf.Wpf.Program.LoadTheme(app);SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
  var opening=ClientSession.Open(Path.Combine(output,"profile"));Wait(opening);session=opening.Result;session.State.CompactAnimations=false;session.State.CompactAdvancedFeatures=true;session.State.CompactConnectionStyle="classic";
  typeof(ClientSession).GetField("catalogTask",F).SetValue(session,Task.FromResult(new[]{new ZapretStrategy{Id="fixture",Name="Fixture",Family="Свои",Tcp="--filter-tcp=443"}}));
  owner=new MainWindow(new[]{"--data",Path.Combine(output,"profile")},p=>Task.FromResult(session)){ShowActivated=false,ShowInTaskbar=false,Left=-30000,Top=-30000,WindowStartupLocation=WindowStartupLocation.Manual};owner.Show();Pump();((DispatcherTimer)Get("health")).Stop();
  var node=Links.Read("csqtt://connect?v=2&host=192.0.2.1&peer=56005&password=fixture&hashes="+new string('A',43)).Single();node.CsqttGeneration="2.1.9";session.State.Servers.Add(node);
  foreach(var theme in new[]{"dark","light"}){
   session.State.CompactTheme=theme;Call("ApplyWorkspacePalette",false);Call("Page","Главная");Pump();var body=(Panel)Get("body");var originalBody=body.Children[0];
   Modal(()=>{Call("QueueVkTurnEditor",node);Until(()=>!(bool)Get("vkEditorQueued"));},dialog=>Click(Named<Button>(dialog,"vkCancelConnection")));Check(Object.ReferenceEquals(originalBody,body.Children[0]),theme+" server cancel preserves page visual tree");Check(Get("modalVeil")==null,theme+" modal veil cleared");
   Editor(null,theme,true);var route=session.State.Lists.Last();Check(route.Name=="Fixture "+theme&&route.Text=="example.invalid",theme+" add saved");Editor(route,theme,true);Check(new StateStore(Path.Combine(output,"profile")).Load().Lists.Any(r=>r.Id==route.Id),theme+" edit persists");
   Call("Page","VK TURN");Pump();var slider=Named<Slider>(owner,"vkStreams_csqtt");slider.BringIntoView();Pump();owner.UpdateLayout();Check(All<StreamTrackPoints>(slider).Count()==1,theme+" dots are inside slider template");Check(!All<UniformGrid>(owner).Any(g=>g.Name.EndsWith("Points")),theme+" no separate bottom dots row");var dots=All<StreamTrackPoints>(slider).Single();Check(Math.Abs(Bounds(dots,slider).Top+dots.ActualHeight/2-slider.ActualHeight/2)<1,theme+" dots centered on rail");Shot(owner,theme+"-stream-track");
   Call("Page","Маршрутизация");Pump();owner.UpdateLayout();var dns=Named<Button>(owner,"compactRouteDns_"+route.Id);var game=Named<Button>(owner,"compactRuleGame_"+route.Id);Check(dns.Background==game.Background||dns.Background.ToString()==game.Background.ToString(),theme+" DNS and Game backgrounds match");
   int windows=Application.Current.Windows.Count;Click(game);var popup=(Popup)game.Tag;Check(popup.IsOpen&&popup.Placement==PlacementMode.Bottom&&popup.PlacementTarget==game,theme+" Game opens anchored picker");Check(Application.Current.Windows.Count==windows&&Get("modalVeil")==null,theme+" Game creates no modal window or veil");Check(!Named<CheckBox>(popup.Child,"routeGameTcp").IsEnabled,theme+" Game respects non-Zapret scope");Shot((FrameworkElement)popup.Child,theme+"-game-picker");Click(Named<Button>(popup.Child,"routeGameCancel"));Check(!popup.IsOpen,theme+" Game picker cancels");Shot(owner,theme+"-rules");
   var profile=new ZapretProfile{Name="Fixture profile",Enabled=false};session.State.ZapretProfiles.Add(profile);var gameRoute=new RouteList{Name="Game fixture",Enabled=false,Target="zapret",ZapretProfileId=profile.Id,Text="example.invalid"};session.State.Lists.Add(gameRoute);Call("Page","Маршрутизация");Pump();var gameButton=Named<Button>(owner,"compactRuleGame_"+gameRoute.Id);Click(gameButton);var gamePopup=(Popup)gameButton.Tag;var tcp=Named<CheckBox>(gamePopup.Child,"routeGameTcp");var udp=Named<CheckBox>(gamePopup.Child,"routeGameUdp");Check(tcp.IsEnabled&&udp.IsEnabled,theme+" Zapret picker enables TCP and UDP");tcp.IsChecked=false;udp.IsChecked=true;Click(Named<Button>(gamePopup.Child,"routeGameApply"));Until(()=>!gamePopup.IsOpen);var persisted=new StateStore(Path.Combine(output,"profile")).Load().Lists.Single(x=>x.Id==gameRoute.Id);Check(persisted.GameFilterTcp==false&&persisted.GameFilterUdp==true,theme+" anchored picker persists protocol selection");
   Call("Page","Журнал");Pump();Check(!All<Expander>(owner).Any(),theme+" Settings uses navigation not expanders");var links=All<Button>(owner).Select(b=>Convert.ToString(b.Content)).ToArray();Check(Array.IndexOf(links,"Обновления")==Array.IndexOf(links,"Поведение программы")+1,theme+" Updates follows behavior");Check(Array.IndexOf(links,"Локальный прокси")==Array.IndexOf(links,"Автоопределение БС")+1,theme+" Proxy follows BS");Shot(owner,theme+"-settings");
   Call("Page","Обновления");Pump();owner.UpdateLayout();var updates=Named<DockPanel>(owner,"compactUpdatesLayout");Check(updates.LastChildFill&&updates.Children[updates.Children.Count-1] is ListBox,theme+" Updates list fills remaining height");Shot(owner,theme+"-updates");
  }
  owner.Close();app.Shutdown();Console.WriteLine("RESULT "+count+" checks PASS; private profile, offscreen WPF renders; no physical compositor claim");return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);foreach(var w in Application.Current.Windows.Cast<Window>().ToArray())w.Close();return 1;}}
}
