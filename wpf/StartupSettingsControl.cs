using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class StartupServices {
  internal Func<bool,Task> Write;
  internal Func<Task<bool?>> Read;
 }
 public sealed partial class MainWindow {
  bool startupStateKnown=true;
  async Task ChangeStartupOption(CheckBox check,StartupServices services,TextBlock feedback){
   Action sync=()=>{check.IsChecked=startupStateKnown?(bool?)startupEnabled:null;check.Content=startupStateKnown?"Автозапуск Windows":"Автозапуск · неизвестно";};
   if(busy||closing||closeRequested){sync();return;}startupReadGeneration++;bool requested=check.IsChecked==true;check.IsEnabled=false;feedback.Visibility=Visibility.Collapsed;status.Text="";
   var surface=Window.GetWindow(check);bool closed=false;EventHandler onClosed=(s,e)=>closed=true;if(surface!=null)surface.Closed+=onClosed;Func<bool> alive=()=>!closed&&check.IsLoaded&&!closing&&!closeRequested;
   try{await Run(async()=>{
    Exception failure=null;bool? observed=null;
    try{await services.Write(requested);}catch(Exception ex){failure=ex;}
    try{observed=await services.Read();}catch(Exception ex){failure=failure==null?ex:new AggregateException(failure,ex);}
    startupStateKnown=observed.HasValue;if(observed.HasValue)startupEnabled=observed.Value;
    string reason=failure==null?null:JournalStyle.Redact(failure.Message);string message=!observed.HasValue?"Не удалось подтвердить состояние автозапуска Windows. Проверьте его в системных настройках.":observed.Value!=requested?"Windows не подтвердила выбранное состояние автозапуска. Показано фактическое состояние."+(reason==null?"":" "+reason):failure!=null?"Автозапуск "+(observed.Value?"включён":"выключен")+", но операция завершилась с ошибкой. "+reason:null;
    if(message!=null){if(alive()){feedback.Text=message;feedback.ToolTip=JournalStyle.Redact(failure==null?message:failure.Message);feedback.Foreground=LogColor("ERROR");feedback.Visibility=Visibility.Visible;}throw new InvalidOperationException(message,failure);}
    if(alive()){feedback.Text="Автозапуск "+(startupEnabled?"включён.":"выключен.");feedback.Foreground=LogColor("SUCCESS");feedback.Visibility=Visibility.Visible;status.Text=feedback.Text;}
   },()=>{if(alive())sync();});}finally{if(surface!=null)surface.Closed-=onClosed;if(alive()){sync();check.IsEnabled=true;}}
  }
 }
}
