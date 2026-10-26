using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class SetupWizardSelection {
  internal ZapretService Service;internal ZapretCheckRow Row;
  internal string Output{get{return String.IsNullOrEmpty(Row.StrategyId)?"direct":Row.Family=="ByeTube"?"byetube":"zapret";}}
 }
 internal static class SetupWizardFlow {
  internal static void Record(ZapretCheckReport report,SetupWizardSelection item,bool works,ZapretSettings settings){
   if(report.ManualResults==null)report.ManualResults=new List<ServiceManualResult>();
   report.ManualResults.Add(new ServiceManualResult{ServiceId=item.Service.Id,StrategyId=item.Row.StrategyId,Works=works,CheckedAt=DateTime.Now,Settings=settings,Hosts=HostsCandidates.ForResult(item.Row,item.Service.Id)});
   var measured=item.Row.Services.FirstOrDefault(s=>s.Id==item.Service.Id);if(measured==null){measured=new ZapretServiceResult{Id=item.Service.Id};item.Row.Services.Add(measured);}
   string automatic=measured.Detail;measured.Access=works?ServiceAccess.Available:ServiceAccess.Unavailable;measured.Detail="Очная проверка: пользователь подтвердил, что сервис "+(works?"работает.":"не работает.")+(String.IsNullOrEmpty(automatic)?"":"\nПредыдущий результат: "+automatic);
   if(item.Service.Id=="discord"){
    item.Row.Services.RemoveAll(s=>s.Id=="discord-voice");item.Row.Services.Add(new ZapretServiceResult{Id="discord-voice",InterfaceStrategyId=item.Row.StrategyId,Access=measured.Access,Detail="Очная проверка слышимости в обе стороны: "+(works?"подтверждено пользователем.":"не подтверждено.")});
   }
  }
  internal static ZapretCheckRow[] Candidates(ZapretCheckReport report,string service){
   return report.Rows.Where(r=>!r.Checking&&String.IsNullOrEmpty(r.Error)&&r.Services.Any(s=>s.Id==service))
    .OrderBy(r=>r.Access(service)==ServiceAccess.Available?0:r.Access(service)==ServiceAccess.Unknown?1:2)
    .ThenBy(r=>String.IsNullOrEmpty(r.StrategyId)?0:1).ThenBy(r=>r.TotalMilliseconds).GroupBy(r=>r.StrategyId??"").Select(g=>g.First()).ToArray();
  }
  internal static string[] ServiceIds(ZapretCheckReport report){return (report.WizardServiceIds!=null&&report.WizardServiceIds.Count>0?report.WizardServiceIds:report.ServiceIds!=null&&report.ServiceIds.Count>0?report.ServiceIds:report.Settings==null?new List<string>():report.Settings.CheckServices??new List<string>()).Distinct().ToArray();}
  internal static bool CanContinue(ZapretCheckReport report){return report!=null&&report.Rows!=null&&ZapretChecks.Selected(ServiceIds(report)).Any(s=>Candidates(report,s.Id).Length>0);}
  internal static string OutputName(ZapretCheckRow row){return String.IsNullOrEmpty(row.StrategyId)?"Напрямую (без Zapret)":row.Family=="ByeTube"?"ByeTube":"Zapret";}
  internal static ZapretCheckRow[] TrialCandidates(ZapretCheckReport report,SetupWizardSelection selected){
   string output=selected.Output;return new[]{selected.Row}.Concat(Candidates(report,selected.Service.Id).Where(row=>row!=selected.Row&&new SetupWizardSelection{Service=selected.Service,Row=row}.Output==output)).ToArray();
  }
  internal static void MergePlan(ClientState state,ZapretProfile[] profiles,RouteList[] routes,string byeStrategy){
   if(state==null||profiles==null||routes==null||profiles.Any(p=>p==null)||routes.Any(r=>r==null))throw new InvalidOperationException("Некорректный план мастера.");
   var keys=new HashSet<string>(routes.Select(r=>r.BuiltinPreset));if(routes.Length==0||keys.Count!=routes.Length||keys.Any(k=>String.IsNullOrEmpty(k)||!k.StartsWith("wpf-setup:",StringComparison.Ordinal)))throw new InvalidOperationException("Некорректный план мастера.");
   if(routes.Any(r=>r.Target!="direct"&&r.Target!="zapret"&&r.Target!="byetube"||r.Target!="zapret"&&!String.IsNullOrEmpty(r.ZapretProfileId)))throw new InvalidOperationException("В плане мастера выбран некорректный выход.");
   if(routes.Any(r=>r.Target=="byetube")?(byeStrategy==null||!ByeTube.Strategies.Contains(byeStrategy)):byeStrategy!=null)throw new InvalidOperationException("В плане мастера не подтверждена стратегия ByeTube.");
   var oldIds=new HashSet<string>(state.Lists.Where(r=>keys.Contains(r.BuiltinPreset)).Select(r=>r.ZapretProfileId).Where(id=>!String.IsNullOrEmpty(id)));
   var remaining=state.Lists.Where(r=>!keys.Contains(r.BuiltinPreset)).ToArray();
   var obsolete=state.ZapretProfiles.Where(p=>!String.IsNullOrEmpty(p.WizardServiceId)&&keys.Contains("wpf-setup:"+p.WizardServiceId)&&oldIds.Contains(p.Id)&&!remaining.Any(r=>r.ZapretProfileId==p.Id)).ToArray();
   if(obsolete.Any(p=>DefaultRouteRouting.Requested(state)=="zapret:"+p.Id))throw new InvalidOperationException("Профиль мастера используется для трафика вне правил. Сначала измените общий выход в маршрутах, затем повторите применение.");
   if(profiles.Any(p=>String.IsNullOrEmpty(p.Id))||profiles.Select(p=>p.Id).Distinct().Count()!=profiles.Length||profiles.Any(p=>state.ZapretProfiles.Any(old=>old.Id==p.Id&&!obsolete.Contains(old))))throw new InvalidOperationException("План мастера содержит повторяющийся идентификатор профиля.");
   if(routes.Any(r=>String.IsNullOrEmpty(r.Id))||routes.Select(r=>r.Id).Distinct().Count()!=routes.Length||routes.Any(r=>state.Lists.Any(old=>old.Id==r.Id&&!keys.Contains(old.BuiltinPreset))))throw new InvalidOperationException("План мастера конфликтует с сохранённым правилом.");
   if(profiles.Any(p=>String.IsNullOrEmpty(p.WizardServiceId)||!routes.Any(r=>r.BuiltinPreset=="wpf-setup:"+p.WizardServiceId&&r.Target=="zapret"&&r.ZapretProfileId==p.Id))||routes.Any(r=>r.Target=="zapret"&&!profiles.Any(p=>p.Id==r.ZapretProfileId&&r.BuiltinPreset=="wpf-setup:"+p.WizardServiceId)))throw new InvalidOperationException("Профили мастера не связаны с выбранными сервисами.");
   state.Lists.RemoveAll(r=>keys.Contains(r.BuiltinPreset));state.ZapretProfiles.RemoveAll(p=>!String.IsNullOrEmpty(p.WizardServiceId)&&keys.Contains("wpf-setup:"+p.WizardServiceId)&&oldIds.Contains(p.Id)&&!state.Lists.Any(r=>r.ZapretProfileId==p.Id));
   state.ZapretProfiles.InsertRange(0,profiles);state.Lists.InsertRange(0,routes);if(byeStrategy!=null)state.ByeTubeStrategy=byeStrategy;
  }
  internal static async Task Run(ZapretCheckReport report,Func<Task<SetupWizardSelection[]>> choose,
   Func<SetupWizardSelection,CancellationToken,Task<bool?>> verify,Func<SetupWizardSelection[],Task> apply,Action save,CancellationToken token){
   await RunCore(report,choose,verify,apply,save,token,false);
  }
  internal static async Task RunCore(ZapretCheckReport report,Func<Task<SetupWizardSelection[]>> choose,
   Func<SetupWizardSelection,CancellationToken,Task<bool?>> verify,Func<SetupWizardSelection[],Task> apply,Action save,CancellationToken token,bool repeatManual){
   token.ThrowIfCancellationRequested();if(!CanContinue(report))throw new InvalidOperationException("Нет проверенных конфигураций. Сначала запустите поиск стратегий.");
   var chosen=await choose();token.ThrowIfCancellationRequested();if(chosen==null)return;
   var accepted=new List<SetupWizardSelection>();
   foreach(var item in chosen){token.ThrowIfCancellationRequested();var previous=(report.ManualResults??new List<ServiceManualResult>()).Where(r=>r.ServiceId==item.Service.Id&&r.StrategyId==item.Row.StrategyId).OrderBy(r=>r.CheckedAt).LastOrDefault();
    if(!repeatManual&&previous!=null&&previous.Works&&(item.Service.Id!="discord"||item.Row.Access("discord-voice")==ServiceAccess.Available)){accepted.Add(item);continue;}
    bool? answer=await verify(item,token);token.ThrowIfCancellationRequested();save();if(answer==true)accepted.Add(item);
   }
   token.ThrowIfCancellationRequested();if(accepted.Count>0)await apply(accepted.ToArray());
  }
 }
}
