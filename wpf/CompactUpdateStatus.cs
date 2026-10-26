using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using SplifyWin;

namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  UIElement UpdatePreferencesRecovery(){var panel=new StackPanel{Name="updatePreferencesUnavailable",Margin=new Thickness(4,8,4,8)};panel.Children.Add(Text("Настройки обновлений недоступны",20));panel.Children.Add(Text(session.Updates.PreferencesError,13));Button retry=null;retry=AsyncButton("Повторить чтение",async()=>{retry.IsEnabled=false;try{await System.Threading.Tasks.Task.Run(()=>session.Updates.ReloadPreferences());RefreshAfterOperation("Обновления");}finally{if(!closing&&!closeRequested)retry.IsEnabled=true;}});retry.Name="updatePreferencesRetry";StyleCompactButton(retry);panel.Children.Add(retry);return panel;}
  void WatchCompactUpdates(TextBlock activity,ProgressBar progress,Button check,Button install,ListBox picker,CheckBox startup,CheckBox automatic,Action refresh){
   var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(150)};
   Action update=()=>{
    if(closing||closeRequested){timer.Stop();return;}
    refresh();bool running=session.Updates.Checking||session.Updates.Installing;
    activity.Text=busy&&!running?"Выполняется операция…":String.IsNullOrWhiteSpace(session.Updates.Activity)?"Проверка ещё не выполнялась.":JournalStyle.Redact(session.Updates.Activity);
    progress.Visibility=running||busy?Visibility.Visible:Visibility.Collapsed;
    progress.IsIndeterminate=session.Updates.Checking||busy&&!session.Updates.Installing;
    progress.Value=Math.Max(0,Math.Min(100,session.Updates.Progress));
    check.IsEnabled=!running&&!busy;picker.IsEnabled=!running&&!busy;startup.IsEnabled=!running&&!busy;automatic.IsEnabled=!running&&!busy;
    if(running||busy)install.IsEnabled=false;
   };
   timer.Tick+=(s,e)=>update();activity.Loaded+=(s,e)=>{update();timer.Start();};activity.Unloaded+=(s,e)=>timer.Stop();update();
  }
 }
}
