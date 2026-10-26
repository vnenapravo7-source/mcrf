using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  CancellationTokenSource vkCallsCancellation;bool vkCallsCloseHook;
  void VkTurnPage(){
   try{body.Children.Add(Card(VkTurnContent()));}
   catch(Exception ex){var panel=new StackPanel{Name="vkSettingsUnavailable"};panel.Children.Add(Text("Настройки VK TURN недоступны",18));panel.Children.Add(Text("Не удалось прочитать сохранённые настройки. Файл не изменён. Повторите загрузку или восстановите свою резервную копию; автоматический сброс не выполняется."));var retry=Button("Повторить загрузку",()=>Page("VK TURN"));retry.Name="vkSettingsRetry";panel.Children.Add(retry);body.Children.Add(Card(panel));RecordEvent("Ошибка чтения VK TURN: "+JournalStyle.Redact(ex.Message));}
  }
  UIElement VkTurnContent(){
   if(!vkCallsCloseHook){vkCallsCloseHook=true;Closing+=(s,e)=>{if(vkCallsCancellation!=null&&(forceExit||session==null||!session.State.CloseToTray))vkCallsCancellation.Cancel();};}
   var page=new StackPanel{Margin=new Thickness(4)};var account=new StackPanel();var info=Text("");info.Name="vkAccountStatus";
   Action showAccount=()=>{var p=VkTurnSettings.Load(root);info.Text=p.Linked?"Аккаунт VK привязан · сессия до "+p.TokenExpires.ToLocalTime().ToString("g"):"Гостевой режим · ручные ссылки не требуют авторизации";};showAccount();account.Children.Add(Text("Аккаунт VK",18));account.Children.Add(info);
   HookVkAccountLifetime();var accountFeedback=Text("");accountFeedback.Name="vkAccountFeedback";accountFeedback.Text="";accountFeedback.Visibility=Visibility.Collapsed;accountFeedback.MaxHeight=64;
   Button link=null,unlink=null,cancelLogin=null;
   link=AsyncButton("Привязать VK",async()=>{if(vkAccountCancellation!=null||vkAccountClosed||closing||closeRequested)return;var pending=new CancellationTokenSource();vkAccountCancellation=pending;link.IsEnabled=unlink.IsEnabled=false;cancelLogin.IsEnabled=true;accountFeedback.Visibility=Visibility.Visible;accountFeedback.Text="Завершите вход на официальной странице VK.";try{bool linked=await LinkVkAccount(pending.Token);if(!vkAccountClosed&&!closing&&!closeRequested){showAccount();accountFeedback.Text=linked?"Аккаунт VK привязан.":"Вход отменён. Аккаунт не изменён.";}}catch(OperationCanceledException){if(!vkAccountClosed)accountFeedback.Text="Вход отменён. Аккаунт не изменён.";}catch(Exception ex){if(!vkAccountClosed)accountFeedback.Text=FailureExplanations.FromException(ex);}finally{if(Object.ReferenceEquals(vkAccountCancellation,pending))vkAccountCancellation=null;pending.Dispose();if(!vkAccountClosed){link.IsEnabled=unlink.IsEnabled=true;cancelLogin.IsEnabled=false;}}});link.Name="vkLinkAccount";
   unlink=Button("Отвязать",()=>{if(vkAccountCancellation!=null||vkAccountClosed||closing||closeRequested)return;if(!Confirm("Отвязать аккаунт VK? Сохранённые звонки останутся.","VK TURN"))return;ProfileRecovery.DemandHealthy(root);VkTurnSettings.Update(root,p=>{p.Token=null;p.TokenExpires=DateTime.MinValue;});showAccount();});unlink.Name="vkUnlinkAccount";
   cancelLogin=Button("Отменить вход",()=>{if(vkAccountCancellation!=null)vkAccountCancellation.Cancel();},true);cancelLogin.Name="vkCancelLogin";cancelLogin.IsEnabled=false;cancelLogin.Visibility=Visibility.Collapsed;cancelLogin.IsEnabledChanged+=(s,e)=>cancelLogin.Visibility=cancelLogin.IsEnabled?Visibility.Visible:Visibility.Collapsed;
   account.Children.Add(CompactTools(link,unlink,cancelLogin));account.Children.Add(accountFeedback);
   account.Children.Add(Text("Пароль вводится только на vk.ru. Сессия, звонки и настройки хранятся зашифрованно для текущего пользователя Windows. Режим капчи выбирается для каждого подключения; вход в аккаунт всегда подтверждаете вы."));page.Children.Add(Card(account));
   var pool=new StackPanel{Margin=new Thickness(0,14,0,0)};pool.Children.Add(Text("Звонки и ручные хэши",18));pool.Children.Add(Text("Вставьте ссылки VK или хэши: по одному на строку, через пробел или запятую. Повторы удаляются. Уже созданные звонки сохраняются даже при частичной ошибке генерации."));
   var preferences=VkTurnSettings.Load(root);var calls=new TextBox{Name="vkTurnCalls",Text=String.Join("\n",preferences.Calls),AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,MinHeight=110,MaxHeight=220,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};pool.Children.Add(VkCallRows(calls));
   Func<bool> saveCalls=()=>{try{string[] values=System.Text.RegularExpressions.Regex.Split(calls.Text??"",@"[\s,;]+").Where(s=>!String.IsNullOrWhiteSpace(s)).Select(VkCalls.CallHash).Distinct(StringComparer.Ordinal).Select(h=>"https://vk.ru/call/join/"+h).ToArray();VkTurnSettings.Update(root,p=>p.Calls=values.ToList());calls.Text=String.Join("\n",values);status.Text="Звонки VK сохранены · "+values.Length;return true;}catch(Exception ex){status.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));return false;}};
   var count=new ComboBox{Name="vkCallCount",ItemsSource=new[]{1,2,3,5,6,10,15,20,30,50},SelectedItem=6,Width=100,Margin=new Thickness(0)};
   Button stopCalls=null;
   var generate=AsyncButton("Создать звонки VK",async()=>{if(!saveCalls())return;var p=VkTurnSettings.Load(root);if(!p.Linked)throw new InvalidOperationException("Сначала привяжите аккаунт VK.");var token=new VkLoginToken{Value=p.Token,Expires=p.TokenExpires};vkCallsCancellation=new CancellationTokenSource();stopCalls.IsEnabled=true;count.IsEnabled=false;var cancel=vkCallsCancellation.Token;try{await VkCalls.CreateBatch((int)count.SelectedItem,()=>VkCalls.CreateCall(token,url=>VkPage(url,true,cancel),cancel),partial=>{VkTurnSettings.Update(root,next=>next.Calls=next.Calls.Concat(partial).Select(VkCalls.CallHash).Distinct().Select(h=>"https://vk.ru/call/join/"+h).ToList());calls.Text=String.Join("\n",VkTurnSettings.Load(root).Calls);},cancel);}finally{token.Value=null;vkCallsCancellation.Dispose();vkCallsCancellation=null;stopCalls.IsEnabled=false;count.IsEnabled=true;}});generate.Name="vkGenerateCalls";
   stopCalls=Button("Остановить создание",()=>{if(vkCallsCancellation!=null)vkCallsCancellation.Cancel();},true);stopCalls.Name="vkStopCalls";stopCalls.IsEnabled=false;
   count.HorizontalAlignment=HorizontalAlignment.Left;pool.Children.Add(Field("Количество создаваемых звонков",count));pool.Children.Add(CompactTools(Button("Сохранить звонки",()=>saveCalls()),generate,stopCalls));
   var supplement=new CheckBox{Name="vkSupplementAll",Content="Дополнять звонками все TURN-подключения",IsChecked=preferences.SupplementAll};bool supplementSaved=supplement.IsChecked==true;supplement.Click+=(s,e)=>{supplementSaved=PersistVkToggle(supplement,supplementSaved,value=>VkTurnSettings.Update(root,p=>p.SupplementAll=value),()=>status.Text="Общие звонки применятся при следующем подключении.");};pool.Children.Add(supplement);pool.Children.Add(Text("Собственные звонки сервера идут первыми. Импортированные ссылки и подписки не переписываются. CSQTT 2.5, WDTT и QWDTT используют до 32 звонков; старые поколения CSQTT — до 6. Звонки аккаунта не смешиваются с общим ручным пулом."));page.Children.Add(Card(pool));
   var streams=new StackPanel{Margin=new Thickness(0,14,0,0)};streams.Children.Add(Text("Потоки по умолчанию",18));streams.Children.Add(Text("Применяются при следующем подключении. Число автоматически ограничивается доступным количеством звонков. При частой смене сети уменьшайте потоки до 36 или ниже."));
   foreach(string kind in new[]{"csqtt","csqtt25","qwdtt","wdtt"}){
    string key=kind;int saved;int value=preferences.Streams.TryGetValue(kind,out saved)?saved:kind=="qwdtt"?9:18;
    streams.Children.Add(VkStreamSlider("vkStreams_"+kind,kind=="csqtt"?"CSQTT 2.0 / SWG 2.1":kind=="csqtt25"?"SWG 2.5.1 / Rebirth":kind.ToUpperInvariant(),kind,value,false,n=>{VkTurnSettings.Update(root,p=>p.Streams[key]=n);status.Text="Потоки сохранены; текущее подключение не прерывается.";}));
   }page.Children.Add(Card(streams));SpaceVkTurn(page);
   VkTurnExtraSettings(page);return new ScrollViewer{Name="vkTurnSettingsScroll",Content=page,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled};
  }
 }
}
