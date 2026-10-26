using System;
using System.Threading.Tasks;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static async Task SaveVkTurnDraft(ServerNode node,ServerNode candidate,VkTurnServerPreferences draft,string name,Func<Task> save){string oldName=node.Name,oldGeneration=node.CsqttGeneration,oldLink=node.Link;var oldSettings=node.TurnPreferences;try{node.Name=name;node.CsqttGeneration=candidate.CsqttGeneration;node.Link=candidate.Link;node.TurnPreferences=draft;await save();}catch{node.Name=oldName;node.CsqttGeneration=oldGeneration;node.Link=oldLink;node.TurnPreferences=oldSettings;throw;}}
  bool PersistVkToggle(CheckBox check,bool previous,Action<bool> save,Action committed=null){if(closing||closeRequested){check.IsChecked=previous;return previous;}bool next=check.IsChecked==true;try{save(next);}catch(Exception ex){check.IsChecked=previous;string detail=JournalStyle.Redact(ex.Message);status.Text=FailureExplanations.UserMessage(detail);RecordEvent("Ошибка сохранения VK TURN: "+detail);return previous;}try{if(committed!=null)committed();}catch(Exception ex){string detail=JournalStyle.Redact(ex.Message);status.Text="Настройка сохранена; не удалось обновить интерфейс.";RecordEvent("Ошибка обновления VK TURN после сохранения: "+detail);}return next;}
 }
}
