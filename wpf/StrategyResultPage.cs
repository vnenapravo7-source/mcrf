using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  void ResultsPage(){
   bool compact=ActualWidth<500&&!detachedWorkspaceOpen;body.Children.Clear();var panel=new DockPanel();var header=new StackPanel();header.Children.Add(Text(reportFromEditor?"Результаты проверки копии · выберите конкретную область применения":"Результаты проверки · ничего не применяется автоматически"));
   var report=lastReport??new ZapretCheckReport{Settings=new ZapretSettings{CheckServices=new List<string>()},ServiceIds=new List<string>()};
   var targets=new[]{new Choice{Key="new",Label="Новый профиль для выбранных сервисов"}}.Concat(session.State.ZapretProfiles.Select(p=>new Choice{Key=p.Id,Label="Профиль · "+p.Name})).ToArray();
   var target=new ComboBox{Name="resultApplyTarget",ItemsSource=targets,SelectedItem=targets.FirstOrDefault(c=>c.Key==report.ProfileId)??targets[0]};target.ToolTip=target.SelectedItem;target.SelectionChanged+=(s,e)=>target.ToolTip=target.SelectedItem;
   var checks=ServiceChoices(null);foreach(var c in checks)c.IsChecked=(report.ServiceIds??new List<string>()).Contains((string)c.Tag);
   var services=ServicePicker(checks);var targetField=Field("Куда применить",target);var serviceField=Field("Сервисы для нового профиля",services);if(compact){header.Children.Add(targetField);header.Children.Add(serviceField);}else header.Children.Add(Columns(targetField,serviceField));DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);
   var table=CheckTable(report);table.Name="strategyResultsTable";table.ItemsSource=ResultRows(report);var detail=Text("Выберите строку результата.");detail.Name="strategyResultDetail";detail.TextWrapping=TextWrapping.Wrap;
   var footer=new StackPanel();footer.Children.Add(new ScrollViewer{Content=detail,MaxHeight=compact?64:90,VerticalScrollBarVisibility=ScrollBarVisibility.Auto});
   var apply=WorkspaceResultButton("Применить выбранное",async()=>{
    var selected=table.SelectedItem as ResultRow;if(selected==null||!StrategyResultApplication.CanApply(selected.Source))throw new InvalidOperationException("Выберите завершённую успешную проверку стратегии.");
    string profileId=target.SelectedItem==null?null:((Choice)target.SelectedItem).Key;var serviceIds=checks.Where(c=>c.IsChecked==true).Select(c=>(string)c.Tag).ToArray();
    var json=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};var row=json.Deserialize<ZapretCheckRow>(json.Serialize(selected.Source));
    header.IsEnabled=table.IsEnabled=false;
    try{await ApplyStrategyResult(report,row,profileId,serviceIds);}finally{header.IsEnabled=table.IsEnabled=true;}
   },detail);apply.Name="strategyResultApply";Accent(apply);apply.IsEnabled=false;
   table.SelectionChanged+=(s,e)=>{
    var selected=table.SelectedItem as ResultRow;var row=selected==null?null:selected.Source;
    detail.Text=row==null?"Выберите строку результата.":JournalStyle.Redact(String.Join("\n",(row.Services??new List<ZapretServiceResult>()).Select(v=>v.Id+": "+v.Detail)));
    if(row!=null&&String.IsNullOrEmpty(row.StrategyId))detail.Text="Прямой выход можно проверить и назначить через мастер настройки. Здесь применяются только стратегии.\n"+detail.Text;
    if(row!=null&&row.Family=="ByeTube")detail.Text="Будет сохранена общая стратегия ByeTube; профиль Zapret не создаётся.\n"+detail.Text;
    bool bye=row!=null&&row.Family=="ByeTube";target.IsEnabled=services.IsEnabled=!bye;apply.Content=bye?"Сохранить стратегию ByeTube":"Применить выбранное";apply.IsEnabled=StrategyResultApplication.CanApply(row);
   };
   var actions=new List<Button>{apply};if(!reportFromEditor){var best=AsyncButton("Лучшие профили",BestProfiles);var load=AsyncButton("Загрузить сохранённые",async()=>{var loaded=await Task.Run(()=>ZapretChecks.Load(root));if(closing||closeRequested)return;lastReport=loaded;ShowWorkspaceResults();});if(compact){FinishCompactToolButton(best,"resultBestProfiles","Лучшие профили","deploy");FinishCompactToolButton(load,"resultLoadReport","Загрузить сохранённый отчёт","refresh");foreach(var tool in new[]{best,load}){tool.Width=38;tool.Height=38;tool.MinHeight=38;tool.Margin=new Thickness(4,0,0,0);}}actions.Add(best);actions.Add(load);}
   if(!reportFromEditor){var manual=WorkspaceResultButton("Очные проверки",async()=>{await PrepareTests();scanCancellation=new System.Threading.CancellationTokenSource();try{await ContinueSetupWizard(report,scanCancellation.Token,true);status.Text="Результаты очных проверок сохранены.";}finally{scanCancellation.Dispose();scanCancellation=null;}},detail);manual.Name="resultManualChecks";manual.IsEnabled=SetupWizardFlow.CanContinue(report);footer.Children.Add(manual);}
   if(!compact&&!detachedWorkspaceOpen)actions.Add(Button(reportFromEditor?"В редактор":"К Zapret",()=>Page(reportFromEditor?"Редактор стратегий":"Zapret")));
   if(compact){var actionRow=new DockPanel{Margin=new Thickness(0,8,0,0)};var tools=new StackPanel{Orientation=Orientation.Horizontal};foreach(var tool in actions.Skip(1))tools.Children.Add(tool);DockPanel.SetDock(tools,Dock.Right);actionRow.Children.Add(tools);actionRow.Children.Add(apply);footer.Children.Add(actionRow);}else footer.Children.Add(Toolbar(actions.ToArray()));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(table);body.Children.Add(Card(panel));
  }
  async Task ApplyStrategyResult(ZapretCheckReport report,ZapretCheckRow row,string profileId,string[] serviceIds){
   if(closing||closeRequested)throw new OperationCanceledException();
   var plan=new StrategyResultPlan();string success;
   if(row.Family=="ByeTube"){plan.ByeTubeStrategy=ByeTube.Strategies[StrategyResultApplication.ByeTubeIndex(row)];success="Стратегия ByeTube сохранена.";}
   else{
    if(String.IsNullOrEmpty(profileId))throw new InvalidOperationException("Выберите профиль для применения.");
    var catalog=await session.Strategies();if(closing||closeRequested)throw new OperationCanceledException();var strategy=catalog.FirstOrDefault(v=>v.Id==row.StrategyId&&v.Available);if(strategy==null)throw new InvalidOperationException("Промежуточный вариант не сохранён. Выберите подтверждённую стратегию.");plan.Strategy=strategy;
    ZapretProfile profile;
    if(profileId=="new"){
     var services=ZapretChecks.Selected(serviceIds);if(services.Length==0)throw new InvalidOperationException("Выберите сервисы.");if(services.Any(v=>row.Access(v.Id)!=ServiceAccess.Available))throw new InvalidOperationException("Не все выбранные сервисы подтверждены этим вариантом.");
     profile=new ZapretProfile{Name=strategy.ShortName+" · выбранные сервисы",Enabled=true,Settings=new ZapretSettings{Strategy=strategy.Id,Family=strategy.Family,MatchMode="addresses"}};plan.CreatedProfile=profile;
     var additions=new List<RouteList>();foreach(var service in services){additions.Add(await ServicePresetRoutes.Create(service,"zapret",profile.Id,session.Core.DownloadTextAsync));if(closing||closeRequested)throw new OperationCanceledException();}plan.Additions=additions.ToArray();
    }else{profile=session.State.ZapretProfiles.FirstOrDefault(p=>p.Id==profileId);if(profile==null)throw new InvalidOperationException("Профиль уже удалён. Выберите другой профиль.");plan.ProfileId=profileId;}
    if(closing||closeRequested)throw new OperationCanceledException();if(!Confirm("Применить «"+JournalStyle.Redact(row.Name)+"» только к «"+profile.Name+"»? Остальные профили сохраняются.","Применить результат"))return;
    success="Применено к профилю «"+profile.Name+"».";
   }
   string warning=await StrategyResultApplication.Commit(report,row.StrategyId,()=>session.ChangeConfiguration(()=>{if(closing||closeRequested)throw new OperationCanceledException();plan.Apply(session.State);},plan.ByeTubeStrategy==null),()=>Task.Run(()=>ZapretChecks.Save(root,report)));
   if(!closing&&!closeRequested)status.Text=warning??success;
  }
 }
}
