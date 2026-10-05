using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed class ServiceManualResult {
  public string ServiceId{get;set;}public string StrategyId{get;set;}public bool Works{get;set;}public DateTime CheckedAt{get;set;}public ZapretSettings Settings{get;set;}public List<HostsCandidate> Hosts{get;set;}
 }
 public static class ManualServiceChecks {
  public const string InstagramReel="https://www.instagram.com/p/Da-ePNVMV_M/";
  public static string[] Urls(ZapretService service){return (service.Id=="instagram"?new[]{InstagramReel}:service.Urls.Concat(service.Id=="rutor"?new[]{"https://rutracker.org/forum/index.php"}:new string[0])).Where(u=>u.StartsWith("https://",StringComparison.OrdinalIgnoreCase)).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();}
  public static string Progress(int index,int total,string strategy){return "Проверка "+(index+1)+" из "+total+" · Осталось: "+Math.Max(0,total-index-1)+"\n"+strategy;}
public static ZapretSettings AppliedSettings(ZapretCheckReport report,string service,string strategy){var manual=(report.ManualResults??new List<ServiceManualResult>()).Where(r=>r.ServiceId==service&&r.StrategyId==strategy&&r.Works&&r.Settings!=null).OrderByDescending(r=>r.CheckedAt).FirstOrDefault();var settings=ZapretChecks.CopySettings(manual==null?report.ApplySettings??report.Settings:manual.Settings);settings.Hosts=manual!=null&&manual.Hosts!=null?manual.Hosts:HostsCandidates.ForResult(report.Rows.FirstOrDefault(r=>r.StrategyId==strategy),service);return settings;}
  public static ZapretCheckRow[] Candidates(ZapretCheckReport report,string serviceId,string preferred){
   return report.Rows.Where(r=>!r.Checking&&!String.IsNullOrEmpty(r.StrategyId)&&String.IsNullOrEmpty(r.Error))
    .OrderByDescending(r=>r.StrategyId==preferred).ThenByDescending(r=>r.Access(serviceId)==ServiceAccess.Available)
    .ThenBy(r=>r.Services.Where(s=>s.Id==serviceId&&s.Access==ServiceAccess.Available).Select(s=>s.Milliseconds).DefaultIfEmpty(Int32.MaxValue).Min())
    .GroupBy(r=>r.StrategyId).Select(g=>g.First()).ToArray();
  }
  public static ZapretSettings Trial(ZapretCheckReport report,ZapretService service,ZapretStrategy strategy){
   var settings=ZapretChecks.CopySettings(report.ApplySettings??report.Settings);settings.Strategy=strategy.Id;settings.Family=strategy.Family;
   settings.ScopeRules=null;settings.MatchMode="addresses";settings.ScopeSources=null;settings.CustomScopeText=null;
settings.ScopeText=service.Domains+(service.Id=="rutor"?"\n"+ZapretChecks.Services.First(s=>s.Id=="rutracker").Domains:"");settings=HostsCandidates.WithScope(settings,HostsCandidates.ForResult(report.Rows.FirstOrDefault(r=>r.StrategyId==strategy.Id),service.Id));return settings;
  }
 }
 public sealed partial class MainForm {
  ZapretCheckReport StrategyReport(){var setup=ZapretChecks.Load(System.IO.Path.Combine(store.Root,"setup-results"));EnsureZapretReport();var report=setup!=null&&(zapretReport==null||setup.StartedAt>=zapretReport.StartedAt)?setup:zapretReport;if(report==null)return null;var manual=(report.ManualResults??new List<ServiceManualResult>()).ToList();foreach(var id in new[]{"instagram","chatgpt"}){var hostReport=ZapretChecks.Load(System.IO.Path.Combine(store.Root,"hosts-results-"+id));if(hostReport!=null)manual.AddRange((hostReport.ManualResults??new List<ServiceManualResult>()).Where(r=>r.ServiceId==id&&r.CheckedAt>=report.StartedAt));}report.ManualResults=manual.GroupBy(r=>r.ServiceId+"\n"+r.StrategyId+"\n"+r.CheckedAt.Ticks).Select(g=>g.Last()).ToList();return report;}
  public static ServiceAccess StrategyVerdict(ZapretCheckReport report,string strategy,string service){
   if(report==null||String.IsNullOrEmpty(strategy))return ServiceAccess.Unknown;
   if(service=="rutracker"||service=="pornhub")service="rutor";
   var manual=(report.ManualResults??new List<ServiceManualResult>()).Where(r=>r.ServiceId==service&&r.StrategyId==strategy).OrderByDescending(r=>r.CheckedAt).FirstOrDefault();
   if(manual!=null)return manual.Works?ServiceAccess.Available:ServiceAccess.Unavailable;
   var row=report.Rows.FirstOrDefault(r=>r.StrategyId==strategy&&!r.Checking);return row==null?ServiceAccess.Unknown:row.Access(service);
  }
  static Color VerdictColor(ServiceAccess value){return value==ServiceAccess.Available?GlassInk.Mint:value==ServiceAccess.Unavailable?Color.FromArgb(255,130,150):Color.FromArgb(255,220,110);}
  static Color StrategyInk(ZapretCheckReport report,string strategy,string service){var access=StrategyVerdict(report,strategy,service);if(service=="discord-voice"&&access==ServiceAccess.Available&&report!=null){var row=report.Rows.FirstOrDefault(r=>r.StrategyId==strategy);if(row!=null&&row.Services.Any(s=>s.Id==service&&s.PingQuality=="high"))return VerdictColor(ServiceAccess.Unknown);}return VerdictColor(access);}
  internal static Color VerdictFill(Color ink){return ink==GlassInk.Mint?Color.FromArgb(28,83,84):ink==GlassInk.Cyan?Color.FromArgb(27,66,87):ink==Color.FromArgb(255,130,150)?Color.FromArgb(84,45,69):Color.FromArgb(75,66,57);}
  Color ProfileStrategyColor(ZapretCheckReport report,ZapretProfile profile,string strategy){
   var lists=state.Lists.Where(l=>l.Target=="zapret"&&l.ZapretProfileId==profile.Id).ToArray();
   var services=ZapretChecks.Services.Where(s=>lists.Any(l=>l.BuiltinPreset=="setup-service:"+s.Id||l.BuiltinPreset=="service-constructor:"+s.Id||(l.PresetNames??new List<string>()).Any(n=>n==s.Name||n.StartsWith(s.Name+" · "))));
   var values=services.Select(s=>StrategyInk(report,strategy,s.Id=="discord"?"discord-voice":s.Id)).ToArray();
   if(values.Distinct().Count()>1)return GlassInk.Cyan;return values.Length==0?VerdictColor(ServiceAccess.Unknown):values[0];
  }
  async Task CheckSetupServicesManually(ZapretCheckReport report,ZapretService[] services,Dictionary<string,SetupAssignment> assignments,CancellationToken token){
   if(report.WizardManualCompleted==null)report.WizardManualCompleted=new List<string>();
   foreach(var service in services.Where(s=>ZapretChecks.CanCheckZapret(s)&&s.Id!="discord")){
    token.ThrowIfCancellationRequested();if(report.WizardManualCompleted.Contains(service.Id))continue;
    Exception failure=null;bool hostsService=service.Id=="instagram"||service.Id=="chatgpt";
    if(hostsService&&report.WizardSkipHosts)assignments[service.Id].Output="skip";
    else try{await CheckSetupServiceManually(report,service,assignments[service.Id],token);}catch(Exception ex){failure=ex;}
    if(failure!=null){if(!hostsService||!HostsRepair.IsHostsFileFailure(failure))throw failure;WriteLog("Ошибка hosts в мастере: "+failure.ToString());report.WizardSkipHosts=await HostsFailureChoice(service.Name,failure,token);assignments[service.Id].Output="skip";assignments[service.Id].Strategy=null;assignments[service.Id].Hosts=null;}
    token.ThrowIfCancellationRequested();report.WizardManualCompleted.Add(service.Id);report.WizardAssignments=assignments.Values.ToList();ZapretChecks.Save(System.IO.Path.Combine(store.Root,"setup-results"),report);
   }
  }
  async Task CheckSetupServiceManually(ZapretCheckReport report,ZapretService service,SetupAssignment assignment,CancellationToken token){
    token.ThrowIfCancellationRequested();if(assignment.Output=="skip")return;service.Domains=ServicePresetRoutes.LocalScope(core,service);if(service.Id=="rutor"){TrackerLists.Use(await TrackerLists.Load(core.DownloadTextAsync));token.ThrowIfCancellationRequested();}
    // A ByeTube trial must use its own engine, never label a Zapret trial as ByeTube.
    if(assignment.Output=="direct"){bool? direct=await CheckMappedServiceManually(service,service.Urls[0],new List<HostsCandidate>(),token);token.ThrowIfCancellationRequested();if(direct==true)return;assignment.Output="warp";}
    if(assignment.Output=="byetube"){if(await CheckSetupByeTubeManually(service,assignment.Strategy,token))return;assignment.Output="warp";assignment.Strategy=null;}
if((service.Id=="instagram"||service.Id=="chatgpt")&&!report.Rows.Any(r=>HostsCandidates.ForResult(r,service.Id).Count>0)&&(assignment.Output=="warp"||assignment.Strategy==null||assignment.Strategy.Access(service.Id)!=ServiceAccess.Available)&&await CheckSetupHosts(report,service,assignment,token,message=>WriteLog("Мастер: "+message)))return;
    var selected=await CheckServiceManually(report,service,assignment.Strategy,token);token.ThrowIfCancellationRequested();
assignment.Strategy=selected;assignment.Output=selected==null?"warp":"zapret";assignment.Hosts=selected==null?null:HostsCandidates.ForResult(selected,service.Id);
  }
  async Task<bool> HostsFailureChoice(string service,Exception failure,CancellationToken token){
   var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(850,ClientSize.Width-40),350)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
   Add(popup,L(service+" · не удалось изменить hosts",17,true),24,20);var hint=L("Проверьте права доступа к файлу и блокировку антивирусом или другой программой. При блокировке сделаны 3 повторные попытки с паузой 15 секунд.\n\n"+HostsEditor.PathName+"\n\n"+failure.Message+"\n\nРезультаты предыдущих проверок сохранены. Если ошибка была при откате, проверьте текущие записи hosts; бэкап — в hosts-backups.",10,false,Color.FromArgb(255,220,110));hint.AutoSize=false;hint.SetBounds(24,65,popup.Width-48,200);popup.Controls.Add(hint);
   var next=B("К следующему сервису",(s,e)=>done.TrySetResult(false),true);next.Name="hostsFailureNext";next.SetBounds(24,285,280,40);popup.Controls.Add(next);var skip=B("Пропустить шаг hosts",(s,e)=>done.TrySetResult(true));skip.Name="hostsFailureSkip";skip.SetBounds(320,285,popup.Width-344,40);popup.Controls.Add(skip);veil.Disposed+=(s,e)=>done.TrySetCanceled();Controls.Add(veil);veil.BringToFront();layout();
   using(token.Register(()=>done.TrySetCanceled()))try{return await done.Task;}finally{if(!veil.IsDisposed)veil.Dispose();}
  }
  async Task<ZapretCheckRow> CheckServiceManually(ZapretCheckReport report,ZapretService service,ZapretCheckRow preferred,CancellationToken token){
   var rows=ManualServiceChecks.Candidates(report,service.Id,preferred==null?null:preferred.StrategyId);
   if(rows.Length==0)return null;
   bool wasRunning=zapret.Running,touched=false;ZapretCheckRow selected=null;
   var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(880,ClientSize.Width-40),370)};veil.Controls.Add(popup);
   Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
   Add(popup,L(service.CheckName+" · ручная проверка",18,true),24,20);
   var hint=L(service.Id=="rutor"?"Откройте Rutor и RuTracker. Нажимайте «Работает», только если работают оба сайта.":"Откройте сервис и проверьте нужную функцию. «Работает» сохраняет эту стратегию; «Не работает» включает следующую.",11,false,Muted);hint.AutoSize=false;hint.SetBounds(24,75,popup.Width-48,70);popup.Controls.Add(hint);
   int linkX=24;var urls=ManualServiceChecks.Urls(service);
   foreach(string url in urls){string target=url;var link=new LinkLabel{Text=new Uri(url).Host,AutoSize=true,Location=new Point(linkX,139),BackColor=Color.Transparent,LinkColor=GlassInk.Cyan,ActiveLinkColor=GlassInk.Mint,VisitedLinkColor=GlassInk.Cyan};link.LinkClicked+=(s,e)=>{try{System.Diagnostics.Process.Start(target);}catch(Exception ex){Toast(ex.Message);}};popup.Controls.Add(link);linkX+=link.PreferredWidth+24;}
   var status=L("Подготовка стратегии…",11,true,GlassInk.Cyan);status.AutoSize=false;status.SetBounds(24,174,popup.Width-48,60);popup.Controls.Add(status);
   var decision=new TaskCompletionSource<int>();
   var works=B("Работает",(s,e)=>decision.TrySetResult(1),true);works.Name="serviceManualWorks";works.SetBounds(24,245,230,40);popup.Controls.Add(works);
   var fails=B("Не работает",(s,e)=>decision.TrySetResult(0));fails.Name="serviceManualFails";fails.SetBounds(270,245,230,40);popup.Controls.Add(fails);
