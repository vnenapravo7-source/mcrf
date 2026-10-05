using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed class SetupAssignment {public string ServiceId,Output;public ZapretCheckRow Strategy;public List<HostsCandidate> Hosts;}
public sealed class SetupWarpChoice {public string Country,Protocol,SourceId;public bool ExcludeDme;public List<string> ServiceIds;}
 public sealed class SetupStagedPlan {public List<ZapretProfile> Profiles=new List<ZapretProfile>();public List<RouteList> Routes=new List<RouteList>();}
 public static class SetupServiceVerdicts {
  public static ServiceAccess Access(string id,IDictionary<string,SetupAssignment> assignments,ZapretCheckRow direct,ZapretCheckRow vpn,ISet<string> failed,ISet<string> unverified){
   if(unverified.Contains(id))return ServiceAccess.Unknown;
   if(failed.Contains(id))return ServiceAccess.Unavailable;
   SetupAssignment assignment;if(assignments==null||!assignments.TryGetValue(id,out assignment))return ServiceAccess.Unknown;
   if(assignment.Output=="direct")return direct==null?ServiceAccess.Unknown:direct.Access(id);
   if(assignment.Output=="warp")return vpn==null?ServiceAccess.Unknown:vpn.Access(id);
   if(assignment.Output=="zapret"||assignment.Output=="byetube")return assignment.Strategy==null?ServiceAccess.Unknown:assignment.Strategy.Access(id);
   return ServiceAccess.Unknown;
  }
 }
 public static class SetupAssignments {
public static SetupAssignment Recommend(ZapretService service,ZapretCheckReport report,ZapretCheckRow direct){var best=BestServiceProfiles.Choose(report).FirstOrDefault(r=>r.Service.Id==service.Id);return new SetupAssignment{ServiceId=service.Id,Output=ZapretChecks.WarpPreferred(service.Id)?"warp":direct!=null&&direct.Access(service.Id)==ServiceAccess.Available?"direct":best!=null?"zapret":"warp",Hosts=HostsCandidates.ForResult(best==null?null:best.Row,service.Id),Strategy=best==null?null:best.Row};}
  public static async Task<SetupStagedPlan> Stage(IEnumerable<ZapretService> services,IDictionary<string,string> destinations,IDictionary<string,SetupAssignment> assignments,ZapretCheckRow selected,ZapretCheckReport report,Func<string,Task<string>> download){
   var staged=new SetupStagedPlan();ZapretProfile rutorProfile=null;foreach(var service in services.Where(s=>s.Id!="spotify"&&destinations[s.Id]!="skip").OrderBy(s=>s.Id=="rutor"?0:1)){string destination=destinations[service.Id];if(String.IsNullOrEmpty(destination))throw new InvalidOperationException("Не выбран выход для "+service.Name);ZapretProfile profile=null;
if(destination=="zapret"){var strategy=(service.Id=="rutracker"||service.Id=="pornhub")&&rutorProfile!=null?(assignments==null?selected:assignments["rutor"].Strategy):assignments==null?selected:assignments[service.Id].Strategy;if(strategy==null)throw new InvalidOperationException("Не выбрана стратегия для "+service.Name);if((service.Id=="rutracker"||service.Id=="pornhub")&&rutorProfile!=null)profile=rutorProfile;else profile=new ZapretProfile{Name=service.Id=="discord"?"Мастер · Discord: голос":"Мастер · "+service.Name,Settings=ManualServiceChecks.AppliedSettings(report,service.Id,strategy.StrategyId),Enabled=true};profile.Settings.Strategy=strategy.StrategyId;profile.Settings.Family=strategy.Family;if(service.Id=="discord"){DiscordVoiceScores.Configure(profile.Settings,strategy);profile.Name=profile.Settings.DiscordVoiceEnabled?"Мастер · Discord: голос":"Мастер · Discord: интерфейс";}string hostScope=assignments!=null&&assignments[service.Id].Hosts!=null?"\n"+String.Join("\n",assignments[service.Id].Hosts.Select(h=>h.Address).Distinct()):"";profile.Settings.ScopeText=profile==rutorProfile?profile.Settings.ScopeText+"\n"+service.Domains:service.Domains+hostScope;if(!staged.Profiles.Contains(profile))staged.Profiles.Add(profile);if(service.Id=="rutor")rutorProfile=profile;}
var route=await ServicePresetRoutes.Create(service,destination,profile==null?null:profile.Id,download);route.Name=service.Id=="discord"?(profile!=null&&profile.Settings.DiscordVoiceEnabled?"Мастер · Discord: голос":"Мастер · Discord: интерфейс"):"Мастер · "+service.Name;route.BuiltinPreset="setup-service:"+service.Id;route.ExcludeRussia=route.ExcludeTorrents=WarpOutputs.IsTarget(destination)||destination=="proxy";RouteCompiler.Compile(route,"direct");staged.Routes.Add(route);
   }return staged;
  }
 }
 public sealed partial class MainForm {
  Task ShowSetupTelegram(CancellationToken token){
   var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(700,ClientSize.Width-40),300)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Add(popup,L("Telegram",18,true),24,20);
   var hint=L("Локальный Telegram-прокси уже создан и запущен. Нажмите «Подключить в Telegram» и подтвердите подключение в Telegram.",11,false,Muted);hint.AutoSize=false;hint.SetBounds(24,78,popup.Width-48,80);popup.Controls.Add(hint);
   var connect=B("Подключить в Telegram",(s,e)=>{try{System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(telegramBridge.SetupLink){UseShellExecute=true});}catch(Exception ex){Toast(ex.Message);}},true);connect.Name="setupTelegramConnect";connect.SetBounds(24,205,300,40);popup.Controls.Add(connect);
   var next=B("Продолжить",(s,e)=>veil.Dispose());next.SetBounds(popup.Width-200,205,176,40);popup.Controls.Add(next);veil.Disposed+=(s,e)=>done.TrySetResult(true);Controls.Add(veil);veil.BringToFront();layout();var registration=token.Register(()=>{done.TrySetCanceled();if(!veil.IsDisposed&&veil.IsHandleCreated)veil.BeginInvoke(new Action(()=>veil.Dispose()));});done.Task.ContinueWith(t=>registration.Dispose());return done.Task;
  }
  async Task<ServerNode> PrepareSetupWarp(SetupWarpChoice options,CancellationToken token,Action<string> progress){
   string accountId=Guid.NewGuid().ToString("N");ServerNode source=null;
   while(true){
    Exception failure=null;
    try{
     var saved=options.SourceId==null?null:state.Servers.FirstOrDefault(n=>n.Id==options.SourceId);
     if(source==null)source=saved==null?await WarpScout.GetOrCreate(core,token,progress,accountId):new System.Web.Script.Serialization.JavaScriptSerializer().Deserialize<ServerNode>(new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(saved));
     var picked=await PickWarpWithFallback(source,options.Protocol,options.ExcludeDme,token,progress,options.Country);
     return picked==null?null:picked.Node;
    }catch(OperationCanceledException){throw;}catch(Exception ex){failure=ex;}
    token.ThrowIfCancellationRequested();
    progress("WARP не подготовлен: "+JournalStyle.Redact(failure.Message)+" Сервисы через WARP ещё не проверены.");
if(!await ConfirmChange(JournalStyle.Redact(failure.Message)+"\n\nЭто не означает, что сервисы через него не работают. Повторить попытку? При отказе можно применить остальные назначения.","Повторить WARP","WARP · подготовка не завершена"))return null;
   }
  }
  Task ShowSetupServiceStatus(ZapretService[] services,IDictionary<string,SetupAssignment> assignments,ZapretCheckRow direct,ZapretCheckRow vpn,ISet<string> failed,ISet<string> unverified,CancellationToken token){
var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(800,ClientSize.Width-40),Math.Min(Math.Min(570,220+48*services.Count(s=>s.Id!="rutracker"&&s.Id!="pornhub")),ClientSize.Height-40))};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
   Add(popup,L(unverified.Count>0?"WARP не проверен":"Результаты сервисов",18,true),24,18);
   var hint=L(unverified.Count>0?"WARP не удалось подготовить. Жёлтые сервисы через него ещё не проверялись — необходимость VPN не подтверждена.":"Для красных сервисов рабочий способ без VPN не найден. Остальные назначения можно применить.",11,false,Muted);hint.AutoSize=false;hint.SetBounds(24,65,popup.Width-48,65);popup.Controls.Add(hint);
   var body=new FlowLayoutPanel{Name="setupServiceStatus",FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,BackColor=Color.Transparent};body.SetBounds(24,140,popup.Width-48,popup.Height-220);popup.Controls.Add(body);
   foreach(var service in services.Where(s=>s.Id!="rutracker"&&s.Id!="pornhub")){
    var access=SetupServiceVerdicts.Access(service.Id,assignments,direct,vpn,failed,unverified);
    string detail=unverified.Contains(service.Id)?"WARP не проверен":access==ServiceAccess.Available?"Работает":access==ServiceAccess.Unavailable?"Не работает без VPN":"Не подтверждено / пропущено";
    var label=L(service.CheckName+" · "+detail,11,true,VerdictColor(access));label.Name="setupServiceStatus_"+service.Id;label.AutoSize=false;label.Size=new Size(body.Width-26,42);body.Controls.Add(label);
   }
   var ok=B("Понятно",(s,e)=>veil.Dispose(),true);ok.SetBounds(popup.Width-185,popup.Height-60,160,38);popup.Controls.Add(ok);veil.Disposed+=(s,e)=>done.TrySetResult(true);Controls.Add(veil);veil.BringToFront();layout();var registration=token.Register(()=>{if(!veil.IsDisposed&&veil.IsHandleCreated)veil.BeginInvoke(new Action(()=>veil.Dispose()));done.TrySetCanceled();});done.Task.ContinueWith(t=>registration.Dispose());return done.Task;
  }
  int foreignWarpFailures;
  async Task<WarpPickResult> PickWarpWithFallback(ServerNode source,string protocol,bool excludeDme,CancellationToken token,Action<string> progress,string country){
   while(true){
    Exception failure=null;
    try{var result=await WarpScout.Pick(core,source,protocol,excludeDme,token,progress,country);if(country!="RU"&&country!="any")foreignWarpFailures=0;return result;}
    catch(OperationCanceledException){throw;}
    catch(Exception ex){failure=ex;}
    {
     token.ThrowIfCancellationRequested();progress("Выход не найден: "+JournalStyle.Redact(failure.Message));bool foreign=country!="RU"&&country!="any";
     if(foreign&&(foreignWarpFailures+=(failure is WarpScanExhaustedException?((WarpScanExhaustedException)failure).Attempts:1))>=3){if(!await ConfirmChange("Три попытки подбора иностранного WARP не дали подтверждённого выхода. Попробовать российский WARP? Геоблокировки он может не снять.","Попробовать WARP РФ","WARP · альтернативный выход"))return null;country="RU";excludeDme=false;}
     else if(!await ConfirmChange("Подходящий выход не подтверждён. Повторить подбор? Сохранённые маршруты не изменены.","Повторить подбор","WARP"))return null;
    }
   }
  }
  Task<Dictionary<string,SetupAssignment>> ChooseSetupDistribution(ZapretCheckReport report,ZapretService[] services,ZapretCheckRow direct,CancellationToken token){
   var done=new TaskCompletionSource<Dictionary<string,SetupAssignment>>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(1060,ClientSize.Width-40),Math.Min(650,ClientSize.Height-40))};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
   Add(popup,L("Сервисы, стратегии и выходы",18,true),24,18);var hint=L("Предложена лучшая подтверждённая стратегия каждого сервиса. Выход и маршрут будут созданы только при применении плана.",10,false,Muted);hint.AutoSize=false;hint.SetBounds(24,65,popup.Width-48,46);popup.Controls.Add(hint);
   var grid=(GlassGrid)Grid();grid.Name="setupDistribution";grid.SetBounds(24,118,popup.Width-48,popup.Height-208);grid.RowTemplate.Height=46;grid.Columns.Add("service","Сервис / маршрут");grid.Columns.Add("output","Выход / стратегия");grid.Columns[0].FillWeight=80;grid.Columns[1].FillWeight=170;popup.Controls.Add(grid);
   var choices=new Dictionary<string,SetupAssignment[]>();var pickers=new List<Tuple<int,GlassPicker>>();
