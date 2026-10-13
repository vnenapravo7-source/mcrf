using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Mcrf.Wpf;
using SplifyWin;
using Drawing=System.Drawing;

class BrandingCheck20261013 {
 static readonly BindingFlags F=BindingFlags.Instance|BindingFlags.NonPublic;
 static readonly int[] Sizes={16,20,24,32,40,48,64,96,128,256};
 static int checks;static MainWindow main;static string root;
 [DllImport("user32.dll")]static extern int GetGuiResources(IntPtr process,uint flags);
 static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 static void Pump(){var frame=new DispatcherFrame();Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background,new Action(()=>frame.Continue=false));Dispatcher.PushFrame(frame);}
 static void Until(Func<bool> done){var limit=DateTime.UtcNow.AddSeconds(30);while(!done()&&DateTime.UtcNow<limit){Pump();Thread.Sleep(10);}Check(done(),"async operation completes");}
 static IEnumerable<T> All<T>(DependencyObject parent) where T:DependencyObject {for(int i=0;i<VisualTreeHelper.GetChildrenCount(parent);i++){var item=VisualTreeHelper.GetChild(parent,i);if(item is T)yield return (T)item;foreach(var child in All<T>(item))yield return child;}}
 static void Page(string page){typeof(MainWindow).GetMethod("Page",F).Invoke(main,new object[]{page});Pump();main.UpdateLayout();}
 [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]static void SftpDependencyCheck(){using(var sftp=new Renci.SshNet.SftpClient("127.0.0.1",22,"fixture","fixture"))Check(!sftp.IsConnected,"single-file SFTP dependencies resolve offline");}
 static void Save(double dpi,string name){main.UpdateLayout();var image=new RenderTargetBitmap((int)Math.Ceiling(main.ActualWidth*dpi/96),(int)Math.Ceiling(main.ActualHeight*dpi/96),dpi,dpi,PixelFormats.Pbgra32);image.Render(main);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(image));using(var stream=File.Create(Path.Combine(root,name+".png")))encoder.Save(stream);}
 static void Artwork(Drawing.Bitmap image,string label){int red=0,blue=0,cyan=0;for(int y=0;y<image.Height;y++)for(int x=0;x<image.Width;x++){var p=image.GetPixel(x,y);if(p.A<80)continue;if(p.R>p.B*1.4&&p.R>p.G*1.4)red++;if(p.B>p.R*1.4)blue++;if(p.G>p.R*1.25&&p.B>p.R*1.25)cyan++;}Check(red>0&&blue>10&&cyan>10,label+" contains red clown makeup and cyan/blue diamond");Check(image.GetPixel(0,0).A==0&&image.GetPixel(image.Width-1,image.Height-1).A==0,label+" has transparent corners");}
 static void Embedded(){
  var assembly=typeof(BrandArt).Assembly;
  using(var stream=assembly.GetManifestResourceStream(BrandArt.LogoResourceName))using(var sha=SHA256.Create()){Check(stream!=null,"approved PNG embedded");Check(BitConverter.ToString(sha.ComputeHash(stream)).Replace("-","")=="9C6A9D6466D148B8E63F0338DCF037ED173CDF0875386D78F319B25DF53B278A","embedded artwork matches approved image byte for byte");}
  using(var stream=assembly.GetManifestResourceStream(BrandArt.IconResourceName))using(var reader=new BinaryReader(stream)){Check(reader.ReadUInt16()==0&&reader.ReadUInt16()==1,"embedded multi-resolution ICO header");int count=reader.ReadUInt16();Check(count==Sizes.Length,"ICO covers ten icon sizes");for(int i=0;i<count;i++){int width=reader.ReadByte(),height=reader.ReadByte();if(width==0)width=256;if(height==0)height=256;reader.ReadByte();reader.ReadByte();Check(width==Sizes[i]&&height==Sizes[i],"ICO size "+Sizes[i]);Check(reader.ReadUInt16()==1&&reader.ReadUInt16()==32,"ICO RGBA entry "+Sizes[i]);int length=reader.ReadInt32(),offset=reader.ReadInt32();Check(length>0&&offset>=6+16*count&&offset+length<=stream.Length,"ICO payload bounds "+Sizes[i]);}}
  using(var contact=new Drawing.Bitmap(640,180))using(var graphics=Drawing.Graphics.FromImage(contact)){graphics.Clear(Drawing.Color.FromArgb(12,17,31));int x=8;foreach(int size in Sizes){using(var image=BrandArt.RenderIcon(size)){Check(image.Width==size&&image.Height==size,"generated icon dimensions "+size);Artwork(image,"render "+size);image.Save(Path.Combine(root,"icon-"+size+".png"));if(size<=64){graphics.DrawImageUnscaled(image,x,12);x+=size+12;}}}using(var logo=BrandArt.RenderIcon(96)){graphics.DrawImageUnscaled(logo,12,80);graphics.FillRectangle(Drawing.Brushes.White,125,78,110,100);graphics.DrawImageUnscaled(logo,132,80);}contact.Save(Path.Combine(root,"icon-contact-sheet.png"));}
  foreach(int size in Sizes)using(var icon=BrandArt.CreateIcon(size))using(var image=icon.ToBitmap()){Check(icon.Width==size&&icon.Height==size,"runtime ICO selects "+size);Artwork(image,"runtime "+size);}
  int before=GetGuiResources(System.Diagnostics.Process.GetCurrentProcess().Handle,1);for(int i=0;i<100;i++)using(var icon=BrandArt.CreateIcon(32))using(var image=icon.ToBitmap()){}int after=GetGuiResources(System.Diagnostics.Process.GetCurrentProcess().Handle,1);Check(after-before<8,"runtime icon ownership does not leak GDI handles");
 }
 [STAThread]static int Main(string[] args){Application app=null;try{
  root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Choose a new isolated test directory.");Directory.CreateDirectory(root);Embedded();
  using(var icon=Drawing.Icon.ExtractAssociatedIcon(Path.GetFullPath(args[1])))using(var image=icon.ToBitmap()){Artwork(image,"actual EXE shell icon");image.Save(Path.Combine(root,"exe-shell-icon.png"));}
  Mcrf.Wpf.Program.ConfigureDpi();app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};Mcrf.Wpf.Program.LoadTheme(app);SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
  // Construction exercises the formerly missing SFTP transitive dependency without connecting.
  SftpDependencyCheck();
  main=new MainWindow(new[]{"--data",Path.Combine(root,"profile")});main.ShowActivated=false;main.WindowStartupLocation=WindowStartupLocation.Manual;main.Left=main.Top=-10000;main.Show();Until(()=>typeof(MainWindow).GetField("session",F).GetValue(main)!=null);Page("Главная");
  var session=(ClientSession)typeof(MainWindow).GetField("session",F).GetValue(main);Check(!session.Core.Running&&!session.Zapret.Running&&!session.Telegram.Running,"isolated startup launches no interception");
  var header=All<Image>(main).Single(i=>i.Name=="appLogo");var source=(BitmapSource)header.Source;Check(source.PixelWidth==1254&&source.PixelHeight==1254&&source.IsFrozen,"header uses cached full-resolution approved PNG");Check(!All<System.Windows.Shapes.Path>(All<Button>(main).Single(b=>b.Name=="projectLogo")).Any(),"header no longer draws paper-plane geometry");
  foreach(double width in new[]{1050d,1366d}){main.Width=width;main.Height=width==1050?680:865;foreach(string page in new[]{"Главная","Маршрутизация","Серверы","Zapret & Byetube","Мастер настройки","Журнал","Обновления","Автодеплой"}){Page(page);Check(Object.ReferenceEquals(All<Image>(main).Single(i=>i.Name=="appLogo").Source,source),"same logo remains after navigation "+page+" "+width);Check(header.ActualWidth==40&&header.ActualHeight==40,"header logo layout remains square "+page+" "+width);}Page("Главная");foreach(double dpi in new[]{96d,120d,144d,192d})Save(dpi,"home-"+width+"-dpi"+dpi);}
  Check(main.Icon!=null&&((BitmapSource)main.Icon).PixelWidth==48,"main window icon uses approved artwork");var dialog=(Window)typeof(MainWindow).GetMethod("Dialog",F).Invoke(main,new object[]{"Brand diagnostic",480d,240d});Check(Object.ReferenceEquals(dialog.Icon,main.Icon),"modal dialogs share window branding");dialog.Close();
  typeof(MainWindow).GetMethod("InstallTray",F).Invoke(main,null);var tray=(System.Windows.Forms.NotifyIcon)typeof(MainWindow).GetField("tray",F).GetValue(main);tray.Visible=false;using(var image=tray.Icon.ToBitmap()){Artwork(image,"tray icon");image.Save(Path.Combine(root,"tray-icon.png"));}
  Check(!Directory.GetFiles(Path.GetDirectoryName(Path.GetFullPath(args[1]))).Any(p=>new[]{".png",".ico",".dll"}.Contains(Path.GetExtension(p).ToLowerInvariant())),"working application folder requires only one EXE");
  main.Close();Until(()=>!main.IsVisible);Check(!session.Core.Running&&!session.Zapret.Running&&!session.Telegram.Running,"branding checks leave engines stopped");app.Shutdown();Console.WriteLine("OK "+checks+" branding checks");return 0;
 }catch(Exception ex){Console.WriteLine(ex);if(main!=null)main.Close();if(app!=null)app.Shutdown();return 1;}}
}
