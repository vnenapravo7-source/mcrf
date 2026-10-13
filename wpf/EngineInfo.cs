using System;
using System.Diagnostics;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  UIElement RepositoryLink(string caption,string url){var text=new TextBlock{Margin=new Thickness(0,6,0,10)};var link=new Hyperlink(new Run(caption)){Foreground=Brush("#4EE7FC"),NavigateUri=new Uri(url)};link.RequestNavigate+=(s,e)=>{Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri){UseShellExecute=true});e.Handled=true;};text.Inlines.Add(link);return text;}
  sealed class ProtocolRow{public string Name,Features,Engine,Source;public string Optional;}
  UIElement ProtocolsContent(){
   var panel=new DockPanel();var intro=Text("Протокол определяется сервером. Дополнительные движки устанавливаются отдельно; существующие подключения сохраняются.");intro.Margin=new Thickness(0,8,0,14);DockPanel.SetDock(intro,Dock.Top);panel.Children.Add(intro);
   var install=Button("Установить дополнительные движки",()=>{updatesType=1;Page("Обновления");});install.HorizontalAlignment=HorizontalAlignment.Left;install.Margin=new Thickness(0,14,0,0);DockPanel.SetDock(install,Dock.Bottom);panel.Children.Add(install);
   var rows=new[]{
    new ProtocolRow{Name="VPN / прокси",Features="VLESS · VMess · Trojan · Shadowsocks · AnyTLS · NaiveProxy · SSH · HTTP · SOCKS5",Engine="sing-box · встроен",Source="https://github.com/vnenapravo7-source/3x-ui-tunnels"},
    new ProtocolRow{Name="UDP-транспорты",Features="Hysteria 1/2 · TUIC",Engine="sing-box · встроен",Source="https://github.com/vnenapravo7-source/3x-ui-tunnels"},
    new ProtocolRow{Name="Транспорт и защита",Features="TCP · gRPC · WebSocket · XHTTP · TLS · REALITY",Engine="Зависит от сервера",Source="https://github.com/vnenapravo7-source/3x-ui-tunnels"},
    new ProtocolRow{Name="WARP / WireGuard",Features="WireGuard · AmneziaWG · выход Cloudflare WARP",Engine="sing-box / WarpScout",Source="https://github.com/vnenapravo7-source/3x-ui-tunnels"},
    new ProtocolRow{Name="OpenFlux",Features="TCP через Yandex Docs, Volga, Board, Telemost, Mail.ru, Cups.online или Direct",Optional="openflux",Source="https://github.com/vnenapravo7-source/openflux-deploy"},
    new ProtocolRow{Name="CSQTT",Features="TURN/RTP · отдельный Windows-клиент · некоммерческая лицензия",Optional="csqtt",Source="https://github.com/amurcanov/csqtt"},
    new ProtocolRow{Name="WDTT",Features="WireGuard через DTLS/TURN · SOCKS5-выход",Optional="wdtt",Source="https://github.com/Ivan4537/WDTT-Plus"},
    new ProtocolRow{Name="QWDTT",Features="Вариант SpaceNeuroX · свой SOCKS5-движок · не заменяется WDTT",Optional="qwdtt",Source="https://github.com/SpaceNeuroX/proxy-turn-vk-android"}
   };foreach(var row in rows)if(row.Optional!=null)row.Engine=session.Core.Engines.Installed(row.Optional)?"Установлен отдельно":"Не установлен";
   var table=new DataGrid{Name="protocolsGrid",ItemsSource=rows,RowHeight=Double.NaN,MinRowHeight=58,EnableColumnVirtualization=false};
   table.Columns.Add(CellColumn("Компонент",1.15,o=>Text(((ProtocolRow)o).Name)));table.Columns.Add(CellColumn("Протоколы и возможности",3.4,o=>{var text=Text(((ProtocolRow)o).Features);text.Margin=new Thickness(4,7,4,7);return text;}));table.Columns.Add(CellColumn("Движок / состояние",1.65,o=>Text(((ProtocolRow)o).Engine)));table.Columns.Add(CellColumn("Источник",.8,o=>{var row=(ProtocolRow)o;var link=Button("Открыть",()=>Process.Start(new ProcessStartInfo(row.Source){UseShellExecute=true}));link.Margin=new Thickness(6);return link;}));table.Columns[0].MinWidth=115;table.Columns[1].MinWidth=230;table.Columns[2].MinWidth=150;table.Columns[3].Width=100;panel.Children.Add(table);return panel;
  }
  Task EngineInfo(){var window=Dialog("Протоколы",1020,720);window.Content=new Border{Padding=new Thickness(24),Child=ProtocolsContent()};window.ShowDialog();return Task.FromResult(0);}
 }
}