foreach(var service in services.Where(v=>v.Id!="rutracker"&&v.Id!="pornhub")){var recommended=SetupAssignments.Recommend(service,report,direct);var options=new List<SetupAssignment>{new SetupAssignment{ServiceId=service.Id,Output="skip"},new SetupAssignment{ServiceId=service.Id,Output="direct"},new SetupAssignment{ServiceId=service.Id,Output="warp"}};if(ZapretChecks.CanCheckZapret(service))options.AddRange(report.Rows.Where(r=>!r.Checking&&!String.IsNullOrEmpty(r.StrategyId)&&String.IsNullOrEmpty(r.Error)&&r.Access(service.Id)==ServiceAccess.Available).OrderBy(r=>r.Services.First(s=>s.Id==service.Id).Milliseconds).Select(r=>new SetupAssignment{ServiceId=service.Id,Output="zapret",Strategy=r,Hosts=HostsCandidates.ForResult(r,service.Id)}));choices[service.Id]=options.ToArray();int row=grid.Rows.Add(service.Id=="discord"?"Discord: голос · проверим далее":service.CheckName,"");grid.Rows[row].Tag=service;
    var picker=new GlassPicker{Name="setupAssignment_"+service.Id,FlatSurface=true};picker.SetItems(options.Select(o=>o.Output=="skip"?"Не назначать":o.Output=="direct"?"Напрямую"+(direct.Access(service.Id)==ServiceAccess.Available?"":" · не подтверждено"):o.Output=="warp"?"WARP · настроить следующим шагом":"Zapret · "+o.Strategy.Name));picker.SelectedIndex=Math.Max(0,options.FindIndex(o=>o.Output==recommended.Output&&(o.Strategy==null||o.Strategy.StrategyId==recommended.Strategy.StrategyId)));grid.Controls.Add(picker);pickers.Add(Tuple.Create(row,picker));
   }
   Action arrange=()=>{foreach(var item in pickers){var r=grid.GetCellDisplayRectangle(1,item.Item1,false);item.Item2.Visible=r.Top>=grid.ColumnHeadersHeight&&r.Bottom<=grid.Height;if(item.Item2.Visible)item.Item2.SetBounds(r.X+4,r.Y+5,Math.Max(30,r.Width-24),r.Height-10);}};grid.Scroll+=(s,e)=>{arrange();if(grid.IsHandleCreated&&!grid.IsDisposed)grid.BeginInvoke(arrange);};grid.Resize+=(s,e)=>arrange();grid.ColumnWidthChanged+=(s,e)=>arrange();
   var next=B("Продолжить",(s,e)=>{var result=new Dictionary<string,SetupAssignment>();foreach(var item in pickers){var service=(ZapretService)grid.Rows[item.Item1].Tag;result[service.Id]=choices[service.Id][item.Item2.SelectedIndex];}done.TrySetResult(result);veil.Dispose();},true);next.Name="setupDistributionNext";next.SetBounds(24,popup.Height-62,280,40);popup.Controls.Add(next);var cancel=B("Отмена",(s,e)=>veil.Dispose());cancel.SetBounds(popup.Width-180,popup.Height-62,156,40);popup.Controls.Add(cancel);veil.Disposed+=(s,e)=>done.TrySetResult(null);Controls.Add(veil);veil.BringToFront();layout();arrange();var registration=token.Register(()=>{if(!veil.IsDisposed)veil.BeginInvoke(new Action(()=>veil.Dispose()));done.TrySetCanceled();});done.Task.ContinueWith(t=>registration.Dispose());return done.Task;
  }
