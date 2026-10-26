using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using SplifyWin;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 Button RouteGamePicker(RouteList route){
  string id=route.Id;var button=PickerButton("Game",false);
  var panel=new StackPanel{Width=240,Margin=new Thickness(12)};
  var hint=Text("",12);panel.Children.Add(hint);
  var tcp=new CheckBox{Name="routeGameTcp",Content="TCP",Style=(Style)FindResource("LabeledPacketCheck"),Margin=new Thickness(0,4,0,10)};
  var udp=new CheckBox{Name="routeGameUdp",Content="UDP",Style=(Style)FindResource("LabeledPacketCheck"),Margin=new Thickness(0,0,0,10)};
  panel.Children.Add(tcp);panel.Children.Add(udp);var error=Text("",11);error.Name="routeGameFeedback";error.Visibility=Visibility.Collapsed;panel.Children.Add(error);
  var popup=new Popup{PlacementTarget=button,Placement=PlacementMode.Bottom,AllowsTransparency=true,StaysOpen=false};
  popup.Child=new Border{Background=Brush(CompactLight?"#F5F8FA":"#171E28"),BorderBrush=Brush(CompactOutline),BorderThickness=new Thickness(1),CornerRadius=new CornerRadius(12),Child=panel};
  bool loading=false;var autosave=new PickerAutoSave(async()=>{
   if(closing||closeRequested)return;var currentRoute=CurrentSavedRoute(id);if(currentRoute.Target!="zapret")return;
   bool nextTcp=tcp.IsChecked==true,nextUdp=udp.IsChecked==true;if(currentRoute.GameTcpEnabled!=nextTcp||currentRoute.GameUdpEnabled!=nextUdp)await SaveRouteGame(id,nextTcp,nextUdp);error.Visibility=Visibility.Collapsed;
  },ex=>{error.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));error.Visibility=Visibility.Visible;});
  RoutedEventHandler changed=(sender,e)=>{if(!loading)autosave.Request();};tcp.Checked+=changed;tcp.Unchecked+=changed;udp.Checked+=changed;udp.Unchecked+=changed;
  ApplyCompactControlPalette(panel);
  popup.Opened+=(sender,e)=>{if(autosave.Pending)return;loading=true;try{var currentRoute=CurrentSavedRoute(id);bool available=currentRoute.Target=="zapret";hint.Text=available?"Протоколы для этого правила Zapret. Изменения сохраняются автоматически.":"TCP/UDP-фильтр применяется только к правилам Zapret.";tcp.IsChecked=currentRoute.GameTcpEnabled;udp.IsChecked=currentRoute.GameUdpEnabled;tcp.IsEnabled=udp.IsEnabled=available;error.Visibility=Visibility.Collapsed;}catch(Exception ex){popup.IsOpen=false;status.Text=JournalStyle.Redact(ex.Message);}finally{loading=false;}};
  popup.Closed+=(sender,e)=>autosave.Flush();
  button.Tag=popup;button.Click+=(s,e)=>{if(!busy&&!closing&&!closeRequested)popup.IsOpen=!popup.IsOpen;};button.Unloaded+=(s,e)=>{autosave.Stop();popup.IsOpen=false;};return button;
 }
}}
