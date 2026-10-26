using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  UIElement ProfileTools(){return ProfileToolsWithServices(new StartupServices{Write=value=>Task.Run(()=>WpfWindowsStartup.Set(value,System.Reflection.Assembly.GetExecutingAssembly().Location)),Read=()=>Task.Run(()=>WpfWindowsStartup.ReadState())});}
  UIElement ProfileToolsWithServices(StartupServices services){
   var panel=new StackPanel{Margin=new Thickness(0)};var startup=new CheckBox{Name="compactStartupEnabled",Content=startupStateKnown?"Автозапуск Windows":"Автозапуск · неизвестно",IsThreeState=false,IsChecked=startupStateKnown?(bool?)startupEnabled:null,HorizontalAlignment=HorizontalAlignment.Center,Margin=new Thickness(0,8,0,10)};
   var feedback=Text("",11);feedback.Name="compactStartupFeedback";feedback.TextWrapping=TextWrapping.Wrap;feedback.TextTrimming=TextTrimming.WordEllipsis;feedback.MaxHeight=64;feedback.Visibility=Visibility.Collapsed;startup.Click+=async(s,e)=>await ChangeStartupOption(startup,services,feedback);panel.Children.Add(startup);panel.Children.Add(feedback);
   var backup=AsyncButton("Создать резервную копию",CreateProfileBackup);backup.Name="compactCreateBackup";backup.FontSize=11;backup.Padding=new Thickness(8,4,8,4);var actions=AsyncButton("Сброс / восстановление",ProfileActions);actions.Name="compactResetProfile";actions.FontSize=11;actions.Padding=new Thickness(8,4,8,4);var keys=AsyncButton("Горячие клавиши",HotkeysDialog);keys.Name="compactEditHotkeys";keys.ToolTip="Глобальные горячие клавиши · назначаются нажатием";keys.MinWidth=100;keys.Padding=new Thickness(10,5,10,5);backup.Margin=new Thickness(0,0,0,8);actions.Margin=new Thickness(0,0,4,0);keys.Margin=new Thickness(4,0,0,0);panel.Children.Remove(startup);startup.Margin=new Thickness(0);startup.HorizontalAlignment=HorizontalAlignment.Left;startup.FontSize=12;keys.MinWidth=0;keys.FontSize=12;keys.Margin=new Thickness(0);var startupRow=new Grid{Name="compactStartupRow",Margin=new Thickness(0,4,0,8)};startupRow.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(1.2,GridUnitType.Star)});startupRow.ColumnDefinitions.Add(new ColumnDefinition());Grid.SetColumn(keys,1);startupRow.Children.Add(startup);startupRow.Children.Add(keys);panel.Children.Insert(0,startupRow);panel.Children.Add(backup);panel.Children.Add(actions);return panel;
  }
  async Task CreateProfileBackup(){string folder=await session.BackupProfile(()=>closing||closeRequested);if(closing||closeRequested)return;status.Text="Резервная копия создана: "+folder;RecordEvent(status.Text);}
  Task ProfileActions(){return ProfileActionsWithServices(new ProfileResetServices{ChooseFolder=ChooseRestoreFolder,Confirm=ConfirmProfileReset,Reset=session.ResetProfileChecked,RecoveryRequired=ShowProfileRecoveryPage,Completed=()=>{selectedProfile=null;ApplyInterfaceMode();RefreshAfterOperation("Главная",session.LastProfileResetWarning??"Настройки профиля обновлены.");}});}
 }
}
