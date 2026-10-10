using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed partial class MainForm {
  async Task<bool> SaveZapretPresetSelection(string profileId,RouteList[] lists,RoutePreset[] presets,int[] selected){
   if(zapretCancellation!=null||setupCancellation!=null||discordVoiceChecking||connecting||warpCancellation!=null||byetubeCancellation!=null){Toast("Дождитесь завершения текущей операции");return false;}
   var selectedIds=new HashSet<string>(selected.Where(n=>n<lists.Length).Select(n=>lists[n].Id));
   var added=new List<RouteList>();
   foreach(int n in selected.Where(n=>n>=lists.Length)){
    var preset=presets[n-lists.Length];
    var route=new RouteList{Name=preset.Name,Text=await PresetStorage.Load(core,preset),SourceUrls=PresetStorage.Sources(preset).ToList(),PresetNames=new List<string>{preset.Name},Target="zapret",ZapretProfileId=profileId,MatchMode="addresses"};
    ZapretScope.Validate(route);added.Add(route);
   }
   if(added.Count>1)added=new List<RouteList>{RouteSources.Merge(added)};
   if(!await OfferStopTun())return false;
   return await ChangeActiveProfiles(()=>{
    var profile=state.ZapretProfiles.FirstOrDefault(p=>p.Id==profileId);if(profile==null)throw new InvalidOperationException("Профиль уже удалён.");
    foreach(var id in selectedIds){var route=state.Lists.FirstOrDefault(l=>l.Id==id);if(route==null)throw new InvalidOperationException("Список уже удалён. Повторите выбор.");if(route.Target=="zapret"&&route.ZapretProfileId!=profileId)throw new InvalidOperationException("Список уже назначен другому профилю. Переназначьте его в маршрутизации.");}
    foreach(var route in state.Lists.Where(l=>lists.Any(old=>old.Id==l.Id))){
     if(selectedIds.Contains(route.Id)){route.Target="zapret";route.ZapretProfileId=profileId;route.Enabled=true;}
     else if(route.Target=="zapret"&&route.ZapretProfileId==profileId){route.Target="direct";route.ZapretProfileId=null;}
    }
    state.Lists.AddRange(added);
    if(ZapretRoutes.Lists(state,profileId).Length==0)profile.Enabled=false;
   },()=>{});
  }
  void ZapretBlue(Panel body){
   ZapretLegacyBlue(body);var family=body.Controls.Find("zapretCheckFamilies",true).First();var services=body.Controls.Find("zapretCheckServices",true).First();var start=body.Controls.Find("zapretAutoPick",true).First();var results=body.Controls.Find("zapretResults",true).First();var cancel=body.Controls.Find("zapretCancel",true).First();var status=body.Controls.Find("zapretStatus",true).First();foreach(Control control in body.Controls)control.Visible=false;
   foreach(var control in new[]{family,services,start,results,cancel,status}){body.Controls.Add(control);control.Anchor=AnchorStyles.Top|AnchorStyles.Left;control.Visible=control!=cancel;}
   SyncProfileHotkeys();var profiles=state.ZapretProfiles.ToArray();var lists=state.Lists.ToArray();var presets=RoutePresets.Groups.Where(p=>!p.OutsideRussia).ToArray();
   var grid=(GlassGrid)Grid();grid.Name="zapretOutputTable";grid.SetBounds(0,0,body.Width,42+Math.Max(1,(body.Height-326)/54)*54+2);grid.RowTemplate.Height=54;foreach(string c in new[]{"Профиль / выход","Стратегия","Включён","Пресеты","Горячая клавиша"})grid.Columns.Add(c,c);grid.Columns[0].FillWeight=100;grid.Columns[1].FillWeight=140;grid.Columns[2].FillWeight=65;grid.Columns[3].FillWeight=150;grid.Columns[4].AutoSizeMode=DataGridViewAutoSizeColumnMode.None;grid.Columns[4].Width=218;body.Controls.Add(grid);
   var fields=new List<Tuple<int,int,Control>>();bool busy=false;
   for(int i=0;i<profiles.Length;i++){var p=profiles[i];int row=grid.Rows.Add(ZapretStrategy.CleanLabel(p.Name),"","","");grid.Rows[row].Tag=p;
    var catalog=ZapretCatalog.Load(zapret.DirectoryPath).Where(x=>x.Available).ToArray();var strategyPick=new GlassPicker{Name="zapretOutputStrategy_"+p.Id,FlatSurface=true};strategyPick.SetItems(new[]{"Выберу позже"}.Concat(catalog.Select(x=>x.ShortName)));strategyPick.ItemHint=n=>n>0?catalog[n-1].Name:"";strategyPick.SelectedIndex=String.IsNullOrEmpty(p.Settings.Strategy)?0:1+Array.FindIndex(catalog,x=>x.Id==p.Settings.Strategy);var colorReport=StrategyReport();strategyPick.ItemColor=colorIndex=>colorIndex<=0?VerdictColor(ServiceAccess.Unknown):ProfileStrategyColor(colorReport,p,catalog[colorIndex-1].Id);strategyPick.SurfaceColor=()=>VerdictFill(strategyPick.ItemColor(strategyPick.SelectedIndex));grid.Controls.Add(strategyPick);fields.Add(Tuple.Create(row,1,(Control)strategyPick));strategyPick.MouseWheel+=(s,e)=>grid.ShiftRows(e.Delta>0?-3:3);strategyPick.SelectedIndexChanged+=async(s,e)=>{if(busy)return;if(zapretCancellation!=null||setupCancellation!=null||discordVoiceChecking){Toast("Дождитесь завершения проверки");return;}busy=true;grid.Enabled=false;try{var selected=strategyPick.SelectedIndex<=0?null:catalog[strategyPick.SelectedIndex-1];await ChangeActiveProfiles(()=>{p.Settings.Strategy=selected==null?"":selected.Id;p.Settings.Family=selected==null?"":selected.Family;if(selected==null)p.Enabled=false;},()=>{});}catch(Exception ex){GlassNotice.Show(this,ex.Message,"Стратегия не изменена");}finally{busy=false;if(!grid.IsDisposed)grid.Enabled=true;if(!IsDisposed&&page=="zapret")ShowPage("zapret");}};
    var toggle=new GlassToggle{Name="zapretOutputToggle_"+p.Id,Text="",Checked=p.Enabled};grid.Controls.Add(toggle);fields.Add(Tuple.Create(row,2,(Control)toggle));toggle.CheckedChanged+=async(s,e)=>{if(busy)return;busy=true;grid.Enabled=false;try{await SetProfileEnabled(p.Id,toggle.Checked,false);}finally{busy=false;if(!grid.IsDisposed)grid.Enabled=true;if(!IsDisposed&&Visible&&page=="zapret"&&!body.IsDisposed)ShowPage("zapret");}};
    var pick=new GlassPicker{Name="zapretOutputPresets_"+p.Id,MultiSelect=true,FlatSurface=true,EmptySelectionText="Выберите пресеты",SelectionCaption="Выбрано"};pick.SetItems(lists.Select(l=>ZapretStrategy.CleanLabel(l.Name)).Concat(presets.Select(x=>"Пресет: "+x.DisplayName)));pick.ItemSource=sourceIndex=>sourceIndex<lists.Length?RouteSources.SourceLabel(lists[sourceIndex]):presets[sourceIndex-lists.Length].SourceLabel;pick.ItemHint=n=>n<lists.Length?"Ваш сохранённый список: "+lists[n].Name:presets[n-lists.Length].Description;for(int n=0;n<lists.Length;n++)if(lists[n].Target=="zapret"&&lists[n].ZapretProfileId==p.Id)pick.SetChecked(n,true);pick.CheckedItemsChanged+=async(s,e)=>{if(busy)return;var selected=pick.SelectedIndices;busy=true;grid.Enabled=false;try{await SaveZapretPresetSelection(p.Id,lists,presets,selected);}catch(Exception ex){GlassNotice.Show(this,ex.Message,"Пресеты не сохранены");}finally{busy=false;if(!grid.IsDisposed)grid.Enabled=true;if(!IsDisposed&&page=="zapret"&&!body.IsDisposed)ShowPage("zapret");}};pick.MouseWheel+=(s,e)=>grid.ShiftRows(e.Delta>0?-3:3);grid.Controls.Add(pick);fields.Add(Tuple.Create(row,3,(Control)pick));
    var hotkey=B((hotkeyErrors.ContainsKey(p.Id)?"! ":"")+(p.Hotkey==null?"Не назначена":p.Hotkey.Replace(" + ","+")),async(s,e)=>{if(!busy)await EditProfileHotkey(p.Id);});((ModernButton)hotkey).FlatSurface=true;hotkey.Font=new Font(Font.FontFamily,14f,FontStyle.Regular,GraphicsUnit.Pixel);hotkey.Name="zapretHotkey_"+p.Id;grid.Controls.Add(hotkey);fields.Add(Tuple.Create(row,4,(Control)hotkey));
   }
   Action arrange=()=>{foreach(var item in fields){var r=grid.GetCellDisplayRectangle(item.Item2,item.Item1,false);item.Item3.Visible=r.Top>=grid.ColumnHeadersHeight&&r.Bottom<=grid.Height;if(item.Item3.Visible){item.Item3.SetBounds(r.Left+6,r.Top+7,Math.Max(30,r.Width-24),r.Height-14);item.Item3.BringToFront();}}};grid.Scroll+=(s,e)=>{arrange();if(grid.IsHandleCreated&&!grid.IsDisposed)grid.BeginInvoke(arrange);};grid.Resize+=(s,e)=>arrange();grid.ColumnWidthChanged+=(s,e)=>arrange();
   int y=grid.Bottom+12;var add=B("Новый профиль",async(s,e)=>{if(!busy&&await EditZapretProfile(null))ShowPage("zapret");},true);add.SetBounds(0,y,180,38);body.Controls.Add(add);var edit=B("Изменить",async(s,e)=>{if(!busy&&grid.CurrentRow!=null&&await EditZapretProfile(grid.CurrentRow.Tag as ZapretProfile))ShowPage("zapret");});edit.SetBounds(192,y,140,38);body.Controls.Add(edit);
   // Strategies, presets and enabled state are saved by their controls.
   var remove=B("Удалить",async(s,e)=>{if(busy||grid.CurrentRow==null)return;var p=grid.CurrentRow.Tag as ZapretProfile;if(state.ZapretProfiles.Count<2||state.Lists.Any(l=>l.Target=="zapret"&&l.ZapretProfileId==p.Id)){Toast("Сначала переназначьте его списки; один профиль должен остаться");return;}if(await ChangeActiveProfiles(()=>state.ZapretProfiles.Remove(p),()=>{})){EnsureZapretProfiles();ShowPage("zapret");}});remove.SetBounds(body.Width-124,y,124,38);body.Controls.Add(remove);
   var title=L("Начать проверку",14,true);title.SetBounds(0,y+52,body.Width,30);body.Controls.Add(title);var duration=L("Проверка займёт около 10 минут.",10,true,Color.FromArgb(255,220,110));duration.SetBounds(220,y+52,body.Width-220,30);body.Controls.Add(duration);family.SetBounds(0,y+90,(body.Width-16)/2,40);services.SetBounds((body.Width+16)/2,y+90,(body.Width-16)/2,40);start.SetBounds(0,body.Height-44,220,40);cancel.SetBounds(232,body.Height-44,140,40);results.SetBounds(body.Width-240,body.Height-44,240,40);status.SetBounds(0,y+138,body.Width,Math.Max(28,body.Height-y-188));arrange();
  }
 }
}
