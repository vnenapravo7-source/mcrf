using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  UIElement ServerConnectionHeader(){
   var row=new DockPanel{Name="serverConnectionHeader",Margin=new Thickness(0,0,0,14)};
   var mode=AsyncButton("",async()=>{await session.ChangeConfiguration(()=>session.State.Mode=session.State.Mode=="tun"?"proxy":"tun");if(refreshHome!=null)refreshHome();});mode.Name="serverConnectionMode";mode.MinWidth=145;mode.Margin=new Thickness(16,0,0,0);DockPanel.SetDock(mode,Dock.Right);row.Children.Add(mode);
   var power=AsyncButton("⏻",async()=>{await ToggleVpnWithPresentation();if(refreshHome!=null)refreshHome();status.Text=session.Core.Running?(session.State.Mode=="tun"?"VPN включён":"Прокси запущен"):"VPN / прокси отключён";});power.Name="serverConnectButton";power.Width=48;power.Height=48;power.FontSize=25;power.Padding=new Thickness(0);power.Margin=new Thickness(0,0,14,0);power.Style=(Style)FindResource("NeonButton");DockPanel.SetDock(power,Dock.Left);row.Children.Add(power);
   var labels=new StackPanel{VerticalAlignment=VerticalAlignment.Center};var state=new TextBlock{FontSize=18,TextTrimming=TextTrimming.CharacterEllipsis};state.Name="serverConnectionState";var detail=new TextBlock{FontSize=12,Foreground=Brush("#BFCCE2"),TextTrimming=TextTrimming.CharacterEllipsis,Margin=new Thickness(0,5,0,0)};labels.Children.Add(state);labels.Children.Add(detail);row.Children.Add(labels);
   refreshHome=()=>{bool active=session.Core.Running&&session.State.VpnEnabled&&!session.Core.Starting;string id=active&&!String.IsNullOrEmpty(session.Core.ActiveServerId)?session.Core.ActiveServerId:session.State.SelectedServerId;var node=session.State.Servers.FirstOrDefault(n=>n.Id==id);state.Text=session.Core.Starting?"Подключаем…":active?(session.State.Mode=="tun"?"VPN включён":"Прокси запущен"):"VPN / прокси отключён";detail.Text=(node==null?(session.State.AutoSelect?"Автовыбор сервера":"Выберите сервер ниже"):node.Name)+(session.State.Mode=="proxy"?" · HTTP / SOCKS5: 127.0.0.1:10808":"");mode.Content=session.State.Mode=="tun"?"Режим TUN":"Только прокси";power.BorderBrush=Brush(active?"#6BFAD8":"#B78DFF");power.ToolTip=active?"Отключить":"Подключить";};refreshHome();return row;
  }
 }
}
