using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  sealed class SimplePresetRow {public RoutePreset Preset;public string Name{get{return Preset.DisplayName;}}public int Direction;}
  int simpleWarpTab;string simpleWarpId;
  static string SimpleRouteKey(string target){return "simple-constructor:"+target;}
  void SimplePresetPage(string target){body.Children.Add(Card(SimplePresetContent(target,false)));}
  // Restore the old single-exit GlassPicker multi-select pattern
  // (native/ServiceConstructor.cs constructorVpnServices; NativeClient.cs routePresets).
  // Keep the current recipe model: this is a visual change, not a reset.
  UIElement SimplePresetContent(string target,bool withHosts){
   var old=session.State.Lists.FirstOrDefault(r=>r.BuiltinPreset==SimpleRouteKey(target));bool warp=WarpOutputs.IsTarget(target);
   var panel=new DockPanel();var form=new StackPanel();
   form.Children.Add(Text(warp?"Пресеты WARP":"Один выход · VPN / прокси",21));
   form.Children.Add(Text("Отметьте нужные сервисы. Выбранное будет направляться через один выход; исключения — напрямую."));
   var outside=new CheckBox{Name="simpleOutsideRussia",Content=OutsideRussiaPreset.Name,IsChecked=old!=null&&(old.PresetNames??new List<string>()).Any(n=>n==OutsideRussiaPreset.Name||n=="Все кроме РФ")};var all=new CheckBox{Name="simpleAllInternet",Content="Весь интернет",IsChecked=old!=null&&old.Text=="0.0.0.0/0\n::/0"};
   var ru=new CheckBox{Name="simpleDirectRussia",Content="РФ — напрямую",IsChecked=old==null||old.ExcludeRussia};
   var torrents=new CheckBox{Name="simpleDirectTorrents",Content="Торренты — напрямую",IsChecked=old==null||old.ExcludeTorrents};
   var rows=RoutePresets.Groups.Where(p=>!p.OutsideRussia&&!p.AdBlock).OrderBy(p=>p.Name.Contains("Популярные сервисы")?0:1).ThenBy(p=>p.Name).Select(p=>new SimplePresetRow{Preset=p,Direction=old!=null&&(old.PresetNames??new List<string>()).Contains(p.Name)?1:old!=null&&(old.DirectPresetNames??new List<string>()).Contains(p.Name)?2:0}).ToArray();
   Action refreshThrough=null,refreshDirect=null;
   var through=SimplePresetPicker(rows,new[]{all,outside},1,warp?"Через WARP":"Через VPN / прокси","simpleThroughPresets",()=>{if(refreshThrough!=null)refreshThrough();if(refreshDirect!=null)refreshDirect();},out refreshThrough);
   var direct=SimplePresetPicker(rows,new[]{ru,torrents},2,"Напрямую","simpleDirectPresets",()=>{if(refreshThrough!=null)refreshThrough();if(refreshDirect!=null)refreshDirect();},out refreshDirect);
   form.Children.Add(Columns(through,direct));
   var extra=new TextBox{Name="simplePresetExtra",Text=old==null?"":old.CustomTextDirection=="direct"?old.DirectCustomText??"":old.CustomText??"",AcceptsReturn=true,Height=65,MinHeight=65,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,ToolTip="Например: example.org или 192.0.2.0/24 · одна запись на строку"};
   var custom=new DockPanel{Margin=new Thickness(0,10,0,0)};var customHeading=new StackPanel();customHeading.Children.Add(Text("Свои домены и IP",18));var customDirection=new ComboBox{Name="simpleCustomDirection",ItemsSource=new[]{warp?"Через WARP":"Через VPN / прокси","Напрямую"},SelectedIndex=old!=null&&old.CustomTextDirection=="direct"?1:0,MaxWidth=260,HorizontalAlignment=HorizontalAlignment.Left};customHeading.Children.Add(Field("Куда направлять свои записи",customDirection));customHeading.Children.Add(Text("Необязательно · домены, IP или подсети, по одной записи на строку",12));DockPanel.SetDock(customHeading,Dock.Top);custom.Children.Add(customHeading);extra.Height=Double.NaN;extra.MinHeight=48;extra.VerticalContentAlignment=VerticalAlignment.Top;custom.Children.Add(extra);
   if(withHosts){var hosts=Button("hosts · настроить",SimpleWarpHosts);hosts.Name="simpleWarpHosts";form.Children.Add(hosts);}
   var save=AsyncButton("Применить пресеты",async()=>{await ApplySimpleRecipe(target,rows.Where(r=>r.Direction==1).Select(r=>r.Preset).Concat(outside.IsChecked==true?RoutePresets.Groups.Where(p=>p.OutsideRussia):new RoutePreset[0]).ToArray(),rows.Where(r=>r.Direction==2).Select(r=>r.Preset).ToArray(),all.IsChecked==true,ru.IsChecked==true,torrents.IsChecked==true,extra.Text,customDirection.SelectedIndex==1);status.Text="Пресеты сохранены для "+(warp?"WARP":"VPN / прокси")+".";});save.Name="applySimplePresets";Accent(save);save.Margin=new Thickness(0,14,0,0);DockPanel.SetDock(save,Dock.Bottom);panel.Children.Add(save);
   DockPanel.SetDock(form,Dock.Top);panel.Children.Add(form);panel.Children.Add(custom);return panel;
  }
  UIElement SimplePresetPicker(SimplePresetRow[] rows,CheckBox[] specials,int direction,string heading,string name,Action changed,out Action refresh){
   var panel=new StackPanel{Margin=new Thickness(4)};panel.Children.Add(Text(heading,18));
   var summary=new WrapPanel{Margin=new Thickness(0,12,0,0)};Button choose=null;
   choose=Button("Выбрать пресеты",()=>{var window=Dialog(heading+" · пресеты",700,570);var host=new DockPanel{Margin=new Thickness(24)};
    var search=new TextBox{Name="simplePresetSearch",Margin=new Thickness(0,0,0,12),ToolTip="Поиск по сервису или источнику"};
    var top=new StackPanel();top.Children.Add(Text("Найдите сервисы и отметьте нужные."));top.Children.Add(search);DockPanel.SetDock(top,Dock.Top);host.Children.Add(top);
    var checks=rows.Select(r=>new CheckBox{Content=r.Name,Tag=r,IsChecked=r.Direction==direction,Style=(Style)FindResource("LabeledPacketCheck"),ToolTip=r.Preset.Description,Margin=new Thickness(0,5,0,7)}).ToArray();
    var drafts=specials.Select(c=>new CheckBox{Name=c.Name,Content=c.Content,IsChecked=c.IsChecked,Style=(Style)FindResource("LabeledPacketCheck"),Margin=new Thickness(0,5,0,7)}).ToArray();var list=new StackPanel();foreach(var check in drafts.Concat(checks))list.Children.Add(check);
    search.TextChanged+=(s,e)=>{string query=search.Text.Trim();foreach(var check in drafts.Concat(checks)){var r=check.Tag as SimplePresetRow;string label=r==null?Convert.ToString(check.Content):r.Name+" "+r.Preset.SourceLabel;check.Visibility=query.Length==0||label.IndexOf(query,StringComparison.CurrentCultureIgnoreCase)>=0?Visibility.Visible:Visibility.Collapsed;}};
    var actions=Toolbar(Button("Готово",()=>window.DialogResult=true),Button("Отмена",()=>window.DialogResult=false));DockPanel.SetDock(actions,Dock.Bottom);host.Children.Add(actions);host.Children.Add(new ScrollViewer{Content=list,VerticalScrollBarVisibility=ScrollBarVisibility.Auto});window.Content=host;
    if(window.ShowDialog()!=true)return;for(int i=0;i<specials.Length;i++)specials[i].IsChecked=drafts[i].IsChecked;foreach(var check in checks){var r=(SimplePresetRow)check.Tag;if(check.IsChecked==true)r.Direction=direction;else if(r.Direction==direction)r.Direction=0;}changed();
   });choose.Name=name;choose.Style=(Style)FindResource("PickerAction");choose.Background=Brush("#28354F");choose.HorizontalContentAlignment=HorizontalAlignment.Left;panel.Children.Add(choose);panel.Children.Add(new ScrollViewer{Content=summary,MaxHeight=58,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled});
   refresh=()=>{var selected=specials.Where(c=>c.IsChecked==true).Select(c=>Convert.ToString(c.Content)).Concat(rows.Where(r=>r.Direction==direction).Select(r=>r.Name)).ToArray();choose.Content=selected.Length==0?"Выбрать пресеты":"Выбрано: "+selected.Length;summary.Children.Clear();foreach(var label in selected.Take(6)){summary.Children.Add(new Border{Background=Brush("#293A55"),CornerRadius=new CornerRadius(10),Padding=new Thickness(9,5,9,5),Margin=new Thickness(0,0,6,6),ToolTip=label,Child=new TextBlock{Text=label,MaxWidth=240,TextTrimming=TextTrimming.CharacterEllipsis,FontSize=12}});}if(selected.Length>6)summary.Children.Add(Text("И ещё "+(selected.Length-6)));if(selected.Length==0)summary.Children.Add(Text("Не выбрано",12));};refresh();var card=Card(panel);card.Background=Brush("#151F33");card.Name=name+"Card";return card;
  }
  Task ApplySimplePresets(string target,RoutePreset[] chosen,RoutePreset[] direct,bool all,bool ru,bool torrents,string extra){return ApplySimpleRecipe(target,chosen,direct,all,ru,torrents,extra,false);}
  async Task ApplySimpleRecipe(string target,RoutePreset[] chosen,RoutePreset[] direct,bool all,bool ru,bool torrents,string extra,bool customDirect){
   if(chosen.Any(p=>p.OutsideRussia)){all=true;ru=true;}
   string directExtra=customDirect?extra??"":"";extra=customDirect?"":extra;
   var needed=chosen.Concat(direct).GroupBy(p=>p.Name).Select(g=>g.First()).ToArray();var texts=await Task.WhenAll(needed.Select(p=>PresetStorage.Load(session.Core,p)));var loaded=needed.Select((p,i)=>new{p.Name,Text=texts[i]}).ToDictionary(p=>p.Name,p=>p.Text);string entries=all?"0.0.0.0/0\n::/0":String.Join("\n",chosen.Select(p=>loaded[p.Name]).Concat(new[]{extra??""}));if(String.IsNullOrWhiteSpace(entries))throw new InvalidOperationException("Выберите хотя бы один пресет или весь интернет.");
   var existing=session.State.Lists.FirstOrDefault(r=>r.BuiltinPreset==SimpleRouteKey(target));var route=new RouteList{Id=existing==null?Guid.NewGuid().ToString("N"):existing.Id,Name=target=="proxy"?"VPN / прокси · конструктор":"WARP · конструктор",BuiltinPreset=SimpleRouteKey(target),Target=target,Enabled=true,MatchMode="addresses",Text=entries,CustomText=extra??"",ExcludedText=String.Join("\n",direct.Select(p=>loaded[p.Name])),DirectPresetNames=direct.Select(p=>p.Name).ToList(),ExcludeRussia=ru,ExcludeTorrents=torrents,PresetNames=chosen.Select(p=>p.Name).ToList(),PresetEntries=chosen.ToDictionary(p=>p.Name,p=>loaded[p.Name]),SourceUrls=chosen.Where(p=>!p.OutsideRussia).SelectMany(PresetStorage.Sources).Distinct().ToList()};RouteCompiler.Compile(route,target);
   route.CustomTextDirection=customDirect?"direct":"proxy";route.DirectCustomText=directExtra;route.ExcludedText=String.Join("\n",new[]{route.ExcludedText,directExtra});if(!String.IsNullOrWhiteSpace(route.ExcludedText))RouteCompiler.Compile(new RouteList{Text=route.ExcludedText,Target="direct",MatchMode="addresses"},"direct");
   await session.ChangeConfiguration(()=>{int index=existing==null?0:session.State.Lists.IndexOf(existing);if(existing!=null)session.State.Lists.Remove(existing);session.State.Lists.Insert(Math.Max(0,index),route);});
  }
  void SimpleWarpPage(){
   var host=new DockPanel();var tabs=CompactTools(Button("Профили WARP",()=>{simpleWarpTab=0;Page("WARP");}),Button("Кастомный WARP · пресеты и hosts",()=>{simpleWarpTab=1;Page("WARP");}));DockPanel.SetDock(tabs,Dock.Top);host.Children.Add(tabs);
   if(simpleWarpTab==0)host.Children.Add(WarpWorkspace());else{var profiles=WarpOutputs.Profiles(session.State);if(profiles.Length==0){var empty=new StackPanel();empty.Children.Add(Text("Сначала добавьте профиль WARP на вкладке «Профили WARP»."));empty.Children.Add(Button("Добавить WARP",()=>{simpleWarpTab=0;Page("WARP");}));host.Children.Add(empty);}else{var selected=profiles.FirstOrDefault(n=>n.Id==simpleWarpId)??profiles[0];simpleWarpId=selected.Id;var selector=new ComboBox{Name="simpleWarpProfile",ItemsSource=profiles,DisplayMemberPath="Name",SelectedItem=selected,Margin=new Thickness(0,0,0,12)};selector.SelectionChanged+=(s,e)=>{var node=selector.SelectedItem as ServerNode;if(node!=null&&node.Id!=simpleWarpId){simpleWarpId=node.Id;Page("WARP");}};DockPanel.SetDock(selector,Dock.Top);host.Children.Add(selector);host.Children.Add(SimplePresetContent(WarpOutputs.Tag(selected),true));}}body.Children.Add(Card(host));
  }
  void SimpleWarpHosts(){var window=Dialog("Кастомный WARP · hosts",800,350);var panel=new DockPanel{Margin=new Thickness(24)};var close=Button("Готово",()=>window.Close());DockPanel.SetDock(close,Dock.Bottom);panel.Children.Add(close);var form=new StackPanel();form.Children.Add(Text("Записи hosts действуют для всей Windows. Выбор источников не меняет WARP-профиль; применение требует отдельного подтверждения."));form.Children.Add(RoutingHostsControls());panel.Children.Add(form);window.Content=panel;window.ShowDialog();}
 }
}