var open=B("Открыть сервис",(s,e)=>{try{foreach(string url in urls)System.Diagnostics.Process.Start(url);}catch(Exception ex){Toast(ex.Message);}});open.SetBounds(516,245,popup.Width-540,40);popup.Controls.Add(open);
   var skip=B("Без Zapret, дальше",(s,e)=>decision.TrySetResult(2));skip.Name="serviceManualSkip";skip.SetBounds(24,307,270,40);popup.Controls.Add(skip);
   var cancel=B("Отменить мастер",(s,e)=>veil.Dispose());cancel.SetBounds(popup.Width-210,307,186,40);popup.Controls.Add(cancel);
   veil.Disposed+=(s,e)=>decision.TrySetCanceled();Controls.Add(veil);veil.BringToFront();layout();
   var registration=token.Register(()=>{decision.TrySetCanceled();if(!veil.IsDisposed&&veil.IsHandleCreated)veil.BeginInvoke(new Action(()=>veil.Dispose()));});
   Exception failure=null;
   try{
for(int attempt=0;attempt<rows.Length;attempt++){var row=rows[attempt];string progress=ManualServiceChecks.Progress(attempt,rows.Length,row.Name);
     token.ThrowIfCancellationRequested();works.Enabled=fails.Enabled=open.Enabled=false;
     var strategy=ZapretCatalog.Load(zapret.DirectoryPath).FirstOrDefault(s=>s.Id==row.StrategyId&&s.Available);if(strategy==null)continue;
     var settings=ManualServiceChecks.Trial(report,service,strategy);status.Text="Включаем: "+row.Name;
     decision=new TaskCompletionSource<int>();
     try{touched=true;await zapret.Start(settings,strategy,token);}catch(OperationCanceledException){throw;}catch(Exception ex){status.Text="Не удалось запустить: "+ex.Message;continue;}
     token.ThrowIfCancellationRequested();if(veil.IsDisposed)throw new OperationCanceledException();
var mapped=HostsCandidates.ForResult(row,service.Id);if(service.Id=="instagram"||service.Id=="chatgpt"){if(mapped.Count==0){status.Text="Для этой стратегии нет проверенных hosts; пропускаем.";continue;}status.Text=progress+"\nПроверьте hosts + Zapret";bool? answerMapped=await CheckMappedServiceWithProgress(service,service.Id=="instagram"?ManualServiceChecks.InstagramReel:service.Urls.First(ServiceBrowserProbe.Supports),mapped,token,null,progress);token.ThrowIfCancellationRequested();if(!answerMapped.HasValue)break;decision.TrySetResult(answerMapped.Value?1:0);}else{status.Text=progress+"\n"+(service.Id=="rutor"?"Проверьте оба сайта: Rutor и RuTracker.":"Проверьте сервис в браузере.");works.Enabled=fails.Enabled=open.Enabled=true;}
     int answer=await decision.Task;if(answer==2)break;
     if(!zapret.ProfileApplied(null,row.StrategyId))throw new InvalidOperationException("Стратегия уже не включена. Результат не сохранён.");
     if(report.ManualResults==null)report.ManualResults=new List<ServiceManualResult>();
report.ManualResults.Add(new ServiceManualResult{ServiceId=service.Id,StrategyId=row.StrategyId,Works=answer==1,CheckedAt=DateTime.Now,Settings=settings,Hosts=mapped});
     var measured=row.Services.FirstOrDefault(r=>r.Id==service.Id);if(measured==null){measured=new ZapretServiceResult{Id=service.Id};row.Services.Add(measured);}measured.Access=answer==1?ServiceAccess.Available:ServiceAccess.Unavailable;measured.Detail=answer==1?"Работа сервиса подтверждена вручную на этой стратегии.":"Ручная проверка: сервис не работает на этой стратегии.";
     ZapretChecks.Save(System.IO.Path.Combine(store.Root,"setup-results"),report);
     if(answer==1){selected=row;break;}
    }
   }catch(Exception ex){failure=ex;}
   finally{registration.Dispose();if(!veil.IsDisposed)veil.Dispose();if(touched)zapret.Stop();}
   if(wasRunning&&touched&&!IsDisposed)await StartSavedZapretProfiles(CancellationToken.None);
   if(failure!=null)throw failure;return selected;
  }
 }
}
