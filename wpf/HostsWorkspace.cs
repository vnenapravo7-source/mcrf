using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Button AdditionalHostsPicker(){
   var button=PickerButton("Выбрать сервисы");button.Name="routingHosts_additional";button.Margin=new Thickness(0,0,0,8);bool loading=false;button.Click+=async(s,e)=>{if(loading||busy)return;loading=true;try{byte[] expected=await Task.Run(()=>File.ReadAllBytes(HostsEditor.PathName));var data=await session.Core.DownloadTextAsync(HostsCandidates.MalwSource);if(!button.IsLoaded)return;string currentEntries=MalwHosts.Entries(expected);var groups=MalwHosts.Catalog(data);var checks=groups.Select(g=>new CheckBox{Content=g.Name,Tag=g,IsChecked=g.Entries.Any(line=>currentEntries.Contains(line))}).ToArray();
    var picker=MultiPicker("hostsAdditionalChoices",checks,async()=>{string entries=String.Join("\n",checks.Where(c=>c.IsChecked==true).SelectMany(c=>((MalwHostsService)c.Tag).Entries).Distinct());if(!Confirm("Применить выбранные сервисы в hosts? Остальные блоки сохранятся; будет резервная копия.","Дополнительные сервисы"))return;byte[] composed=MalwHosts.Compose(expected,entries);await HostsRepair.ApplyAsync(root,HostsEditor.Managed(composed),expected);status.Text="Дополнительные сервисы hosts сохранены.";RefreshAfterOperation(InterfaceMode==2?"WARP":"Маршрутизация");});
    var parent=button.Parent as Panel;int index=parent.Children.IndexOf(button);parent.Children.Remove(button);parent.Children.Insert(index,picker);picker.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
   }catch(Exception ex){status.Text="Сервисы hosts: "+ex.Message;}finally{loading=false;}};return button;
  }
 }
}

