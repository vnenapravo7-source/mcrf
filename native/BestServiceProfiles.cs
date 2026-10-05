using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed class ServiceProfileRecommendation {public ZapretService Service;public ZapretCheckRow Row;public int Milliseconds;}
 public static class BestServiceProfiles {
  public static ServiceProfileRecommendation[] Choose(ZapretCheckReport report){
   if(report==null)return new ServiceProfileRecommendation[0];var answers=new List<ServiceProfileRecommendation>();
   foreach(var service in ZapretChecks.Services.Where(ZapretChecks.CanCheckZapret).Concat(new[]{DiscordVoiceScores.Service})){
    var candidate=report.Rows.Where(r=>!r.Checking&&!String.IsNullOrEmpty(r.StrategyId)&&String.IsNullOrEmpty(r.Error)&&r.Access(service.Id)==ServiceAccess.Available).OrderBy(r=>service.Id=="discord-voice"&&r.Services.First(s=>s.Id==service.Id).PingQuality=="high"?1:0).ThenBy(r=>{var result=r.Services.First(s=>s.Id==service.Id);return result.Milliseconds>0?result.Milliseconds:Int32.MaxValue;}).ThenBy(r=>r.StrategyId,StringComparer.Ordinal).FirstOrDefault();
    if(candidate!=null)answers.Add(new ServiceProfileRecommendation{Service=service,Row=candidate,Milliseconds=candidate.Services.First(s=>s.Id==service.Id).Milliseconds});
   }return answers.ToArray();
  }
 }
 public sealed partial class MainForm {
  async Task ShowBestServiceProfiles(ZapretCheckReport report){
   if(zapretCancellation!=null||setupCancellation!=null||discordVoiceChecking){Toast("Дождитесь завершения проверки");return;}var recommendations=BestServiceProfiles.Choose(report);if(recommendations.Length==0){Toast("Нет подтверждённых стратегий для создания профилей");return;}
   var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(900,ClientSize.Width-40),Math.Min(620,ClientSize.Height-32))};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Add(popup,L("Лучший профиль для каждого сервиса",17,true),24,18);
   var grid=(GlassGrid)Grid();grid.Name="bestServiceProfiles";grid.SetBounds(24,70,popup.Width-48,popup.Height-212);grid.RowTemplate.Height=42;foreach(string c in new[]{"Сервис","Стратегия","Ответ, мс"})grid.Columns.Add(c,c);grid.Columns[0].FillWeight=100;grid.Columns[1].FillWeight=160;grid.Columns[2].FillWeight=55;popup.Controls.Add(grid);foreach(var item in recommendations)grid.Rows.Add(item.Service.Name,item.Row.Name,item.Milliseconds>0?item.Milliseconds.ToString():"—");
   var selection=new GlassPicker{Name="bestServiceSelection",MultiSelect=true,Location=new Point(24,popup.Height-128),Width=popup.Width-48,EmptySelectionText="Выберите сервисы"};selection.SetItems(recommendations.Select(r=>r.Service.Name));for(int i=0;i<recommendations.Length;i++)selection.SetChecked(i,recommendations[i].Service.Id!="discord");popup.Controls.Add(selection);
   bool busy=false;var create=B("Создать профили",async(s,e)=>{if(busy)return;var selected=selection.SelectedIndices.Select(i=>recommendations[i]).Where(item=>item.Service.Id!="discord").ToArray();if(selected.Length==0){Toast("Выберите сервис; для Discord создаётся профиль голоса после голосовой проверки");return;}busy=true;((Control)s).Enabled=false;try{
var stagedProfiles=new List<ZapretProfile>();var stagedRoutes=new List<RouteList>();foreach(var item in selected){var profile=new ZapretProfile{Name=item.Service.Name,Enabled=true,Settings=ManualServiceChecks.AppliedSettings(report,item.Service.Id,item.Row.StrategyId)};profile.Settings.Strategy=item.Row.StrategyId;profile.Settings.Family=item.Row.Family;if(item.Service.Id=="discord-voice")DiscordVoiceScores.Configure(profile.Settings,item.Row);var merged=await ServicePresetRoutes.Create(item.Service,"zapret",profile.Id,constructorPresetDownload??core.DownloadTextAsync);ZapretScope.Validate(merged);stagedRoutes.Add(merged);stagedProfiles.Add(profile);}
    if(!await OfferStopTun())return;bool saved=await ChangeActiveProfiles(()=>{state.ZapretProfiles.InsertRange(0,stagedProfiles);state.Lists.InsertRange(0,stagedRoutes);},()=>{});if(saved){done.TrySetResult(true);veil.Dispose();ShowPage("zapret");}
   }catch(Exception ex){GlassNotice.Show(this,ex.Message,"Профили не созданы");}finally{busy=false;if(!((Control)s).IsDisposed)((Control)s).Enabled=true;}},true);create.Name="bestServiceCreate";create.SetBounds(24,popup.Height-64,220,38);popup.Controls.Add(create);var cancel=B("Отмена",(s,e)=>{if(!busy)veil.Dispose();});cancel.SetBounds(popup.Width-170,popup.Height-64,146,38);popup.Controls.Add(cancel);veil.Disposed+=(s,e)=>done.TrySetResult(false);Controls.Add(veil);veil.BringToFront();layout();await done.Task;
  }
 }
}
