using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Automation;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  CheckBox CompactAutoPoolMark(ServerNode node){
   var check=new CheckBox{Name="compactAutoPoolMark",Style=(Style)FindResource("ServerAutoCheck"),Width=32,Height=44,VerticalAlignment=VerticalAlignment.Center,Foreground=Brush(CompactVisualAccent),IsEnabled=AutomaticServers.Allowed(node),IsChecked=AutomaticServers.Allowed(node)&&node.UseInAutoSelection};
   check.ToolTip=AutomaticServers.Allowed(node)?"Использовать в автовыборе":"Только ручное подключение";
   AutomationProperties.SetName(check,"Использовать "+ServerIdentity.Display(node.Name)+" в автовыборе");
   Action restore=()=>{var current=session.State.Servers.FirstOrDefault(n=>n.Id==node.Id);check.IsChecked=current!=null&&AutomaticServers.Allowed(current)&&current.UseInAutoSelection;};
   check.Click+=async(s,e)=>{
    if(busy||closing||closeRequested){restore();return;}bool wanted=check.IsChecked==true;
    await Run(async()=>{bool changed=await session.SetAutoSelectionMark(node.Id,wanted,()=>closing||closeRequested);if(changed){status.Text=wanted?"Сервер включён в автовыбор.":"Сервер исключён из автовыбора.";if(!closing&&!closeRequested&&refreshServer!=null)refreshServer();}},restore);
    if(!closing&&!closeRequested)restore();
   };
   return check;
  }
 }
}
