using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
namespace Mcrf.Wpf {
 internal sealed class ProfileResetServices {
  internal Func<Window,Task<string>> ChooseFolder;
  internal Func<Window,string,Task<bool>> Confirm;
  internal Func<int,string,bool,Func<bool>,Task<bool>> Reset;
  internal Action Completed;
  internal Action<string> RecoveryRequired;
 }
 public sealed partial class MainWindow {
  sealed class RestorePickerOwner:System.Windows.Forms.IWin32Window {public IntPtr Handle{get;private set;}public RestorePickerOwner(Window owner){Handle=new WindowInteropHelper(owner).Handle;}}
  Task<string> ChooseRestoreFolder(Window owner){using(var picker=new System.Windows.Forms.FolderBrowserDialog{Description="Выберите папку резервной копии MCRF"})return Task.FromResult(picker.ShowDialog(new RestorePickerOwner(owner))==System.Windows.Forms.DialogResult.OK?picker.SelectedPath:null);}
  Task<bool> ConfirmProfileReset(Window owner,string message){var w=Dialog("Подтверждение",440,300);w.Owner=owner;w.SizeToContent=SizeToContent.Height;var panel=new StackPanel{Margin=new Thickness(20)};panel.Children.Add(Text(message));var cancel=Button("Отмена",()=>w.DialogResult=false);cancel.IsDefault=true;cancel.IsCancel=true;panel.Children.Add(Toolbar(Button("Подтвердить",()=>w.DialogResult=true),cancel));w.Content=panel;return Task.FromResult(w.ShowDialog()==true);}
  async Task ProfileActionsWithServices(ProfileResetServices services){
   var window=Dialog("Сброс и восстановление",470,490);window.Name="profileResetDialog";window.SizeToContent=SizeToContent.Height;
   var panel=new StackPanel{Margin=new Thickness(20)};panel.Children.Add(Text("Операция изменит текущий профиль MCRF и остановит его движки. При восстановлении сначала проверяется резервная копия."));
   var choice=new ComboBox{Name="profileResetMode",ItemsSource=new[]{"Сбросить с резервной копией","Сбросить без резервной копии","Восстановить резервную копию"},SelectedIndex=0,Margin=new Thickness(0,12,0,10)};panel.Children.Add(choice);
   string folder=null;var path=Text("Папка не выбрана",11);path.Name="profileRestorePath";path.TextTrimming=TextTrimming.CharacterEllipsis;path.MaxHeight=48;
   var browse=Button("Выбрать папку",()=>{});browse.Name="profileRestoreBrowse";var folderPanel=new StackPanel();folderPanel.Children.Add(path);folderPanel.Children.Add(browse);panel.Children.Add(folderPanel);
   var hosts=new CheckBox{Name="profileResetHosts",Content="Также изменить записи MCRF в системном hosts",Style=(Style)FindResource("LabeledPacketCheck"),IsChecked=false,Margin=new Thickness(0,12,0,12)};panel.Children.Add(hosts);
   var feedback=Text("",11);feedback.Name="profileResetFeedback";feedback.Visibility=Visibility.Collapsed;var feedbackScroll=new ScrollViewer{Name="profileResetFeedbackScroll",Content=feedback,MaxHeight=100,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,Visibility=Visibility.Collapsed};panel.Children.Add(feedbackScroll);
   bool dead=false,pending=false,critical=false,recoveryRequired=false;Task flight=Task.FromResult(0);
   Func<bool> cancelled=()=>dead||closing||closeRequested;
   var proceed=Button("Сбросить",()=>{});proceed.Name="profileResetProceed";var cancel=Button("Отмена",()=>window.Close());cancel.IsCancel=true;
   Action sync=()=>{folderPanel.Visibility=choice.SelectedIndex==2?Visibility.Visible:Visibility.Collapsed;proceed.Content=choice.SelectedIndex==2?"Восстановить":"Сбросить";choice.IsEnabled=!pending&&!recoveryRequired;hosts.IsEnabled=!pending&&!recoveryRequired;browse.IsEnabled=!pending&&!recoveryRequired;proceed.IsEnabled=!pending&&!recoveryRequired&&choice.SelectedIndex>=0&&(choice.SelectedIndex!=2||folder!=null);};
   Action<Exception> error=ex=>{feedback.Text=SplifyWin.JournalStyle.Redact(ex.Message);feedback.ToolTip=feedback.Text;feedback.Foreground=LogColor("ERROR");feedback.Visibility=Visibility.Visible;feedbackScroll.Visibility=Visibility.Visible;};
   Func<Task> choose=async()=>{pending=true;sync();try{string selected=await services.ChooseFolder(window);if(!cancelled()&&!String.IsNullOrWhiteSpace(selected)){folder=selected;path.Text=folder;path.ToolTip=folder;}}catch(Exception ex){if(!cancelled())error(ex);}finally{pending=false;if(!cancelled())sync();}};
   Func<Task> apply=async()=>{int mode=choice.SelectedIndex;bool include=hosts.IsChecked==true;string selected=folder;if(recoveryRequired||mode<0||mode>2||mode==2&&selected==null)return;pending=true;sync();feedback.Visibility=Visibility.Collapsed;feedbackScroll.Visibility=Visibility.Collapsed;
    try{string message=mode==2?"Восстановить выбранную копию? Текущие настройки будут сохранены в резервной копии.":mode==0?"Сбросить настройки с резервной копией?":"Сбросить настройки БЕЗ резервной копии? Это действие нельзя отменить.";
     if(!await services.Confirm(window,message)||cancelled())return;
     if(include&&(!await services.Confirm(window,"Изменить общие записи MCRF в системном hosts? Пользовательские записи сохранятся.")||cancelled()))return;
     critical=true;bool applied=await services.Reset(mode,selected,include,cancelled);critical=false;if(applied&&!cancelled()){services.Completed();window.DialogResult=true;}
    }catch(Exception ex){critical=false;var restore=ex as SplifyWin.ProfileReset.RestoreFailure;var reset=ex as SplifyWin.ProfileReset.ResetFailure;if(restore!=null&&restore.RecoveryRequired||reset!=null&&reset.RecoveryRequired)recoveryRequired=true;if(!cancelled())error(ex);}finally{pending=false;if(!cancelled())sync();}
   };
   browse.Click+=(s,e)=>{if(!pending&&!cancelled())flight=choose();};proceed.Click+=(s,e)=>{if(!pending&&!cancelled())flight=apply();};choice.SelectionChanged+=(s,e)=>sync();
   panel.Children.Add(Toolbar(proceed,cancel));window.Content=panel;sync();window.Closing+=(s,e)=>{if(critical)e.Cancel=true;};window.Closed+=(s,e)=>dead=true;
   var lifetime=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(100)};lifetime.Tick+=(s,e)=>{if((closing||closeRequested)&&!critical)window.Close();};lifetime.Start();try{window.ShowDialog();await flight;}finally{lifetime.Stop();}if(recoveryRequired&&!closing&&!closeRequested&&services.RecoveryRequired!=null)services.RecoveryRequired(feedback.Text);
  }
 }
}
