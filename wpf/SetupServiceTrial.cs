using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Forms.Integration;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal bool SetupTrialOfflineForTests;
  static bool SetupTrialNeedsBrowser(string service){return service!="discord";}
  static Size SetupTrialWindowSize(string service){return SetupTrialNeedsBrowser(service)?new Size(1100,800):new Size(820,500);}
  FrameworkElement SetupTrialBrowserHost(string service,out WebView2 browser){
   if(SetupTrialOfflineForTests){browser=null;return SetupTrialNeedsBrowser(service)?new Border{Background=Brush("#1C2844")}:null;}
   browser=SetupTrialNeedsBrowser(service)?new WebView2{DefaultBackgroundColor=System.Drawing.Color.FromArgb(255,28,40,68)}:null;
   return browser==null?null:new WindowsFormsHost{Child=browser,Background=Brush("#1C2844")};
  }
  Panel SetupTrialLinks(ZapretService service,Action<string> navigate){
   var links=new System.Windows.Controls.WrapPanel{Margin=new Thickness(0,8,0,8)};
   foreach(string url in service.Id=="discord"?new[]{"https://discord.com/app"}:ManualServiceChecks.Urls(service)){
    string target=url;var button=new Button{Content=service.Id=="youtube"?"Обычное видео":new Uri(url).Host,Margin=new Thickness(0,0,8,6)};button.Click+=(s,e)=>navigate(target);links.Children.Add(button);
   }
   if(service.Id=="youtube"){var shorts=new Button{Content="Открыть Shorts",Margin=new Thickness(0,0,8,6)};shorts.Click+=(s,e)=>navigate(VideoProbe.ShortsTarget);links.Children.Add(shorts);}
   return links;
  }
  [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
  Task<int> ShowSetupServiceTrial(SetupWizardSelection item,string proxy,CancellationToken token){
   var size=SetupTrialWindowSize(item.Service.Id);var window=Dialog("Мастер · "+item.Service.CheckName+" · очная проверка",size.Width,size.Height);window.Name="setupTrialWindow";bool alive=true,ready=false;int answer=-1;
   bool voice=!SetupTrialNeedsBrowser(item.Service.Id);var layout=new DockPanel{Margin=new Thickness(18),LastChildFill=!voice};
   var note=Text(item.Service.CheckName+" · "+item.Row.Name+"\n"+(voice?"В приложении Discord переподключитесь к голосовому каналу. Проверьте слышимость в обе стороны; таймера нет.":item.Service.Id=="youtube"?"Проверьте обычный ролик и Shorts. Если сайт просит подтверждение, выполните его сами. Таймера нет.":"Проверьте нужную функцию сервиса. Таймера нет.")+"\n«Не работает» включает следующую конфигурацию; «Пропустить» переходит к следующему сервису.",13);note.TextWrapping=TextWrapping.Wrap;DockPanel.SetDock(note,Dock.Top);layout.Children.Add(note);
   var hear=new CheckBox{Content="Я слышу собеседника",Margin=new Thickness(0,8,0,6)};var heard=new CheckBox{Content="Собеседник слышит меня",Margin=new Thickness(0,0,0,8)};
   var voiceFields=new StackPanel{Visibility=voice?Visibility.Visible:Visibility.Collapsed};voiceFields.Children.Add(hear);voiceFields.Children.Add(heard);DockPanel.SetDock(voiceFields,Dock.Top);layout.Children.Add(voiceFields);
   var info=Text(voice?"Голос проверяйте в установленном Discord. Встроенный браузер для этого этапа не запускается.":"Открываем отдельную браузерную сессию…",12);info.TextWrapping=TextWrapping.Wrap;DockPanel.SetDock(info,Dock.Top);layout.Children.Add(info);
   var works=new Button{Name="setupTrialWorks",Content="Работает",IsEnabled=false};var next=new Button{Name="setupTrialNext",Content="Не работает — следующий вариант",IsEnabled=false};
   var skip=new Button{Name="setupTrialSkip",Content="Пропустить сервис"};var cancel=new Button{Name="setupTrialCancel",Content="Отменить мастер"};
   Action refresh=()=>works.IsEnabled=ready&&(!voice||hear.IsChecked==true&&heard.IsChecked==true);hear.Checked+=(s,e)=>refresh();hear.Unchecked+=(s,e)=>refresh();heard.Checked+=(s,e)=>refresh();heard.Unchecked+=(s,e)=>refresh();
   works.Click+=(s,e)=>{if(works.IsEnabled){answer=1;window.DialogResult=true;}};next.Click+=(s,e)=>{answer=0;window.DialogResult=true;};skip.Click+=(s,e)=>window.Close();cancel.Click+=(s,e)=>{answer=-2;window.Close();};
   var actions=CompactTools(works,next,skip,cancel);DockPanel.SetDock(actions,Dock.Bottom);layout.Children.Add(actions);
   WebView2 browser;var browserHost=SetupTrialBrowserHost(item.Service.Id,out browser);if(browserHost!=null)layout.Children.Add(browserHost);window.Content=layout;
   string profile=voice?null:Path.Combine(root,"setup-browser","session-"+Guid.NewGuid().ToString("N"));if(profile!=null)Directory.CreateDirectory(profile);
   using(var observeCancellation=CancellationTokenSource.CreateLinkedTokenSource(token))
   using(token.Register(()=>Dispatcher.BeginInvoke(new Action(()=>{if(alive){answer=-2;window.Close();}})))){
    Task<DiscordVoiceObservation> observation=null;
    window.Loaded+=async(s,e)=>{try{
     if(SetupTrialOfflineForTests){ready=true;next.IsEnabled=true;info.Text="Изолированная проверка интерфейса без сети.";refresh();return;}
     if(voice){ready=true;next.IsEnabled=true;refresh();voiceFields.Children.Add(SetupTrialLinks(item.Service,url=>System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url){UseShellExecute=true})));observation=Task.Run(()=>DiscordVoiceObserver.Observe(Path.Combine(session.Zapret.DirectoryPath,"WinDivert.dll"),observeCancellation.Token,message=>Dispatcher.BeginInvoke(new Action(()=>{if(alive)info.Text=message;}))));return;}
     var options=new CoreWebView2EnvironmentOptions("--disable-quic "+(proxy==null?"--no-proxy-server":"--proxy-server="+proxy)){AllowSingleSignOnUsingOSPrimaryAccount=false};
     var environment=await CoreWebView2Environment.CreateAsync(null,profile,options);environment.BrowserProcessExited+=(sender,args)=>{try{if(Directory.Exists(profile))Directory.Delete(profile,true);}catch{}};
     if(!alive)return;await browser.EnsureCoreWebView2Async(environment);if(!alive)return;
     var web=browser.CoreWebView2;web.Profile.PreferredColorScheme=CoreWebView2PreferredColorScheme.Dark;web.Settings.AreHostObjectsAllowed=false;web.Settings.IsPasswordAutosaveEnabled=false;web.Settings.IsGeneralAutofillEnabled=false;web.DownloadStarting+=(sender,args)=>args.Cancel=true;web.PermissionRequested+=(sender,args)=>args.State=CoreWebView2PermissionState.Deny;web.ServerCertificateErrorDetected+=(sender,args)=>args.Action=CoreWebView2ServerCertificateErrorAction.Cancel;web.NewWindowRequested+=(sender,args)=>args.Handled=true;
     web.NavigationStarting+=(sender,args)=>{Uri uri;if(!Uri.TryCreate(args.Uri,UriKind.Absolute,out uri)||uri.Scheme!="https"||uri.Port!=443||uri.UserInfo.Length!=0)args.Cancel=true;};
     string initial=voice?"https://discord.com/app":ManualServiceChecks.Urls(item.Service)[0];web.Navigate(initial);
     voiceFields.Children.Add(SetupTrialLinks(item.Service,url=>web.Navigate(url)));if(!voice){hear.Visibility=heard.Visibility=Visibility.Collapsed;voiceFields.Visibility=Visibility.Visible;}
     ready=true;next.IsEnabled=true;refresh();info.Text=voice?"Голос проверяйте в установленном Discord. Браузер ниже — только для проверки интерфейса.":"Проверка идёт с выбранной конфигурацией, без изменения системного прокси. Подтвердите результат кнопкой.";
    }catch(Exception error){if(alive){ready=voice;next.IsEnabled=true;refresh();info.Text="Браузерная проверка не открылась: "+error.Message+(voice?". Голос можно проверить в установленном Discord.":". Пропустите сервис или попробуйте следующий вариант.");}}};
    try{token.ThrowIfCancellationRequested();window.ShowDialog();}
    finally{alive=false;observeCancellation.Cancel();if(browser!=null)browser.Dispose();if(observation!=null)try{observation.GetAwaiter().GetResult();}catch(Exception error){RecordEvent("Наблюдение Discord: "+error.Message);}}
   }
   token.ThrowIfCancellationRequested();if(answer==-2)throw new OperationCanceledException("Мастер отменён.");return Task.FromResult(answer);
  }
 }
}
