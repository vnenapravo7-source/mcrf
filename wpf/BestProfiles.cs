using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  sealed class BestProfileRow {public ServiceProfileRecommendation Item{get;set;}public string Service{get{return Item.Service.Name;}}public string Strategy{get{return Item.Row.Name;}}public string Time{get{return Item.Milliseconds>0?Item.Milliseconds+" мс":"—";}}}
  async Task BestProfiles(){
   var recommendations=BestServiceProfiles.Choose(lastReport).Where(r=>r.Service.Id!="discord"&&r.Service.Id!="discord-voice").ToArray();if(recommendations.Length==0)throw new InvalidOperationException("Нет подтверждённых стратегий для создания профилей. Discord: интерфейс не подтверждает голос.");var window=Dialog("Лучший профиль для каждого сервиса",960,620);var panel=new DockPanel{Margin=new Thickness(24)};var note=Text("Выберите сервисы. Для каждого будет создан отдельный профиль с лучшим проверенным вариантом. Существующие маршруты не заменяются. Голос Discord требует отдельной голосовой проверки.");DockPanel.SetDock(note,Dock.Top);panel.Children.Add(note);var rows=recommendations.Select(r=>new BestProfileRow{Item=r}).ToArray();var table=Table(rows,"Сервис|Service","Стратегия|Strategy","Ответ|Time");table.SelectionMode=DataGridSelectionMode.Extended;foreach(var row in rows)table.SelectedItems.Add(row);var actions=Toolbar(Button("Создать профили",()=>{if(table.SelectedItems.Count>0)window.DialogResult=true;}),Button("Отмена",()=>window.DialogResult=false));DockPanel.SetDock(actions,Dock.Bottom);panel.Children.Add(actions);panel.Children.Add(table);window.Content=panel;if(window.ShowDialog()!=true)return;
   var selected=table.SelectedItems.Cast<BestProfileRow>().ToArray();var catalog=await session.Strategies();var profiles=new List<ZapretProfile>();var routes=new List<RouteList>();foreach(var row in selected){var item=row.Item;if(!catalog.Any(s=>s.Id==item.Row.StrategyId))throw new InvalidOperationException("Стратегия больше недоступна: "+item.Row.Name);if(session.State.Lists.Any(r=>r.BuiltinPreset=="wpf-best:"+item.Service.Id&&session.State.ZapretProfiles.Any(p=>p.Id==r.ZapretProfileId&&p.Settings.Strategy==item.Row.StrategyId)))continue;var settings=ManualServiceChecks.AppliedSettings(lastReport,item.Service.Id,item.Row.StrategyId);settings.Strategy=item.Row.StrategyId;settings.Family=item.Row.Family;var profile=new ZapretProfile{Name=item.Service.Name,Enabled=true,Settings=settings};var route=await ServicePresetRoutes.Create(item.Service,"zapret",profile.Id,session.Core.DownloadTextAsync);route.BuiltinPreset="wpf-best:"+item.Service.Id;ZapretScope.Validate(route);profiles.Add(profile);routes.Add(route);}if(routes.Count==0)throw new InvalidOperationException("Эти профили уже созданы.");await session.ChangeConfiguration(()=>{session.State.ZapretProfiles.InsertRange(0,profiles);session.State.Lists.InsertRange(0,routes);},true);RegisterHotkeys();RefreshAfterOperation("Zapret");
  }
 }
}
