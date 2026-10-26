using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  sealed class CapturedRouteChoice {public string Name;public string Target;public bool Enabled;}
  DispatcherTimer WatchCaptureOwner(Window window){var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(200)};timer.Tick+=(s,e)=>{if(closing||closeRequested)window.Close();};window.Closed+=(s,e)=>timer.Stop();timer.Start();return timer;}
  static void SetCapturePickerLocked(ListBox list,TextBox search,Button refresh,bool locked){list.IsHitTestVisible=!locked;list.IsEnabled=!locked;search.IsEnabled=!locked;refresh.IsEnabled=!locked;}
  CapturedRouteChoice CapturedRouteDialog(GameRecording recording){return CapturedRouteDialogWithCommit(recording,null);}
  CapturedRouteChoice CapturedRouteDialogWithCommit(GameRecording recording,Func<RouteList,Task> commit){
   if(closing||closeRequested)return null;
   var window=Dialog("Сохранить маршрут",640,ActualWidth<500?550:400);window.MaxHeight=window.Height;window.SizeToContent=SizeToContent.Height;var panel=new DockPanel{Margin=new Thickness(16),LastChildFill=true};
   var body=new StackPanel();body.Children.Add(Text("Записано "+recording.IpCount+" IP для "+String.Join(", ",recording.Game.Processes)+". Создайте правило и выберите, куда направлять этот трафик."));
   var name=new TextBox{Name="capturedRouteName",Text=recording.Game.Name,Margin=new Thickness(0,4,0,12)};body.Children.Add(Field("Название правила",name));
   var output=new ComboBox{Name="capturedRouteTarget",ItemsSource=new[]{"Через VPN / прокси","Напрямую"},SelectedIndex=0,Margin=new Thickness(0,4,0,12)};body.Children.Add(Field("Куда направлять",output));
   var enabled=new CheckBox{Name="capturedRouteEnabled",Content="Включить правило сразу",IsChecked=true,Margin=new Thickness(0,4,0,0)};body.Children.Add(enabled);
   var error=Text("",11);error.Name="capturedRouteError";error.Foreground=LogColor("ERROR");error.TextWrapping=TextWrapping.Wrap;error.TextTrimming=TextTrimming.WordEllipsis;error.MaxHeight=48;error.Visibility=Visibility.Collapsed;
   bool saving=false,closed=false;var create=new Button{Name="capturedRouteCreate",Content="Создать правило",IsEnabled=!String.IsNullOrWhiteSpace(name.Text)};
   var cancel=Button("Отмена",()=>window.DialogResult=false);cancel.Name="capturedRouteCancel";
   name.TextChanged+=(s,e)=>{create.IsEnabled=!saving&&!String.IsNullOrWhiteSpace(name.Text);error.Visibility=Visibility.Collapsed;};
   create.Click+=async(s,e)=>{
    if(saving||closed||closing||closeRequested)return;if(String.IsNullOrWhiteSpace(name.Text)){name.Focus();return;}
    saving=true;create.IsEnabled=cancel.IsEnabled=false;body.IsEnabled=false;error.Visibility=Visibility.Collapsed;
    try{if(commit!=null){var route=recording.CreateRoute();route.ZapretProfileId=null;route.Name=name.Text.Trim();route.Target=output.SelectedIndex==1?"direct":"proxy";route.Enabled=enabled.IsChecked==true;route.PresetNames=new System.Collections.Generic.List<string>{route.Name};await commit(route);}saving=false;if(!closed)window.DialogResult=true;}
    catch(Exception ex){if(!closed){error.Text=JournalStyle.Redact(ex.Message);error.ToolTip=error.Text;error.Foreground=LogColor("ERROR");error.Visibility=Visibility.Visible;}}
    finally{saving=false;if(!closed){body.IsEnabled=true;cancel.IsEnabled=true;create.IsEnabled=!String.IsNullOrWhiteSpace(name.Text);if(closing||closeRequested)window.Close();}}
   };
   window.Closing+=(s,e)=>{if(saving)e.Cancel=true;};window.Closed+=(s,e)=>closed=true;
   var actions=new StackPanel{Margin=new Thickness(0,12,0,0)};actions.Children.Add(error);actions.Children.Add(Toolbar(create,cancel));DockPanel.SetDock(actions,Dock.Bottom);panel.Children.Add(actions);panel.Children.Add(new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled});window.Content=panel;window.Loaded+=(s,e)=>{name.Focus();name.SelectAll();};
   var lifetime=WatchCaptureOwner(window);bool accepted=false;try{accepted=window.ShowDialog()==true;}finally{lifetime.Stop();}
   return accepted?new CapturedRouteChoice{Name=name.Text.Trim(),Target=output.SelectedIndex==1?"direct":"proxy",Enabled=enabled.IsChecked==true}:null;
  }
  Task CaptureProcess(){return CaptureProcessWithServices(new CaptureServices{Processes=()=>Task.Run(()=>ApplicationExclusions.Running()),CreateObserver=()=>new NativeCaptureObserver(session.Zapret)});}
  async Task CaptureProcessWithServices(CaptureServices services){
   if(closing||closeRequested)return;var processes=await services.Processes();if(closing||closeRequested)return;
   var window=Dialog("Захват процесса",940,690);window.Name="processCaptureWindow";var panel=new DockPanel{Margin=new Thickness(24)};var header=new StackPanel();header.Children.Add(Text("Выберите процесс, начните запись и переподключите приложение. Записываются IP новых TCP/UDP-соединений. Домены и уже открытые соединения могут быть пропущены. VPN не включается; для наблюдения нужны права администратора."));DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);
   var list=ProcessList(processes);list.Name="captureProcessList";bool closed=false,loading=false;CaptureObserver observer=null;var cancellation=new CancellationTokenSource();
   var picker=new RunningProcessPicker(list,processes,false,services.Processes,()=>!closed&&!loading&&observer==null&&!closing&&!closeRequested);var search=picker.Search;search.Name="processSearch";
   var footer=new StackPanel();var progress=Text("Запись не начата");progress.Name="captureProgress";progress.MaxHeight=84;progress.TextTrimming=TextTrimming.WordEllipsis;
   var progressCard=new Border{Name="captureStatus",Background=Brush("#CC102D24"),BorderBrush=Brush("#FF245B49"),BorderThickness=new Thickness(1),CornerRadius=new CornerRadius(9),Padding=new Thickness(12,9,12,9),Margin=new Thickness(0,4,0,4),Child=progress};progress.Foreground=Brush("#FF83D7A8");footer.Children.Add(progressCard);
   var start=new Button{Name="captureStart",Content="Записать процесс"};Accent(start);var save=new Button{Name="captureDone",Content="Готово",IsEnabled=false};var cancel=Button("Отмена",()=>window.Close(),true);cancel.Name="captureCancel";footer.Children.Add(Toolbar(start,save,cancel));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(picker);window.Content=panel;
   var recording=new GameRecording();var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(400)};bool accepted=false;Task starting=Task.FromResult(0);
   Action<string> message=text=>{if(!closed&&!closing&&!closeRequested){progress.Text=JournalStyle.Redact(text);progress.ToolTip=progress.Text;}};
   Action drain=()=>{if(observer!=null)recording.Observe(observer.Drain());};
   timer.Tick+=(s,e)=>{if(observer==null||closed)return;try{drain();message("Записано IP: "+recording.IpCount+". "+(observer.Error??(observer.Running?"Используйте приложение, затем нажмите «Готово».":"Наблюдение остановлено. Сохраните записанные адреса или отмените запись.")));if(!observer.Running)timer.Stop();}catch(Exception ex){timer.Stop();message("Ошибка записи: "+ex.Message);}};
   Func<Task> begin=async()=>{
    loading=true;start.IsEnabled=false;SetCapturePickerLocked(list,search,picker.Refresh,true);Exception failure=null;
    try{string executable=Convert.ToString(list.SelectedItem);var process=GameCatalog.Items.FirstOrDefault(g=>g.Processes.Contains(executable,StringComparer.OrdinalIgnoreCase))??new GamePreset{Name=System.IO.Path.GetFileNameWithoutExtension(executable),Processes=new[]{executable}};
     observer=services.CreateObserver();await observer.Start(process,cancellation.Token);cancellation.Token.ThrowIfCancellationRequested();if(closed||closing||closeRequested)throw new OperationCanceledException();
     recording.Start(process);save.IsEnabled=true;message("Записано IP: 0. Используйте приложение, затем нажмите «Готово».");timer.Start();
    }catch(Exception ex){failure=ex;}
    if(failure!=null){var failed=observer;observer=null;if(failed!=null){try{await failed.Stop();}catch(Exception ex){failure=new AggregateException(failure,ex);}try{await failed.Dispose();}catch(Exception ex){failure=new AggregateException(failure,ex);}}
     if(!closed&&!closing&&!closeRequested){message(failure.Message);start.IsEnabled=true;SetCapturePickerLocked(list,search,picker.Refresh,false);}}
    loading=false;
   };
   start.Click+=(s,e)=>{if(loading||observer!=null||closed||closing||closeRequested)return;if(list.SelectedItem==null){message("Выберите процесс");return;}starting=begin();};
   save.Click+=(s,e)=>{if(observer==null||loading||closed||closing||closeRequested)return;try{drain();if(recording.IpCount+recording.DomainCount==0){message("Адресов пока нет. Переподключите выбранное приложение.");return;}accepted=true;window.DialogResult=true;}catch(Exception ex){message("Не удалось завершить запись: "+ex.Message);}};
   window.Closed+=(s,e)=>{closed=true;timer.Stop();cancellation.Cancel();};
   Exception cleanupError=null;
   var lifetime=WatchCaptureOwner(window);try{window.ShowDialog();}catch(Exception ex){cleanupError=ex;}
   closed=true;lifetime.Stop();timer.Stop();cancellation.Cancel();await starting;
   var active=observer;observer=null;
   if(active!=null){try{await active.Stop();drainStoppedRecording(recording,active);}catch(Exception ex){cleanupError=cleanupError==null?ex:new AggregateException(cleanupError,ex);}try{await active.Dispose();}catch(Exception ex){cleanupError=cleanupError==null?ex:new AggregateException(cleanupError,ex);}}
   recording.Stop();cancellation.Dispose();
   if(cleanupError!=null)throw new InvalidOperationException("Не удалось завершить наблюдение соединений: "+JournalStyle.Redact(cleanupError.Message),cleanupError);
   if(!accepted||closing||closeRequested)return;
   var choice=CapturedRouteDialogWithCommit(recording,route=>RouteChange(()=>{if(closing||closeRequested)throw new OperationCanceledException();session.State.Lists.Add(route);},false));
   if(choice!=null&&!closing&&!closeRequested)RefreshAfterOperation("Маршрутизация");
  }
  static void drainStoppedRecording(GameRecording recording,CaptureObserver observer){recording.Observe(observer.Drain());}
 }
}
