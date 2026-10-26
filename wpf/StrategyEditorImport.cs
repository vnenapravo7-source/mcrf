using System;
using System.IO;
using System.Text;
namespace Mcrf.Wpf {
 public partial class MainWindow {
  internal Func<string> StrategyImportFileForTests;
  string PickStrategyImportFile(){if(StrategyImportFileForTests!=null)return StrategyImportFileForTests();var picker=new Microsoft.Win32.OpenFileDialog{Filter="Конфигурации (*.bat;*.cmd;*.txt)|*.bat;*.cmd;*.txt|Все файлы|*.*"};return picker.ShowDialog(detachedWorkspaceWindow??this)==true?picker.FileName:null;}
 }
 static class StrategyEditorImport {
  internal const int ByteLimit=256000;
  internal static string Read(string path){
   using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read)){
    if(stream.Length>ByteLimit)throw new InvalidDataException("BAT слишком большой: максимум 256 КБ.");
    using(var reader=new StreamReader(stream,Encoding.UTF8,true)){
     var text=new StringBuilder();var buffer=new char[4096];int read;
     while((read=reader.Read(buffer,0,buffer.Length))>0){if(text.Length+read>ByteLimit)throw new InvalidDataException("BAT слишком большой: максимум 256 КБ.");text.Append(buffer,0,read);}
     string result=text.ToString();if(String.IsNullOrWhiteSpace(result))throw new FormatException("Файл BAT пуст.");if(result.IndexOf('\0')>=0)throw new FormatException("Выберите текстовый BAT, CMD или TXT, а не двоичный файл.");return result;
    }
   }
  }
 }
}
