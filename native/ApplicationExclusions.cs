using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
namespace SplifyWin {
 public static class ApplicationExclusions {
  public static string[] Normalize(IEnumerable<string> entries){
   var names=new List<string>();foreach(var raw in entries??new string[0]){
    string name=(raw??"").Trim();if(name.Length==0)continue;if(name.StartsWith("app:",StringComparison.OrdinalIgnoreCase))name=name.Substring(4).Trim();
    if(!Regex.IsMatch(name,@"^[^\\/:*?""<>|\r\n\x00]+\.exe$",RegexOptions.IgnoreCase)||name.Length>150)throw new FormatException("Введите имя приложения, например game.exe, без пути и масок.");names.Add(name);
   }var result=names.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();if(result.Length>256)throw new FormatException("Можно исключить до 256 приложений.");return result;
  }
  public static string[] Running(){var names=new List<string>();foreach(var p in Process.GetProcesses())using(p){try{names.Add(p.ProcessName+".exe");}catch(InvalidOperationException){}catch(System.ComponentModel.Win32Exception){}}return names.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(x=>x,StringComparer.OrdinalIgnoreCase).ToArray();}
  public static string ZapretArgument(IEnumerable<string> entries,string directory){var names=Normalize(entries);if(names.Length==0)return "";Directory.CreateDirectory(directory);string file=Path.Combine(directory,"global-bypass-apps.txt");File.WriteAllLines(file,names.Select(n=>"app:"+n),new UTF8Encoding(false));return "--mcrf-bypass-apps="+ZapretRuntime.Quote(file)+" ";}
  public static Dictionary<string,object> DirectRule(IEnumerable<string> entries){return new Dictionary<string,object>{{"process_name",Normalize(entries)},{"action","route"},{"outbound","direct"}};}
 }
}
