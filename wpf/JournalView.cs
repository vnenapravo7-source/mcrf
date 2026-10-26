using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static string JournalDisplay(string message){
   string text=System.Text.RegularExpressions.Regex.Replace(message??"", @"\s*\[\d+\s+[^\]]*ms\]", "");
   text=System.Text.RegularExpressions.Regex.Replace(text,@"\brouter: found process path:\s*","Приложение: ");
   text=System.Text.RegularExpressions.Regex.Replace(text,@"\boutbound packet connection to\s*","Пакеты отправляются на ");
   text=System.Text.RegularExpressions.Regex.Replace(text,@"\boutbound connection to\s*","Соединение с ");
   text=System.Text.RegularExpressions.Regex.Replace(text,@"\binbound connection from\s*","Подключение от ");
   text=System.Text.RegularExpressions.Regex.Replace(text,@"\binbound connection to\s*","Запрос к ");
   text=System.Text.RegularExpressions.Regex.Replace(text,@"\bsing-box started\b","Сетевой движок запущен");
   return JournalStyle.Redact(text);
  }
  Brush LogColor(string message){var tone=JournalStyle.Tone(message);if(CompactLight)return Brush(tone==JournalTone.Error?"#9B2F35":tone==JournalTone.Warning?"#805200":tone==JournalTone.Success?"#14624F":tone==JournalTone.Debug?"#536170":"#155F73");return Brush(tone==JournalTone.Error?"#FF8CA1":tone==JournalTone.Warning?"#FFC972":tone==JournalTone.Success?"#6BFAD8":tone==JournalTone.Debug?"#99A8C5":"#4EE7FC");}
  void RecordEvent(string text){try{if(!String.IsNullOrWhiteSpace(text))FileAppendEvent(root,text);}catch(System.IO.IOException){}catch(UnauthorizedAccessException){}}
  static void FileAppendEvent(string folder,string text){System.IO.File.AppendAllText(System.IO.Path.Combine(folder,"client.log"),ClientJournal.Stamp(text)+Environment.NewLine);}
  ListBox LogList(bool compact=false){var list=new ListBox{Name=compact?"homeJournal":"journalEvents",Background=Brushes.Transparent,BorderThickness=new Thickness(0),HorizontalContentAlignment=HorizontalAlignment.Stretch};ScrollViewer.SetHorizontalScrollBarVisibility(list,ScrollBarVisibility.Disabled);ScrollViewer.SetVerticalScrollBarVisibility(list,compact?ScrollBarVisibility.Disabled:ScrollBarVisibility.Auto);if(compact){ScrollViewer.SetCanContentScroll(list,false);var itemStyle=new Style(typeof(ListBoxItem),(Style)FindResource(typeof(ListBoxItem)));itemStyle.Setters.Add(new Setter(Control.PaddingProperty,new Thickness(8,4,8,4)));list.ItemContainerStyle=itemStyle;}var factory=new FrameworkElementFactory(typeof(CellPresenter));factory.SetValue(CellPresenter.CreateProperty,new Func<object,UIElement>(o=>{var entry=(JournalEntry)o;var row=new DockPanel{Margin=new Thickness(0,compact?0:4,0,compact?0:4)};if(compact)row.Height=32;var marker=new TextBlock{Text=compact?"•":entry.Time.ToLocalTime().ToString("HH:mm:ss"),Foreground=compact?LogColor(entry.Message):Brush("#8F9FB9"),FontSize=compact?13:11,VerticalAlignment=VerticalAlignment.Center,Width=compact?16:62};DockPanel.SetDock(marker,Dock.Left);row.Children.Add(marker);var text=new TextBlock{Text=JournalDisplay(entry.Message),Foreground=compact&&JournalStyle.Tone(entry.Message)==JournalTone.Info?Foreground:LogColor(entry.Message),TextWrapping=compact?TextWrapping.NoWrap:TextWrapping.Wrap,TextTrimming=compact?TextTrimming.CharacterEllipsis:TextTrimming.None,FontSize=13,VerticalAlignment=VerticalAlignment.Center};text.ToolTip=text.Text;row.Children.Add(text);return row;}));list.ItemTemplate=new DataTemplate{VisualTree=factory};return list;}
  internal static string JournalCopy(JournalEntry[] entries){return String.Join(Environment.NewLine,entries.Select(e=>e.Time.ToLocalTime().ToString("HH:mm:ss")+" ["+e.Source+"] "+JournalDisplay(e.Message)));}
  static async Task CopyText(string text){for(int attempt=0;attempt<5;attempt++){try{Clipboard.SetText(text);return;}catch(System.Runtime.InteropServices.COMException){if(attempt==4)throw new InvalidOperationException("Буфер обмена занят другим приложением. Попробуйте скопировать ещё раз.");}await Task.Delay(80);}}
  void JournalWorkspace(){var panel=new DockPanel();var filters=new[]{"Все события","Клиент","Сетевой движок","Zapret","ByeTube","Telegram WS","WARP","Только ошибки"};var tabs=new ComboBox{Name="journalFilters",ItemsSource=filters,SelectedIndex=0,Margin=new Thickness(0,0,0,8)};DockPanel.SetDock(tabs,Dock.Top);panel.Children.Add(tabs);var list=LogList();list.MinHeight=240;list.MaxHeight=390;JournalEntry[] entries=new JournalEntry[0],shown=new JournalEntry[0];Action display=()=>{int index=tabs.SelectedIndex;shown=entries.Where(e=>index==0||index==7&&JournalStyle.Tone(e.Message)==JournalTone.Error||index==1&&e.Source=="Клиент"||index==2&&e.Source=="Сеть"||index==3&&e.Source=="Zapret"||index==4&&e.Source=="ByeTube"||index==5&&e.Source=="Telegram WS"||index==6&&e.Source=="WARP").ToArray();list.ItemsSource=shown;};tabs.SelectionChanged+=(s,e)=>display();Func<Task> reload=async()=>{entries=await Task.Run(()=>ClientJournal.Read(root,1000));if(!closing&&current=="События"&&list.IsLoaded)display();};
   var copy=AsyncButton("Копировать",async()=>{await CopyText(JournalCopy(shown));status.Text="Скопированы события текущей вкладки.";});copy.HorizontalAlignment=HorizontalAlignment.Right;copy.MinWidth=150;copy.Margin=new Thickness(0,12,0,0);DockPanel.SetDock(copy,Dock.Bottom);panel.Children.Add(copy);panel.Children.Add(list);body.Children.Add(Card(panel));refreshLog=reload;Dispatcher.BeginInvoke(new Action(()=>LoadBasicJournal(reload)),System.Windows.Threading.DispatcherPriority.Loaded);
  }
 }
}
