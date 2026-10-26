using System;
using System.Linq;
using SplifyWin;
namespace Mcrf.Wpf {
 internal static class BestProfilePlan {
  internal static int Apply(ClientState state,ZapretProfile[] profiles,RouteList[] routes){
   if(profiles==null||routes==null||profiles.Length!=routes.Length||routes.Length==0||profiles.Any(p=>p==null||p.Settings==null||String.IsNullOrEmpty(p.Id)||String.IsNullOrWhiteSpace(p.Settings.Strategy))||routes.Any(r=>r==null||String.IsNullOrEmpty(r.Id)))throw new InvalidOperationException("Некорректный план профилей.");
   if(profiles.Select(p=>p.Id).Distinct().Count()!=profiles.Length||routes.Select(r=>r.Id).Distinct().Count()!=routes.Length||routes.Select(r=>r.BuiltinPreset).Distinct().Count()!=routes.Length)throw new InvalidOperationException("План содержит повторяющиеся профили или сервисы.");
   for(int i=0;i<routes.Length;i++){var route=routes[i];if(route.Target!="zapret"||!route.Enabled||route.ZapretProfileId!=profiles[i].Id||String.IsNullOrEmpty(route.BuiltinPreset)||!route.BuiltinPreset.StartsWith("wpf-best:",StringComparison.Ordinal)||route.BuiltinPreset.Length<=9)throw new InvalidOperationException("Профиль не связан с выбранным сервисом.");ZapretRoutes.Validate(route,profiles);}
   var indices=Enumerable.Range(0,routes.Length).Where(i=>!state.Lists.Any(r=>r.BuiltinPreset==routes[i].BuiltinPreset&&state.ZapretProfiles.Any(p=>p.Id==r.ZapretProfileId&&p.Settings!=null&&p.Settings.Strategy==profiles[i].Settings.Strategy))).ToArray();
   if(indices.Length==0)throw new InvalidOperationException("Эти профили уже созданы.");
   if(indices.Any(i=>state.ZapretProfiles.Any(p=>p.Id==profiles[i].Id)||state.Lists.Any(r=>r.Id==routes[i].Id)))throw new InvalidOperationException("План конфликтует с сохранёнными профилями или правилами.");
   state.ZapretProfiles.InsertRange(0,indices.Select(i=>profiles[i]));state.Lists.InsertRange(0,indices.Select(i=>routes[i]));return indices.Length;
  }
 }
}
