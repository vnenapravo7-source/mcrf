using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  int serversTab;
  void ServersWorkspace(){
   var panel=new DockPanel();var top=new DockPanel{Margin=new Thickness(0,0,0,8)};var update=AsyncButton("Обновить подписки",UpdateSubscriptions);update.Name="updateSubscriptions";update.MinWidth=168;update.Margin=new Thickness(12,0,0,0);DockPanel.SetDock(update,Dock.Right);top.Children.Add(update);var tabs=new StackPanel{Name="serversSections",Orientation=Orientation.Horizontal};
   foreach(var item in new[]{"VPN / прокси","WARP","Протоколы"}){int index=tabs.Children.Count;var button=Button(item,()=>{serversTab=index;Page("Серверы");});button.Name="serversSection"+index;button.Background=Brush(serversTab==index?"#42376B":"#202D46");button.Margin=new Thickness(0,0,8,0);button.Padding=new Thickness(14,6,14,6);tabs.Children.Add(button);}top.Children.Add(tabs);DockPanel.SetDock(top,Dock.Top);panel.Children.Add(top);panel.Children.Add(serversTab==1?WarpWorkspace():serversTab==2?ProtocolsContent():VpnServers());body.Children.Add(Card(panel));
  }
  UIElement AutoSelectSettings(){
   var row=new Grid{Name="autoSelectSettings",HorizontalAlignment=HorizontalAlignment.Left,Margin=new Thickness(0,2,0,6)};row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(310)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(12)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(120)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(12)});row.ColumnDefinitions.Add(new ColumnDefinition());
   var method=new ComboBox{Name="autoSelectMethod",ItemsSource=new[]{"Автовыбор сервера выкл","Автовыбор первого рабочего","Автовыбор самого быстрого"},SelectedIndex=!session.State.AutoSelect?0:session.State.AutoSelectMode=="first"?1:2};
   var interval=new TextBox{Name="autoSelectInterval",Text=Math.Max(5,session.State.AutoSelectIntervalSeconds).ToString()};var tolerance=new TextBox{Name="autoSelectTolerance",Text=session.State.AutoSelectToleranceMs.ToString()};var msField=Field("Отклонение, мс",tolerance);var intervalField=Field("Замер раз в, с",interval);
   Action availability=()=>{msField.Visibility=method.SelectedIndex==2?Visibility.Visible:Visibility.Collapsed;row.ColumnDefinitions[3].Width=new GridLength(method.SelectedIndex==2?12:0);row.ColumnDefinitions[4].Width=method.SelectedIndex==2?new GridLength(120):new GridLength(0);intervalField.Visibility=method.SelectedIndex>0?Visibility.Visible:Visibility.Collapsed;row.ColumnDefinitions[1].Width=new GridLength(method.SelectedIndex>0?12:0);row.ColumnDefinitions[2].Width=new GridLength(method.SelectedIndex>0?120:0);tolerance.IsEnabled=method.SelectedIndex==2;interval.IsEnabled=method.SelectedIndex!=0;};availability();
   Func<Task> commit=async()=>{int ms=session.State.AutoSelectToleranceMs,seconds=session.State.AutoSelectIntervalSeconds;if(method.SelectedIndex==2&&(!Int32.TryParse(tolerance.Text,out ms)||ms<0||ms>10000))throw new FormatException("Отклонение пинга: 0–10000 мс.");if(method.SelectedIndex>0&&(!Int32.TryParse(interval.Text,out seconds)||seconds<5||seconds>3600))throw new FormatException("Замер: 5–3600 секунд.");await session.ChangeConfiguration(()=>{session.State.AutoSelectSettingsVersion=1;session.State.AutoSelect=method.SelectedIndex>0;session.State.AutoSelectMode=method.SelectedIndex==1?"first":"fast";session.State.AutoSelectToleranceMs=ms;session.State.AutoSelectIntervalSeconds=seconds;});status.Text="Автовыбор сервера сохранён.";};
   method.SelectionChanged+=async(s,e)=>{availability();await Run(commit);};foreach(var field in new[]{tolerance,interval}){field.LostKeyboardFocus+=async(s,e)=>{if(field.IsEnabled&&field.IsVisible)await Run(commit);};field.PreviewKeyDown+=async(s,e)=>{if(e.Key==System.Windows.Input.Key.Enter){await Run(commit);e.Handled=true;}};}
   row.Children.Add(Field("Автовыбор сервера",method));Grid.SetColumn(intervalField,2);row.Children.Add(intervalField);Grid.SetColumn(msField,4);row.Children.Add(msField);return row;
  }
  DateTime lastSelectionProbe;int lastSelectionGeneration;
  async Task AutoProbeHealth(){if(diagnostic||closing||busy||session==null||!session.State.AutoSelect||!session.State.Servers.Any(n=>!WarpPicker.IsWarp(n))||serverProbeRunning)return;try{var interval=TimeSpan.FromSeconds(Math.Max(5,session.State.AutoSelectIntervalSeconds));if(DateTime.UtcNow-lastServerProbe>=interval)await ProbeServers();if(session.Core.Running&&!session.Core.Starting&&session.State.AutoSelectMode=="first"&&(lastSelectionGeneration!=session.Core.StartGeneration||DateTime.UtcNow-lastSelectionProbe>=interval)){lastSelectionGeneration=session.Core.StartGeneration;lastSelectionProbe=DateTime.UtcNow;bool selected=await Task.Run(()=>session.Core.SelectFirstWorking(()=>closing));if(!selected&&!closing)status.Text=session.Core.ActiveSelectionError;}}catch(Exception ex){if(!closing)status.Text="Автовыбор: "+ex.Message;}}
 }
}
