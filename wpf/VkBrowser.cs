using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Forms.Integration;
using System.Web.Script.Serialization;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  async Task<string[]> CreateVkCalls(int count,Action<string[]> progress){
   if(!Confirm("Войти на странице VK и создать "+count+" звонков для SWG? Пароль вводится только на странице VK; клиент получает временный токен. Каждый звонок создаётся отдельным запросом, без автоматических повторов при сетевой ошибке.","Создать звонки VK"))return null;
   string state=VkCalls.NewState(),response=await VkPage("https://vk.ru/",false,CancellationToken.None,VkCalls.LoginUrl(state));
   if(response==null)return null;var token=VkCalls.ParseToken(response,state);
   try{return await VkCalls.CreateBatch(count,()=>VkCalls.CreateCall(token,url=>VkPage(url,true,CancellationToken.None),CancellationToken.None),progress,CancellationToken.None);}finally{token.Value=null;}
  }
  [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
  Task<string> VkPage(string address,bool captcha,CancellationToken token,string afterLogin=null){
   if(!Dispatcher.CheckAccess())return Dispatcher.Invoke(()=>VkPage(address,captcha,token,afterLogin));
   VideoBrowserLibraries.Install(root);return ShowVkPage(address,captcha,token,afterLogin,false);
  }
  async Task<string> VkCaptchaFlow(string address,CancellationToken token){
   if(!Dispatcher.CheckAccess())return await Dispatcher.InvokeAsync(()=>VkCaptchaFlow(address,token)).Task.Unwrap();
   status.Text="VK: автоматическая проверка подключения…";RecordEvent("VK запросил подтверждение подключения");
   string automatic=await ShowVkPage(address,true,token,null,true);
   if(VkCalls.ValidProof(automatic))return automatic;
   token.ThrowIfCancellationRequested();status.Text="VK просит ручное подтверждение. Открываем проверку…";RecordEvent(automatic=="error:slider_detected"?"VK: автоматическая проверка потребовала интерактивный слайдер":"VK: автоматическая проверка не завершилась, требуется подтверждение");
   if(tray!=null){tray.BalloonTipTitle="Нужно подтверждение VK";tray.BalloonTipText="Откройте проверку, чтобы продолжить подключение.";tray.ShowBalloonTip(5000);}
   RestoreWindow();return await ShowVkPage(address,true,token,null,false);
  }
  [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
  Task<string> ShowVkPage(string address,bool captcha,CancellationToken token,string afterLogin=null,bool automatic=false){
   if(!VkCalls.AllowedPage(address)||afterLogin!=null&&(!VkCalls.AllowedPage(afterLogin)||captcha))throw new FormatException("Открываются только HTTPS-страницы VK.");
   string profile=Path.Combine(root,"vk-browser","session-"+Guid.NewGuid().ToString("N"));var window=Dialog(captcha?(automatic?"VK · автоматическая проверка":"VK · подтвердите, что вы человек"):"Вход VK",automatic?460:1000,automatic?260:760);var view=new WebView2();var host=new WindowsFormsHost{Child=view,Opacity=automatic?.02:1,MinHeight=automatic?2:240};var panel=new DockPanel{Margin=new Thickness(18)};var note=Text(captcha?(automatic?"Проверяем официальный запрос VK. Если потребуется действие человека, MCRF сразу откроет видимую страницу.":"Подтвердите на официальной странице VK, что вы не робот. Результат будет передан подключению автоматически."):(afterLogin==null?"Введите данные только на официальной странице VK. Пароль не передаётся MCRF.":"Сначала войдите на главной vk.ru. Затем нажмите «Я вошёл — получить токен». Переход будет в этой же сессии."));DockPanel.SetDock(note,Dock.Top);panel.Children.Add(note);panel.Children.Add(host);var continueLogin=new Button{Name="vkContinueLogin",Content="Я вошёл — получить токен",IsEnabled=false,Margin=new Thickness(0,12,0,0),Visibility=afterLogin==null?Visibility.Collapsed:Visibility.Visible};DockPanel.SetDock(continueLogin,Dock.Bottom);panel.Children.Remove(host);panel.Children.Add(continueLogin);panel.Children.Add(host);continueLogin.Click+=(sender,args)=>{if(view.CoreWebView2==null)return;continueLogin.IsEnabled=false;continueLogin.Visibility=Visibility.Collapsed;note.Text="Получаем разрешение VK. Токен используется только для создания звонков и не сохраняется.";view.CoreWebView2.Navigate(afterLogin);};window.Content=panel;string result=null;bool alive=true;string failure=null;var retry=new System.Windows.Threading.DispatcherTimer{Interval=TimeSpan.FromMilliseconds(500)};var fallback=new System.Windows.Threading.DispatcherTimer{Interval=TimeSpan.FromSeconds(10)};
   using(token.Register(()=>Dispatcher.BeginInvoke(new Action(()=>{if(alive)window.Close();})))){
    window.Loaded+=async(s,e)=>{try{
     var environment=await CoreWebView2Environment.CreateAsync(null,profile,new CoreWebView2EnvironmentOptions{AllowSingleSignOnUsingOSPrimaryAccount=false});environment.BrowserProcessExited+=(sender,args)=>{string basePath=Path.GetFullPath(Path.Combine(root,"vk-browser"))+Path.DirectorySeparatorChar;if(Path.GetFullPath(profile).StartsWith(basePath,StringComparison.OrdinalIgnoreCase))try{Directory.Delete(profile,true);}catch{}};
     if(!alive||token.IsCancellationRequested)return;
     await view.EnsureCoreWebView2Async(environment);if(!alive||token.IsCancellationRequested)return;var browser=view.CoreWebView2;browser.Settings.AreDevToolsEnabled=false;browser.Settings.AreHostObjectsAllowed=false;browser.Settings.IsPasswordAutosaveEnabled=false;browser.Settings.IsGeneralAutofillEnabled=false;browser.PermissionRequested+=(sender,args)=>args.State=CoreWebView2PermissionState.Deny;browser.DownloadStarting+=(sender,args)=>args.Cancel=true;browser.NewWindowRequested+=(sender,args)=>args.Handled=true;browser.ServerCertificateErrorDetected+=(sender,args)=>args.Action=CoreWebView2ServerCertificateErrorAction.Cancel;
     Action<string> callback=url=>{if(!alive||captcha||!VkCalls.Callback(url))return;result=url;window.DialogResult=true;};
     browser.NavigationStarting+=(sender,args)=>{if(!captcha&&VkCalls.Callback(args.Uri)){args.Cancel=true;callback(args.Uri);return;}if(!VkCalls.AllowedPage(args.Uri)||args.Uri.IndexOf("access_token=",StringComparison.OrdinalIgnoreCase)>=0){args.Cancel=true;failure="VK вернул неподходящий адрес.";window.Close();}};
     browser.SourceChanged+=(sender,args)=>callback(browser.Source);
     if(captcha){string expectedHost=new Uri(address).Host;browser.WebMessageReceived+=(sender,args)=>{try{if(!alive||!VkCalls.AllowedPage(args.Source)||new Uri(args.Source).Host!=expectedHost)return;string proof=args.TryGetWebMessageAsString();if(automatic&&proof.StartsWith("ok|"))proof=proof.Substring(3);if(VkCalls.ValidProof(proof)){result=proof;window.DialogResult=true;}else if(automatic&&(proof=="slider"||proof=="rejected")){result=proof=="slider"?"error:slider_detected":"error:auto_rejected";window.DialogResult=false;}}catch(FormatException){}};await browser.AddScriptToExecuteOnDocumentCreatedAsync(automatic?AutoCaptchaProofScript:CaptchaProofScript);if(automatic){retry.Tick+=(sender,args)=>{if(alive&&view.CoreWebView2!=null)view.CoreWebView2.ExecuteScriptAsync(AutoCaptchaProofScript);};fallback.Tick+=(sender,args)=>{fallback.Stop();if(alive){result="error:auto_timeout";window.DialogResult=false;}};retry.Start();fallback.Start();}}
     continueLogin.IsEnabled=afterLogin!=null;
     browser.Navigate(address);
    }catch(Exception){failure="Не удалось открыть страницу VK. Проверьте Microsoft Edge WebView2 и доступ к VK.";if(alive)window.Close();}};
    try{window.ShowDialog();}finally{alive=false;retry.Stop();fallback.Stop();view.Dispose();host.Dispose();}
   }
   if(failure!=null){if(!automatic)throw new InvalidOperationException(failure);result="error:auto_browser";}token.ThrowIfCancellationRequested();return Task.FromResult(result);
  }
  const string CaptchaProofScript=@"(()=>{if(window.__mcrfProof)return;window.__mcrfProof=true;const send=d=>{if(d&&d.response&&typeof d.response.success_token==='string')window.chrome.webview.postMessage(d.response.success_token);};const original=window.fetch;window.fetch=async function(...args){const r=await original.apply(this,args);const u=String(args[0]&&args[0].url||args[0]);if(u.includes('captchaNotRobot.check'))r.clone().json().then(send).catch(()=>{});return r;};const open=XMLHttpRequest.prototype.open,sendX=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url,...rest){this.__mcrfCheck=String(url).includes('captchaNotRobot.check');return open.call(this,method,url,...rest);};XMLHttpRequest.prototype.send=function(...args){if(this.__mcrfCheck)this.addEventListener('load',()=>{try{send(JSON.parse(this.responseText));}catch{}});return sendX.apply(this,args);};})()";
  const string AutoCaptchaProofScript=@"(()=>{if(!window.__mcrfAutoHook){window.__mcrfAutoHook=true;const report=d=>{const r=d&&d.response;if(r&&typeof r.success_token==='string')window.chrome.webview.postMessage('ok|'+r.success_token);else if(r&&r.show_captcha_type==='slider')window.chrome.webview.postMessage('slider');else if(d&&d.error||r&&['BOT','ERROR_LIMIT','ERROR'].includes(r.status))window.chrome.webview.postMessage('rejected');};const oldFetch=window.fetch;window.fetch=async function(...args){const response=await oldFetch.apply(this,args),u=String(args[0]&&args[0].url||args[0]);if(u.includes('captchaNotRobot.check'))response.clone().json().then(report).catch(()=>{});return response;};const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u,...rest){this.__mcrfAutoUrl=String(u);return open.call(this,m,u,...rest);};XMLHttpRequest.prototype.send=function(...args){if((this.__mcrfAutoUrl||'').includes('captchaNotRobot.check'))this.addEventListener('load',()=>{try{report(JSON.parse(this.responseText));}catch{}});return send.apply(this,args);};}if(document.querySelector('[class*=SliderCaptcha],[class*=Kaleidoscope],[class*=SwipeButton]')){window.chrome.webview.postMessage('slider');return;}if(!window.__mcrfAutoClicked){const e=document.querySelector('label[for=not-robot-captcha-checkbox],label.vkc__Checkbox-module__Checkbox')||document.getElementById('not-robot-captcha-checkbox');if(e){const r=e.getBoundingClientRect(),s=getComputedStyle(e);if(r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'){window.__mcrfAutoClicked=true;e.click();}}}})()";
 }
}
