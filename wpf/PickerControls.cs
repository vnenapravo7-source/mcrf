using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Button PickerButton(string caption,bool showArrow=true){var button=new Button{Height=30,HorizontalContentAlignment=HorizontalAlignment.Stretch,Padding=new Thickness(10,3,10,3),Background=Brush(CompactLight?"#FFFFFF":"#20262D"),Foreground=Brush(CompactPrimary),BorderBrush=Brush(CompactOutline),Style=(Style)FindResource("PickerAction")};var panel=new DockPanel();var arrow=new System.Windows.Shapes.Path{Data=Geometry.Parse("M0,0 L4,4 8,0"),Stroke=Brush(CompactPrimary),StrokeThickness=1.7,Width=8,Height=4,Stretch=Stretch.Fill,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(8,0,4,0)};DockPanel.SetDock(arrow,Dock.Right);if(showArrow)panel.Children.Add(arrow);var label=CellText(caption);label.Foreground=Brush(CompactPrimary);panel.Children.Add(label);button.Content=panel;return button;}
  UIElement RouteDnsPicker(RouteList route){
   string id=route.Id;var button=PickerButton(ExitDnsRouting.Label(session.State,route));button.Name="compactRouteDns_"+id.Replace("-","_");button.ToolTip=ExitDnsRouting.Label(session.State,route);
   var panel=new StackPanel{Margin=new Thickness(12),Width=240};var provider=new ComboBox{Name="routeDnsProvider",ItemsSource=new[]{"DNS выхода","Общий DNS"}.Concat(DnsOptions.Providers).ToArray()};var protocol=new ComboBox{Name="routeDnsProtocol"};var custom=new TextBox{Name="routeDnsCustom"};
   panel.Children.Add(Field("DNS-провайдер",provider));panel.Children.Add(Field("Тип DNS",protocol));panel.Children.Add(custom);
   var inherited=Text("",11);inherited.Name="routeDnsInheritance";panel.Children.Add(inherited);var error=Text("",11);error.Name="routeDnsError";error.Visibility=Visibility.Collapsed;panel.Children.Add(error);
   bool loading=false;var popup=new Popup{PlacementTarget=button,Placement=PlacementMode.Bottom,AllowsTransparency=true,StaysOpen=false};
   popup.Child=new Border{Background=Brush(CompactLight?"#F5F8FA":"#171E28"),BorderBrush=Brush(CompactOutline),BorderThickness=new Thickness(1),CornerRadius=new CornerRadius(12),Child=panel};
   var autosave=new PickerAutoSave(async()=>{
    if(closing||closeRequested)return;
    string p=Convert.ToString(provider.SelectedItem);if(String.IsNullOrEmpty(p))return;
    ExitDnsSettings dns=p=="DNS выхода"?null:new ExitDnsSettings{Provider=p,Protocol=Convert.ToString(protocol.SelectedItem),Custom=p=="Свой"?custom.Text.Trim():""};ValidateDns(dns);
    if(!SameRouteDns(CurrentSavedRoute(id).Dns,dns))await ChangeSavedRoute(id,actual=>{if(closing||closeRequested)throw new OperationCanceledException();actual.Dns=dns;},false);
    button.ToolTip="DNS · "+ExitDnsRouting.Label(session.State,CurrentSavedRoute(id));error.Visibility=Visibility.Collapsed;
   },ex=>{error.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));error.Foreground=LogColor("ERROR");error.Visibility=Visibility.Visible;});
   Action update=()=>{string p=Convert.ToString(provider.SelectedItem);bool inherit=p=="DNS выхода",global=p=="Общий DNS";protocol.ItemsSource=inherit?new[]{"По DNS выхода"}:global?new[]{"По общему DNS"}:DnsOptions.Protocols(p);protocol.SelectedIndex=0;protocol.IsEnabled=!inherit&&!global;custom.Visibility=p=="Свой"?Visibility.Visible:Visibility.Collapsed;inherited.Visibility=inherit?Visibility.Visible:Visibility.Collapsed;};
   provider.SelectionChanged+=(sender,e)=>{if(loading)return;loading=true;try{update();}finally{loading=false;}autosave.Request();};
   protocol.SelectionChanged+=(sender,e)=>{if(!loading)autosave.Request();};
   custom.TextChanged+=(sender,e)=>{if(!loading){error.Visibility=Visibility.Collapsed;autosave.Request(450);}};
   custom.LostKeyboardFocus+=(sender,e)=>autosave.Flush();
   popup.Opened+=(sender,e)=>{if(autosave.Pending)return;loading=true;try{var currentRoute=CurrentSavedRoute(id);provider.SelectedItem=currentRoute.Dns==null?"DNS выхода":currentRoute.Dns.Provider;update();if(currentRoute.Dns!=null&&currentRoute.Dns.Provider!="Общий DNS")protocol.SelectedItem=currentRoute.Dns.Protocol;custom.Text=currentRoute.Dns==null?"":currentRoute.Dns.Custom;inherited.Text="По выходу сейчас: "+ExitDnsRouting.Label(session.State,new RouteList{Target=currentRoute.Target,ZapretProfileId=currentRoute.ZapretProfileId});error.Visibility=Visibility.Collapsed;}catch(Exception ex){popup.IsOpen=false;status.Text=JournalStyle.Redact(ex.Message);}finally{loading=false;}};
   popup.Closed+=(sender,e)=>autosave.Flush();ApplyCompactControlPalette(panel);button.Tag=popup;
   button.Click+=(sender,e)=>{if(!busy&&!closing&&!closeRequested)popup.IsOpen=!popup.IsOpen;};button.Unloaded+=(sender,e)=>{autosave.Stop();popup.IsOpen=false;};return button;
  }
  static bool SameRouteDns(ExitDnsSettings a,ExitDnsSettings b){return a==null||b==null?a==b:a.Provider==b.Provider&&(a.Provider=="Общий DNS"||a.Protocol==b.Protocol&&(a.Provider!="Свой"||String.Equals((a.Custom??"").Trim(),b.Custom,StringComparison.Ordinal)));}
 }
}
