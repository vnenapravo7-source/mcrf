using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
namespace Mcrf.Wpf {
 // All pickers replace immutable membership snapshots instead of changing a bound List.
 public sealed class RunningProcessPicker : DockPanel {
  public readonly ListBox List;public readonly TextBox Search;public readonly Button Refresh;
  readonly HashSet<string> selected=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
  readonly Func<Task<string[]>> load;readonly Func<bool> alive;string[] processes;bool changing,refreshing;
  public string[] Selected{get{return selected.OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();}}
  public RunningProcessPicker(ListBox list,string[] initial,bool multiple,Func<Task<string[]>> loader,Func<bool> isAlive){
   List=list;processes=initial??new string[0];load=loader;alive=isAlive;List.SelectionMode=multiple?SelectionMode.Extended:SelectionMode.Single;
   Search=new TextBox{Name="runningProcessSearch",Margin=new Thickness(0,0,10,0),ToolTip="Поиск по имени EXE"};
   Refresh=new Button{Name="refreshRunningProcesses",Content="Обновить",MinWidth=110,ToolTip="Заново получить список запущенных процессов"};
   var top=new DockPanel{Margin=new Thickness(0,0,0,8)};DockPanel.SetDock(Refresh,Dock.Right);top.Children.Add(Refresh);top.Children.Add(Search);DockPanel.SetDock(top,Dock.Top);Children.Add(top);Children.Add(List);
   List.SelectionChanged+=(s,e)=>{if(changing)return;if(!multiple)selected.Clear();foreach(string item in e.AddedItems)selected.Add(item);if(multiple)foreach(string item in e.RemovedItems)selected.Remove(item);};
   Search.TextChanged+=(s,e)=>Apply();Refresh.Click+=async(s,e)=>await Reload();Apply();
  }
  void Apply(){changing=true;try{List.ItemsSource=processes.Where(p=>p.IndexOf(Search.Text,StringComparison.OrdinalIgnoreCase)>=0).ToArray();foreach(string p in List.Items)if(selected.Contains(p))List.SelectedItems.Add(p);}finally{changing=false;}}
  public async Task Reload(){if(refreshing||!Refresh.IsEnabled||!List.IsEnabled)return;refreshing=true;Refresh.IsEnabled=false;try{var next=await load();if(!alive())return;processes=next.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(p=>p,StringComparer.OrdinalIgnoreCase).ToArray();Apply();Refresh.ToolTip="Список обновлён · процессов: "+processes.Length;}catch(Exception){if(alive())Refresh.ToolTip="Не удалось обновить список. Повторите попытку.";}finally{refreshing=false;if(alive())Refresh.IsEnabled=List.IsEnabled;}}
 }
}
