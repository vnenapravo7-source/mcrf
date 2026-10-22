using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  ListBox ProcessList(string[] processes){var list=new ListBox{Name="processList",ItemsSource=processes,Background=(System.Windows.Media.Brush)FindResource("CardSurface"),Margin=new Thickness(0,6,0,10)};ScrollViewer.SetHorizontalScrollBarVisibility(list,ScrollBarVisibility.Disabled);var factory=new FrameworkElementFactory(typeof(CellPresenter));factory.SetValue(CellPresenter.CreateProperty,new Func<object,UIElement>(o=>CellText(Convert.ToString(o))));list.ItemTemplate=new DataTemplate{VisualTree=factory};return list;}
  async Task GlobalApplicationExclusions(){
   var processes=await Task.Run(()=>ApplicationExclusions.Running());var window=Dialog("Глобальное исключение приложений",920,700);var panel=new DockPanel{Margin=new Thickness(24)};var header=new StackPanel();header.Children.Add(Text("Выберите работающие приложения или впишите имя EXE. Исключения действуют для всех правил."));
   DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);var list=ProcessList(processes);bool closed=false;window.Closed+=(s,e)=>closed=true;var picker=new RunningProcessPicker(list,processes,true,()=>Task.Run(()=>ApplicationExclusions.Running()),()=>!closed);picker.Search.Name="exclusionSearch";
   var manual=new TextBox{Name="exclusionManual",Text=String.Join("\n",session.State.ExcludedApplications??new System.Collections.Generic.List<string>()),AcceptsReturn=true,Height=100,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
   var footer=new StackPanel();footer.Children.Add(Button("Добавить выбранные процессы",()=>manual.Text=AppendRouteText(manual.Text,String.Join("\n",picker.Selected))));footer.Children.Add(Field("Имена EXE · по одному на строку",manual));footer.Children.Add(Toolbar(Button("Сохранить",()=>window.DialogResult=true),Button("Отмена",()=>window.Close())));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(picker);window.Content=panel;if(window.ShowDialog()!=true)return;
   var values=ApplicationExclusions.Normalize(manual.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Concat(picker.Selected)).ToList();await RouteChange(()=>session.State.ExcludedApplications=values);
  }
  async Task<string[]> ChooseRuleApplications(Window owner){var processes=await Task.Run(()=>ApplicationExclusions.Running());if(!owner.IsVisible)return null;var window=Dialog("Запущенные приложения",800,600);window.Owner=owner;bool closed=false;window.Closed+=(s,e)=>closed=true;var picker=new RunningProcessPicker(ProcessList(processes),processes,true,()=>Task.Run(()=>ApplicationExclusions.Running()),()=>!closed);var panel=new DockPanel{Margin=new Thickness(24)};var actions=Toolbar(Button("Добавить выбранные",()=>window.DialogResult=true),Button("Отмена",()=>window.Close()));DockPanel.SetDock(actions,Dock.Bottom);panel.Children.Add(actions);panel.Children.Add(picker);window.Content=panel;return window.ShowDialog()==true?ApplicationExclusions.Normalize(picker.Selected):null;}
 }
}
