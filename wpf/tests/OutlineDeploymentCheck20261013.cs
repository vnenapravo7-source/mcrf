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
class OutlineDeploymentCheck20261013 {
 static readonly BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;static int checks,failures;static string root;static MainWindow main;
 static void Check(bool value,string label){checks++;if(!value)failures++;Console.WriteLine((value?"PASS ":"FAIL ")+label);}
 static void Pump(){var frame=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>frame.Continue=false));Dispatcher.PushFrame(frame);}
 static void Wait(Func<bool> done){var limit=DateTime.UtcNow.AddSeconds(25);while(!done()&&DateTime.UtcNow<limit){Pump();Thread.Sleep(10);}if(!done())throw new Exception("Async UI did not load");}
 static IEnumerable<T> All<T>(DependencyObject parent) where T:DependencyObject {for(int i=0;i<VisualTreeHelper.GetChildrenCount(parent);i++){var child=VisualTreeHelper.GetChild(parent,i);if(child is T)yield return (T)child;foreach(var item in All<T>(child))yield return item;}}
 static void Page(string name){typeof(MainWindow).GetMethod("Page",F).Invoke(main,new object[]{name});Pump();main.UpdateLayout();}
 static Rect Bounds(FrameworkElement element,Visual parent){return element.TransformToAncestor(parent).TransformBounds(new Rect(0,0,element.ActualWidth,element.ActualHeight));}
 static readonly DependencyPropertyKey HoverKey=(DependencyPropertyKey)typeof(UIElement).GetField("IsMouseOverPropertyKey",BindingFlags.Static|BindingFlags.NonPublic).GetValue(null);
 static bool VisibleIcon(Button button){for(DependencyObject p=VisualTreeHelper.GetParent(button);p!=null;p=VisualTreeHelper.GetParent(p)){var viewport=p as ScrollContentPresenter;if(viewport!=null&&!new Rect(0,0,viewport.ActualWidth,viewport.ActualHeight).Contains(Bounds(button,viewport)))return false;}return true;}
 static bool Unclipped(Button button,out string detail){var rect=new Rect(.5,.5,button.ActualWidth-1,button.ActualHeight-1);for(DependencyObject p=VisualTreeHelper.GetParent(button);p!=null;p=VisualTreeHelper.GetParent(p)){var element=p as FrameworkElement;if(element==null)continue;var clip=LayoutInformation.GetLayoutClip(element);if(clip==null)clip=VisualTreeHelper.GetClip(element);if(clip==null)continue;var transformed=button.TransformToAncestor(element).TransformBounds(rect);if(!clip.Bounds.Contains(transformed)){detail=element.GetType().Name+" clip="+clip.Bounds+" outline="+transformed;return false;}}detail="";return true;}
 static bool Cyan(byte[] pixels,int stride,int width,int height,int x,int y){if(x<0||y<0||x>=width||y>=height)return false;int p=y*stride+x*4;return pixels[p+1]>125&&pixels[p+1]-pixels[p+2]>35&&pixels[p]-pixels[p+2]>35;}
 static bool Edge(byte[] pixels,int stride,int width,int height,Rect r,int side){double middle=side<2?r.Top+r.Height/2:r.Left+r.Width/2;for(int offset=-3;offset<=3;offset++){for(int inward=-1;inward<=3;inward++){int x=(int)Math.Round(side==0?r.Left+inward:side==1?r.Right-1-inward:middle+offset);int y=(int)Math.Round(side==2?r.Top+inward:side==3?r.Bottom-1-inward:middle+offset);if(Cyan(pixels,stride,width,height,x,y))return true;}}return false;}
 static void Icons(string page,string label){Page(page);if(page=="Zapret & Byetube")Wait(()=>All<Button>(main).Any(b=>b.Style==(Style)main.FindResource("IconAction")));var icons=All<Button>(main).Where(b=>b.IsVisible&&b.Style==(Style)main.FindResource("IconAction")&&VisibleIcon(b)).ToArray();Check(icons.Length>0,label+" icons present");foreach(var icon in icons)icon.SetValue(HoverKey,true);Pump();main.UpdateLayout();var dpi=VisualTreeHelper.GetDpi(main);var bitmap=new RenderTargetBitmap((int)Math.Ceiling(main.ActualWidth*dpi.DpiScaleX),(int)Math.Ceiling(main.ActualHeight*dpi.DpiScaleY),96*dpi.DpiScaleX,96*dpi.DpiScaleY,PixelFormats.Pbgra32);bitmap.Render(main);int stride=bitmap.PixelWidth*4;var pixels=new byte[stride*bitmap.PixelHeight];bitmap.CopyPixels(pixels,stride,0);int index=0;foreach(var icon in icons){string detail;Check(Unclipped(icon,out detail),label+" unclipped "+index+" "+detail);var dipRect=Bounds(icon,main);var rect=new Rect(dipRect.X*dpi.DpiScaleX,dipRect.Y*dpi.DpiScaleY,dipRect.Width*dpi.DpiScaleX,dipRect.Height*dpi.DpiScaleY);Check(Enumerable.Range(0,4).All(side=>Edge(pixels,stride,bitmap.PixelWidth,bitmap.PixelHeight,rect,side)),label+" hover contour has all four sides "+index);if(index<3){var crop=new Int32Rect(Math.Max(0,(int)Math.Floor(rect.X)-2),Math.Max(0,(int)Math.Floor(rect.Y)-2),Math.Min((int)Math.Ceiling(rect.Width)+4,bitmap.PixelWidth-(int)Math.Floor(rect.X)+2),Math.Min((int)Math.Ceiling(rect.Height)+4,bitmap.PixelHeight-(int)Math.Floor(rect.Y)+2));var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(new CroppedBitmap(bitmap,crop)));using(var stream=File.Create(Path.Combine(root,label+"-"+index+".png")))encoder.Save(stream);}icon.SetValue(HoverKey,false);index++;}var full=new PngBitmapEncoder();full.Frames.Add(BitmapFrame.Create(bitmap));using(var stream=File.Create(Path.Combine(root,label+".png")))full.Save(stream);}
 static void Save(string name){var dpi=VisualTreeHelper.GetDpi(main);var frame=new RenderTargetBitmap((int)Math.Ceiling(main.ActualWidth*dpi.DpiScaleX),(int)Math.Ceiling(main.ActualHeight*dpi.DpiScaleY),96*dpi.DpiScaleX,96*dpi.DpiScaleY,PixelFormats.Pbgra32);frame.Render(main);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(frame));using(var stream=File.Create(Path.Combine(root,name+".png")))encoder.Save(stream);}
 static FrameworkElement Named(string name){return All<FrameworkElement>(main).Single(e=>e.Name==name);}
 static void Aligned(string label,params string[] names){var elements=names.Select(Named).Where(e=>e.IsVisible).ToArray();if(elements.Length<2)return;var bounds=elements.Select(e=>Bounds(e,main)).ToArray();Check(bounds.Max(b=>b.Top)-bounds.Min(b=>b.Top)<=.9,label+" same top "+String.Join("/",names));Check(bounds.Max(b=>b.Height)-bounds.Min(b=>b.Height)<=.9,label+" same height "+String.Join("/",names));}
 static void Deployment(string label){
  Page("Автодеплой");var auth=(ComboBox)Named("deployAuth");var recipe=(ComboBox)Named("deployRecipe");
  foreach(int mode in new[]{0,1})foreach(int recipeIndex in new[]{0,1,2}){
   auth.SelectedIndex=mode;recipe.SelectedIndex=recipeIndex;Pump();main.UpdateLayout();
   int variants=recipeIndex==1?7:recipeIndex==2?3:1;
   for(int variant=0;variant<variants;variant++){
    if(recipeIndex==1)((ComboBox)Named("deployOpenfluxTransport")).SelectedIndex=variant;
    if(recipeIndex==2)((ComboBox)Named("deploySwgProtocol")).SelectedIndex=variant;
    Pump();main.UpdateLayout();string scenario=label+" auth "+mode+" recipe "+recipeIndex+" variant "+variant;
    var required=All<FrameworkElement>(main).Where(e=>e.IsVisible&&new[]{"deployRecipe","deployHost","deploySshPort","deploySshUser","deployAuth","deployPassword","deployKeyPath","deployKeyPicker","deployKeyPassword","deployPanelPort","deployDomain","deployPanelUser","deployCreateProfiles","deployTcp","deployGrpc","deployTcpPort","deployGrpcPort","deployRealitySni","deployGrpcService","deployOpenfluxTransport","deployOpenfluxUrl","deployOpenfluxPort","deploySwgProtocol","deploySwgPort","deploySwgWgPort","deployVkCall","deployVkLogin"}.Contains(e.Name));
    var scroll=All<ScrollViewer>(main).Single(e=>e.Name=="deploymentScroll");
    foreach(var field in required){var bounds=Bounds(field,(Visual)scroll.Content);Check(scroll.ViewportHeight>=bounds.Bottom-.9,scenario+" field visible "+field.Name);Check(bounds.Left>=-.9&&bounds.Right<=scroll.ViewportWidth+.9,scenario+" field fits horizontally "+field.Name);}
    Aligned(scenario,"deployRecipe","deployHost","deploySshPort","deploySshUser");
    Aligned(scenario,"deployAuth","deployPassword","deployKeyPath","deployKeyPicker","deployKeyPassword");
    Aligned(scenario,"deployPanelPort","deployDomain","deployPanelUser");
    Aligned(scenario,"deployTcp","deployTcpPort","deployGrpc","deployGrpcPort");
    Aligned(scenario,"deployRealitySni","deployGrpcService");
    Aligned(scenario,"deploySwgProtocol","deploySwgPort","deploySwgWgPort");

    Check(!Named("deployProgress").IsVisible,scenario+" unused progress does not reserve space");
    Check(!Named("deployResult").IsVisible,scenario+" unused result does not reserve space");
    if(variant==0)Save(label+"-auth"+mode+"-recipe"+recipeIndex);
   }
  }
  var button=All<Button>(main).First(b=>Convert.ToString(b.Content)=="Установить панель");Check(Bounds(button,main).Bottom<=main.ActualHeight-12,label+" deployment actions stay inside window");
 }
 [STAThread]static int Main(string[] args){try{root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh fixture required");Directory.CreateDirectory(root);var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};Mcrf.Wpf.Program.ConfigureDpi();Mcrf.Wpf.Program.LoadTheme(app);SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());main=new MainWindow(new[]{"--data",Path.Combine(root,"profile")}){ShowActivated=false,WindowStartupLocation=WindowStartupLocation.Manual,Left=-10000,Top=-10000};main.Show();Wait(()=>typeof(MainWindow).GetField("session",F).GetValue(main)!=null);var session=(ClientSession)typeof(MainWindow).GetField("session",F).GetValue(main);session.State.Lists=Enumerable.Range(0,30).Select(i=>new RouteList{Name="Diagnostic "+i,Target="direct",Text="example.test",Enabled=true}).ToList();session.State.Servers=Enumerable.Range(0,20).Select(i=>new ServerNode{Name="DE Diagnostic "+i,Protocol="vless",Host="192.0.2.1",Port=443,Link="vless://00000000-0000-0000-0000-000000000001@192.0.2.1:443",Source="https://subscription.example/test"}).ToList();session.State.ZapretProfiles=new List<ZapretProfile>{new ZapretProfile{Name="Diagnostic",Settings=new ZapretSettings{Strategy="ALT"}}};
  foreach(double width in new[]{1050d,1366d,1920d}){main.WindowState=WindowState.Normal;main.Width=width;main.Height=width==1050?680:865;Pump();Icons("Маршрутизация","routes-"+width);Icons("Серверы","servers-"+width);Icons("Zapret & Byetube","zapret-"+width);Deployment("deploy-"+width);}main.WindowState=WindowState.Maximized;Pump();Icons("Маршрутизация","routes-maximized");Icons("Серверы","servers-maximized");Deployment("deploy-maximized");main.Close();Wait(()=>!main.IsVisible);app.Shutdown();Console.WriteLine("RESULT "+checks+" checks; "+failures+" failures");return failures==0?0:1;
 }catch(Exception ex){Console.WriteLine(ex);if(main!=null)main.Close();return 2;}}
}
