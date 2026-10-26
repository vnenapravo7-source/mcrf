using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
namespace Mcrf.Wpf {
 public partial class MainWindow {
  internal Func<string> RouteImportFileForTests;
  internal Func<string,Task<string>> RouteDownloadForTests;
  internal Func<SplifyWin.RoutePreset,Task<string>> RoutePresetLoadForTests;
  Task<string> LoadRoutePreset(SplifyWin.RoutePreset preset){return RoutePresetLoadForTests==null?SplifyWin.PresetStorage.Load(session.Core,preset):RoutePresetLoadForTests(preset);}
  string PickRouteImportFile(Window owner){if(RouteImportFileForTests!=null)return RouteImportFileForTests();var picker=new Microsoft.Win32.OpenFileDialog{Filter="Списки (*.txt;*.list)|*.txt;*.list|Все файлы|*.*"};return picker.ShowDialog(owner)==true?picker.FileName:null;}
  Task<string> DownloadRouteSource(string source){return RouteDownloadForTests==null?session.Core.DownloadTextAsync(source):RouteDownloadForTests(source);}
 }
 static class RouteSourceImport {
  internal const int ByteLimit=10485760;
  internal static string Read(string path){using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read)){
   if(stream.Length>ByteLimit)throw new InvalidDataException("Список слишком большой: максимум 10 МБ.");
   using(var reader=new StreamReader(stream,Encoding.UTF8,true)){var text=new StringBuilder();var buffer=new char[4096];int count;
    while((count=reader.Read(buffer,0,buffer.Length))>0){if(text.Length+count>ByteLimit)throw new InvalidDataException("Список слишком большой: максимум 10 МБ.");text.Append(buffer,0,count);}
    var result=text.ToString();if(String.IsNullOrWhiteSpace(result))throw new FormatException("Список пуст.");if(result.IndexOf('\0')>=0)throw new FormatException("Выберите текстовый TXT или LIST, а не двоичный файл.");return result;
   }
  }}
 }
}
