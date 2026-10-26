using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
namespace SplifyWin {
 public static class BatStrategyConfig {
  public static string ReadBasis(ZapretStrategy source,string directory){if(source.CustomProfiles!=null)return "winws.exe "+String.Join(" --new ^\r\n",source.CustomProfiles);if(source.Id.StartsWith("flowseal:")){using(var stream=ZapretCatalog.Flowseal(directory))using(var zip=new ZipArchive(stream,ZipArchiveMode.Read)){var entry=zip.Entries.FirstOrDefault(e=>Path.GetFileName(e.FullName)==source.Id.Substring(9));if(entry!=null)using(var reader=new StreamReader(entry.Open(),Encoding.UTF8)){string script=reader.ReadToEnd();script=Regex.Replace(script,@"(?im)^\s*set\s+""?BIN=.*$","set \"BIN="+directory+Path.DirectorySeparatorChar+"\"");return script;}}}return "winws.exe --filter-tcp=80,443 "+source.Tcp+" ^\r\n--new ^\r\n--filter-udp=443 "+source.Udp;}
  public static string[] Parse(string text,string payloadDirectory=null){
   if(String.IsNullOrWhiteSpace(text)||text.Length>64000)throw new FormatException("Вставьте конфиг до 64 КБ");text=text.Replace("^\r\n"," ").Replace("^\n"," ").Replace("^!","!");
   var variables=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);foreach(Match set in Regex.Matches(text,@"(?im)^\s*set\s+(?:""([^""\r\n]+)""|([^\r\n]+))")){string assignment=set.Groups[1].Success?set.Groups[1].Value:set.Groups[2].Value;int equal=assignment.IndexOf('=');if(equal>0)variables[assignment.Substring(0,equal).Trim()]=assignment.Substring(equal+1).Trim();}
   if(!String.IsNullOrEmpty(payloadDirectory)){string bin;if(!variables.TryGetValue("BIN",out bin)||bin.Contains("%~dp0"))variables["BIN"]=payloadDirectory+Path.DirectorySeparatorChar;}
   // Only the engine command is interpreted; BAT control flow/services never run.
   var commands=text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Where(l=>Regex.IsMatch(l,@"(?i)winws(?:\.exe)?" )&&l.Contains("--")).ToArray();if(commands.Length>1)throw new FormatException("Несколько запусков winws в одном BAT не поддерживаются. Вставьте один конфиг.");string command=commands.FirstOrDefault();if(command==null){if(Regex.IsMatch(text,@"(?im)^\s*(?:@?echo|call|start|set)\b"))throw new FormatException("Не найдена команда winws с параметрами");command=text;}
   int engineOptions=command.IndexOf("--",StringComparison.Ordinal);if(engineOptions<0)throw new FormatException("Нет параметров движка");command=command.Substring(engineOptions);
   command=Regex.Replace(command,@"--(?:hostlist[a-z-]*|ipset[a-z-]*|wf-tcp|wf-udp|debug)=(?:""[^""]*""|[^\s]+)","");
   for(int depth=0;depth<16;depth++){bool replaced=false;command=Regex.Replace(command,@"%([A-Za-z_][A-Za-z0-9_]*)%",m=>{string value;if(!variables.TryGetValue(m.Groups[1].Value,out value))throw new FormatException("Не задана переменная %"+m.Groups[1].Value+"%. Вставьте её строку set.");replaced=true;return value;});if(!replaced)break;}
   if(command.Contains("%"))throw new FormatException("Не удалось раскрыть переменные BAT (например %~dp0). Задайте явный путь в set.");
   int first=command.IndexOf("--",StringComparison.Ordinal);if(first<0)throw new FormatException("Нет параметров движка");command=command.Substring(first).Trim();
   var result=new List<string>();foreach(string section in Regex.Split(command,@"\s*--new(?:\s+|$)")){
    var options=new List<string>();int at=0;var tokens=Regex.Matches(section,@"--[a-z0-9-]+(?:=(?:""[^""\r\n]*""|[^\s""\r\n]+))?");foreach(Match token in tokens){if(!String.IsNullOrWhiteSpace(section.Substring(at,token.Index-at)))throw new FormatException("Не удалось разобрать строку возле "+token.Value);at=token.Index+token.Length;string key=token.Value.Split('=')[0];
     if(key=="--wf-tcp"||key=="--wf-udp"||key=="--debug")continue;
     if(key.StartsWith("--hostlist")||key.StartsWith("--ipset"))continue;
     if(!StrategyParameterEditor.IsEditable(key)&&key!="--filter-ipv")throw new FormatException("Параметр не поддерживается импортом: "+key);options.Add(token.Value);
    }if(!String.IsNullOrWhiteSpace(section.Substring(at)))throw new FormatException("Не удалось разобрать хвост строки конфигурации");if(options.Count==0)continue;
    if(!options.Any(o=>o.StartsWith("--filter-tcp=")||o.StartsWith("--filter-udp=")))throw new FormatException("У каждого блока нужен фильтр TCP или UDP");result.Add(String.Join(" ",options));
   }if(result.Count==0||result.Count>32)throw new FormatException("В конфиге должно быть от 1 до 32 блоков");return result.ToArray();
  }
 }
}
