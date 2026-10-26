using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class ProfileRecoveryServices {
  internal Func<Task<string>> ChooseFolder;
  internal Func<string,Task<bool>> Confirm;
  internal Func<string,Task> Restore;
 }
 public sealed partial class MainWindow {
  bool profileRecoveryMode;
  void ShowProfileRecoveryPage(string reason){ShowProfileRecoveryPageWithServices(reason,new ProfileRecoveryServices{ChooseFolder=()=>ChooseRestoreFolder(this),Confirm=message=>ConfirmProfileReset(this,message),Restore=async folder=>{if(session!=null){if(!await session.ResetProfileChecked(2,folder,false,()=>closing||closeRequested))throw new OperationCanceledException();}else await Task.Run(()=>ProfileReset.Restore(new StateStore(root),folder,false));}});}
  void ShowProfileRecoveryPageWithServices(string reason,ProfileRecoveryServices services){
   profileRecoveryMode=true;health.Stop();hotkeyEditing=true;forceExit=true;navigation.IsEnabled=false;if(compactNavigation!=null){compactNavigation.IsEnabled=false;compactNavigation.Visibility=Visibility.Collapsed;}if(compactBackButton!=null)compactBackButton.Visibility=Visibility.Collapsed;
   if(compactThemeButton!=null){compactThemeButton.Visibility=Visibility.Collapsed;var chrome=compactThemeButton.Parent as Panel;if(chrome!=null)foreach(UIElement child in chrome.Children){var button=child as Button;if(button!=null&&button.Name=="compactHeaderSettings")button.Visibility=Visibility.Collapsed;}}
   title.Text="Восстановление профиля";title.FontSize=22;title.FontWeight=FontWeights.SemiBold;title.Margin=new Thickness(0,0,0,12);title.Visibility=Visibility.Visible;body.Children.Clear();status.Text="Профиль заблокирован; требуется восстановление.";
   var panel=new StackPanel{Margin=new Thickness(4,12,4,12)};var description=Text("Обычное сохранение и запуск соединений заблокированы. Выберите заведомо исправную резервную копию. Системный hosts изменяться не будет.");description.Foreground=Foreground;description.Opacity=.85;panel.Children.Add(description);
   var detail=Text(JournalStyle.Redact(reason),11);detail.Foreground=Foreground;detail.Opacity=.85;detail.Name="profileRecoveryReason";panel.Children.Add(new ScrollViewer{Content=detail,MaxHeight=160,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,Margin=new Thickness(0,12,0,12)});
   var feedback=Text("",11);feedback.Name="profileRecoveryFeedback";feedback.TextWrapping=TextWrapping.Wrap;feedback.Visibility=Visibility.Collapsed;panel.Children.Add(feedback);
   var restore=Button("Восстановить из копии…",()=>{});StyleCompactButton(restore);restore.Margin=new Thickness(0,0,0,10);restore.Name="profileRecoveryRestore";bool pending=false,complete=false;
   restore.Click+=async(s,e)=>{if(pending||complete||busy||closing||closeRequested)return;pending=true;restore.IsEnabled=false;bool ownsBusy=false;try{
     string folder=await services.ChooseFolder();if(closing||closeRequested||String.IsNullOrWhiteSpace(folder))return;
     if(!await services.Confirm("Восстановить выбранную копию? Текущее состояние будет сохранено отдельно. После восстановления перезапустите MCRF.")||closing||closeRequested)return;
     if(busy)return;ownsBusy=true;busy=true;await services.Restore(folder);if(closing||closeRequested)return;complete=true;feedback.Text="Профиль восстановлен. Закройте и снова запустите MCRF.";feedback.Foreground=LogColor("SUCCESS");feedback.Visibility=Visibility.Visible;status.Text=feedback.Text;
    }catch(Exception ex){var restored=ex as ProfileReset.RestoreFailure;if(restored!=null&&restored.Applied)complete=true;if(!closing&&!closeRequested){feedback.Text=JournalStyle.Redact(ex.Message);feedback.Foreground=LogColor("ERROR");feedback.Visibility=Visibility.Visible;}}
    finally{if(ownsBusy)busy=false;pending=false;if(!closing&&!closeRequested)restore.IsEnabled=!complete;if(closeRequested&&ownsBusy)Close();}
   };panel.Children.Add(restore);var close=Button("Закрыть MCRF",()=>Close());StyleCompactButton(close);panel.Children.Add(close);body.Children.Add(new ScrollViewer{Name="profileRecoveryScroll",Content=panel,MaxHeight=Math.Max(160,ActualHeight-200),VerticalScrollBarVisibility=ScrollBarVisibility.Auto});
  }
 }
}
