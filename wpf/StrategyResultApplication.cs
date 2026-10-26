using System;
using System.Linq;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class StrategyResultPlan {
  internal string ProfileId,ByeTubeStrategy;
  internal ZapretStrategy Strategy;
  internal ZapretProfile CreatedProfile;
  internal RouteList[] Additions=new RouteList[0];
  internal void Apply(ClientState state){
   if(ByeTubeStrategy!=null){if(!ByeTube.Strategies.Contains(ByeTubeStrategy))throw new InvalidOperationException("Стратегия ByeTube недоступна.");state.ByeTubeStrategy=ByeTubeStrategy;return;}
   if(Strategy==null||String.IsNullOrWhiteSpace(Strategy.Id)||!Strategy.Available)throw new InvalidOperationException("Стратегия больше недоступна.");
   ZapretProfile profile;
   if(CreatedProfile==null){profile=state.ZapretProfiles.FirstOrDefault(p=>p.Id==ProfileId);if(profile==null)throw new InvalidOperationException("Профиль уже удалён. Выберите другой профиль.");if(!state.Lists.Any(r=>r.Enabled&&r.Target=="zapret"&&r.ZapretProfileId==profile.Id))throw new InvalidOperationException("Назначьте профилю включённые правила или создайте новый профиль для выбранных сервисов.");}
   else{
    profile=CreatedProfile;if(String.IsNullOrEmpty(profile.Id)||state.ZapretProfiles.Any(p=>p.Id==profile.Id))throw new InvalidOperationException("Профиль уже существует или не имеет идентификатора. Откройте результаты заново.");
    if(Additions==null||Additions.Length==0||Additions.Any(r=>r==null||String.IsNullOrEmpty(r.Id)||!r.Enabled||r.Target!="zapret"||r.ZapretProfileId!=profile.Id)||Additions.Select(r=>r.Id).Distinct().Count()!=Additions.Length||Additions.Any(r=>state.Lists.Any(old=>old.Id==r.Id)))throw new InvalidOperationException("Некорректный план новых правил.");
    foreach(var route in Additions)ZapretRoutes.Validate(route,state.ZapretProfiles.Concat(new[]{profile}));
   }
   if(profile.Settings==null)throw new InvalidOperationException("Настройки профиля отсутствуют. Выберите другой профиль.");
   if(CreatedProfile!=null){state.ZapretProfiles.Add(profile);state.Lists.AddRange(Additions);}
   profile.Settings.Strategy=Strategy.Id;profile.Settings.Family=Strategy.Family;profile.Enabled=true;
  }
 }
 internal static class StrategyResultApplication {
  internal static bool CanApply(ZapretCheckRow row){return row!=null&&!row.Checking&&String.IsNullOrEmpty(row.Error)&&!String.IsNullOrEmpty(row.StrategyId)&&row.Services!=null&&row.Services.Any(s=>s.Access==ServiceAccess.Available);}
  internal static int ByeTubeIndex(ZapretCheckRow row){int index;if(row==null||row.StrategyId==null||!row.StrategyId.StartsWith("byetube:",StringComparison.Ordinal)||!Int32.TryParse(row.StrategyId.Substring(8),out index)||index<0||index>=ByeTube.Strategies.Length)throw new InvalidOperationException("Стратегия ByeTube недоступна.");return index;}
  internal static async Task<string> Commit(ZapretCheckReport report,string strategyId,Func<Task> apply,Func<Task> saveReport){
   if(report==null||apply==null||saveReport==null)throw new ArgumentException("Нет отчёта или обработчика применения.");
   await apply();report.LastAppliedId=strategyId;report.LastAppliedAt=DateTime.Now;
   Exception warning=null;try{await saveReport();}catch(Exception ex){warning=ex;}
   return warning==null?null:"Настройки применены, но отметку в отчёте сохранить не удалось: "+JournalStyle.Redact(warning.Message);
  }
 }
}
