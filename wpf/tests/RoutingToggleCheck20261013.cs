using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
class RoutingToggleCheck20261013 {
 static readonly BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;static int checks,failures;static MainWindow main;static string root;static ClientSession session;
 static void Check(bool value,string label){checks++;if(!value)failures++;Console.WriteLine((value?"PASS ":"FAIL ")+label);}
 static void Pump(){var frame=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>frame.Continue=false));Dispatcher.PushFrame(frame);}
 static void Wait(Func<bool> done){var until=DateTime.UtcNow.AddSeconds(25);while(!done()&&DateTime.UtcNow<until){Pump();Thread.Sleep(10);}if(!done())throw new Exception("Timed out");Pump();main.UpdateLayout();}
 static IEnumerable<T> All<T>(DependencyObject parent)where T:DependencyObject{for(int i=0;i<VisualTreeHelper.GetChildrenCount(parent);i++){var child=VisualTreeHelper.GetChild(parent,i);if(child is T)yield return (T)child;foreach(var item in All<T>(child))yield return item;}}
 static DataGrid Table(){return All<DataGrid>(main).Single(t=>t.Name=="routeGrid");}
 static Rect Bounds(FrameworkElement item){return item.TransformToAncestor(main).TransformBounds(new Rect(0,0,item.ActualWidth,item.ActualHeight));}
 static void Page(){typeof(MainWindow).GetMethod("Page",F).Invoke(main,new object[]{"Маршрутизация"});Pump();main.UpdateLayout();}
 static void Save(string label){var dpi=VisualTreeHelper.GetDpi(main);var bitmap=new RenderTargetBitmap((int)Math.Ceiling(main.ActualWidth*dpi.DpiScaleX),(int)Math.Ceiling(main.ActualHeight*dpi.DpiScaleY),96*dpi.DpiScaleX,96*dpi.DpiScaleY,PixelFormats.Pbgra32);bitmap.Render(main);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using(var stream=File.Create(Path.Combine(root,label+".png")))encoder.Save(stream);}
 static void Toggle(int rowIndex,int column,bool expected,string label){
  var table=Table();var route=session.State.Lists[rowIndex];table.ScrollIntoView(route);Pump();main.UpdateLayout();var row=(DataGridRow)table.ItemContainerGenerator.ContainerFromItem(route);var cell=All<DataGridCell>(row).Single(c=>c.Column==table.Columns[column]);var check=All<CheckBox>(cell).Single();var widths=table.Columns.Select(c=>c.ActualWidth).ToArray();var before=Bounds(table);var logo=All<Image>(main).Single(i=>i.Name=="appLogo");var brandBounds=Bounds(logo);var sidebar=(FrameworkElement)typeof(MainWindow).GetField("side",F).GetValue(main);var sideBounds=Bounds(sidebar);var scroll=All<ScrollViewer>(table).First();double vertical=scroll.VerticalOffset,horizontal=scroll.HorizontalOffset;
  check.Focus();check.IsChecked=!(check.IsChecked==true);check.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));Wait(()=>!(bool)typeof(MainWindow).GetField("busy",F).GetValue(main));
  var current=Table();Check(Object.ReferenceEquals(table,current),label+" no page/table replacement");Check(before==Bounds(current),label+" grid bounds stable");Check(brandBounds==Bounds(logo)&&Bounds(logo).Left>=0,label+" logo stays inside window");Check(sideBounds==Bounds(sidebar)&&sideBounds.Left>=0,label+" sidebar does not slide offscreen");Check(widths.Zip(current.Columns,(w,c)=>Math.Abs(w-c.ActualWidth)<=.9).All(v=>v),label+" all column widths stable");var actual=session.State.Lists.Single(r=>r.Id==route.Id);Check((column==4?actual.GameTcpEnabled:actual.GameUdpEnabled)==expected,label+" saved or rejected flag correct");var newRow=(DataGridRow)current.ItemContainerGenerator.ContainerFromItem(actual);if(newRow==null){current.ScrollIntoView(actual);Pump();newRow=(DataGridRow)current.ItemContainerGenerator.ContainerFromItem(actual);}var newCell=All<DataGridCell>(newRow).Single(c=>c.Column==current.Columns[column]);Check(All<CheckBox>(newCell).Single().IsChecked==expected,label+" checkbox matches actual state");var afterScroll=All<ScrollViewer>(current).First();Check(Math.Abs(afterScroll.VerticalOffset-vertical)<.9&&Math.Abs(afterScroll.HorizontalOffset-horizontal)<.9,label+" scroll preserved");Save(label);
 }
 [STAThread]static int Main(string[] args){try{root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh fixture required");Directory.CreateDirectory(root);var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};Mcrf.Wpf.Program.ConfigureDpi();Mcrf.Wpf.Program.LoadTheme(app);SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());main=new MainWindow(new[]{"--data",Path.Combine(root,"profile")}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000};main.Show();Wait(()=>typeof(MainWindow).GetField("session",F).GetValue(main)!=null);session=(ClientSession)typeof(MainWindow).GetField("session",F).GetValue(main);var profile=new ZapretProfile{Name="Fixture",Settings=new ZapretSettings{Strategy="ALT"}};session.State.ZapretProfiles=new List<ZapretProfile>{profile};session.State.Lists=Enumerable.Range(0,30).Select(i=>new RouteList{Name="Fixture "+i,Target="zapret",ZapretProfileId=profile.Id,MatchMode="addresses",Text=i==1?"0.0.0.0/0":"203.0.113."+(i+1)+"/32",Enabled=true,GameFilterTcp=false,GameFilterUdp=false}).ToList();
  foreach(double width in new[]{1050d,1366d,1920d}){main.Width=width;main.Height=width==1050?680:865;Page();Toggle(0,4,true,"tcp-on-"+width);Toggle(0,5,true,"udp-on-"+width);Toggle(0,4,false,"tcp-off-"+width);Toggle(0,5,false,"udp-off-"+width);Toggle(1,4,false,"invalid-tcp-"+width);Toggle(1,5,false,"invalid-udp-"+width);}
  main.WindowState=WindowState.Maximized;Page();Toggle(0,4,true,"tcp-maximized");Toggle(0,5,true,"udp-maximized");main.Close();Wait(()=>!main.IsVisible);app.Shutdown();Console.WriteLine("RESULT "+checks+" checks; "+failures+" failures");return failures==0?0:1;
 }catch(Exception ex){Console.WriteLine(ex);if(main!=null)main.Close();return 2;}}
}
