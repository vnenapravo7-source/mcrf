using System;
using System.Linq;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static string GameTemplateKey(string kind){switch(kind){case "alt11":return "game:wardogs-alt11";case "alternative":return "game:wardogs-alternative";case "arhekog":return "game:wardogs-arhekog";default:throw new ArgumentException("Неизвестный игровой шаблон.");}}
  static Tuple<ZapretProfile,RouteList> BuildGameTemplate(string kind,ZapretStrategy[] catalog){
   GameTemplateKey(kind);catalog=catalog??new ZapretStrategy[0];
   var pair=kind=="alt11"?SteamGameTemplates.Wardogs(catalog):kind=="alternative"?SteamGameTemplates.WardogsAlternative(catalog):SteamGameTemplates.WardogsArchive(catalog);
   var strategy=catalog.First(s=>s.Id==pair.Item1.Settings.Strategy);
   if((pair.Item2.GameTcpEnabled||pair.Item2.GameUdpEnabled)&&(!strategy.GameAvailable||(pair.Item2.GameTcpEnabled&&String.IsNullOrWhiteSpace(strategy.GameTcp))||(pair.Item2.GameUdpEnabled&&String.IsNullOrWhiteSpace(strategy.GameUdp))))throw new InvalidOperationException("У Flowseal ALT11 недоступны игровые фильтры TCP / UDP. Обновите набор стратегий.");
   return pair;
  }
  async Task<bool> AddGameTemplate(string kind,Func<Task<ZapretStrategy[]>> load){
   string key=GameTemplateKey(kind);if(closing||closeRequested)return false;
   if(session.State.Lists.Any(r=>r.BuiltinPreset==key))throw new InvalidOperationException("Шаблон уже добавлен. Измените существующее правило в маршрутах.");
   var catalog=await load();if(closing||closeRequested)return false;
   return await session.ChangeConfigurationIf(()=>!closing&&!closeRequested,()=>{
    if(session.State.Lists.Any(r=>r.BuiltinPreset==key))throw new InvalidOperationException("Шаблон уже добавлен. Измените существующее правило в маршрутах.");
    var pair=BuildGameTemplate(kind,catalog);ZapretRoutes.Validate(pair.Item2,session.State.ZapretProfiles.Concat(new[]{pair.Item1}));
    session.State.ZapretProfiles.Add(pair.Item1);
    if(kind=="arhekog"&&!session.State.Lists.Any(r=>r.BuiltinPreset=="game:wardogs-arhekog-protect"))session.State.Lists.Insert(0,new RouteList{Name="Wardogs arhekog · без подмены",Target="direct",Text=SteamGameTemplates.WardogsArchiveProtected,MatchMode="addresses",BuiltinPreset="game:wardogs-arhekog-protect",Enabled=true});
    session.State.Lists.Add(pair.Item2);
   });
  }
 }
}
