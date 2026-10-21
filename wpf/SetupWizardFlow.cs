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
   return report.Rows.Where(r=>!r.Checking&&String.IsNullOrEmpty(r.Error)&&(String.IsNullOrEmpty(r.StrategyId)||r.Services.Any(s=>s.Id==service)))
    .OrderBy(r=>r.Access(service)==ServiceAccess.Available?0:r.Access(service)==ServiceAccess.Unknown?1:2)
    .ThenBy(r=>String.IsNullOrEmpty(r.StrategyId)?0:1).ThenBy(r=>r.TotalMilliseconds).GroupBy(r=>r.StrategyId??"").Select(g=>g.First()).ToArray();
  }
  internal static string OutputName(ZapretCheckRow row){return String.IsNullOrEmpty(row.StrategyId)?"Напрямую (без Zapret)":row.Family=="ByeTube"?"ByeTube":"Zapret";}
  internal static ZapretCheckRow[] TrialCandidates(ZapretCheckReport report,SetupWizardSelection selected){
   string output=selected.Output;return new[]{selected.Row}.Concat(Candidates(report,selected.Service.Id).Where(row=>row!=selected.Row&&new SetupWizardSelection{Service=selected.Service,Row=row}.Output==output)).ToArray();
  }
  internal static void MergePlan(ClientState state,ZapretProfile[] profiles,RouteList[] routes,string byeStrategy){
   var keys=new HashSet<string>(routes.Select(r=>r.BuiltinPreset));if(keys.Count!=routes.Length||keys.Any(k=>String.IsNullOrEmpty(k)||!k.StartsWith("wpf-setup:",StringComparison.Ordinal)))throw new InvalidOperationException("Некорректный план мастера.");
   var oldIds=new HashSet<string>(state.Lists.Where(r=>keys.Contains(r.BuiltinPreset)).Select(r=>r.ZapretProfileId).Where(id=>!String.IsNullOrEmpty(id)));
   state.Lists.RemoveAll(r=>keys.Contains(r.BuiltinPreset));state.ZapretProfiles.RemoveAll(p=>!String.IsNullOrEmpty(p.WizardServiceId)&&keys.Contains("wpf-setup:"+p.WizardServiceId)&&oldIds.Contains(p.Id)&&!state.Lists.Any(r=>r.ZapretProfileId==p.Id));
   state.ZapretProfiles.InsertRange(0,profiles);state.Lists.InsertRange(0,routes);if(byeStrategy!=null)state.ByeTubeStrategy=byeStrategy;
  }
  internal static async Task Run(ZapretCheckReport report,Func<Task<SetupWizardSelection[]>> choose,
   Func<SetupWizardSelection,CancellationToken,Task<bool?>> verify,Func<SetupWizardSelection[],Task> apply,Action save,CancellationToken token){
   await RunCore(report,choose,verify,apply,save,token,false);
  }
  internal static async Task RunCore(ZapretCheckReport report,Func<Task<SetupWizardSelection[]>> choose,
   Func<SetupWizardSelection,CancellationToken,Task<bool?>> verify,Func<SetupWizardSelection[],Task> apply,Action save,CancellationToken token,bool repeatManual){
   token.ThrowIfCancellationRequested();if(!report.Complete)throw new InvalidOperationException("Сначала завершите поиск стратегий.");
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
