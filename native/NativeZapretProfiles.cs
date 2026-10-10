using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
  public sealed partial class MainForm {
    async Task<bool> ChangeActiveProfiles(Action change,Action refresh,bool fullRestart=false){
      if(profileChangeBusy){Toast("Дождитесь сохранения профилей");return false;}profileChangeBusy=true;
      try{
      if(zapretCancellation!=null||setupCancellation!=null||discordVoiceChecking||connecting||warpCancellation!=null){Toast("Дождитесь завершения текущей операции");return false;}
      bool running=zapret.Running;
      var serializer=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};string backup=serializer.Serialize(state);Exception failure=null;
      var oldServices=state.ZapretProfiles.SelectMany(p=>p.Settings.Hosts??new List<HostsCandidate>()).Select(h=>h.Service).Distinct().ToArray();byte[] hostsBefore=null,hostsAfter=null;bool cleared=false;Task rollback=null;
      try{
       if(running){await Task.Run(()=>zapret.Stop());if(fullRestart){WriteLog("Перезапуск Zapret целиком: прежний движок остановлен; применяем переключение профиля");await Task.Delay(300);}}change();var active=ZapretRoutes.Active(state);
       var newServices=state.ZapretProfiles.SelectMany(p=>p.Settings.Hosts??new List<HostsCandidate>()).Select(h=>h.Service).Distinct().ToArray();var removed=oldServices.Except(newServices).Where(id=>id=="instagram"||id=="chatgpt").ToArray();
       if(removed.Length>0){hostsBefore=await Task.Run(()=>HostsRepair.ReadHostsFile(HostsEditor.PathName));hostsAfter=hostsBefore;foreach(var id in removed)hostsAfter=HostsEditor.Compose(hostsAfter,"",id);if(!hostsBefore.SequenceEqual(hostsAfter)){if(!await ConfirmChange("Убрать из hosts записи отключённых комбинаций: "+String.Join(", ",removed)+"? Другие записи сохранятся; создадим бэкап.","Убрать записи hosts"))throw new OperationCanceledException();await HostsRepair.ApplyAsync(store.Root,HostsEditor.Managed(hostsAfter),hostsBefore);cleared=true;}}
       if(running&&active.Length>0){await StartSavedZapretProfiles(CancellationToken.None);if(cleared)foreach(var group in active.SelectMany(p=>p.Settings.Hosts??new List<HostsCandidate>()).GroupBy(h=>h.Service))hostsAfter=HostsEditor.Compose(hostsAfter,HostsCandidates.Entries(group),group.Key);}
       store.Save(state);Toast(running?"Изменения сохранены · Zapret автоматически перезапущен":"Профили сохранены");SyncTray();
      }
      catch(Exception ex){WriteLog("Перезапуск профилей Zapret не выполнен: "+ex.ToString());zapret.Stop();state=serializer.Deserialize<ClientState>(backup);if(cleared)rollback=Task.Run(()=>HostsRepair.Apply(store.Root,HostsEditor.Managed(hostsBefore),hostsAfter,false));store.Save(state);failure=ex;}
      if(rollback!=null)try{await rollback;}catch(Exception restore){WriteLog("Не удалось откатить hosts: "+restore.ToString());}
      if(failure!=null){if(running)try{await StartSavedZapretProfiles(CancellationToken.None);}catch(Exception ex){WriteLog("Не удалось восстановить профили: "+ex.Message);}if(!(failure is OperationCanceledException))GlassNotice.Show(this,failure.Message,"Профили Zapret");}
      refresh();blueDashboard.Invalidate();SyncProfileHotkeys();return failure==null;
      }finally{profileChangeBusy=false;}
    }
    void EnsureZapretProfiles(){
      if(state.ZapretProfiles==null)state.ZapretProfiles=new List<ZapretProfile>();state.ZapretProfiles.RemoveAll(x=>x==null);
      if(state.ZapretProfiles.Count==0)state.ZapretProfiles.Add(new ZapretProfile{Name="Основной",Enabled=state.Zapret!=null&&!String.IsNullOrEmpty(state.Zapret.Strategy),Settings=state.Zapret??new ZapretSettings()});
      var ids=new HashSet<string>();foreach(var profile in state.ZapretProfiles){if(profile.Id==null||!System.Text.RegularExpressions.Regex.IsMatch(profile.Id,@"^[a-f0-9]{32}$")||!ids.Add(profile.Id)){profile.Id=Guid.NewGuid().ToString("N");ids.Add(profile.Id);}if(profile.Settings==null)profile.Settings=new ZapretSettings();}
      var current=state.ZapretProfiles.FirstOrDefault(x=>x.Id==state.SelectedZapretProfileId)??state.ZapretProfiles[0];state.SelectedZapretProfileId=current.Id;state.Zapret=current.Settings;
      if(!state.ZapretRoutingMigrated){ZapretRoutes.Migrate(state);store.Save(state);}
    }
    ZapretProfile CurrentZapretProfile(){EnsureZapretProfiles();return state.ZapretProfiles.First(x=>x.Id==state.SelectedZapretProfileId);}
    void SaveZapretSettings(ZapretSettings settings){var profile=CurrentZapretProfile();profile.Settings=settings;state.Zapret=settings;store.Save(state);}
    async Task StartSavedZapretProfiles(CancellationToken token){EnsureZapretProfiles();var active=ZapretRoutes.Active(state);var mapped=active.SelectMany(p=>p.Settings.Hosts??new List<HostsCandidate>()).ToArray();byte[] before=null,after=null;bool written=false;if(mapped.Length>0){before=await Task.Run(()=>HostsRepair.ReadHostsFile(HostsEditor.PathName));after=before;foreach(var group in mapped.GroupBy(h=>h.Service))after=HostsEditor.Compose(after,HostsCandidates.Entries(group),group.Key);if(!before.SequenceEqual(after)){if(!await ConfirmChange("Применить hosts из проверенной комбинации вместе с Zapret? Меняется только блок MCRF, остальные записи сохранятся. Создадим бэкап. ChatGPT использует стороннее SNI-реле с проверкой TLS.","Применить hosts + Zapret","Проверенная комбинация"))throw new OperationCanceledException();token.ThrowIfCancellationRequested();await HostsRepair.ApplyAsync(store.Root,HostsEditor.Managed(after),before);written=true;}}Exception failure=null;try{await zapret.StartProfiles(active,token);}catch(Exception ex){failure=ex;}if(failure!=null){if(written)await HostsRepair.ApplyAsync(store.Root,HostsEditor.Managed(before),after);throw failure;}}
    void ZapretProfilesBlue(){
      EnsureZapretProfiles();var box=Box();box.Dock=DockStyle.Fill;content.Controls.Add(box);
      var help=L("У каждого профиля — свои списки и стратегия. Включённые профили работают вместе в одном движке. При пересечении списков применяется первый подходящий профиль сверху.",10,false,Muted);help.AutoSize=false;help.SetBounds(22,16,box.Width-44,62);help.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(help);
      var grid=Grid();grid.Name="zapretProfilesGrid";grid.SetBounds(22,90,box.Width-44,box.Height-244);grid.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;
      foreach(var pair in new[]{new[]{"name","Профиль"},new[]{"enabled","Запускать"},new[]{"strategy","Стратегия"},new[]{"scope","Списки / область"}})grid.Columns.Add(pair[0],pair[1]);grid.Columns[0].FillWeight=90;grid.Columns[1].FillWeight=70;grid.Columns[2].FillWeight=120;grid.Columns[3].FillWeight=160;box.Controls.Add(grid);
      var name=new TextBox{Name="zapretProfileName",BackColor=GlassFieldPanel.InputColor,ForeColor=Ink,BorderStyle=BorderStyle.None,Font=Font};name.SetBounds(22,box.Height-139,230,32);name.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;name.Visible=false;var hint=L("Изменение активного профиля предлагает остановку и применяет обновлённые профили. Списки задаются в маршрутизации.",9,false,Muted);hint.AutoSize=false;hint.SetBounds(22,box.Height-183,box.Width-44,35);hint.Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(hint);
      Func<ZapretProfile> selected=()=>grid.CurrentRow==null?null:grid.CurrentRow.Tag as ZapretProfile;
      Action refresh=()=>{string id=selected()==null?state.SelectedZapretProfileId:selected().Id;grid.Rows.Clear();foreach(var profile in state.ZapretProfiles){int row=grid.Rows.Add(profile.Name,profile.Enabled?"Да":"Нет",String.IsNullOrEmpty(profile.Settings.Strategy)?"Не выбрана":profile.Settings.Strategy,String.Join(", ",ZapretRoutes.Lists(state,profile.Id).Select(l=>l.Name)));grid.Rows[row].Tag=profile;if(profile.Id==id)grid.CurrentCell=grid.Rows[row].Cells[0];}var p=selected();name.Text=p==null?"":p.Name;};
      grid.SelectionChanged+=(s,e)=>{var profile=selected();if(profile!=null)name.Text=profile.Name;};
      var rename=B("Переименовать",(s,e)=>{var p=selected();if(p==null||String.IsNullOrWhiteSpace(name.Text))return;p.Name=name.Text.Trim();store.Save(state);refresh();});rename.SetBounds(266,box.Height-145,168,37);rename.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;rename.Visible=false;
      var enabled=B("Вкл / выкл профиль",async(s,e)=>{var p=selected();if(p==null)return;await ChangeActiveProfiles(()=>p.Enabled=!p.Enabled,refresh);});enabled.SetBounds(446,box.Height-145,196,37);enabled.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(enabled);
      var up=B("Выше",async(s,e)=>{var p=selected();int index=state.ZapretProfiles.IndexOf(p);if(index<1)return;await ChangeActiveProfiles(()=>{state.ZapretProfiles.RemoveAt(index);state.ZapretProfiles.Insert(index-1,p);},refresh);});up.SetBounds(654,box.Height-145,95,37);up.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(up);
      var add=B("Новый профиль",async(s,e)=>{if(await EditZapretProfile(null))refresh();},true);add.Name="zapretProfileAdd";add.SetBounds(22,box.Height-58,174,38);add.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(add);
      var edit=B("Изменить профиль",async(s,e)=>{var p=selected();if(p!=null&&await EditZapretProfile(p))refresh();});edit.Name="zapretProfileEdit";edit.SetBounds(208,box.Height-58,201,38);edit.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(edit);
      var remove=B("Удалить",async(s,e)=>{var p=selected();if(p==null)return;if(!await OfferStopZapret())return;if(state.Lists.Any(l=>ZapretRoutes.IsZapret(l)&&l.ZapretProfileId==p.Id)){Toast("Сначала переназначьте или удалите его списки в маршрутизации");return;}if(state.ZapretProfiles.Count<2){Toast("Оставьте хотя бы один профиль");return;}state.ZapretProfiles.Remove(p);reportLoaded=false;zapretReport=null;EnsureZapretProfiles();store.Save(state);refresh();});remove.SetBounds(421,box.Height-58,112,38);remove.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(remove);
      var back=B("Назад",(s,e)=>ShowPage("zapret"));back.SetBounds(box.Width-132,box.Height-58,110,38);back.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;box.Controls.Add(back);refresh();
    }
    string ScopeDescription(ZapretSettings settings){var ids=settings.ScopeSources;if(ids==null||ids.Count==0)return (settings.ScopeText??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Length+" записей";return String.Join(", ",ids.Select(id=>id=="custom"?"Свои записи":id=="youtube"?"YouTube":id=="services"?"Все сервисы":state.Lists.FirstOrDefault(x=>"list:"+x.Id==id)==null?"Удалённый список":state.Lists.First(x=>"list:"+x.Id==id).Name));}
  }
}
