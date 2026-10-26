using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  RouteList CurrentSavedRoute(string id){var route=session.State.Lists.FirstOrDefault(r=>r.Id==id);if(route==null)throw new InvalidOperationException("Правило уже удалено. Откройте список маршрутов заново.");return route;}
  Task ChangeSavedRoute(string id,Action<RouteList> change,bool refresh=true){return RouteChange(()=>change(CurrentSavedRoute(id)),refresh);}
  static Style CenteredHeader(){var style=new Style(typeof(DataGridColumnHeader),(Style)Application.Current.FindResource(typeof(DataGridColumnHeader)));style.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty,HorizontalAlignment.Center));style.Setters.Add(new Setter(Control.PaddingProperty,new Thickness(0)));return style;}
  internal static string AppendRouteText(string original,string added){return String.Join("\n",(original??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Concat((added??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)).Select(s=>s.Trim()).Where(s=>s.Length>0).Distinct(StringComparer.OrdinalIgnoreCase));}
  internal static RouteList MergeToOutput(RouteList[] selected,string output){return new RouteList{Name=String.Join(" + ",selected.Select(r=>r.Name).Distinct()),Target=output.StartsWith("zapret:")?"zapret":output,ZapretProfileId=output.StartsWith("zapret:")?output.Substring(7):null,Text=selected.Aggregate("",(text,r)=>AppendRouteText(text,r.Text)),CustomText=selected.Aggregate("",(text,r)=>AppendRouteText(text,r.CustomText)),SourceUrls=selected.SelectMany(RouteSources.Urls).Distinct().ToList(),PresetNames=selected.SelectMany(r=>r.PresetNames??new List<string>()).Distinct().ToList(),PresetEntries=selected.Where(r=>r.PresetEntries!=null).SelectMany(r=>r.PresetEntries).GroupBy(p=>p.Key).ToDictionary(g=>g.Key,g=>g.Aggregate("",(text,p)=>AppendRouteText(text,p.Value))),Enabled=true,MatchMode="any",OriginalEngine=selected.All(r=>r.OriginalEngine),GameFilterTcp=selected.Any(r=>r.GameTcpEnabled),GameFilterUdp=selected.Any(r=>r.GameUdpEnabled),Dns=new ExitDnsSettings{Provider="Общий DNS"}};}
  async Task MergeRouteSelection(RouteList[] selected){var window=Dialog("Объединить маршруты",760,ActualWidth<500?360:330);var panel=new StackPanel{Margin=new Thickness(20)};panel.Children.Add(Text("Выберите общий выход. Записи и источники объединятся без повторов. Новый маршрут будет включён, условие — приложения ИЛИ адреса, DNS — общий; отдельные исключения исходных правил не переносятся."));var output=new ComboBox{ItemsSource=RouteOutputs(),SelectedIndex=0};panel.Children.Add(Field("Куда отправить объединённый список",output));var actions=Toolbar(Button("Объединить",()=>window.DialogResult=true),Button("Отмена",()=>window.DialogResult=false));if(ActualWidth<500)actions.Margin=new Thickness(0);panel.Children.Add(actions);window.Content=panel;if(window.ShowDialog()!=true)return;string[] ids=selected.Select(r=>r.Id).Distinct().ToArray();string target=((Choice)output.SelectedItem).Key;await RouteChange(()=>{var current=ids.Select(CurrentSavedRoute).ToArray();if(current.Length<2)throw new InvalidOperationException("Выберите хотя бы два правила.");var merged=MergeToOutput(current,target);int index=current.Min(r=>session.State.Lists.IndexOf(r));foreach(var route in current)session.State.Lists.Remove(route);session.State.Lists.Insert(index,merged);});}
 }
}

