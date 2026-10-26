using System;
using System.IO;
using System.Reflection;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using SplifyWin;

namespace Mcrf.Wpf {
 // The Android drawing source is embedded unchanged. Only the platform lifecycle is adapted.
 internal sealed class AndroidConnectionScene : Border {
  readonly WebView2CompositionControl view;
  readonly string profile;
  readonly Func<Task<CoreWebView2Environment>> createEnvironment;
  string pending,sent;bool ready,disposed,initializing;
  Window hostWindow;
  internal string RendererError {get;private set;}
  internal AndroidConnectionScene(string root):this(root,null){}
  internal AndroidConnectionScene(string root,Func<Task<CoreWebView2Environment>> createEnvironment){
   this.createEnvironment=createEnvironment;
   profile=Path.Combine(root,"connection-scene-browser");
   VideoBrowserLibraries.Install(root);
   view=new WebView2CompositionControl{DefaultBackgroundColor=System.Drawing.Color.Transparent,IsHitTestVisible=false,Focusable=false,AllowExternalDrop=false,UseLayoutRounding=false};
   Child=view;Background=Brushes.Transparent;ClipToBounds=true;
   Loaded+=async(s,e)=>{if(disposed)return;DetachHost();hostWindow=Window.GetWindow(this);if(hostWindow!=null){hostWindow.StateChanged+=HostStateChanged;hostWindow.SizeChanged+=HostSizeChanged;hostWindow.AddHandler(ScrollViewer.ScrollChangedEvent,new ScrollChangedEventHandler(HostScrolled),true);}SyncVisibility();await Initialize();};
   IsVisibleChanged+=(s,e)=>SyncVisibility();
   SizeChanged+=(s,e)=>SyncVisibility();
   Unloaded+=(s,e)=>{if(disposed)return;disposed=true;DetachHost();ready=false;sent=null;view.Dispose();};
  }
  bool SceneVisible{get{
   if(!IsVisible||ActualWidth<=0||ActualHeight<=0||hostWindow==null||hostWindow.WindowState==WindowState.Minimized)return false;
   try{var visible=TransformToAncestor(hostWindow).TransformBounds(new Rect(RenderSize));visible.Intersect(new Rect(0,0,hostWindow.ActualWidth,hostWindow.ActualHeight));
    for(DependencyObject ancestor=VisualTreeHelper.GetParent(this);ancestor!=null&&ancestor!=hostWindow;ancestor=VisualTreeHelper.GetParent(ancestor)){
     var element=ancestor as FrameworkElement;if(element==null)continue;
     if(element is ScrollContentPresenter||element.ClipToBounds)visible.Intersect(element.TransformToAncestor(hostWindow).TransformBounds(new Rect(element.RenderSize)));
     var clip=VisualTreeHelper.GetClip(element);if(clip!=null)visible.Intersect(element.TransformToAncestor(hostWindow).TransformBounds(clip.Bounds));
    }return !visible.IsEmpty&&visible.Width>0&&visible.Height>0;
   }catch(InvalidOperationException){return false;}
  }}
  void DetachHost(){if(hostWindow==null)return;hostWindow.StateChanged-=HostStateChanged;hostWindow.SizeChanged-=HostSizeChanged;hostWindow.RemoveHandler(ScrollViewer.ScrollChangedEvent,new ScrollChangedEventHandler(HostScrolled));hostWindow=null;}
  void HostStateChanged(object sender,EventArgs e){SyncVisibility();}
  void HostSizeChanged(object sender,SizeChangedEventArgs e){SyncVisibility();}
  void HostScrolled(object sender,ScrollChangedEventArgs e){SyncVisibility();}
  void SyncVisibility(){if(disposed||pending==null)return;var json=new JavaScriptSerializer();var payload=json.Deserialize<System.Collections.Generic.Dictionary<string,object>>(pending);payload["visible"]=SceneVisible;pending=json.Serialize(payload);FlushPending();}
  async Task Initialize(){
   if(disposed||initializing||ready)return;
   initializing=true;
   try{
    var options=new CoreWebView2EnvironmentOptions("--disable-background-networking --disable-component-update");
    var environment=await (createEnvironment==null?CoreWebView2Environment.CreateAsync(null,profile,options):createEnvironment());
    if(disposed)return;
    await view.EnsureCoreWebView2Async(environment);
    if(disposed)return;
    var core=view.CoreWebView2;
    core.Settings.AreDevToolsEnabled=false;core.Settings.AreDefaultContextMenusEnabled=false;
    core.Settings.AreHostObjectsAllowed=false;core.Settings.IsWebMessageEnabled=false;
    core.Settings.IsStatusBarEnabled=false;core.Settings.IsZoomControlEnabled=false;
    core.ProcessFailed+=(s,e)=>ProcessFailed(e.ProcessFailedKind);
    core.PermissionRequested+=(s,e)=>e.State=CoreWebView2PermissionState.Deny;
    core.NewWindowRequested+=(s,e)=>e.Handled=true;
    core.NavigationStarting+=(s,e)=>{if(!e.Uri.StartsWith("about:blank",StringComparison.OrdinalIgnoreCase)&&!e.Uri.StartsWith("data:text/html",StringComparison.OrdinalIgnoreCase))e.Cancel=true;};
    core.AddWebResourceRequestedFilter("*",CoreWebView2WebResourceContext.All);
    core.WebResourceRequested+=(s,e)=>{if(e.Request.Uri.StartsWith("http",StringComparison.OrdinalIgnoreCase))e.Response=core.Environment.CreateWebResourceResponse(new MemoryStream(new byte[0]),403,"Blocked","");};
    core.NavigationCompleted+=(s,e)=>{if(disposed||!String.IsNullOrEmpty(RendererError))return;ready=e.IsSuccess;if(ready)FlushPending();else RendererError="Не удалось загрузить сцену подключения: "+e.WebErrorStatus;};
    using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("Mcrf.Android.connection-scene.html")){
     if(stream==null)throw new IOException("В сборке отсутствует оригинальная Android-сцена");
     using(var reader=new StreamReader(stream))core.NavigateToString(reader.ReadToEnd());
    }
   }catch(Exception error){if(!disposed)RendererError=JournalStyle.Redact(error.Message);}finally{initializing=false;}
  }
  void ProcessFailed(CoreWebView2ProcessFailedKind kind){
   if(disposed)return;
   // Auxiliary processes (for example GPU) can recover without replacing the page.
   if(kind!=CoreWebView2ProcessFailedKind.BrowserProcessExited&&kind!=CoreWebView2ProcessFailedKind.RenderProcessExited&&kind!=CoreWebView2ProcessFailedKind.RenderProcessUnresponsive)return;
   ready=false;sent=null;RendererError="Процесс сцены подключения недоступен: "+kind;
  }
  internal void Present(string style,string signal,bool motion,double speed,string background,string panel,string text,string muted,string line){
   if(disposed)return;
   pending=new JavaScriptSerializer().Serialize(new{style=style,exit="lock",signal=signal,speed=Math.Max(0,speed),motion=motion,visible=SceneVisible,palette=new{bg=Rgb(background),panel=Rgb(panel),text=Rgb(text),muted=Rgb(muted),line=Rgb(line),metal=Rgb(muted),steel=Rgb(panel)}});
   FlushPending();
  }
  static string Rgb(string value){var color=(Color)ColorConverter.ConvertFromString(value);return String.Format("#{0:X2}{1:X2}{2:X2}",color.R,color.G,color.B);}
  async void FlushPending(){try{await Flush();}catch(Exception error){if(!disposed){sent=null;RendererError=JournalStyle.Redact(error.Message);}}}
  async Task Flush(){if(disposed||!ready||pending==null||pending==sent)return;var payload=pending;sent=payload;await view.ExecuteScriptAsync("window.McrfScene.update("+payload+")");}
 }
}
