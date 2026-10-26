using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  sealed class BestProfileRow {public ServiceProfileRecommendation Item{get;set;}public string Service{get{return Item.Service.Name;}}public string Strategy{get{return JournalStyle.Redact(Item.Row.Name);}}public string Time{get{return Item.Milliseconds>0?Item.Milliseconds+" мс":"—";}}}
  static bool HasBestProfile(ClientState state,ServiceProfileRecommendation item){return state.Lists.Any(r=>r.BuiltinPreset=="wpf-best:"+item.Service.Id&&state.ZapretProfiles.Any(p=>p.Id==r.ZapretProfileId&&p.Settings!=null&&p.Settings.Strategy==item.Row.StrategyId));}
  Task BestProfiles(){return BestProfilesWithServices(session.Strategies,(service,id)=>ServicePresetRoutes.Create(service,"zapret",id,session.Core.DownloadTextAsync));}
  async Task BestProfilesWithServices(Func<Task<ZapretStrategy[]>> load,Func<ZapretService,string,Task<RouteList>> makeRoute){
   if(closing||closeRequested)return;
   var report=lastReport==null?null:ReportSnapshot(lastReport);
   var recommendations=(report==null||report.Rows==null?new ServiceProfileRecommendation[0]:BestServiceProfiles.Choose(report)).Where(r=>r.Service.Id!="discord"&&r.Service.Id!="discord-voice"&&r.Row.Family!="ByeTube").ToArray();
   if(recommendations.Length==0)throw new InvalidOperationException("Нет подтверждённых стратегий Zapret для создания профилей. Discord: интерфейс не подтверждает голос.");
   bool compact=ActualWidth<500;var window=Dialog("Лучший профиль для каждого сервиса",860,compact?Math.Min(640,Math.Max(500,390+recommendations.Length*40)):Math.Min(620,Math.Max(360,220+recommendations.Length*44)));window.Name="bestProfilesWindow";
   var panel=new DockPanel{Margin=new Thickness(16)};var note=Text("Выберите сервисы. Для каждого будет создан профиль проверенной стратегии. Существующие правила сохраняются. Создание не включает отключённый Zapret; работающий Zapret применит новые маршруты. Голос Discord требует отдельной проверки.");DockPanel.SetDock(note,Dock.Top);panel.Children.Add(note);
   var rows=recommendations.Select(r=>new BestProfileRow{Item=r}).ToArray();var table=Table(rows,"Сервис|Service","Стратегия|Strategy","Ответ|Time");table.Name="bestProfilesTable";table.SelectionMode=DataGridSelectionMode.Extended;foreach(var row in rows)table.SelectedItems.Add(row);table.MinHeight=90;table.MaxHeight=Math.Max(100,window.Height-330);
   if(compact){table.Columns[0].Width=100;table.Columns[1].Width=130;table.Columns[2].Width=64;}var strategyStyle=new Style(typeof(TextBlock),(Style)TryFindResource(typeof(TextBlock)));strategyStyle.Setters.Add(new Setter(FrameworkElement.ToolTipProperty,new System.Windows.Data.Binding("Strategy")));strategyStyle.Setters.Add(new Setter(TextBlock.TextTrimmingProperty,TextTrimming.CharacterEllipsis));((DataGridTextColumn)table.Columns[1]).ElementStyle=strategyStyle;
   var feedback=Text("",11);feedback.Name="bestProfilesFeedback";feedback.TextWrapping=TextWrapping.Wrap;feedback.TextTrimming=TextTrimming.WordEllipsis;feedback.MaxHeight=54;feedback.Visibility=Visibility.Collapsed;
   var create=new Button{Name="bestProfilesCreate",Content="Создать профили",IsEnabled=table.SelectedItems.Count>0};var cancel=new Button{Name="bestProfilesCancel",Content="Отмена"};
   var footer=new StackPanel{Margin=new Thickness(0,10,0,0)};footer.Children.Add(feedback);footer.Children.Add(Toolbar(create,cancel));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(table);window.Content=panel;window.MaxHeight=window.Height;window.SizeToContent=SizeToContent.Height;
   bool closed=false,preparing=false,saving=false;int created=0;var cancellation=new CancellationTokenSource();Task operation=Task.FromResult(0);
   table.SelectionChanged+=(s,e)=>create.IsEnabled=!preparing&&!saving&&table.SelectedItems.Count>0;
   window.Closing+=(s,e)=>{if(saving)e.Cancel=true;};window.Closed+=(s,e)=>{closed=true;cancellation.Cancel();};cancel.Click+=(s,e)=>window.Close();
   Func<Task> prepare=async()=>{
    preparing=true;create.IsEnabled=false;table.IsEnabled=false;feedback.Visibility=Visibility.Collapsed;create.Content="Подготовка…";
    try{
     var selected=table.SelectedItems.Cast<BestProfileRow>().Select(r=>r.Item).Where(item=>!HasBestProfile(session.State,item)).ToArray();if(selected.Length==0)throw new InvalidOperationException("Эти профили уже созданы.");
     var catalog=await load();cancellation.Token.ThrowIfCancellationRequested();if(closing||closeRequested)throw new OperationCanceledException();
     var profiles=new List<ZapretProfile>();var routes=new List<RouteList>();
     foreach(var item in selected){
      if(catalog==null||!catalog.Any(s=>s.Id==item.Row.StrategyId&&s.Available))throw new InvalidOperationException("Стратегия больше недоступна: "+JournalStyle.Redact(item.Row.Name));
      var settings=ManualServiceChecks.AppliedSettings(report,item.Service.Id,item.Row.StrategyId);settings.Strategy=item.Row.StrategyId;settings.Family=item.Row.Family;var profile=new ZapretProfile{Name=item.Service.Name,Enabled=true,Settings=settings};
      var route=await makeRoute(item.Service,profile.Id);cancellation.Token.ThrowIfCancellationRequested();if(closing||closeRequested)throw new OperationCanceledException();route.BuiltinPreset="wpf-best:"+item.Service.Id;ZapretScope.Validate(route);profiles.Add(profile);routes.Add(route);
     }
     preparing=false;saving=true;cancel.IsEnabled=false;create.Content="Сохранение…";
     int proposed=0;bool committed=await session.ChangeConfigurationIf(()=>!closed&&!closing&&!closeRequested&&!cancellation.IsCancellationRequested,()=>proposed=BestProfilePlan.Apply(session.State,profiles.ToArray(),routes.ToArray()));
     saving=false;if(committed){created=proposed;if(!closed)window.DialogResult=true;}
    }catch(OperationCanceledException){}
    catch(Exception ex){if(!closed&&!closing&&!closeRequested){feedback.Text=JournalStyle.Redact(ex.Message);feedback.ToolTip=feedback.Text;feedback.Foreground=LogColor("ERROR");feedback.Visibility=Visibility.Visible;}}
    finally{preparing=saving=false;if(!closed){cancel.IsEnabled=table.IsEnabled=true;create.Content="Создать профили";create.IsEnabled=table.SelectedItems.Count>0;if(closing||closeRequested)window.Close();}}
   };
   create.Click+=(s,e)=>{if(closed||preparing||saving||closing||closeRequested)return;operation=prepare();};
   var lifetime=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(100)};lifetime.Tick+=(s,e)=>{if(closing||closeRequested){cancellation.Cancel();window.Close();}};lifetime.Start();
   Exception modalError=null;try{window.ShowDialog();}catch(Exception ex){modalError=ex;}
   closed=true;lifetime.Stop();cancellation.Cancel();await operation;cancellation.Dispose();if(modalError!=null)throw modalError;
   if(created>0&&!closing&&!closeRequested){RegisterHotkeys();RefreshAfterOperation("Zapret","Создано профилей: "+created+". Отключённый Zapret не включался.");}
  }
 }
}
