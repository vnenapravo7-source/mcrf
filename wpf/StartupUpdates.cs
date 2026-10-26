using System;
using System.Linq;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static UpdateItem[] AutomaticComponentUpdates(System.Collections.Generic.IEnumerable<UpdateItem> items){return (items??Enumerable.Empty<UpdateItem>()).Where(i=>i!=null&&i.Available&&!i.Ignored&&i.Id!="app"&&(i.Kind=="mcrf-core"||i.Kind=="optional")).ToArray();}
  async void CheckStartupUpdates(){await CheckStartupUpdatesCore();}
  async Task CheckStartupUpdatesCore(){if(closing||closeRequested)return;try{var snapshot=session.ConfigurationSnapshot();await Task.Run(()=>session.Updates.Check(snapshot));if(closing||closeRequested)return;RecordEvent("Обновления: "+JournalStyle.Redact(session.Updates.Activity));if(session.Updates.Preferences.AutoInstallComponents){foreach(var item in AutomaticComponentUpdates(session.Updates.Items)){if(closing||closeRequested)return;await session.Updates.Apply(item,session.State,session.Store);if(closing||closeRequested)return;RecordEvent("Автообновление компонента: "+JournalStyle.Redact(item.Name)+" · готово");}session.Zapret.ReloadUpdates();session.InvalidateStrategies();}await RefreshStartupUpdatesView();}catch(Exception ex){if(closing||closeRequested)return;string detail=JournalStyle.Redact(ex.Message);RecordEvent("Ошибка автообновления компонентов: "+detail);if(current=="Обновления")status.Text=FailureExplanations.UserMessage(detail);}}
  async Task RefreshStartupUpdatesView(){while(busy&&!closing&&!closeRequested&&current=="Обновления")await Task.Delay(100);if(!closing&&!closeRequested&&current=="Обновления")Page("Обновления");}
 }
}