Task<SetupWarpChoice> ChooseSetupWarp(ZapretService[] services,CancellationToken token){
   var done=new TaskCompletionSource<SetupWarpChoice>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(760,ClientSize.Width-40),490)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Add(popup,L("WARP для оставшихся сервисов",18,true),24,18);
var targets=new GlassPicker{Name="setupWarpServices",MultiSelect=true,SelectionCaption="Сервисы для WARP",Location=new Point(24,78),Width=popup.Width-48};targets.SetItems(services.Select(s=>s.CheckName));for(int i=0;i<services.Length;i++)targets.SetChecked(i,true);popup.Controls.Add(targets);var profiles=WarpOutputs.Profiles(state);var profile=new GlassPicker{Name="setupWarpProfile",Location=new Point(24,142),Width=popup.Width-48};profile.SetItems(new[]{"Создать отдельный WARP"}.Concat(profiles.Select(p=>p.Name)));profile.SelectedIndex=profiles.Length>0?1:0;popup.Controls.Add(profile);
   var countries=new[]{"any","foreign","RU","NL","DE","FI","SE","PL","FR","GB","US"};var country=new GlassPicker{Name="setupWarpCountry",Location=new Point(24,196),Width=popup.Width-48,FlatSurface=true};country.SetItems(new[]{"Любая страна","Не Россия","Россия (RU)","Нидерланды","Германия","Финляндия","Швеция","Польша","Франция","Великобритания","США"});country.SelectedIndex=1;popup.Controls.Add(country);var protocol=new GlassPicker{Name="setupWarpProtocol",Location=new Point(24,250),Width=popup.Width-48,FlatSurface=true};protocol.SetItems(new[]{"Авто: AmneziaWG → WireGuard","AmneziaWG · QUIC","WireGuard"});protocol.SelectedIndex=0;popup.Controls.Add(protocol);
   var agree=new GlassToggle{Name="setupWarpAgree",Text="Принимаю условия Cloudflare",Checked=true,Location=new Point(24,302),Width=popup.Width-48};popup.Controls.Add(agree);var terms=RepositoryLink(popup,"setupWarpTermsLink","Условия Cloudflare","https://www.cloudflare.com/application/terms/",24,346,popup.Width-48);
