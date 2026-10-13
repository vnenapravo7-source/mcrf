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
   var search=new TextBox{Name="exclusionSearch"};header.Children.Add(Field("Поиск текущих процессов",search));DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);var list=ProcessList(processes);list.SelectionMode=SelectionMode.Extended;var selected=new System.Collections.Generic.HashSet<string>(StringComparer.OrdinalIgnoreCase);bool filtering=false;list.SelectionChanged+=(s,e)=>{if(filtering)return;foreach(string item in e.AddedItems)selected.Add(item);foreach(string item in e.RemovedItems)selected.Remove(item);};
   var manual=new TextBox{Name="exclusionManual",Text=String.Join("\n",session.State.ExcludedApplications??new System.Collections.Generic.List<string>()),AcceptsReturn=true,Height=100,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
   search.TextChanged+=(s,e)=>{filtering=true;try{list.ItemsSource=processes.Where(p=>p.IndexOf(search.Text,StringComparison.OrdinalIgnoreCase)>=0).ToArray();foreach(string item in list.Items)if(selected.Contains(item))list.SelectedItems.Add(item);}finally{filtering=false;}};var footer=new StackPanel();footer.Children.Add(Button("Добавить выбранные процессы",()=>manual.Text=AppendRouteText(manual.Text,String.Join("\n",selected))));footer.Children.Add(Field("Имена EXE · по одному на строку",manual));footer.Children.Add(Toolbar(Button("Сохранить",()=>window.DialogResult=true),Button("Отмена",()=>window.Close())));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(list);window.Content=panel;if(window.ShowDialog()!=true)return;
   var values=ApplicationExclusions.Normalize(manual.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Concat(selected)).ToList();await RouteChange(()=>session.State.ExcludedApplications=values);
  }
 }
}
