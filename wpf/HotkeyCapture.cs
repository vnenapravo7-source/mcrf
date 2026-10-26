using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static string CapturedChord(Key key,ModifierKeys modifiers){if(key==Key.System||key==Key.LeftCtrl||key==Key.RightCtrl||key==Key.LeftAlt||key==Key.RightAlt||key==Key.LeftShift||key==Key.RightShift||key==Key.LWin||key==Key.RWin)return null;var native=(System.Windows.Forms.Keys)KeyInterop.VirtualKeyFromKey(key);var parts=new List<string>();if((modifiers&ModifierKeys.Control)!=0)parts.Add("Ctrl");if((modifiers&ModifierKeys.Shift)!=0)parts.Add("Shift");if((modifiers&ModifierKeys.Alt)!=0)parts.Add("Alt");parts.Add(ProfileHotkey.KeyLabel(native));return ProfileHotkey.Parse(String.Join(" + ",parts)).ToString();}
  static readonly DependencyProperty CaptureCancelProperty=DependencyProperty.RegisterAttached("CaptureCancel",typeof(Func<bool>),typeof(MainWindow));
  static readonly DependencyProperty CaptureActiveProperty=DependencyProperty.RegisterAttached("CaptureActive",typeof(Func<bool>),typeof(MainWindow));
  internal static bool ChordCaptureActive(DependencyObject focused){var active=focused==null?null:focused.GetValue(CaptureActiveProperty) as Func<bool>;return active!=null&&active();}
  internal static bool CancelChordCapture(DependencyObject focused){var cancel=focused==null?null:focused.GetValue(CaptureCancelProperty) as Func<bool>;return cancel!=null&&cancel();}
  Button CaptureButton(string chord){var button=new Button{Content=String.IsNullOrEmpty(chord)?"Назначить":chord,Style=(Style)FindResource("PickerAction"),Background=(System.Windows.Media.Brush)FindResource("Field"),HorizontalContentAlignment=HorizontalAlignment.Left,Padding=new Thickness(10,4,10,4)};button.Tag=chord;bool listening=false;button.Name="hotkeyCapture";Action cancel=()=>{listening=false;button.Content=button.Tag??"Не назначена";};button.SetValue(CaptureActiveProperty,new Func<bool>(()=>listening));button.SetValue(CaptureCancelProperty,new Func<bool>(()=>{if(!listening)return false;cancel();return true;}));button.LostKeyboardFocus+=(s,e)=>{if(listening)cancel();};button.Click+=(s,e)=>{listening=true;button.Content="Нажмите сочетание…";button.Focus();};button.PreviewKeyDown+=(s,e)=>{if(!listening)return;e.Handled=true;Key key=e.Key==Key.System?e.SystemKey:e.Key;if(key==Key.Escape){cancel();return;}if((key==Key.Back||key==Key.Delete)&&Keyboard.Modifiers==ModifierKeys.None){button.Tag=null;button.Content="Не назначена";listening=false;return;}try{string captured=CapturedChord(key,Keyboard.Modifiers);if(captured!=null){button.Tag=captured;button.Content=captured;listening=false;}}catch(Exception ex){status.Text=ex.Message;}};return button;}
  Task HotkeysDialog(){return EditHotkeys(null);}
  Task ProfileHotkeyDialog(ZapretProfile profile){return EditHotkeys(profile.Id);}
  Task EditHotkeys(string profileId){return EditHotkeysWithServices(profileId,NativeHotkeyServices());}
  async Task EditHotkeysWithServices(string profileId,HotkeyServices services){
   if(closing||closeRequested||hotkeyEditing)return;
   if(profileId!=null&&!session.State.ZapretProfiles.Any(p=>p.Id==profileId))throw new InvalidOperationException("Профиль уже удалён. Откройте список заново.");
   var ids=profileId==null?HotkeySettings.Globals:new[]{profileId};var window=Dialog(profileId==null?"Глобальные горячие клавиши":"Горячая клавиша профиля",700,profileId==null?470:280);window.Name="hotkeySettingsWindow";var panel=new DockPanel{Margin=new Thickness(20,12,20,20)};var forms=new StackPanel();forms.Children.Add(Text("Нажмите поле, затем сочетание Ctrl / Shift / Alt с обычной клавишей. Backspace снимает назначение; Escape отменяет ввод."));
   var edits=new Dictionary<string,Button>();foreach(string id in ids){
    string chord=null,label=id=="@zapret"?"Zapret":id=="@warp"?"WARP":id=="@tun"?"TUN":session.State.ZapretProfiles.First(p=>p.Id==id).Name;
    if(id.StartsWith("@")){if(session.State.GlobalHotkeys!=null)session.State.GlobalHotkeys.TryGetValue(id,out chord);}else chord=session.State.ZapretProfiles.First(p=>p.Id==id).Hotkey;
    var capture=CaptureButton(chord);capture.ToolTip=label;if(id.StartsWith("@")&&!ModuleAvailable(id)){capture.IsEnabled=false;capture.ToolTip=label+" · доступно при включении всех функций";}edits[id]=capture;var field=Field(label,capture);var caption=field.Children.OfType<TextBlock>().First();caption.TextWrapping=TextWrapping.NoWrap;caption.TextTrimming=TextTrimming.CharacterEllipsis;caption.ToolTip=label;forms.Children.Add(field);
   }
   var feedback=Text("",11);feedback.Name="hotkeySettingsFeedback";feedback.TextWrapping=TextWrapping.Wrap;feedback.TextTrimming=TextTrimming.WordEllipsis;feedback.MaxHeight=64;feedback.Margin=new Thickness(0,6,0,6);feedback.Visibility=Visibility.Collapsed;
   var save=new Button{Name="hotkeySettingsSave",Content="Сохранить"};var cancel=new Button{Name="hotkeySettingsCancel",Content="Отмена"};var footer=new StackPanel{Margin=new Thickness(0,12,0,0)};footer.Children.Add(feedback);save.Margin=new Thickness(0,0,0,8);cancel.Margin=new Thickness(0);footer.Children.Add(save);footer.Children.Add(cancel);DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(new ScrollViewer{Content=forms,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled});if(ActualWidth<500)window.Height=Math.Min(profileId==null?470:400,ActualHeight-48);window.MaxHeight=window.Height;window.SizeToContent=SizeToContent.Height;window.Content=panel;
   double baseHeight=window.Height;bool closed=false,saving=false,committed=false;Task operation=Task.FromResult(0);window.Closing+=(s,e)=>{if(saving)e.Cancel=true;};window.Closed+=(s,e)=>closed=true;cancel.Click+=(s,e)=>window.Close();
   Func<Task> persist=async()=>{
    saving=true;save.IsEnabled=cancel.IsEnabled=forms.IsEnabled=false;feedback.Visibility=Visibility.Collapsed;window.Height=baseHeight;
    try{var draft=edits.ToDictionary(p=>p.Key,p=>p.Value.Tag==null?null:Convert.ToString(p.Value.Tag));committed=await session.SaveHotkeys(draft,services,()=>closed||closing||closeRequested);saving=false;if(committed&&!closed)window.DialogResult=true;}
    catch(Exception ex){if(!closed&&!closing&&!closeRequested){feedback.Text=ex is OperationCanceledException?"Операция отменена. Назначения не изменены.":JournalStyle.Redact(ex.Message);feedback.ToolTip=feedback.Text;feedback.Foreground=LogColor("ERROR");feedback.Visibility=Visibility.Visible;feedback.Measure(new Size(Math.Max(100,window.Width-40),Double.PositiveInfinity));window.Height=Math.Min(Math.Max(baseHeight,ActualHeight-48),baseHeight+Math.Min(80,feedback.DesiredSize.Height));}}
    finally{saving=false;if(!closed){save.IsEnabled=cancel.IsEnabled=forms.IsEnabled=true;if(closing||closeRequested)window.Close();}}
   };
   save.Click+=(s,e)=>{if(closed||saving||closing||closeRequested)return;operation=persist();};
   var lifetime=new System.Windows.Threading.DispatcherTimer{Interval=TimeSpan.FromMilliseconds(100)};lifetime.Tick+=(s,e)=>{if(closing||closeRequested)window.Close();};lifetime.Start();hotkeyEditing=true;Exception modalError=null;
   try{window.ShowDialog();}catch(Exception ex){modalError=ex;}
   closed=true;lifetime.Stop();try{await operation;}finally{hotkeyEditing=false;}if(modalError!=null)throw modalError;
   if(committed&&!closing&&!closeRequested){status.Text="Горячие клавиши сохранены.";if(current=="Zapret & Byetube")RefreshAfterOperation("Zapret");}
  }
 }
}