var start=B("Подобрать и проверить",(s,e)=>{if(targets.SelectedIndices.Length==0){Toast("Выберите сервисы для WARP");return;}if(profile.SelectedIndex==0&&!agree.Checked){Toast("Для создания нового аккаунта подтвердите условия Cloudflare");return;}done.TrySetResult(new SetupWarpChoice{ServiceIds=targets.SelectedIndices.Select(i=>services[i].Id).ToList(),Country=countries[country.SelectedIndex],Protocol=new[]{"auto","awg","wg"}[protocol.SelectedIndex],ExcludeDme=country.SelectedIndex!=2,SourceId=profile.SelectedIndex==0?null:profiles[profile.SelectedIndex-1].Id});veil.Dispose();},true);start.Name="setupWarpStart";start.SetBounds(24,414,270,40);popup.Controls.Add(start);var skip=B("Без WARP",(s,e)=>veil.Dispose());skip.SetBounds(popup.Width-200,414,176,40);popup.Controls.Add(skip);veil.Disposed+=(s,e)=>done.TrySetResult(null);Controls.Add(veil);veil.BringToFront();layout();var registration=token.Register(()=>{if(!veil.IsDisposed)veil.BeginInvoke(new Action(()=>veil.Dispose()));done.TrySetCanceled();});done.Task.ContinueWith(t=>registration.Dispose());return done.Task;
  }
 }
}
