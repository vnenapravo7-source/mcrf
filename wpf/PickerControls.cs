using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Button PickerButton(string caption,bool showArrow=true){var button=new Button{Height=30,HorizontalContentAlignment=HorizontalAlignment.Stretch,Padding=new Thickness(10,3,10,3),Background=(Brush)FindResource("Field"),Style=(Style)FindResource("PickerAction")};var panel=new DockPanel();var arrow=new System.Windows.Shapes.Path{Data=Geometry.Parse("M0,0 L4,4 8,0"),Stroke=Brush("#C6D5ED"),StrokeThickness=1.7,Width=8,Height=4,Stretch=Stretch.Fill,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(8,0,4,0)};DockPanel.SetDock(arrow,Dock.Right);if(showArrow)panel.Children.Add(arrow);panel.Children.Add(CellText(caption));button.Content=panel;return button;}
  UIElement RouteDnsPicker(RouteList route){var button=PickerButton(ExitDnsRouting.Label(session.State,route));button.ToolTip=ExitDnsRouting.Label(session.State,route);var panel=new StackPanel{Margin=new Thickness(12)};var provider=new ComboBox{ItemsSource=new[]{"Общий DNS"}.Concat(DnsOptions.Providers).ToArray(),SelectedItem=route.Dns==null?"Общий DNS":route.Dns.Provider,Width=210};var protocol=new ComboBox();var custom=new TextBox{Text=route.Dns==null?"":route.Dns.Custom};panel.Children.Add(Field("DNS-провайдер",provider));panel.Children.Add(Field("Тип DNS",protocol));panel.Children.Add(custom);Action update=()=>{string p=Convert.ToString(provider.SelectedItem);protocol.ItemsSource=p=="Общий DNS"?new[]{"По общему DNS"}:DnsOptions.Protocols(p);protocol.SelectedIndex=0;custom.Visibility=p=="Свой"?Visibility.Visible:Visibility.Collapsed;};provider.SelectionChanged+=(s,e)=>update();update();if(route.Dns!=null)protocol.SelectedItem=route.Dns.Protocol;var popup=new Popup{PlacementTarget=button,Placement=PlacementMode.Bottom,AllowsTransparency=true,StaysOpen=false,Child=new Border{Background=Brush("#202D46"),BorderBrush=Brush("#43516B"),BorderThickness=new Thickness(1),CornerRadius=new CornerRadius(12),Child=panel}};panel.Children.Add(Button("Применить",async()=>{var dns=new ExitDnsSettings{Provider=Convert.ToString(provider.SelectedItem),Protocol=Convert.ToString(protocol.SelectedItem),Custom=custom.Text.Trim()};try{ValidateDns(dns);popup.IsOpen=false;await Run(()=>RouteChange(()=>route.Dns=dns));}catch(Exception ex){status.Text=ex.Message;}}));button.Click+=(s,e)=>popup.IsOpen=!popup.IsOpen;button.Unloaded+=(s,e)=>popup.IsOpen=false;return button;}
 }
}
