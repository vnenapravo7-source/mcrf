using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
namespace SplifyWin {
  public static class ServiceBrowserProbe {
    public static bool Supports(string url){return url=="https://chatgpt.com/"||url=="https://www.instagram.com/";}
public static Task<ZapretEndpointResult> Run(string root,string url,string proxy,CancellationToken token,IEnumerable<HostsCandidate> hosts=null,int budgetMs=22000){
if(!Supports(url))throw new ArgumentException("Неизвестная цель браузерной проверки.");var mappings=(hosts??Enumerable.Empty<HostsCandidate>()).ToArray();HostsCandidates.Entries(mappings);VideoBrowserLibraries.Install(root);return RunInstalled(url,proxy,token,mappings,budgetMs);
    }
    public static bool Confirmed(string service,bool app,bool asset,bool backend,bool challenge,bool geo){return app&&asset&&!challenge&&!geo&&(service!="chatgpt"||backend);}
[MethodImpl(MethodImplOptions.NoInlining)]static async Task<ZapretEndpointResult> RunInstalled(string url,string proxy,CancellationToken token,HostsCandidate[] mappings,int budgetMs){
      var result=new TaskCompletionSource<ZapretEndpointResult>();Form host=null;string session=Path.Combine(VideoBrowserLibraries.libraryRoot,"service-sessions",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(session);int browserPid=0;bool chat=url=="https://chatgpt.com/";var watch=Stopwatch.StartNew();
      var thread=new Thread(()=>{try{using(var window=new Form{ShowInTaskbar=false,StartPosition=FormStartPosition.Manual,Location=new Point(-20000,-20000),Size=new Size(850,650)})using(var view=new WebView2{Dock=DockStyle.Fill}){host=window;window.Controls.Add(view);window.Shown+=async(sender,args)=>{bool asset=false;int navigationError=0;bool tlsError=false;try{
        string rules=mappings.Length==0?"":" --host-resolver-rules=\""+String.Join(", ",mappings.Select(c=>"MAP "+c.Domain+" "+c.Address))+"\"";
        var options=new CoreWebView2EnvironmentOptions("--disable-quic --disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows --disable-features=CalculateNativeWinOcclusion "+(String.IsNullOrEmpty(proxy)?"--no-proxy-server":"--proxy-server="+proxy)+rules){AllowSingleSignOnUsingOSPrimaryAccount=false};
        var env=await CoreWebView2Environment.CreateAsync(null,session,options);await view.EnsureCoreWebView2Async(env);var b=view.CoreWebView2;browserPid=(int)b.BrowserProcessId;b.IsMuted=true;b.Settings.IsPasswordAutosaveEnabled=false;b.Settings.IsGeneralAutofillEnabled=false;b.Settings.AreHostObjectsAllowed=false;b.Settings.AreDevToolsEnabled=false;
        b.PermissionRequested+=(a,e)=>e.State=CoreWebView2PermissionState.Deny;b.NewWindowRequested+=(a,e)=>e.Handled=true;b.DownloadStarting+=(a,e)=>e.Cancel=true;b.ServerCertificateErrorDetected+=(a,e)=>{tlsError=true;e.Action=CoreWebView2ServerCertificateErrorAction.Cancel;};
        b.NavigationStarting+=(a,e)=>{Uri u;if(!Uri.TryCreate(e.Uri,UriKind.Absolute,out u)||u.Scheme!="https"||u.Port!=443||u.UserInfo.Length!=0||!(chat?u.Host=="chatgpt.com"||u.Host.EndsWith(".chatgpt.com"):u.Host=="www.instagram.com"||u.Host=="instagram.com"))e.Cancel=true;};b.NavigationCompleted+=(a,e)=>{if(!e.IsSuccess)navigationError=(int)e.WebErrorStatus;};
        b.WebResourceResponseReceived+=(a,e)=>{Uri u;if(!Uri.TryCreate(e.Request.Uri,UriKind.Absolute,out u)||u.Scheme!="https"||e.Response.StatusCode!=200)return;bool domain=chat?u.Host=="cdn.oaistatic.com"||u.Host=="chatgpt.com":u.Host.EndsWith(".cdninstagram.com")||u.Host=="static.cdninstagram.com"||u.Host=="www.instagram.com";if(domain&&e.Response.Headers.Contains("Content-Type")){string type=e.Response.Headers.GetHeader("Content-Type");if(type.IndexOf("javascript",StringComparison.OrdinalIgnoreCase)>=0||type.StartsWith("text/css",StringComparison.OrdinalIgnoreCase))asset=true;}};
        await b.CallDevToolsProtocolMethodAsync("Network.enable","{}");await b.CallDevToolsProtocolMethodAsync("Network.setCacheDisabled","{\"cacheDisabled\":true}");b.Navigate(url);var budget=Stopwatch.StartNew();bool backend=false,backendStarted=false;string detail="";
while(budget.ElapsedMilliseconds<Math.Min(11000,Math.Max(1000,budgetMs-2500))){await Task.Delay(300,token);if(tlsError){result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unavailable,Detail="TLS-сертификат отклонён браузером"});return;}if(navigationError!=0){result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unavailable,Detail="Браузер не загрузил сервис (код "+navigationError+")"});return;}
          string script="(()=>{let t=document.body?document.body.innerText:'';let visible=e=>e&&e.getClientRects().length>0;let app="+(chat?"[...document.querySelectorAll('textarea,[contenteditable=true]')].some(visible)":"[...document.querySelectorAll('input[name=username],input[name=password],input[name=email],input[name=pass],a[href*=\"accounts/login\"]')].some(visible)")+";return {diag:document.title+' | '+t.slice(0,350)+' | '+[...document.querySelectorAll('input')].map(e=>e.name+':'+e.type).join(','),app:app,challenge:/verify you are human|checking your browser|just a moment|captcha|подтвердите.*не бот|проверяем.*браузер/i.test(t),geo:/unsupported country|unsupported region|not available in your country|недоступ.{0,20}стране/i.test(t)};})()";
          var sample=new JavaScriptSerializer().DeserializeObject(await b.ExecuteScriptAsync(script)) as Dictionary<string,object>;if(sample==null)continue;bool app=Convert.ToBoolean(sample["app"]),challenge=Convert.ToBoolean(sample["challenge"]),geo=Convert.ToBoolean(sample["geo"]);
          if(chat&&app&&!backendStarted){backendStarted=true;await b.ExecuteScriptAsync("window.__mcrfBackend=false;(()=>{let c=new AbortController();setTimeout(()=>c.abort(),3000);fetch('/api/auth/session',{credentials:'omit',signal:c.signal}).then(async r=>{let j=await r.json();window.__mcrfBackend=r.status===200&&j&&typeof j==='object'&&!j.error;}).catch(()=>{});})();");}
          if(chat&&backendStarted)backend=(await b.ExecuteScriptAsync("window.__mcrfBackend===true"))=="true";
          if(Confirmed(chat?"chatgpt":"instagram",app,asset,backend,challenge,geo)){result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Available,Detail=chat?"ChatGPT: загружен рабочий интерфейс ввода, JS/CSS и ответ backend с проверенным TLS. Вход и отправку сообщения нужно подтвердить вручную; данные аккаунта не используются.":"Instagram: загружена форма сервиса и JS/CSS с проверенным TLS. Вход, лента, фото и Reels требуют ручного подтверждения; аккаунт не используется."});return;}
          if(geo){result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unavailable,Detail="Сервис сообщил об ограничении региона; одной стратегии DPI недостаточно"});return;}
          detail=challenge?"Антибот/проверка браузера: не подтверждено":"Интерфейс: "+app+" · JS/CSS: "+asset+(chat?" · backend: "+backend:"");
          if(Environment.GetEnvironmentVariable("MCRF_TEST_DIAGNOSTIC")=="1")detail+=" · DOM: "+Convert.ToString(sample["diag"]);
        }result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unknown,Detail=detail+". Одна HTML-страница не считается успехом."});
      }catch(OperationCanceledException){result.TrySetCanceled();}catch(Exception ex){result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unknown,Detail="Браузерная проверка не завершена: "+ex.GetType().Name});}finally{window.Close();}};Application.Run(window);}}
      catch(Exception ex){result.TrySetResult(new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unknown,Detail="Среда браузерной проверки не запустилась: "+ex.GetType().Name});}
      finally{ThreadPool.QueueUserWorkItem(o=>{try{if(browserPid>0)using(var p=Process.GetProcessById(browserPid)){if(!p.WaitForExit(15000))return;}string owned=Path.GetFullPath(session),parent=Path.GetFullPath(Path.Combine(VideoBrowserLibraries.libraryRoot,"service-sessions"))+Path.DirectorySeparatorChar;Guid id;if(owned.StartsWith(parent,StringComparison.OrdinalIgnoreCase)&&Guid.TryParseExact(Path.GetFileName(owned),"N",out id)&&Directory.Exists(owned))Directory.Delete(owned,true);}catch{}});}}){IsBackground=true,Name="MCRF service check"};thread.SetApartmentState(ApartmentState.STA);thread.Start();
try{if(await Task.WhenAny(result.Task,Task.Delay(budgetMs,token))!=result.Task){token.ThrowIfCancellationRequested();return new ZapretEndpointResult{Url=url,Access=ServiceAccess.Unknown,Milliseconds=(int)watch.ElapsedMilliseconds,Detail="Не подтверждено: браузер не завершил проверку за "+(budgetMs/1000)+" с"};}var value=await result.Task;value.Milliseconds=(int)watch.ElapsedMilliseconds;return value;}
      finally{try{if(host!=null&&!host.IsDisposed)host.BeginInvoke(new Action(()=>host.Close()));}catch{}}
    }
  }
}
