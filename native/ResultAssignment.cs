using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
namespace SplifyWin {
  public sealed partial class MainForm {
    async Task<bool> AssignServiceStrategy(ZapretCheckReport report,ZapretCheckRow row,ZapretService service){
      if(report==null||row==null||!report.Rows.Contains(row)||String.IsNullOrEmpty(row.StrategyId)||row.Checking)throw new InvalidOperationException("Нет завершённой проверки стратегии");
      if(zapretCancellation!=null||setupCancellation!=null||discordVoiceChecking)throw new InvalidOperationException("Дождитесь завершения проверки");
      string id=service.Id=="discord-voice"?"discord":service.Id;
      var canonical=ZapretChecks.Services.FirstOrDefault(s=>s.Id==id);if(canonical==null)throw new InvalidOperationException("Неизвестный сервис");
      EnsureZapretProfiles();
      var routes=state.Lists.Where(l=>l.BuiltinPreset=="setup-service:"+id||l.Name.Equals(canonical.Name,StringComparison.OrdinalIgnoreCase)||l.Name.Equals("Мастер · "+canonical.Name,StringComparison.OrdinalIgnoreCase)||(l.PresetNames??new List<string>()).Any(n=>n.Equals(canonical.Name,StringComparison.OrdinalIgnoreCase)||n.StartsWith(canonical.Name+" · ",StringComparison.OrdinalIgnoreCase))).ToArray();
      if(routes.Length==0)return await AssignCheckedProfile(report,row,canonical.Name);
      var profiles=routes.Where(l=>l.Target=="zapret").Select(l=>state.ZapretProfiles.FirstOrDefault(p=>p.Id==l.ZapretProfileId)).Where(p=>p!=null).Distinct().ToList();
      int other=state.Lists.Count(l=>!routes.Contains(l)&&l.Target=="zapret"&&profiles.Any(p=>p.Id==l.ZapretProfileId));
      if(other>0&&!await ConfirmChange("Выход общий: смена стратегии затронет ещё "+other+" списков. Применить «"+row.Name+"» к этому выходу?","Применить","Общий выход Zapret"))return false;
      if(!await OfferStopTun())return false;
      var json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};string backup=json.Serialize(state);bool running=zapret.Running;Exception failure=null;
      try{
        if(running)zapret.Stop();
        if(routes.Any(l=>l.Target!="zapret"||!profiles.Any(p=>p.Id==l.ZapretProfileId))){
          var created=new ZapretProfile{Name=canonical.Name,Settings=ZapretChecks.CopySettings(report.ApplySettings??report.Settings),Enabled=true};state.ZapretProfiles.Add(created);profiles.Add(created);
          foreach(var route in routes.Where(l=>l.Target!="zapret"||!profiles.Any(p=>p.Id==l.ZapretProfileId))){route.Target="zapret";route.ZapretProfileId=created.Id;route.Enabled=true;}
        }
foreach(var profile in profiles){var selectedHosts=HostsCandidates.ForResult(row,id);profile.Settings.Hosts=(profile.Settings.Hosts??new List<HostsCandidate>()).Where(h=>h.Service!=id).Concat(selectedHosts).ToList();profile.Settings.Strategy=row.StrategyId;profile.Settings.Family=row.Family;if(service.Id=="discord-voice")DiscordVoiceScores.Configure(profile.Settings,row);profile.Enabled=true;}
        await StartSavedZapretProfiles(CancellationToken.None);store.Save(state);report.LastAppliedId=row.StrategyId;report.LastAppliedAt=DateTime.Now;PersistZapretReport(report);
        zapretResultsNotice=canonical.CheckName+" → "+row.Name+". Привязки сохранены; Zapret перезапущен.";WriteLog(zapretResultsNotice);blueDashboard.Invalidate();SyncTray();return true;
      }catch(Exception ex){zapret.Stop();state=json.Deserialize<ClientState>(backup);store.Save(state);failure=ex;}
      if(running)try{await StartSavedZapretProfiles(CancellationToken.None);}catch(Exception ex){WriteLog("Восстановление Zapret: "+ex.Message);}
      System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();return false;
    }
    sealed class ResultScope {public string ProfileId,Name;public int[] Indices;}
    Task<ResultScope> ChooseResultScope(ZapretCheckRow row,RouteList[] lists,RoutePreset[] presets,string presetFilter=null){
      var done=new TaskCompletionSource<ResultScope>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(760,ClientSize.Width-40),440)};veil.Controls.Add(popup);
      Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
      Add(popup,L("Конфиг → профиль и списки",18,true),24,20);var info=L(row.Name+"\nВыбранные списки будут переназначены этому профилю. Остальные профили сохранятся.",10,false,Muted);info.AutoSize=false;info.SetBounds(24,70,popup.Width-48,64);popup.Controls.Add(info);
      var profiles=state.ZapretProfiles.ToArray();var picker=new GlassPicker{Name="resultProfile",Location=new Point(24,149),Width=popup.Width-48};picker.SetItems(new[]{"Создать новый профиль"}.Concat(profiles.Select(p=>p.Name)));picker.SelectedIndex=Math.Max(0,Array.FindIndex(profiles,p=>p.Id==state.SelectedZapretProfileId)+1);popup.Controls.Add(picker);
      var name=T();name.Name="resultProfileName";name.Text="Профиль · "+row.Name;name.BorderStyle=BorderStyle.None;name.BackColor=GlassFieldPanel.InputColor;var nameHost=new GlassFieldPanel{Name="resultProfileNameSurface"};nameHost.SetBounds(24,205,popup.Width-48,38);name.SetBounds(12,9,nameHost.Width-24,24);nameHost.Controls.Add(name);popup.Controls.Add(nameHost);Action nameState=()=>nameHost.Visible=picker.SelectedIndex==0;picker.SelectedIndexChanged+=(s,e)=>nameState();nameState();
      var scope=new GlassPicker{Name="resultPresets",MultiSelect=true,EmptySelectionText="Выберите пресеты",Location=new Point(24,263),Width=popup.Width-48};scope.SetItems(lists.Select(l=>ZapretStrategy.CleanLabel(l.Name)).Concat(presets.Select(p=>"Пресет: "+p.DisplayName)));scope.ItemSource=i=>i<lists.Length?RouteSources.SourceLabel(lists[i]):presets[i-lists.Length].SourceLabel;popup.Controls.Add(scope);
      Action selectBound=()=>{foreach(int checkedIndex in scope.SelectedIndices)scope.SetChecked(checkedIndex,false);if(picker.SelectedIndex>0){var id=profiles[picker.SelectedIndex-1].Id;for(int i=0;i<lists.Length;i++)if(lists[i].Target=="zapret"&&lists[i].ZapretProfileId==id)scope.SetChecked(i,true);}};picker.SelectedIndexChanged+=(s,e)=>selectBound();selectBound();if(presetFilter!=null){picker.SelectedIndex=0;name.Text=presetFilter;for(int i=0;i<presets.Length;i++)if(presets[i].Name.StartsWith(presetFilter,StringComparison.OrdinalIgnoreCase))scope.SetChecked(lists.Length+i,true);}
      var ok=B("Назначить и включить",(s,e)=>{if(scope.SelectedIndices.Length==0){Toast("Выберите хотя бы один список или пресет");return;}if(picker.SelectedIndex==0&&String.IsNullOrWhiteSpace(name.Text)){Toast("Введите название профиля");return;}done.TrySetResult(new ResultScope{ProfileId=picker.SelectedIndex==0?null:profiles[picker.SelectedIndex-1].Id,Name=name.Text.Trim(),Indices=scope.SelectedIndices});veil.Dispose();},true);ok.SetBounds(24,364,280,40);popup.Controls.Add(ok);var creationHint=L("Профиль создаётся только после нажатия кнопки ниже.",10,false,Muted);creationHint.AutoSize=false;creationHint.SetBounds(24,318,popup.Width-48,30);popup.Controls.Add(creationHint);Action caption=()=>{ok.Text=picker.SelectedIndex==0?"Создать и включить":"Применить к профилю";creationHint.Visible=picker.SelectedIndex==0;};picker.SelectedIndexChanged+=(s,e)=>caption();caption();
      var cancel=B("Отмена",(s,e)=>veil.Dispose());cancel.SetBounds(popup.Width-190,364,166,40);popup.Controls.Add(cancel);veil.Disposed+=(s,e)=>done.TrySetResult(null);Controls.Add(veil);veil.BringToFront();layout();return done.Task;
    }
    async Task<bool> AssignCheckedProfile(ZapretCheckReport report,ZapretCheckRow row,string presetFilter=null){
      if(row==null||row.StrategyId==null||report==null||!report.Rows.Contains(row))throw new InvalidOperationException("Нет проверенного конфига");
      if(zapretCancellation!=null||setupCancellation!=null)throw new InvalidOperationException("Дождитесь завершения проверки");
      EnsureZapretProfiles();var lists=state.Lists.ToArray();var presets=RoutePresets.Groups.Where(p=>!p.OutsideRussia).ToArray();var selection=await ChooseResultScope(row,lists,presets,presetFilter);if(selection==null)return false;
      var staged=new List<RouteList>();foreach(int i in selection.Indices.Where(i=>i>=lists.Length)){var preset=presets[i-lists.Length];var text=await PresetStorage.Load(core,preset);var route=new RouteList{Name=preset.Name,Text=text,SourceUrls=PresetStorage.Sources(preset).ToList(),PresetNames=new List<string>{preset.Name},Target="zapret",MatchMode="addresses"};RouteCompiler.Compile(route,"direct");staged.Add(route);}
      if(staged.Count>1)staged=new List<RouteList>{RouteSources.Merge(staged)};
      foreach(int i in selection.Indices.Where(i=>i<lists.Length))if(!state.Lists.Contains(lists[i]))throw new InvalidOperationException("Список изменён. Повторите выбор.");
      bool wasRunning=zapret.Running;if(!await OfferStopTun())return false;
      var json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};string backup=json.Serialize(state);Exception failure=null;
      try{
        if(wasRunning)zapret.Stop();
        var profile=selection.ProfileId==null?new ZapretProfile{Name=selection.Name}:state.ZapretProfiles.First(p=>p.Id==selection.ProfileId);if(selection.ProfileId==null)state.ZapretProfiles.Add(profile);
profile.Settings=ZapretChecks.CopySettings(report.ApplySettings??report.Settings);profile.Settings.Hosts=row.Services.Where(r=>ZapretChecks.Services.Any(s=>s.Id==r.Id&&s.Name==presetFilter)).SelectMany(r=>r.Hosts??new List<HostsCandidate>()).ToList();profile.Settings.Strategy=row.StrategyId;profile.Settings.Family=row.Family;if(presetFilter=="Discord")DiscordVoiceScores.Configure(profile.Settings,row);profile.Enabled=true;
        foreach(int i in selection.Indices.Where(i=>i<lists.Length)){lists[i].Target="zapret";lists[i].ZapretProfileId=profile.Id;lists[i].Enabled=true;}foreach(var route in staged){route.ZapretProfileId=profile.Id;state.Lists.Add(route);}
        profile.Settings=ZapretRoutes.Resolve(state,profile);await StartSavedZapretProfiles(CancellationToken.None);store.Save(state);
        var saved=json.Deserialize<ZapretCheckReport>(json.Serialize(report));saved.ProfileId=profile.Id;saved.LastAppliedId=row.StrategyId;saved.LastAppliedAt=DateTime.Now;PersistZapretReport(saved);
        zapretResultsNotice="Применён «"+row.Name+"» → «"+profile.Name+"». Назначено списков: "+selection.Indices.Length+". Можно назначить следующий конфиг, не закрывая таблицу.";WriteLog(zapretResultsNotice);blueDashboard.Invalidate();return true;
      }catch(Exception ex){zapret.Stop();state=json.Deserialize<ClientState>(backup);store.Save(state);failure=ex;}
      if(wasRunning)try{await StartSavedZapretProfiles(CancellationToken.None);}catch(Exception ex){WriteLog("Не удалось восстановить Zapret: "+ex.Message);}
      System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();return false;
    }
  }
}
