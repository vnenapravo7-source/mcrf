using System;
using System.Linq;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Automation;
using System.Windows.Media;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  sealed class CompactServerRowView {
   internal Grid Row;internal Button Pick,Menu;internal CheckBox Auto;
   internal TextBlock Title,Transport,Delay;internal Image Flag;internal string FlagName;
  }
  CompactServerRowView CreateServerRowView(ServerNode node,int ordinal){
   string id=node.Id;var view=new CompactServerRowView();
   view.Row=new Grid{Name="compactServerRow",Tag=id,Margin=new Thickness(0,0,0,6)};
   view.Row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(0)});
   view.Row.ColumnDefinitions.Add(new ColumnDefinition());
   view.Row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(36)});
   var content=new Grid();content.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(34)});content.ColumnDefinitions.Add(new ColumnDefinition());content.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});
   view.Flag=new Image{Width=24,Height=16,Stretch=System.Windows.Media.Stretch.Uniform,SnapsToDevicePixels=true,VerticalAlignment=VerticalAlignment.Center};System.Windows.Media.RenderOptions.SetBitmapScalingMode(view.Flag,System.Windows.Media.BitmapScalingMode.HighQuality);content.Children.Add(view.Flag);
   var words=new StackPanel{VerticalAlignment=VerticalAlignment.Center};
   view.Title=new TextBlock{FontSize=12.5,TextTrimming=TextTrimming.CharacterEllipsis};view.Transport=new TextBlock{FontSize=9.5,TextTrimming=TextTrimming.CharacterEllipsis};words.Children.Add(view.Title);words.Children.Add(view.Transport);Grid.SetColumn(words,1);content.Children.Add(words);
   view.Delay=new TextBlock{FontSize=10,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(6,0,2,0)};Grid.SetColumn(view.Delay,2);content.Children.Add(view.Delay);
   view.Pick=Button("",async()=>{
    try{if(closing||closeRequested)return;var currentNode=session.State.Servers.FirstOrDefault(n=>n.Id==id);if(currentNode==null){status.Text="Сервер уже удалён.";return;}
    if(WarpPicker.IsWarp(currentNode))OpenExpandedWorkspace("WARP");else await Run(()=>CompactSelectServer(currentNode));
    }catch(Exception ex){string detail=JournalStyle.Redact(ex.Message);status.Text=FailureExplanations.UserMessage(detail);RecordEvent("Ошибка: "+detail);}
   });
   view.Pick.Name="compactServer"+ordinal;view.Pick.Content=content;view.Pick.MinHeight=52;view.Pick.HorizontalContentAlignment=HorizontalAlignment.Stretch;view.Pick.Padding=new Thickness(10,7,8,7);view.Pick.BorderThickness=new Thickness(1);Grid.SetColumn(view.Pick,1);view.Row.Children.Add(view.Pick);
   view.Menu=CompactServerActionsParity(node);Grid.SetColumn(view.Menu,2);view.Row.Children.Add(view.Menu);view.Pick.ContextMenu=view.Menu.ContextMenu;view.Menu.Visibility=BsServers.Is(node)?Visibility.Visible:Visibility.Collapsed;view.Row.ColumnDefinitions[2].Width=new GridLength(BsServers.Is(node)?36:0);
   return view;
  }
  void UpdateServerRowView(CompactServerRowView view,ServerNode node,string activeId){
   bool chosen=node.Id==activeId;string name=ServerIdentity.Display(node.Name);
   if(view.Flag.Source==null||view.FlagName!=node.Name){view.Flag.Source=FlagImage(node.Name);view.FlagName=node.Name;}
   view.Title.Text=name;view.Title.Foreground=Brush(CompactPrimary);view.Title.FontWeight=chosen?FontWeights.SemiBold:FontWeights.Normal;
   view.Transport.Text=node.TransportLabel+(BsServers.Is(node)?" · БС":" · VPN");view.Transport.Foreground=Brush(CompactMuted);
   view.Delay.Text=(chosen?"● ":"")+(node.Latency<0?"—":node.Latency+" мс");view.Delay.Foreground=Brush(chosen?CompactVisualAccent:CompactMuted);
   view.Pick.Foreground=Brush(CompactPrimary);view.Pick.Background=chosen?Brush(AlphaColor(CompactAccentColor,CompactLight?(byte)42:(byte)30)):Brushes.Transparent;
   view.Pick.BorderBrush=chosen?Brush(AlphaColor(CompactAccentColor,98)):Brush(CompactOutline);
   view.Pick.ToolTip=WarpPicker.IsWarp(node)?"WARP · настроить выход и назначенные маршруты":name;
   AutomationProperties.SetName(view.Pick,name+", "+node.TransportLabel+(chosen?", выбран":""));
   bool allowed=AutomaticServers.Allowed(node);
   var probe=view.Menu.ContextMenu.Items.OfType<MenuItem>().FirstOrDefault();
   if(probe!=null){probe.IsEnabled=allowed;probe.ToolTip=allowed?null:"OpenFlux: замер не поддерживается, доступно ручное подключение.";}
   if(session.State.AutoSelect){
    if(view.Auto==null)view.Auto=CompactAutoPoolMark(node);
    if(!view.Row.Children.Contains(view.Auto))view.Row.Children.Add(view.Auto);
    view.Row.ColumnDefinitions[0].Width=new GridLength(36);
    if(!allowed&&FocusManager.GetFocusedElement(FocusManager.GetFocusScope(view.Row))==view.Auto)FocusManager.SetFocusedElement(FocusManager.GetFocusScope(view.Row),view.Pick);
    view.Auto.IsEnabled=allowed;
    if(!busy)view.Auto.IsChecked=allowed&&node.UseInAutoSelection;
    view.Auto.Foreground=Brush(CompactVisualAccent);view.Auto.ToolTip=allowed?"Использовать в автовыборе":"Только ручное подключение";
    AutomationProperties.SetName(view.Auto,"Использовать "+name+" в автовыборе");
   }else{
    if(view.Auto!=null){var scope=FocusManager.GetFocusScope(view.Row);if(FocusManager.GetFocusedElement(scope)==view.Auto)FocusManager.SetFocusedElement(scope,view.Pick);view.Row.Children.Remove(view.Auto);}view.Row.ColumnDefinitions[0].Width=new GridLength(0);
   }
  }
  Action CreateServerListRefresh(StackPanel list,TextBlock count){
   var rows=new Dictionary<string,CompactServerRowView>();int ordinal=0;TextBlock empty=null;
   return ()=>{
    if(session==null||closing||closeRequested)return;
    string activeId=session.Core.Running&&!String.IsNullOrEmpty(session.Core.ActiveServerId)?session.Core.ActiveServerId:session.State.SelectedServerId;
    var nodes=session.State.Servers.Where(n=>n!=null).GroupBy(n=>n.Id).Select(g=>g.First()).OrderByDescending(n=>n.Id==activeId).ThenBy(n=>n.Latency<0?Int32.MaxValue:n.Latency).ToArray();
    count.Text=nodes.Length+" подключений";var ids=new HashSet<string>(nodes.Select(n=>n.Id));
    var scope=FocusManager.GetFocusScope(list);var focused=FocusManager.GetFocusedElement(scope) as DependencyObject;int focusIndex=-1;
    foreach(var id in rows.Keys.Where(id=>!ids.Contains(id)).ToArray()){
     var old=rows[id];if(focused!=null&&(Object.ReferenceEquals(focused,old.Row)||old.Row.IsAncestorOf(focused)))focusIndex=list.Children.IndexOf(old.Row);
     old.Menu.ContextMenu.IsOpen=false;list.Children.Remove(old.Row);rows.Remove(id);
    }
    if(nodes.Length==0){
     if(empty==null){empty=Text("Подключений пока нет",12);empty.TextAlignment=TextAlignment.Center;empty.Margin=new Thickness(0,14,0,18);empty.Foreground=Brush(CompactMuted);}
     if(!list.Children.Contains(empty))list.Children.Add(empty);
    }else{
     if(empty!=null)list.Children.Remove(empty);
     foreach(var node in nodes){
      CompactServerRowView view;if(!rows.TryGetValue(node.Id,out view)){view=CreateServerRowView(node,ordinal++);rows.Add(node.Id,view);list.Children.Add(view.Row);}
      UpdateServerRowView(view,node,activeId);
     }
    }
    // Existing rows keep their position during metric/selection updates, as on Android.
    if(focusIndex>=0){var survivors=list.Children.OfType<Grid>().ToArray();FocusManager.SetFocusedElement(scope,survivors.Length==0?null:rows[Convert.ToString(survivors[Math.Min(focusIndex,survivors.Length-1)].Tag)].Pick);}
   };
  }
 }
}
