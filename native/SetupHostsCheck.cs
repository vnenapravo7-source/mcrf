using System;
using System.Collections.Generic;
using System.Drawing;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
namespace SplifyWin {
 public sealed partial class MainForm {
  async Task<bool?> CheckInstagramExternally(List<HostsCandidate> hosts,CancellationToken token,string progress){
   token.ThrowIfCancellationRequested();var done=new TaskCompletionSource<bool?>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(900,ClientSize.Width-40),440)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Add(popup,L("Instagram · проверка Reels",18,true),24,20);
   var hint=L("Профиль Zapret и hosts временно применены в Windows. Откройте этот Reels в своём браузере: вход не требуется, если Instagram не потребует его сам. Нажмите «Работает», если ролик воспроизводится.",11,false,Muted);hint.AutoSize=false;hint.SetBounds(24,70,popup.Width-48,85);popup.Controls.Add(hint);
   var link=new LinkLabel{Text=ManualServiceChecks.InstagramReel,AutoSize=true,Location=new Point(24,160),BackColor=Color.Transparent,LinkColor=GlassInk.Cyan};Action open=()=>{try{Process.Start(new ProcessStartInfo(ManualServiceChecks.InstagramReel){UseShellExecute=true});}catch(Exception ex){Toast(ex.Message);}};link.LinkClicked+=(s,e)=>open();popup.Controls.Add(link);
   var status=L(progress+"\nАвтопроверка кадров запускается отдельно; проверьте ролик в своём браузере.",11,true,GlassInk.Cyan);status.Name="instagramManualProgress";status.AutoSize=false;status.SetBounds(24,205,popup.Width-48,90);popup.Controls.Add(status);
   var works=B("Работает",(s,e)=>done.TrySetResult(true),true);works.Name="instagramManualWorks";works.SetBounds(24,320,220,40);popup.Controls.Add(works);var fails=B("Не работает",(s,e)=>done.TrySetResult(false));fails.SetBounds(256,320,220,40);popup.Controls.Add(fails);var again=B("Открыть Reels",(s,e)=>open());again.SetBounds(488,320,popup.Width-512,40);popup.Controls.Add(again);var skip=B("Пропустить",(s,e)=>done.TrySetResult(null));skip.SetBounds(24,382,220,38);popup.Controls.Add(skip);
   veil.Disposed+=(s,e)=>done.TrySetResult(null);using(var cancellation=CancellationTokenSource.CreateLinkedTokenSource(token)){
    HostsTrial trial=null;BrowserVideoProbe probe=null;Exception restoreFailure=null,checkFailure=null;bool? answer=null;
    try{trial=await Task.Run(()=>new HostsTrial(store.Root,"instagram",hosts));token.ThrowIfCancellationRequested();Controls.Add(veil);veil.BringToFront();layout();open();VideoBrowserLibraries.Install(store.Root);probe=new BrowserVideoProbe(null,ManualServiceChecks.InstagramReel);probe.FrameProgress=(frames,time)=>{if(!status.IsDisposed&&status.IsHandleCreated)try{status.BeginInvoke(new Action(()=>{if(!status.IsDisposed)status.Text=progress+"\nОтдельный проигрыватель: "+frames+" кадров · "+time.ToString("0.0")+" с. Проверьте свой браузер.";}));}catch{}};
var observing=ObserveInstagramFrames(probe,status,progress,cancellation.Token);var observedContinuation=observing.ContinueWith(t=>{var observed=t.Exception;},TaskContinuationOptions.OnlyOnFaulted);
     using(var registration=token.Register(()=>{done.TrySetCanceled();if(!veil.IsDisposed&&veil.IsHandleCreated)veil.BeginInvoke(new Action(()=>veil.Dispose()));})){answer=await done.Task;}
}catch(Exception ex){checkFailure=ex;}finally{cancellation.Cancel();if(probe!=null)probe.Dispose();if(!veil.IsDisposed)veil.Dispose();}
    if(trial!=null)try{await Task.Run(()=>trial.Dispose());}catch(Exception ex){restoreFailure=ex;}
    if(restoreFailure!=null){WriteLog("Не удалось восстановить hosts после теста Instagram: "+restoreFailure.ToString());throw new IOException("Не удалось восстановить hosts после проверки: "+HostsEditor.PathName+". Исходный файл сохранён в hosts-backups.",restoreFailure);}
    if(checkFailure!=null)throw checkFailure;return answer;
   }
  }
  async Task ObserveInstagramFrames(BrowserVideoProbe probe,Label status,string progress,CancellationToken token){try{var result=await probe.Run(token);if(!status.IsDisposed)status.Text=progress+"\n"+(result.Access==ServiceAccess.Available?"В отдельном проигрывателе кадры и время растут. Подтвердите работу в своём браузере.":"Отдельный проигрыватель не подтвердил кадры. Если в вашем браузере Reels работает — нажмите «Работает».");}catch(OperationCanceledException){}catch(Exception ex){if(!status.IsDisposed)status.Text=progress+"\nАвтопроверка кадров недоступна: "+ex.GetType().Name+". Проверьте Reels вручную.";}}
  async Task<bool> CheckSetupByeTubeManually(ZapretService service,ZapretCheckRow strategy,CancellationToken token){
   int index;if(strategy==null||!strategy.StrategyId.StartsWith("byetube:")||!Int32.TryParse(strategy.StrategyId.Substring(8),out index)||index<0||index>=ByeTube.Strategies.Length)return false;
   var listener=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0);listener.Start();int port=((System.Net.IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
   using(var process=await Task.Run(()=>ByeTube.Start(Path.Combine(core.DataRoot,"core","ciadpi.exe"),ByeTube.Strategies[index],message=>WriteLog(message),token,port))){
    try{return await CheckMappedServiceManually(service,service.Urls[0],new List<HostsCandidate>(),token,"socks5://127.0.0.1:"+port)==true;}
    finally{try{if(!process.HasExited){process.Kill();process.WaitForExit(1500);}}catch{}}
   }
  }
  public static bool ManualNavigationAllowed(ZapretService service,string host){if(service.Id=="chatgpt"&&(host=="accounts.google.com"||host=="appleid.apple.com"||host=="login.microsoftonline.com"))return true;if(service.Id=="instagram"||service.Id=="chatgpt")return HostsCandidates.AllowedDomain(service.Id,host);var domains=service.Domains.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Concat(service.Urls.Select(u=>new Uri(u).Host));if(service.Id=="rutor")domains=domains.Concat(new[]{"rutracker.org","rutracker.net","rutracker.cc"});return domains.Any(d=>host.Equals(d,StringComparison.OrdinalIgnoreCase)||host.EndsWith("."+d,StringComparison.OrdinalIgnoreCase));}
  async Task<bool> CheckSetupHosts(ZapretCheckReport report,ZapretService service,SetupAssignment assignment,CancellationToken token,Action<string> progress,string sourceOverride=null,bool ask=true,bool persistSetup=true){
   bool chat=service.Id=="chatgpt";
string question=chat?"Для ChatGPT можно проверить Zapret вместе со сторонним SNI-реле dns.malw.link. Это не сервер OpenAI: оператор реле видит адреса соединений. TLS проверяется; чужие сертификаты не устанавливаются. Сначала проверим в отдельном браузере, без изменения системного hosts.":"Проверить Instagram через альтернативные IP из Zapret-Manager вместе с Zapret? Профиль и hosts будут временно применены в Windows для проверки указанного Reels в вашем браузере; затем записи восстановятся.";
   if(ask&&!await ConfirmChange(question,"Проверить вариант hosts",service.Name))return false;
   token.ThrowIfCancellationRequested();progress(service.Name+": загружаем кандидаты hosts…");
   string source=sourceOverride??(chat?HostsCandidates.MalwSource:HostsRepair.Source);
   string raw=await core.DownloadTextAsync(source);token.ThrowIfCancellationRequested();
   var candidates=source==HostsRepair.Source?HostsCandidates.ParseScript(raw,service.Id):HostsCandidates.ParseLines(raw,service.Id,source);
var strategies=ManualServiceChecks.Candidates(report,service.Id,assignment.Strategy==null?"flowseal:general (ALT4).bat":assignment.Strategy.StrategyId).Take(3).ToArray();
   bool wasRunning=zapret.Running,touched=false;bool confirmed=false;Exception failure=null;
   try{foreach(var row in strategies){
    token.ThrowIfCancellationRequested();ZapretSettings settings=null;
    if(row!=null){var strategy=ZapretCatalog.Load(zapret.DirectoryPath).FirstOrDefault(s=>s.Available&&s.Id==row.StrategyId);if(strategy==null)continue;settings=ManualServiceChecks.Trial(report,service,strategy);settings.ScopeText+="\nfacebook.com\nfbcdn.net\nfbsbx.com";touched=true;await zapret.Start(settings,strategy,token);}
    progress(service.Name+": проверяем TLS/HTTP адресов…");
    var results=await HostsCandidates.ProbeAll(core,candidates,token,null);
    var hosts=results.Where(r=>r.Usable).GroupBy(r=>r.Candidate.Domain).Select(g=>g.OrderBy(r=>r.Milliseconds).First().Candidate).ToList();
    if(!hosts.Any(h=>h.Domain==(chat?"chatgpt.com":"www.instagram.com"))){progress(service.Name+": корневой адрес не подтверждён; пробуем следующий вариант.");continue;}
if(settings!=null){settings=HostsCandidates.WithScope(settings,hosts);var selectedStrategy=ZapretCatalog.Load(zapret.DirectoryPath).First(s=>s.Id==row.StrategyId);await zapret.Start(settings,selectedStrategy,token);}string url=service.Urls.First(ServiceBrowserProbe.Supports);var automatic=await ServiceBrowserProbe.Run(store.Root,url,null,token,hosts,HostsCandidates.StrategyBudgetMs);
    progress(service.Name+": "+automatic.Detail);
    // HTTP 403 is only transport evidence. Never skip the user's functional test.
if(service.Id!="instagram"&&automatic.Access!=ServiceAccess.Available)continue;
bool? manual=await CheckMappedServiceWithProgress(service,url,hosts,token,null,ManualServiceChecks.Progress(Array.IndexOf(strategies,row),strategies.Length,row.Name));
    token.ThrowIfCancellationRequested();if(!manual.HasValue)break;
    if(report.ManualResults==null)report.ManualResults=new List<ServiceManualResult>();
    report.ManualResults.Add(new ServiceManualResult{ServiceId=service.Id,StrategyId=row==null?"hosts:direct":row.StrategyId,Works=manual.Value,CheckedAt=DateTime.Now,Settings=settings,Hosts=hosts});
    if(row!=null){var measured=row.Services.FirstOrDefault(r=>r.Id==service.Id);if(measured==null){measured=new ZapretServiceResult{Id=service.Id};row.Services.Add(measured);}measured.Hosts=hosts;measured.Access=manual.Value?ServiceAccess.Available:ServiceAccess.Unavailable;measured.Detail="Ручная проверка с выбранными hosts: "+(manual.Value?"работает":"не работает");}
    ZapretChecks.Save(Path.Combine(store.Root,persistSetup?"setup-results":"hosts-results-"+service.Id),report);
    if(!manual.Value)continue;
assignment.Output="zapret";assignment.Strategy=row;assignment.Hosts=hosts;confirmed=true;break;
   }}catch(Exception ex){failure=ex;}finally{if(touched)zapret.Stop();}
   if(wasRunning&&touched&&!IsDisposed)await StartSavedZapretProfiles(CancellationToken.None);
   if(failure!=null)throw failure;return confirmed;
  }
Task<bool?> CheckMappedServiceManually(ZapretService service,string url,List<HostsCandidate> hosts,CancellationToken token,string proxy=null){return CheckMappedServiceWithProgress(service,url,hosts,token,proxy,"Проверка 1 из 1 · Осталось: 0");}
  Task<bool?> CheckMappedServiceWithProgress(ZapretService service,string url,List<HostsCandidate> hosts,CancellationToken token,string proxy,string progress){HostsCandidates.Entries(hosts);if(service.Id=="instagram"&&String.IsNullOrEmpty(proxy))return CheckInstagramExternally(hosts,token,progress);VideoBrowserLibraries.Install(store.Root);return CheckMappedServiceManuallyInstalled(service,url,hosts,token,proxy,progress);}
[MethodImpl(MethodImplOptions.NoInlining)]async Task<bool?> CheckMappedServiceManuallyInstalled(ZapretService service,string url,List<HostsCandidate> hosts,CancellationToken token,string proxy,string progress){
   HostsCandidates.Entries(hosts);VideoBrowserLibraries.Install(store.Root);
   string session=Path.Combine(VideoBrowserLibraries.libraryRoot,"manual-services",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(session);
   using(var window=new Form{Text=service.Name+" · ручная проверка",Size=new Size(1060,780),StartPosition=FormStartPosition.CenterParent,BackColor=Color.FromArgb(18,25,43)})
   using(var view=new WebView2{Dock=DockStyle.Fill}){
    int browserPid=0;var done=new TaskCompletionSource<bool?>();var actions=new FlowLayoutPanel{Dock=DockStyle.Bottom,Height=90,BackColor=window.BackColor,Padding=new Padding(12),WrapContents=true};
var hint=new Label{Text=service.Id=="chatgpt"?"Проверьте вход и ответ на сообщение. Используется выбранное стороннее реле; системный hosts ещё не изменён.":service.Id=="instagram"?"Проверьте вход, ленту, фото и Reels. Системный hosts ещё не изменён.":service.Id=="rutor"?"Проверьте оба сайта: Rutor и RuTracker. «Работает» означает, что открылись оба.":"Проверьте нужную функцию сервиса. Открытая страница сама по себе ещё не означает успех.",ForeColor=GlassInk.Muted,AutoSize=false,Width=1000,Height=40};actions.Controls.Add(hint);actions.SetFlowBreak(hint,true);
foreach(string target in service.Urls.Concat(service.Id=="rutor"?new[]{"https://rutracker.org/forum/index.php"}:new string[0])){string destination=target;var link=new LinkLabel{Text=new Uri(target).Host,AutoSize=true,LinkColor=GlassInk.Cyan,Margin=new Padding(4,2,20,4)};link.LinkClicked+=(s,e)=>{if(view.CoreWebView2!=null)view.CoreWebView2.Navigate(destination);};actions.Controls.Add(link);}if(actions.Controls.Count>1)actions.SetFlowBreak(actions.Controls[actions.Controls.Count-1],true);actions.Height=220;var current=new Label{Name="mappedManualProgress",Text=progress,ForeColor=GlassInk.Cyan,AutoSize=false,Width=1000,Height=40};actions.Controls.Add(current);actions.SetFlowBreak(current,true);if(service.Id=="chatgpt"){var warning=new Label{Name="chatgptBlackScreenHint",Text="Если после загрузки экран остаётся чёрным — не работает. Нажмите «Не работает» для следующей стратегии.",ForeColor=Color.FromArgb(255,220,110),AutoSize=false,Width=1000,Height=32};actions.Controls.Add(warning);actions.SetFlowBreak(warning,true);}
var works=B("Работает",(s,e)=>done.TrySetResult(true),true);var fails=B("Не работает",(s,e)=>done.TrySetResult(false));var skip=B("Пропустить",(s,e)=>done.TrySetResult(null));foreach(var button in new[]{works,fails,skip}){button.Size=new Size(220,34);actions.Controls.Add(button);}works.Enabled=true;works.Name="mappedManualWorks";
    window.Controls.Add(view);window.Controls.Add(actions);window.FormClosed+=(s,e)=>done.TrySetResult(null);window.Show(this);
    using(var registration=token.Register(()=>{done.TrySetCanceled();try{if(!window.IsDisposed)window.BeginInvoke(new Action(()=>window.Close()));}catch{}})){
     try{
Func<Task> initialize=async()=>{
string rules=String.Join(", ",hosts.Select(h=>"MAP "+h.Domain+" "+h.Address));
      var options=new CoreWebView2EnvironmentOptions("--disable-quic "+(String.IsNullOrEmpty(proxy)?"--no-proxy-server":"--proxy-server="+proxy)+" --host-resolver-rules=\""+rules+"\""){AllowSingleSignOnUsingOSPrimaryAccount=false};
var environment=await CoreWebView2Environment.CreateAsync(null,session,options);token.ThrowIfCancellationRequested();if(window.IsDisposed||done.Task.IsCompleted)return;
await view.EnsureCoreWebView2Async(environment);token.ThrowIfCancellationRequested();if(window.IsDisposed||done.Task.IsCompleted)return;var browser=view.CoreWebView2;browserPid=(int)browser.BrowserProcessId;
      browser.Settings.IsPasswordAutosaveEnabled=false;browser.Settings.IsGeneralAutofillEnabled=false;browser.Settings.AreHostObjectsAllowed=false;browser.Settings.AreDevToolsEnabled=false;
      browser.PermissionRequested+=(s,e)=>e.State=CoreWebView2PermissionState.Deny;browser.DownloadStarting+=(s,e)=>e.Cancel=true;browser.NewWindowRequested+=(s,e)=>{e.Handled=true;Uri target;if(Uri.TryCreate(e.Uri,UriKind.Absolute,out target)&&target.Scheme=="https"&&target.Port==443&&target.UserInfo.Length==0&&ManualNavigationAllowed(service,target.Host))browser.Navigate(target.AbsoluteUri);};
      browser.NavigationStarting+=(s,e)=>{Uri target;if(!Uri.TryCreate(e.Uri,UriKind.Absolute,out target)||target.Scheme!="https"||target.Port!=443||target.UserInfo.Length!=0||!ManualNavigationAllowed(service,target.Host))e.Cancel=true;};
      bool tlsError=false;browser.ServerCertificateErrorDetected+=(s,e)=>{tlsError=true;works.Enabled=false;e.Action=CoreWebView2ServerCertificateErrorAction.Cancel;hint.Text="Ошибка TLS. Не вводите данные; этот вариант нельзя подтвердить.";};
browser.NavigationCompleted+=(s,e)=>works.Enabled=!tlsError;browser.Navigate(url);
};var initialization=initialize();await Task.WhenAny(initialization,done.Task);if(initialization.IsCompleted){try{await initialization;}catch(OperationCanceledException){throw;}catch(Exception ex){hint.Text="Браузер не запустился: "+ex.Message;}}else{var observedInitialization=initialization.ContinueWith(t=>{var observed=t.Exception;},TaskContinuationOptions.OnlyOnFaulted);}return await done.Task;
     }finally{if(!window.IsDisposed)window.Close();view.Dispose();ThreadPool.QueueUserWorkItem(o=>{try{if(browserPid>0)using(var process=Process.GetProcessById(browserPid)){if(!process.WaitForExit(12000))return;}string owned=Path.GetFullPath(session),parent=Path.GetFullPath(Path.Combine(VideoBrowserLibraries.libraryRoot,"manual-services"))+Path.DirectorySeparatorChar;Guid id;if(owned.StartsWith(parent,StringComparison.OrdinalIgnoreCase)&&Guid.TryParseExact(Path.GetFileName(owned),"N",out id)&&Directory.Exists(owned))Directory.Delete(owned,true);}catch{}});}
    }
   }
  }
 }
}
