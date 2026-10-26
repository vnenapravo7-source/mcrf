using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class JournalAccessibleName : IValueConverter {
  public object Convert(object value,Type target,object parameter,System.Globalization.CultureInfo culture){var entry=value as JournalEntry;return entry==null?"":JournalStyle.Redact(entry.Time.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")+" · "+JournalStyle.Label(JournalStyle.Tone(entry.Message))+" · "+entry.Source+" · "+MainWindow.JournalPreview(entry.Message));}
  public object ConvertBack(object value,Type target,object parameter,System.Globalization.CultureInfo culture){throw new NotSupportedException();}
 }
 public sealed partial class MainWindow {
  internal Func<string,Task> JournalWriterForTests{get;set;}
  internal Func<Task<JournalEntry[]>> JournalReaderForTests{get;set;}
  internal static string JournalExport(JournalEntry[] entries,string system){return JournalStyle.Redact("MCRF · логи"+Environment.NewLine+system+Environment.NewLine+Environment.NewLine+ClientJournal.Format(entries??new JournalEntry[0]));}
  internal static string JournalDetailsExport(JournalEntry entry,string system){return JournalStyle.Redact("MCRF · логи"+Environment.NewLine+ClientJournal.Format(new[]{entry})+Environment.NewLine+Environment.NewLine+"Системная информация"+Environment.NewLine+system);}
  static string JournalOperatingSystem(){try{using(var key=Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion")){string build=key==null?null:key.GetValue("CurrentBuildNumber") as string;if(!String.IsNullOrWhiteSpace(build)){string revision=Convert.ToString(key.GetValue("UBR"),System.Globalization.CultureInfo.InvariantCulture);string release=key.GetValue("DisplayVersion") as string;return "Windows · сборка "+build+(String.IsNullOrEmpty(revision)?"":"."+revision)+(String.IsNullOrWhiteSpace(release)?"":" · "+release);}}}catch(System.Security.SecurityException){}catch(UnauthorizedAccessException){}catch(System.IO.IOException){}return "Windows · версия API совместимости: "+Environment.OSVersion.VersionString;}
  string JournalSystemInfo(){string version=System.Reflection.Assembly.GetExecutingAssembly().GetName().Version.ToString(3);var rows=session.Updates.LocalItems(session.State).Where(i=>i.Group=="Движки"||i.Kind=="optional").Select(i=>i.Name+": "+(i.CurrentLabel??i.Current??"Неизвестно"));return JournalStyle.Redact("MCRF "+version+Environment.NewLine+JournalOperatingSystem()+" · процесс "+(Environment.Is64BitProcess?"x64":"x86")+Environment.NewLine+"Движки · локальные сведения; обновления не проверялись"+Environment.NewLine+String.Join(Environment.NewLine,rows));}
  async Task CopyJournalExport(string text,TextBlock feedback,Button copy,Func<string,Task> write,string success){
   if(!copy.IsEnabled||closing||closeRequested)return;bool viewClosed=false;var host=Window.GetWindow(copy);EventHandler closed=(s,e)=>viewClosed=true;RoutedEventHandler detached=(s,e)=>viewClosed=true;if(host!=null)host.Closed+=closed;copy.Unloaded+=detached;copy.IsEnabled=false;feedback.Text="";
   try{await write(text);if(closing||closeRequested||viewClosed)return;feedback.Text=success;feedback.Foreground=Brush(CompactSecondary);feedback.ToolTip=feedback.Text;}
   catch(Exception ex){if(closing||closeRequested||viewClosed)return;feedback.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));feedback.Foreground=Brush(CompactError);feedback.ToolTip=feedback.Text;}
   finally{if(host!=null)host.Closed-=closed;copy.Unloaded-=detached;if(!closing&&!closeRequested&&!viewClosed)copy.IsEnabled=true;}
  }
 }
}
