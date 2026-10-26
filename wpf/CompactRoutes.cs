using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;

namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  void CompactRoutesPage(){
   CompactPageChrome("Расширенное управление");var layout=new DockPanel();var panel=new StackPanel();
   var exclusions=AsyncButton("Глобал. исключения",GlobalApplicationExclusions);exclusions.Name="compactGlobalExclusions";
   var network=Button("DNS & hosts",OpenGlobalNetworkDialog);network.Name="compactDnsHosts";panel.Children.Add(CompactButtonPair(exclusions,network));
   var defaults=DefaultRouteOutputs();var fallback=new ComboBox{Name="routeDefaultTarget",ItemsSource=defaults,SelectedItem=defaults.FirstOrDefault(item=>item.Key==DefaultRouteRouting.Requested(session.State))};
   panel.Children.Add(Field("Всё, что не в правилах",fallback));fallback.SelectionChanged+=async(s,e)=>await Run(()=>DefaultRouteChange(fallback.SelectedItem as Choice));
   var capture=AsyncButton("Захват процесса",CaptureProcess);capture.Name="compactCaptureProcess";var add=AsyncButton("Добавить правило",()=>RouteEditor(null));add.Name="compactAddRule";panel.Children.Add(CompactButtonPair(capture,add));
   var table=new DataGrid{Name="compactRulesTable",ItemsSource=RouteTableSnapshot(),AutoGenerateColumns=false,IsReadOnly=true,CanUserAddRows=false,CanUserDeleteRows=false,CanUserSortColumns=false,CanUserReorderColumns=false,SelectionMode=DataGridSelectionMode.Extended,SelectionUnit=DataGridSelectionUnit.FullRow,RowHeight=64,ColumnHeaderHeight=28,MinHeight=80,BorderThickness=new Thickness(0)};
   ScrollViewer.SetHorizontalScrollBarVisibility(table,ScrollBarVisibility.Disabled);ScrollViewer.SetVerticalScrollBarVisibility(table,ScrollBarVisibility.Auto);
   table.Columns.Add(CellColumn("✓",1,o=>{var route=(RouteList)o;var check=new CheckBox{Name="ruleEnabled_"+route.Id,IsChecked=route.Enabled,Style=(Style)FindResource("PacketCheck"),Margin=new Thickness(0),ToolTip="Правило включено"};check.Click+=async(s,e)=>{await Run(()=>ChangeSavedRoute(route.Id,r=>r.Enabled=check.IsChecked==true,false),()=>check.IsChecked=CurrentSavedRoute(route.Id).Enabled);check.IsChecked=CurrentSavedRoute(route.Id).Enabled;};return check;}));table.Columns[0].Width=32;
   table.Columns.Add(CellColumn("Правило / направление",1,o=>{var route=(RouteList)o;var cell=new StackPanel();cell.Children.Add(new TextBlock{Text=route.Name,TextTrimming=TextTrimming.CharacterEllipsis,FontSize=12,ToolTip=route.Name+" · записей: "+EntryCount(route.Text),Margin=new Thickness(0,0,0,3)});var outputs=RouteOutputs();var target=new ComboBox{ItemsSource=outputs,SelectedItem=outputs.FirstOrDefault(c=>c.Key==(route.Target=="zapret"?"zapret:"+route.ZapretProfileId:route.Target)),Margin=new Thickness(0),MinWidth=0,MinHeight=26,Height=26,FontSize=10,Padding=new Thickness(3),ToolTip="Куда направлять трафик"};target.SelectionChanged+=async(s,e)=>await Run(()=>ChangeSavedRoute(route.Id,r=>{var selected=target.SelectedItem as Choice;if(selected==null)return;r.Target=selected.Key.StartsWith("zapret:")?"zapret":selected.Key;r.ZapretProfileId=r.Target=="zapret"?selected.Key.Substring(7):null;if(r.Target!="zapret")r.OriginalEngine=false;}));cell.Children.Add(target);return cell;}));table.Columns[1].MinWidth=90;
   table.Columns.Add(CellColumn("DNS",1,o=>{var route=(RouteList)o;var button=(Button)RouteDnsPicker(route);button.Content=ManagementActionIcon("dns");button.ToolTip="DNS · "+ExitDnsRouting.Label(session.State,route);CompactRuleTextButton(button);button.IsEnabled=route.Target!="block"&&route.Target!="tgws";return button;}));table.Columns[2].Width=34;
   table.Columns.Add(CellColumn("Game",1,o=>{var route=(RouteList)o;var button=RouteGamePicker(route);button.Content=ManagementActionIcon("game");button.Name="compactRuleGame_"+route.Id;CompactRuleTextButton(button);button.ToolTip=route.Target=="zapret"?"Фильтр TCP / UDP":"Настройки TCP / UDP применяются для Zapret";return button;}));table.Columns[3].Width=34;
   table.Columns.Add(CellColumn("",1,o=>{var route=(RouteList)o;return CompactRuleIcon("edit","Изменить правило",()=>RouteEditor(route));}));table.Columns[4].Width=34;table.Columns[4].Header="Edit";
   table.Columns.Add(CellColumn("",1,o=>{var route=(RouteList)o;return CompactRuleIcon("remove","Удалить правило",()=>DeleteRoute(route));}));table.Columns[5].Width=34;table.Columns[5].Header="Del";
   var merge=AsyncButton("Объединить выбранные",async()=>{var routes=table.SelectedItems.Cast<RouteList>().OrderBy(r=>session.State.Lists.IndexOf(r)).ToArray();if(routes.Length<2)throw new InvalidOperationException("Выберите хотя бы два правила.");await MergeRouteSelection(routes);});merge.Name="compactRouteMerge";merge.Visibility=Visibility.Collapsed;StyleCompactButton(merge);table.SelectionChanged+=(s,e)=>merge.Visibility=table.SelectedItems.Count>=2?Visibility.Visible:Visibility.Collapsed;panel.Children.Add(merge);
   panel.Children.Add(Text(session.State.Lists.Count==0?"Сохранённых правил пока нет.":"Порядок — перетаскиванием. Ctrl — выбрать несколько правил.",10));DockPanel.SetDock(panel,Dock.Top);layout.Children.Add(panel);layout.Children.Add(table);InstallRouteDrag(table);ApplyCompactGridPalette(table);for(int i=0;i<table.Columns.Count;i++){var header=new Style(typeof(System.Windows.Controls.Primitives.DataGridColumnHeader),table.ColumnHeaderStyle);header.Setters.Add(new Setter(Control.PaddingProperty,new Thickness(i==1?4:0,0,0,0)));header.Setters.Add(new Setter(Control.FontSizeProperty,10d));header.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty,i==1?HorizontalAlignment.Left:HorizontalAlignment.Center));table.Columns[i].HeaderStyle=header;}body.Children.Add(layout);FinishCompactPagePalette();
  }
  async Task CompactRouteTransportChange(CheckBox check,string id,bool tcp){
   Action sync=()=>{var actual=session.State.Lists.FirstOrDefault(route=>route.Id==id);if(actual!=null)check.IsChecked=tcp?actual.GameTcpEnabled:actual.GameUdpEnabled;};if(busy){sync();return;}bool desired=check.IsChecked==true;await Run(async()=>{var actual=session.State.Lists.Single(route=>route.Id==id);var json=new System.Web.Script.Serialization.JavaScriptSerializer();var proposed=json.Deserialize<RouteList>(json.Serialize(actual));if(tcp)proposed.GameFilterTcp=desired;else proposed.GameFilterUdp=desired;if(proposed.Enabled)ZapretRoutes.Validate(proposed,session.State.ZapretProfiles);await ChangeSavedRoute(id,current=>{if(tcp)current.GameFilterTcp=desired;else current.GameFilterUdp=desired;},false);},sync);sync();
  }
  void CompactGameTemplates(){
   var panel=new StackPanel{Name="gameTemplatesPanel"};panel.Children.Add(Text("Шаблоны создают правило и профиль Zapret. Существующие маршруты не заменяются. Добавление само по себе не запускает движок.",12));
   foreach(string key in new[]{"alt11","alternative","arhekog"}){
    string kind=key;string name=kind=="alt11"?"Wardogs · ALT11":kind=="alternative"?"Wardogs Alternative":"Wardogs arhekog · точечный";
    string entries=kind=="alt11"?SteamGameTemplates.WardogsEntries:kind=="alternative"?SteamGameTemplates.WardogsAlternativeEntries:SteamGameTemplates.WardogsArchiveEntries;
    string detail=kind=="alt11"?"Flowseal ALT11 · TCP + UDP · Cloudflare DNS":kind=="alternative"?"Flowseal ALT11 · TCP + UDP · Google DNS":"Flowseal ALT9 · оригинальный движок · общий DNS";
    var content=new StackPanel();content.Children.Add(Text(detail,12));content.Children.Add(Text("Адресов: "+SteamGameTemplates.AddressCount(entries)+(kind=="arhekog"?" · автопауза в матче; защитное прямое правило":" · процесс игры ИЛИ адреса"),11));
    var feedback=Text("",11);feedback.Name="gameTemplateFeedback_"+kind;feedback.TextWrapping=TextWrapping.Wrap;feedback.TextTrimming=TextTrimming.CharacterEllipsis;feedback.MaxHeight=54;feedback.Visibility=Visibility.Collapsed;
    var existing=session.State.Lists.FirstOrDefault(r=>r.BuiltinPreset==GameTemplateKey(kind));
    var add=new Button{Name="gameTemplateAdd_"+kind,Content=existing==null?"Добавить в маршруты":"Уже добавлен",IsEnabled=existing==null,Margin=new Thickness(0,8,0,0)};
    if(existing!=null){feedback.Text="Сохранено: "+existing.Name;feedback.ToolTip=feedback.Text;feedback.Visibility=Visibility.Visible;}
    add.Click+=async(s,e)=>{
     if(busy||closing||closeRequested)return;add.IsEnabled=false;add.Content="Добавление…";feedback.Visibility=Visibility.Collapsed;
     try{await Run(async()=>{
      try{if(await AddGameTemplate(kind,session.Strategies)){RegisterHotkeys();RefreshAfterOperation("Маршрутизация","Шаблон «"+name+"» добавлен. Существующие правила сохранены.");}}
      catch(Exception ex){if(!closing&&!closeRequested){feedback.Text=JournalStyle.Redact(ex.Message);feedback.ToolTip=feedback.Text;feedback.Foreground=LogColor("ERROR");feedback.Visibility=Visibility.Visible;}throw;}
     },()=>{});}
     finally{if(!closing&&!closeRequested){bool added=session.State.Lists.Any(r=>r.BuiltinPreset==GameTemplateKey(kind));add.IsEnabled=!added;add.Content=added?"Уже добавлен":"Добавить в маршруты";}}
    };
    StyleCompactButton(add);content.Children.Add(add);content.Children.Add(feedback);panel.Children.Add(CompactSettingsCard(name,"игровой шаблон",content));
   }
   body.Children.Add(panel);
  }
 }
}
