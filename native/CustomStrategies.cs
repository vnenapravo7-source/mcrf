using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
namespace SplifyWin {
 public sealed class CustomStrategyDefinition {
  public string Id{get;set;}public string Name{get;set;}public string BaseId{get;set;}public int TcpRepeats{get;set;}public int UdpRepeats{get;set;}public int TcpCutoff{get;set;}public int UdpCutoff{get;set;}public bool CustomizeTcp{get;set;}public bool CustomizeUdp{get;set;}
  public bool Generated{get;set;}public string SplitPosition{get;set;}public int FakeTtl{get;set;}
 }
 public static class CustomStrategies {
  static readonly JavaScriptSerializer Json=new JavaScriptSerializer{MaxJsonLength=1000000};
  public static string Root(string directory){var parent=Directory.GetParent(directory);if(parent!=null&&parent.Name=="core")return parent.Parent.FullName;if(parent!=null&&parent.Name=="installed"&&parent.Parent.Name=="updates")return parent.Parent.Parent.FullName;return directory;}
  public static string FileName(string directory){return Path.Combine(Root(directory),"custom-strategies.json");}
  public static List<CustomStrategyDefinition> Read(string directory){string file=FileName(directory);if(!File.Exists(file))return new List<CustomStrategyDefinition>();if(new FileInfo(file).Length>1000000)throw new InvalidDataException("Слишком большой файл своих стратегий.");return Json.Deserialize<List<CustomStrategyDefinition>>(File.ReadAllText(file))??new List<CustomStrategyDefinition>();}
  static string Tune(string text,int repeats,int cutoff){if(String.IsNullOrWhiteSpace(text))return text;if(repeats<1||repeats>20||cutoff<1||cutoff>100)throw new FormatException("Повторы: 1–20; начальные пакеты: 1–100.");return Regex.Replace(text,@"\s*--dpi-desync-(?:repeats|cutoff)=[^\s]+","")+" --dpi-desync-repeats="+repeats+" --dpi-desync-cutoff=n"+cutoff;}
  static string Shape(string text,CustomStrategyDefinition definition){
   if(String.IsNullOrEmpty(text))return text;
   if(!String.IsNullOrEmpty(definition.SplitPosition)){if(definition.SplitPosition!="2"&&definition.SplitPosition!="1,midsld")throw new FormatException("Недопустимая точка разбиения.");text=Regex.Replace(text,@"--dpi-desync-split-pos=[^\s]+","--dpi-desync-split-pos="+definition.SplitPosition);}
   if(definition.FakeTtl!=0){if(definition.FakeTtl<1||definition.FakeTtl>5)throw new FormatException("Недопустимый TTL подмены.");text=Regex.Replace(text,@"--dpi-desync-ttl=[^\s]+","--dpi-desync-ttl="+definition.FakeTtl);}
   return text;
  }
  public static List<CustomStrategyDefinition> Generate(IEnumerable<ZapretStrategy> catalog,string name,int limit,string preferred){
   if(String.IsNullOrWhiteSpace(name)||name.Trim().Length>48)throw new FormatException("Название: от 1 до 48 символов.");if(limit!=12&&limit!=24)throw new FormatException("Неизвестный размер поиска.");
   var available=catalog.Where(s=>s.Available&&s.Family!="Свои"&&!String.IsNullOrWhiteSpace(s.Tcp)).GroupBy(s=>(s.Tcp??"")+"\n"+(s.Udp??"")).Select(g=>g.First()).ToArray();
   var queues=available.GroupBy(s=>s.Family).Select(g=>new Queue<ZapretStrategy>(g.OrderByDescending(s=>s.Id==preferred).ThenBy(s=>s.Id,StringComparer.Ordinal))).ToArray();var bases=new List<ZapretStrategy>();
   var first=available.FirstOrDefault(s=>s.Id==preferred);if(first!=null)bases.Add(first);while(bases.Count<8&&queues.Any(q=>q.Count>0)){foreach(var queue in queues){if(queue.Count==0)continue;var next=queue.Dequeue();if(!bases.Contains(next))bases.Add(next);if(bases.Count==8)break;}}
   var result=new List<CustomStrategyDefinition>();var seen=new HashSet<string>();
   for(int variant=0;variant<3;variant++)foreach(var source in bases){
    var definition=new CustomStrategyDefinition{Id="local:"+Guid.NewGuid().ToString("N"),Name=name.Trim()+" · "+(result.Count+1).ToString("00"),BaseId=source.Id,Generated=true,CustomizeTcp=variant>0,CustomizeUdp=variant>0,TcpRepeats=variant==1?2:4,UdpRepeats=variant==1?2:4,TcpCutoff=variant==1?2:4,UdpCutoff=variant==1?2:4,SplitPosition=variant==1?"2":variant==2?"1,midsld":null,FakeTtl=variant==1?3:variant==2?5:0};
    var built=Build(definition,available);if(seen.Add((built.Tcp??"")+"\n"+(built.Udp??"")))result.Add(definition);if(result.Count>=limit)return result;
   }if(result.Count==0)throw new InvalidOperationException("Нет доступных основ для генерации.");return result;
  }
  public static void SaveChecked(string directory,IEnumerable<CustomStrategyDefinition> definitions,IEnumerable<ZapretStrategy> catalog,ZapretCheckReport report){
   var tested=new HashSet<string>(report.Rows.Where(r=>!r.Checking&&r.CheckedAt!=default(DateTime)&&String.IsNullOrEmpty(r.Error)&&r.Services.Count>0).Select(r=>r.StrategyId));
   var added=definitions.Where(d=>tested.Contains(d.Id)).ToArray();var all=Read(directory);foreach(var definition in added){Build(definition,catalog);all.RemoveAll(d=>d.Id==definition.Id);all.Add(definition);}if(all.Count>100)throw new InvalidOperationException("Достигнут лимит своих стратегий.");Write(directory,all);
  }
  static void Write(string directory,List<CustomStrategyDefinition> all){string file=FileName(directory);Directory.CreateDirectory(Path.GetDirectoryName(file));string temp=file+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temp,Json.Serialize(all),new UTF8Encoding(false));if(File.Exists(file))File.Replace(temp,file,null);else File.Move(temp,file);}finally{if(File.Exists(temp))File.Delete(temp);}}
  public static ZapretStrategy Build(CustomStrategyDefinition definition,IEnumerable<ZapretStrategy> catalog){
   if(definition==null||String.IsNullOrWhiteSpace(definition.Name)||definition.Name.Length>80||!Regex.IsMatch(definition.Id??"",@"^local:[a-f0-9]{32}$"))throw new FormatException("Некорректное имя или идентификатор своей стратегии.");
   var source=catalog.FirstOrDefault(s=>s.Id==definition.BaseId&&s.Family!="Свои"&&s.Available);if(source==null)throw new InvalidOperationException("Базовая стратегия больше недоступна.");
   return new ZapretStrategy{Id=definition.Id,Name="Своя · "+definition.Name,Family="Свои",Tcp=definition.CustomizeTcp?Shape(Tune(source.Tcp,definition.TcpRepeats,definition.TcpCutoff),definition):source.Tcp,Udp=definition.CustomizeUdp?Shape(Tune(source.Udp,definition.UdpRepeats,definition.UdpCutoff),definition):source.Udp,GameTcp=definition.CustomizeTcp?Shape(Tune(source.GameTcp,definition.TcpRepeats,definition.TcpCutoff),definition):source.GameTcp,GameUdp=definition.CustomizeUdp?Shape(Tune(source.GameUdp,definition.UdpRepeats,definition.UdpCutoff),definition):source.GameUdp,VoiceUdp=source.VoiceUdp,GameUnavailableReason=source.GameUnavailableReason};
  }
  public static void Save(string directory,CustomStrategyDefinition definition,IEnumerable<ZapretStrategy> catalog){Build(definition,catalog);var all=Read(directory);all.RemoveAll(s=>s.Id==definition.Id);if(all.Count>=100)throw new InvalidOperationException("Можно сохранить до 100 своих стратегий.");all.Add(definition);string file=FileName(directory);Directory.CreateDirectory(Path.GetDirectoryName(file));string temp=file+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temp,Json.Serialize(all),new UTF8Encoding(false));if(File.Exists(file))File.Replace(temp,file,null);else File.Move(temp,file);}finally{if(File.Exists(temp))File.Delete(temp);}}
 }
}
