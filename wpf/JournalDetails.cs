using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static string JournalPreview(string message){string text=JournalDisplay(message);return text.Length<=320?text:text.Substring(0,320)+"…";}
  Task CopyJournalDetails(string technical,TextBlock feedback,Button copy,Func<string,Task> write){return CopyJournalExport(technical,feedback,copy,write,"Техническая запись скопирована.");}
  void JournalDetails(JournalEntry entry){var window=Dialog("Подробности события",660,480);var panel=new DockPanel{Margin=new Thickness(20)};string technical=JournalDetailsExport(entry,JournalSystemInfo());var text=new TextBox{Name="journalTechnicalDetails",Text=technical,IsReadOnly=true,AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,FontFamily=new FontFamily("Consolas"),FontSize=12,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};var footer=new StackPanel();var feedback=Text("");feedback.Name="journalDetailFeedback";feedback.MaxHeight=56;feedback.TextTrimming=TextTrimming.WordEllipsis;footer.Children.Add(feedback);Button copy=null;copy=Button("Копировать",async()=>await CopyJournalDetails(technical,feedback,copy,JournalWriterForTests??CopyText));copy.Name="journalCopyDetails";footer.Children.Add(Toolbar(copy,Button("Закрыть",()=>window.Close())));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(text);window.Content=panel;window.ShowDialog();}
 }
}
