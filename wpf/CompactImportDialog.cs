using System;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;

namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  void ShowCompactImportDialog(){
   ShowCompactImportWithServices(new CompactImportServices{
    ChooseFile=window=>{var picker=new Microsoft.Win32.OpenFileDialog{Filter="Подключения|*.txt;*.json;*.conf|Все файлы|*.*"};return picker.ShowDialog(window)==true?picker.FileName:null;},
    LoadFile=ImportFileSource.ReadAsync,Import=ImportServerText
   });
  }
  void ShowCompactImportWithServices(CompactImportServices services){
   var window=Dialog("Добавить подключение",360,270);window.ResizeMode=ResizeMode.NoResize;
   window.MaxHeight=Math.Max(180,Math.Min(420,ActualHeight-48));window.SizeToContent=SizeToContent.Height;
   var panel=new StackPanel{Margin=new Thickness(16)};panel.Children.Add(Text("Вставьте ссылку, подписку или импортируйте файл.",12));
   var input=new TextBox{Name="compactServerImport",AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,Height=100,MaxLength=ImportFileSource.MaxBytes,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,Margin=new Thickness(0,10,0,10)};panel.Children.Add(input);
   var feedback=Text("",11);feedback.Name="compactImportFeedback";feedback.TextWrapping=TextWrapping.Wrap;feedback.MaxHeight=56;feedback.TextTrimming=TextTrimming.WordEllipsis;feedback.Visibility=Visibility.Collapsed;
   bool importing=false,loading=false,closed=false;CancellationTokenSource reading=null;
   Action<string> showError=message=>{if(closed||closing||closeRequested)return;feedback.Text=JournalStyle.Redact(message);feedback.ToolTip=feedback.Text;feedback.Visibility=Visibility.Visible;};
   Button file=null,accept=null;
   Action sync=()=>{if(closed||closing||closeRequested)return;bool pending=loading||importing;file.IsEnabled=!pending;input.IsReadOnly=pending;accept.IsEnabled=!pending&&!String.IsNullOrWhiteSpace(input.Text);file.Content=loading?"Чтение…":"Импорт файла";};
   file=Button("Импорт файла",async()=>{
    if(closed||closing||closeRequested||loading||importing||busy)return;
    CancellationTokenSource cancellation=null;
    try{
     string path=services.ChooseFile(window);if(String.IsNullOrEmpty(path)||closed||closing||closeRequested)return;
     loading=true;feedback.Visibility=Visibility.Collapsed;cancellation=new CancellationTokenSource();reading=cancellation;sync();
     string text=await services.LoadFile(path,cancellation.Token);
     if(closed||closing||closeRequested||cancellation.IsCancellationRequested)return;
     input.Text=ImportFileSource.ValidateText(text);
    }catch(OperationCanceledException){showError("Чтение отменено. Прежний текст сохранён.");}
    catch(Exception ex){showError(ImportFileSource.ErrorMessage(ex));}
    finally{loading=false;if(Object.ReferenceEquals(reading,cancellation))reading=null;if(cancellation!=null)cancellation.Dispose();sync();}
   });file.Name="compactImportFile";file.ToolTip="Текстовые конфигурации до 4 МиБ; UTF-8 или Unicode с BOM.";
   accept=Button("Добавить",async()=>{
    if(closed||closing||closeRequested||loading||importing||busy||String.IsNullOrWhiteSpace(input.Text))return;
    string submitted;
    try{submitted=ImportFileSource.ValidateText(input.Text);}catch(Exception ex){showError(ex.Message);return;}
    bool imported=false;importing=true;feedback.Visibility=Visibility.Collapsed;sync();
    try{await Run(async()=>{imported=await services.Import(submitted);},()=>showError(status.Text));}
    finally{importing=false;sync();}
    if(closed||closing||closeRequested)return;
    if(imported)window.DialogResult=true;else if(feedback.Visibility!=Visibility.Visible)showError("Добавление отменено. Введённый текст сохранён.");
   });accept.Name="compactImportAccept";
   input.TextChanged+=(s,e)=>{if(closed)return;feedback.Visibility=Visibility.Collapsed;sync();};
   window.Closing+=(s,e)=>{if(importing)e.Cancel=true;};
   window.Closed+=(s,e)=>{closed=true;if(reading!=null)reading.Cancel();};
   StyleCompactButton(file);StyleCompactButton(accept,true);panel.Children.Add(Columns(file,accept));panel.Children.Add(feedback);window.Content=panel;
   window.Loaded+=(s,e)=>input.Focus();sync();window.ShowDialog();
  }
 }
}
