using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
namespace SplifyWin {
 public static class RoutePresetSelection {
  public static RoutePreset[] Attached(RouteList route,RoutePreset[] presets){var names=route.PresetNames??new List<string>();var urls=RouteSources.Urls(route);return presets.Where(p=>names.Contains(p.Name)||(!String.IsNullOrEmpty(p.OriginalName)&&names.Contains(p.OriginalName))||PresetStorage.Sources(p).All(urls.Contains)).ToArray();}
  public static async Task<RouteList> Build(NetworkCore core,RouteList route,RoutePreset[] selected,bool keepCurrent){
   var json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};var result=json.Deserialize<RouteList>(json.Serialize(route));
   if(selected.Any(p=>p.OutsideRussia)){
    if(selected.Length!=1)throw new InvalidOperationException("«Все кроме РФ» выбирается отдельно от сервисов.");
    if(route.Target!="proxy"&&!WarpOutputs.IsTarget(route.Target))throw new InvalidOperationException("Для «Все кроме РФ» сначала выберите выход VPN или WARP.");
    var outside=await OutsideRussiaPreset.Create(core);outside.Id=route.Id;outside.Name=route.Name;outside.Target=route.Target;outside.Enabled=route.Enabled;return outside;
   }
   if(route.InvertAddresses&&keepCurrent)throw new InvalidOperationException("Для замены «Все кроме РФ» снимите отметку «Текущие записи / исключения».");
   var attached=Attached(route,RoutePresets.Groups);var presetLines=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
   foreach(var preset in attached.Where(p=>!p.OutsideRussia))foreach(var line in (await PresetStorage.Load(core,preset)).Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries))presetLines.Add(line.Trim());
   var baseLines=(route.Text??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Where(line=>!presetLines.Contains(line.Trim()));
   string manual=keepCurrent?String.Join("\n",baseLines):"";if(keepCurrent&&!String.IsNullOrWhiteSpace(route.CustomText))manual+="\n"+route.CustomText;
   var texts=new List<string>();if(!String.IsNullOrWhiteSpace(manual))texts.Add(manual);foreach(var preset in selected)texts.Add(await PresetStorage.Load(core,preset));
   result.Text=String.Join("\n",texts.SelectMany(t=>t.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)).Select(l=>l.Trim()).Distinct(StringComparer.OrdinalIgnoreCase));
   if(RouteCompiler.Parse(result.Text).Values.Sum(v=>v.Count)==0)throw new InvalidOperationException("Выберите хотя бы один пресет или оставьте текущие записи.");
   var knownSources=new HashSet<string>(attached.SelectMany(PresetStorage.Sources));result.SourceUrls=selected.SelectMany(PresetStorage.Sources).Concat(keepCurrent?RouteSources.Urls(route).Where(u=>!knownSources.Contains(u)):new string[0]).Distinct().ToList();result.SourceUrl=null;
   result.PresetNames=selected.Select(p=>p.Name).Concat(keepCurrent?(route.PresetNames??new List<string>()).Where(n=>!attached.Any(p=>p.Name==n||p.OriginalName==n)):new string[0]).Distinct().ToList();result.CustomText=manual;
   if(route.InvertAddresses){result.InvertAddresses=false;result.ExcludedText=null;result.ExcludeRussia=result.ExcludeTorrents=false;result.BuiltinPreset=null;}
   return result;
  }
 }
 public sealed partial class MainForm {
  Action rebuildRouteFields;
  bool routeInlineBusy;
  void InstallRouteFields(){
   var grid=routesGrid;grid.Name="routeTable";grid.RowTemplate.Height=48;var fields=new List<Tuple<string,int,GlassPicker>>();
   Action arrange=()=>{if(grid.IsDisposed)return;foreach(var field in fields){var row=grid.Rows.Cast<DataGridViewRow>().FirstOrDefault(r=>r.Tag is RouteList&&((RouteList)r.Tag).Id==field.Item1);if(row==null){field.Item3.Visible=false;continue;}var rect=grid.GetCellDisplayRectangle(field.Item2,row.Index,false);bool visible=rect.Top>=grid.ColumnHeadersHeight&&rect.Bottom<=grid.ClientSize.Height&&rect.Width>30;field.Item3.Visible=visible;if(visible){field.Item3.SetBounds(rect.Left+4,rect.Top+5,Math.Max(20,rect.Width-12),rect.Height-10);field.Item3.BringToFront();}}};
   Func<RouteList,GlassPicker,int,GlassPicker> add=(route,pick,column)=>{grid.Controls.Add(pick);fields.Add(Tuple.Create(route.Id,column,pick));pick.MouseWheel+=(s,e)=>((GlassGrid)grid).ShiftRows(e.Delta>0?-3:3);return pick;};
   rebuildRouteFields=()=>{
    if(grid.IsDisposed)return;foreach(var field in fields)field.Item3.Dispose();fields.Clear();var presets=RoutePresets.Groups;
    var targets=new List<string>{"proxy","direct","block","byetube"};var names=new List<string>{"VPN / прокси","Напрямую","Блокировать","ByeTube"};
    foreach(var p in state.ZapretProfiles){targets.Add("zapret:"+p.Id);names.Add("Zapret → "+ZapretStrategy.CleanLabel(p.Name));}foreach(var p in WarpOutputs.Profiles(state)){targets.Add(WarpOutputs.Tag(p));names.Add("WARP → "+p.Name);}
    foreach(DataGridViewRow row in grid.Rows){row.Height=48;var route=row.Tag as RouteList;if(route==null)continue;
     var status=add(route,new GlassPicker{Name="routeStatus_"+route.Id,FlatSurface=true},0);status.SetItems(new[]{"Вкл","Выкл"});status.SelectedIndex=route.Enabled?0:1;status.ItemColor=i=>i==0?Color.FromArgb(110,240,160):Color.FromArgb(255,125,145);status.SurfaceColor=()=>status.SelectedIndex==0?Color.FromArgb(28,74,52):Color.FromArgb(80,35,48);status.SelectedIndexChanged+=async(s,e)=>{if(status.IsDisposed)return;bool enabled=status.SelectedIndex==0;await ChangeInlineRoute(route.Id,r=>{r.Enabled=enabled;return Task.FromResult(r);});};
     var scope=add(route,new GlassPicker{Name="routePresets_"+route.Id,FlatSurface=true,MultiSelect=true,EmptySelectionText="Выберите пресеты",SelectionCaption=ZapretStrategy.CleanLabel(route.Name)},1);
     scope.SetItems(new[]{"Текущие записи / исключения"}.Concat(presets.Select(p=>p.Name)));scope.ItemSource=i=>i==0?RouteSources.SourceLabel(route):presets[i-1].SourceLabel;scope.ItemHint=i=>i==0?"Сохранённые адреса, приложения и ручные дополнения":presets[i-1].Description;
     var attached=RoutePresetSelection.Attached(route,presets);scope.SetChecked(0,true);for(int i=0;i<presets.Length;i++)scope.SetChecked(i+1,attached.Contains(presets[i]));scope.SelectionTextOverride=()=>{var actual=scope.SelectedIndices.Where(n=>n>0).ToArray();return actual.Length>1?"Несколько":actual.Length==1?presets[actual[0]-1].Name:ZapretStrategy.CleanLabel(route.Name);};scope.CheckedItemsChanged+=async(s,e)=>{var indices=scope.SelectedIndices;await ChangeInlineRoute(route.Id,r=>RoutePresetSelection.Build(core,r,indices.Where(i=>i>0).Select(i=>presets[i-1]).ToArray(),indices.Contains(0)));};
     var output=add(route,new GlassPicker{Name="routeOutput_"+route.Id,FlatSurface=true,EmptySelectionText="Выход удалён"},4);var routeTargets=targets.ToList();var routeNames=names.ToList();if(route.Target=="tgws"){routeTargets.Add("tgws");routeNames.Add("Telegram WS");}output.SetItems(routeNames);output.SelectedIndex=routeTargets.IndexOf(route.Target=="zapret"?"zapret:"+route.ZapretProfileId:route.Target);output.ItemHint=i=>routeTargets[i];output.SelectedIndexChanged+=async(s,e)=>{if(output.SelectedIndex<0)return;string target=routeTargets[output.SelectedIndex];await ChangeInlineRoute(route.Id,r=>{r.Target=target.StartsWith("zapret:")?"zapret":target;r.ZapretProfileId=target.StartsWith("zapret:")?target.Substring(7):null;return Task.FromResult(r);});};if(grid.Columns.Contains("routeDns")){var dns=add(route,new GlassPicker{Name="routeDns_"+route.Id,FlatSurface=true},7);dns.SetItems(new[]{ExitDnsRouting.Label(state,route),"Изменить DNS…"});dns.SelectedIndex=0;dns.Enabled=route.Target!="tgws"&&route.Target!="block";dns.ItemHint=i=>"DNS для доменных списков в TUN. Для IP не требуется; условия по приложениям и собственный DoH браузера не гарантируются.";dns.SelectedIndexChanged+=(s,e)=>{if(dns.SelectedIndex==1){dns.SelectedIndex=0;EditRouteDns(route);}};}
    }arrange();
   };
   grid.Scroll+=(s,e)=>arrange();grid.Resize+=(s,e)=>arrange();grid.ColumnWidthChanged+=(s,e)=>arrange();grid.Disposed+=(s,e)=>{foreach(var f in fields)f.Item3.Dispose();fields.Clear();if(routesGrid==grid)rebuildRouteFields=null;};
   grid.CellDoubleClick+=(s,e)=>{if(e.RowIndex>=0&&e.ColumnIndex==2&&!routeInlineBusy)EditRoute(grid.Rows[e.RowIndex].Tag as RouteList);};
   grid.CellContentClick+=async(s,e)=>{
    if(e.RowIndex<0||(e.ColumnIndex!=5&&e.ColumnIndex!=6)||routeInlineBusy)return;
    bool tcp=e.ColumnIndex==5;
    var route=grid.Rows[e.RowIndex].Tag as RouteList;if(route==null)return;
    await ChangeInlineRoute(route.Id,r=>{
     bool enabled=tcp?r.GameTcpEnabled:r.GameUdpEnabled;
     if(!enabled){
      if(r.Target!="zapret")throw new InvalidOperationException("Для Game Filter сначала выберите выход Zapret → профиль.");
      var profile=state.ZapretProfiles.FirstOrDefault(p=>p.Id==r.ZapretProfileId);
      zapret.Prepare();var strategy=profile==null?null:ZapretCatalog.Load(zapret.DirectoryPath).FirstOrDefault(p=>p.Id==profile.Settings.Strategy);
      if(strategy==null||!strategy.GameAvailable)throw new InvalidOperationException("Выберите для этого профиля стратегию Flowseal с доступной игровой частью (например ALT12).");
      var settings=new ZapretSettings{ScopeText=r.Text,MatchMode=r.MatchMode,GameFilterTcp=tcp,GameFilterUdp=!tcp};
      zapret.BuildArguments(settings,strategy,System.IO.Path.Combine(core.DataRoot,"game-filter-validation"));
     }
     r.GameFilterTcp=tcp?!enabled:r.GameTcpEnabled;r.GameFilterUdp=tcp?r.GameUdpEnabled:!enabled;r.GameFilter=false;return Task.FromResult(r);
    });
   };
   rebuildRouteFields();
  }
  async Task ChangeInlineRoute(string id,Func<RouteList,Task<RouteList>> prepare){
   if(routeInlineBusy)return;var owner=routesGrid;var panel=owner==null?null:owner.Parent;var json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};
   routeInlineBusy=true;if(panel!=null)panel.Enabled=false;
   try{
    if(connecting||zapretCancellation!=null||setupCancellation!=null||warpCancellation!=null||discordVoiceChecking||byetubeCancellation!=null)throw new InvalidOperationException("Дождитесь завершения текущей операции.");
    var current=state.Lists.FirstOrDefault(r=>r.Id==id);if(current==null)throw new InvalidOperationException("Маршрут уже удалён.");var snapshot=json.Serialize(current);var staged=await prepare(json.Deserialize<RouteList>(snapshot));
    RouteCompiler.Compile(staged,"direct");ZapretRoutes.Validate(staged,state.ZapretProfiles);
    if(!await OfferStopTun())return;if(IsDisposed||owner==null||owner.IsDisposed)return;
    bool saved=await ChangeActiveProfiles(()=>{int index=state.Lists.FindIndex(r=>r.Id==id);if(index<0||json.Serialize(state.Lists[index])!=snapshot)throw new InvalidOperationException("Маршрут изменился во время выбора. Повторите изменение.");state.Lists[index]=staged;},()=>{});
    if(saved)Toast(core.Running?"Маршрут сохранён. Для VPN переподключитесь.":"Маршрут сохранён.");
   }catch(Exception ex){if(!IsDisposed)GlassNotice.Show(this,ex.Message,"Маршрут не изменён");}
   finally{routeInlineBusy=false;if(panel!=null&&!panel.IsDisposed)panel.Enabled=true;if(owner!=null&&!owner.IsDisposed&&routesGrid==owner)RefreshRoutes();}
  }
 }
}
