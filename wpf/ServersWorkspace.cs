using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  int serversTab;bool serverBsSection;
  void ServersWorkspace(){
   if(!expandedWorkspace){CompactServersWorkspace();return;}
   if(InterfaceMode==1&&serversTab==1)serversTab=0;var panel=new DockPanel();if(InterfaceMode==1){var connection=ServerConnectionHeader();DockPanel.SetDock(connection,Dock.Top);panel.Children.Add(connection);}var top=new DockPanel{Margin=new Thickness(0,0,0,8)};var update=AsyncButton("Обновить подписки",UpdateSubscriptions);update.Name="updateSubscriptions";update.MinWidth=168;update.Margin=new Thickness(12,0,0,0);DockPanel.SetDock(update,Dock.Right);top.Children.Add(update);var tabs=new StackPanel{Name="serversSections",Orientation=Orientation.Horizontal};
   foreach(int tabIndex in (InterfaceMode==1?new[]{0,3,2}:new[]{0,3,1,2})){int index=tabIndex;string item=new[]{"Подключения","WARP","Протоколы","VK TURN"}[index];var button=Button(item,()=>{serversTab=index;Page("Серверы");});button.Name="serversSection"+index;button.Background=Brush(serversTab==index?"#42376B":"#202D46");button.Margin=new Thickness(0,0,8,0);button.Padding=new Thickness(14,6,14,6);tabs.Children.Add(button);}if(serversTab==0){var kind=new StackPanel{Name="serverKindSwitch",Orientation=Orientation.Horizontal,Margin=new Thickness(12,0,0,0)};var vpn=Button("VPN",()=>{serverBsSection=false;Page("Серверы");});vpn.Name="serverKindVpn";var bs=Button("БС",()=>{serverBsSection=true;Page("Серверы");});bs.Name="serverKindBs";vpn.Background=Brush(!serverBsSection?"#315B706F":"#18202D43");bs.Background=Brush(serverBsSection?"#5143766B":"#18202D43");vpn.Margin=new Thickness(0,0,5,0);foreach(var b in new[]{vpn,bs}){b.Padding=new Thickness(12,6,12,6);kind.Children.Add(b);}tabs.Children.Add(kind);}top.Children.Add(tabs);DockPanel.SetDock(top,Dock.Top);panel.Children.Add(top);panel.Children.Add(serversTab==1?WarpWorkspace():serversTab==2?ProtocolsContent():serversTab==3?VkTurnContent():VpnServers());body.Children.Add(Card(panel));
  }
  UIElement AutoSelectSettings(){
   if(!expandedWorkspace){var compactMethod=new ComboBox{Name="autoSelectMethod",ItemsSource=new[]{"Автовыбор выключен","Первый рабочий","Самый быстрый","Последний выбранный"},SelectedIndex=!session.State.AutoSelect?0:session.State.AutoSelectMode=="first"?1:session.State.AutoSelectMode=="recent"?3:2};compactMethod.SelectionChanged+=async(s,e)=>await Run(()=>session.ChangeConfiguration(()=>{session.State.AutoSelectSettingsVersion=1;session.State.AutoSelect=compactMethod.SelectedIndex>0;session.State.AutoSelectMode=compactMethod.SelectedIndex==1?"first":compactMethod.SelectedIndex==3?"recent":"fast";}));return Field("Автовыбор сервера",compactMethod);}
   var row=new Grid{Name="autoSelectSettings",HorizontalAlignment=HorizontalAlignment.Left,Margin=new Thickness(0,2,0,6)};row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(310)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(12)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(120)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(12)});row.ColumnDefinitions.Add(new ColumnDefinition());
   var method=new ComboBox{Name="autoSelectMethod",ItemsSource=new[]{"Автовыбор выключен","Первый рабочий","Самый быстрый","Последний выбранный"},SelectedIndex=!session.State.AutoSelect?0:session.State.AutoSelectMode=="first"?1:session.State.AutoSelectMode=="recent"?3:2};
   var interval=new TextBox{Name="autoSelectInterval",Text=Math.Max(5,session.State.AutoSelectIntervalSeconds).ToString()};var tolerance=new TextBox{Name="autoSelectTolerance",Text=session.State.AutoSelectToleranceMs.ToString()};var failure=new CheckBox{Name="autoSelectFailureOnly",Content="Только при недоступности",IsChecked=session.State.AutoSelectFailureOnly,VerticalAlignment=VerticalAlignment.Center};var msPanel=new StackPanel();msPanel.Children.Add(tolerance);msPanel.Children.Add(failure);var msField=Field("Отклонение",msPanel);var intervalField=Field("Замер раз в, с",interval);
   Action availability=()=>{msField.Visibility=method.SelectedIndex==2?Visibility.Visible:Visibility.Collapsed;row.ColumnDefinitions[3].Width=new GridLength(method.SelectedIndex==2?12:0);row.ColumnDefinitions[4].Width=method.SelectedIndex==2?new GridLength(190):new GridLength(0);intervalField.Visibility=method.SelectedIndex>0?Visibility.Visible:Visibility.Collapsed;row.ColumnDefinitions[1].Width=new GridLength(method.SelectedIndex>0?12:0);row.ColumnDefinitions[2].Width=new GridLength(method.SelectedIndex>0?120:0);tolerance.IsEnabled=method.SelectedIndex==2&&failure.IsChecked!=true;interval.IsEnabled=method.SelectedIndex!=0;};availability();
   Func<Task> commit=async()=>{int ms=session.State.AutoSelectToleranceMs,seconds=session.State.AutoSelectIntervalSeconds;if(method.SelectedIndex==2&&failure.IsChecked!=true&&(!Int32.TryParse(tolerance.Text,out ms)||ms<15||ms>150))throw new FormatException("Отклонение пинга: 15–150 мс.");if(method.SelectedIndex>0&&(!Int32.TryParse(interval.Text,out seconds)||seconds<5||seconds>3600))throw new FormatException("Замер: 5–3600 секунд.");await session.ChangeConfiguration(()=>{session.State.AutoSelectSettingsVersion=1;session.State.AutoSelect=method.SelectedIndex>0;session.State.AutoSelectMode=method.SelectedIndex==1?"first":method.SelectedIndex==3?"recent":"fast";session.State.AutoSelectToleranceMs=ms;session.State.AutoSelectIntervalSeconds=seconds;session.State.AutoSelectFailureOnly=failure.IsChecked==true;});status.Text="Автовыбор сервера сохранён.";};
   method.SelectionChanged+=async(s,e)=>{availability();await Run(commit);};failure.Click+=async(s,e)=>{availability();await Run(commit);};foreach(var field in new[]{tolerance,interval}){field.LostKeyboardFocus+=async(s,e)=>{if(field.IsEnabled&&field.IsVisible)await Run(commit);};field.PreviewKeyDown+=async(s,e)=>{if(e.Key==System.Windows.Input.Key.Enter){await Run(commit);e.Handled=true;}};}
   row.Children.Add(Field("Автовыбор сервера",method));Grid.SetColumn(intervalField,2);row.Children.Add(intervalField);Grid.SetColumn(msField,4);row.Children.Add(msField);return row;
  }
  DateTime lastSelectionProbe;int lastSelectionGeneration;bool selectionProbeRunning;
  async Task AutoProbeHealth(){
   if(diagnostic||closing||closeRequested||busy||selectionProbeRunning||session==null||!session.State.AutoSelect||!session.State.Servers.Any(n=>AutomaticServers.Allowed(n)&&!WarpPicker.IsWarp(n))||serverProbeRunning)return;
   selectionProbeRunning=true;
   try{
    var origin=session.State;var interval=TimeSpan.FromSeconds(Math.Max(5,origin.AutoSelectIntervalSeconds));
    if(DateTime.UtcNow-lastServerProbe>=interval)await ProbeServers();
    if(closing||closeRequested||busy||!Object.ReferenceEquals(origin,session.State)||!origin.AutoSelect)return;
    bool failureOnly=AutoSelectionPolicy.FailureOnly(origin);
    if(session.Core.Running&&!session.Core.Starting&&(origin.AutoSelectMode=="first"||failureOnly)&&(lastSelectionGeneration!=session.Core.StartGeneration||DateTime.UtcNow-lastSelectionProbe>=interval)){
     var snapshot=session.ConfigurationSnapshot();int generation=session.Core.StartGeneration;
     lastSelectionGeneration=generation;lastSelectionProbe=DateTime.UtcNow;
     Func<bool> stale=()=>closing||closeRequested||busy||!Object.ReferenceEquals(origin,session.State)||session.Core.StartGeneration!=generation||!origin.AutoSelect||origin.AutoSelectMode!=snapshot.AutoSelectMode||origin.AutoSelectFailureOnly!=snapshot.AutoSelectFailureOnly||origin.SelectedServerId!=snapshot.SelectedServerId||origin.AutoBsActive!=snapshot.AutoBsActive||origin.AutoDetectBs!=snapshot.AutoDetectBs;
     bool selected=await Task.Run(()=>failureOnly?session.Core.MaintainFailureOnlySelection(snapshot,stale):session.Core.SelectFirstWorking(stale));
     if(!selected&&!stale()&&!String.IsNullOrEmpty(session.Core.ActiveSelectionError))status.Text=session.Core.ActiveSelectionError;
    }
   }catch(Exception ex){if(!closing&&!closeRequested)status.Text="Автовыбор: "+JournalStyle.Redact(ex.Message);}finally{selectionProbeRunning=false;}
  }
 }
}
