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
   VideoBrowserLibraries.Install(root);return ShowVkPage(address,captcha,token,afterLogin);
  }
  [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
  Task<string> ShowVkPage(string address,bool captcha,CancellationToken token,string afterLogin=null){
   if(!VkCalls.AllowedPage(address)||afterLogin!=null&&(!VkCalls.AllowedPage(afterLogin)||captcha))throw new FormatException("Открываются только HTTPS-страницы VK.");
   string profile=Path.Combine(root,"vk-browser","session-"+Guid.NewGuid().ToString("N"));var window=Dialog(captcha?"VK · подтвердите, что вы человек":"Вход VK",1000,760);var view=new WebView2();var host=new WindowsFormsHost{Child=view};var panel=new DockPanel{Margin=new Thickness(18)};var note=Text(captcha?"Пройдите проверку вручную. Автоматического решения капчи нет.":(afterLogin==null?"Введите данные только на официальной странице VK. Пароль не передаётся MCRF.":"Сначала войдите на главной vk.ru. Затем нажмите «Я вошёл — получить токен». Переход будет в этой же сессии."));DockPanel.SetDock(note,Dock.Top);panel.Children.Add(note);panel.Children.Add(host);var continueLogin=new Button{Name="vkContinueLogin",Content="Я вошёл — получить токен",IsEnabled=false,Margin=new Thickness(0,12,0,0),Visibility=afterLogin==null?Visibility.Collapsed:Visibility.Visible};DockPanel.SetDock(continueLogin,Dock.Bottom);panel.Children.Remove(host);panel.Children.Add(continueLogin);panel.Children.Add(host);continueLogin.Click+=(sender,args)=>{if(view.CoreWebView2==null)return;continueLogin.IsEnabled=false;continueLogin.Visibility=Visibility.Collapsed;note.Text="Получаем разрешение VK. Токен используется только для создания звонков и не сохраняется.";view.CoreWebView2.Navigate(afterLogin);};window.Content=panel;string result=null;bool alive=true;string failure=null;
   using(token.Register(()=>Dispatcher.BeginInvoke(new Action(()=>{if(alive)window.Close();})))){
    window.Loaded+=async(s,e)=>{try{
     var environment=await CoreWebView2Environment.CreateAsync(null,profile,new CoreWebView2EnvironmentOptions{AllowSingleSignOnUsingOSPrimaryAccount=false});environment.BrowserProcessExited+=(sender,args)=>{string basePath=Path.GetFullPath(Path.Combine(root,"vk-browser"))+Path.DirectorySeparatorChar;if(Path.GetFullPath(profile).StartsWith(basePath,StringComparison.OrdinalIgnoreCase))try{Directory.Delete(profile,true);}catch{}};
     await view.EnsureCoreWebView2Async(environment);if(!alive)return;var browser=view.CoreWebView2;browser.Settings.AreDevToolsEnabled=false;browser.Settings.AreHostObjectsAllowed=false;browser.Settings.IsPasswordAutosaveEnabled=false;browser.Settings.IsGeneralAutofillEnabled=false;browser.PermissionRequested+=(sender,args)=>args.State=CoreWebView2PermissionState.Deny;browser.DownloadStarting+=(sender,args)=>args.Cancel=true;browser.NewWindowRequested+=(sender,args)=>args.Handled=true;browser.ServerCertificateErrorDetected+=(sender,args)=>args.Action=CoreWebView2ServerCertificateErrorAction.Cancel;
     Action<string> callback=url=>{if(!alive||captcha||!VkCalls.Callback(url))return;result=url;window.DialogResult=true;};
     browser.NavigationStarting+=(sender,args)=>{if(!captcha&&VkCalls.Callback(args.Uri)){args.Cancel=true;callback(args.Uri);return;}if(!VkCalls.AllowedPage(args.Uri)||args.Uri.IndexOf("access_token=",StringComparison.OrdinalIgnoreCase)>=0){args.Cancel=true;failure="VK вернул неподходящий адрес.";window.Close();}};
     browser.SourceChanged+=(sender,args)=>callback(browser.Source);
     if(captcha){string expectedHost=new Uri(address).Host;browser.WebMessageReceived+=(sender,args)=>{if(!alive||!VkCalls.AllowedPage(args.Source)||new Uri(args.Source).Host!=expectedHost)return;try{string proof=args.TryGetWebMessageAsString();if(VkCalls.ValidProof(proof)){result=proof;window.DialogResult=true;}}catch(ArgumentException){}};await browser.AddScriptToExecuteOnDocumentCreatedAsync(CaptchaProofScript);}
     continueLogin.IsEnabled=afterLogin!=null;
     browser.Navigate(address);
    }catch(Exception){failure="Не удалось открыть страницу VK. Проверьте Microsoft Edge WebView2 и доступ к VK.";if(alive)window.Close();}};
    try{window.ShowDialog();}finally{alive=false;view.Dispose();host.Dispose();}
   }
   if(failure!=null)throw new InvalidOperationException(failure);token.ThrowIfCancellationRequested();return Task.FromResult(result);
  }
  const string CaptchaProofScript=@"(()=>{if(window.__mcrfProof)return;window.__mcrfProof=true;const send=d=>{if(d&&d.response&&typeof d.response.success_token==='string')window.chrome.webview.postMessage(d.response.success_token);};const original=window.fetch;window.fetch=async function(...args){const r=await original.apply(this,args);const u=String(args[0]&&args[0].url||args[0]);if(u.includes('captchaNotRobot.check'))r.clone().json().then(send).catch(()=>{});return r;};const open=XMLHttpRequest.prototype.open,sendX=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url,...rest){this.__mcrfCheck=String(url).includes('captchaNotRobot.check');return open.call(this,method,url,...rest);};XMLHttpRequest.prototype.send=function(...args){if(this.__mcrfCheck)this.addEventListener('load',()=>{try{send(JSON.parse(this.responseText));}catch{}});return sendX.apply(this,args);};})()";
 }
}
