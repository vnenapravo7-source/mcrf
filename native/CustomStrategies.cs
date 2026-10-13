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
  public Dictionary<string,string> TcpOverrides{get;set;}public Dictionary<string,string> UdpOverrides{get;set;}
  public Dictionary<string,string> Recipes{get;set;}public Dictionary<string,string> Variables{get;set;}
  public string BatConfig{get;set;}
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
  static string ApplyOverrides(string text,Dictionary<string,string> overrides,bool strict=true){
   if(String.IsNullOrWhiteSpace(text)||overrides==null)return text;
   foreach(var item in overrides.OrderBy(p=>p.Key,StringComparer.Ordinal)){if(item.Value=="__remove__"){text=Regex.Replace(text,@"\s*--dpi-desync-"+Regex.Escape(item.Key)+@"=[^\s]+","");continue;}int value;bool valid=item.Key=="repeats"?Int32.TryParse(item.Value,out value)&&value>=1&&value<=20:item.Key=="cutoff"?item.Value.StartsWith("n")&&Int32.TryParse(item.Value.Substring(1),out value)&&value>=1&&value<=100:item.Key=="ttl"?Int32.TryParse(item.Value,out value)&&value>=1&&value<=5:item.Key=="split-pos"&&(item.Value=="2"||item.Value=="1,midsld");if(!valid)throw new FormatException("Недопустимое изменение параметра стратегии");string pattern=@"--dpi-desync-"+Regex.Escape(item.Key)+@"=[^\s]+";if(Regex.IsMatch(text,pattern))text=Regex.Replace(text,pattern,"--dpi-desync-"+item.Key+"="+item.Value);else if(item.Key=="repeats"||item.Key=="cutoff"||strict&&(item.Key=="ttl"||item.Key=="split-pos"))text+=" --dpi-desync-"+item.Key+"="+item.Value;else if(strict)throw new FormatException("У основы нет параметра "+item.Key);}
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
   if(!String.IsNullOrWhiteSpace(definition.BatConfig)){var payload=Regex.Match((source.Tcp??"")+" "+source.Udp,@"--dpi-desync-fake-[a-z0-9-]+=""([^""]+\.bin)""");string directory=payload.Success?Path.GetDirectoryName(payload.Groups[1].Value):null;var profiles=BatStrategyConfig.Parse(definition.BatConfig,directory);return new ZapretStrategy{Id=definition.Id,Name="Своя · "+definition.Name,Family="Свои",Tcp=source.Tcp,Udp=source.Udp,GameTcp=source.GameTcp,GameUdp=source.GameUdp,VoiceUdp=source.VoiceUdp,CustomProfiles=profiles};}
   var result=new ZapretStrategy{Id=definition.Id,Name="Своя · "+definition.Name,Family="Свои",Tcp=definition.CustomizeTcp?Shape(Tune(source.Tcp,definition.TcpRepeats,definition.TcpCutoff),definition):source.Tcp,Udp=definition.CustomizeUdp?Shape(Tune(source.Udp,definition.UdpRepeats,definition.UdpCutoff),definition):source.Udp,GameTcp=definition.CustomizeTcp?Shape(Tune(source.GameTcp,definition.TcpRepeats,definition.TcpCutoff),definition):source.GameTcp,GameUdp=definition.CustomizeUdp?Shape(Tune(source.GameUdp,definition.UdpRepeats,definition.UdpCutoff),definition):source.GameUdp,VoiceUdp=source.VoiceUdp,GameUnavailableReason=source.GameUnavailableReason};
   result.Tcp=ApplyOverrides(result.Tcp,definition.TcpOverrides);result.Udp=ApplyOverrides(result.Udp,definition.UdpOverrides);result.GameTcp=ApplyOverrides(result.GameTcp,definition.TcpOverrides,false);result.GameUdp=ApplyOverrides(result.GameUdp,definition.UdpOverrides,false);
   if(definition.Recipes!=null){foreach(var key in definition.Recipes.Keys)if(!new[]{"TCP","UDP","Game TCP","Game UDP","Voice UDP"}.Contains(key))throw new FormatException("Неизвестный блок стратегии");string value;if(definition.Recipes.TryGetValue("TCP",out value))result.Tcp=Recipe(value,definition.Variables);if(definition.Recipes.TryGetValue("UDP",out value))result.Udp=Recipe(value,definition.Variables);if(definition.Recipes.TryGetValue("Game TCP",out value))result.GameTcp=Recipe(value,definition.Variables);if(definition.Recipes.TryGetValue("Game UDP",out value))result.GameUdp=Recipe(value,definition.Variables);if(definition.Recipes.TryGetValue("Voice UDP",out value))result.VoiceUdp=Recipe(value,definition.Variables);if(String.IsNullOrWhiteSpace(result.Tcp)&&String.IsNullOrWhiteSpace(result.Udp)&&String.IsNullOrWhiteSpace(result.GameTcp)&&String.IsNullOrWhiteSpace(result.GameUdp))throw new FormatException("Стратегия не может состоять только из пустых блоков");}return result;
  }
  public static string Recipe(string text,Dictionary<string,string> variables){
   text=text??"";if(text.Length>12000)throw new FormatException("Блок стратегии слишком большой");
   text=Regex.Replace(text,@"%([A-Za-z_][A-Za-z0-9_]*)%",m=>{string value;if(variables==null||!variables.TryGetValue(m.Groups[1].Value,out value))throw new FormatException("Не задана переменная %"+m.Groups[1].Value+"%");if(value.IndexOfAny(new[]{'\r','\n','%','\0'})>=0)throw new FormatException("Некорректное значение переменной");return value;});
   text=String.Join(" ",text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Select(l=>l.Trim()).Where(l=>!l.StartsWith("#")));
   var tokens=Regex.Matches(text,@"--[a-z0-9-]+(?:=(?:""[^""\r\n]*""|[^\s""\r\n]+))?");int at=0;foreach(Match token in tokens){if(!String.IsNullOrWhiteSpace(text.Substring(at,token.Index-at)))throw new FormatException("Каждая строка должна содержать параметр --имя=значение");if(!Regex.IsMatch(token.Value,@"^--(?:dpi-desync(?:-[a-z0-9-]+)?|dup(?:-[a-z0-9-]+)?|ip-id)(?:=|$)"))throw new FormatException("Фильтры, --new и параметры запуска задаются маршрутами MCRF, а не блоком стратегии: "+token.Value);at=token.Index+token.Length;}
   if(!String.IsNullOrWhiteSpace(text.Substring(at)))throw new FormatException("Не удалось разобрать строку конфигурации");return String.Join(" ",tokens.Cast<Match>().Select(m=>m.Value));
  }
  public static Dictionary<string,string> ParseVariables(string text){var result=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);foreach(string line in (text??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){if(line.TrimStart().StartsWith("#"))continue;int equal=line.IndexOf('=');if(equal<1||!Regex.IsMatch(line.Substring(0,equal).Trim(),@"^[A-Za-z_][A-Za-z0-9_]*$"))throw new FormatException("Переменная: ИМЯ=значение");string key=line.Substring(0,equal).Trim();if(result.ContainsKey(key))throw new FormatException("Повтор переменной "+key);result[key]=line.Substring(equal+1).Trim();}return result;}
  public static CustomStrategyDefinition Fork(ZapretStrategy source,string name,IEnumerable<CustomStrategyDefinition> saved){var previous=saved.FirstOrDefault(d=>d.Id==source.Id);var result=previous==null?new CustomStrategyDefinition{BaseId=source.Id}:Json.Deserialize<CustomStrategyDefinition>(Json.Serialize(previous));result.Id="local:"+Guid.NewGuid().ToString("N");result.Name=name.Trim();result.Generated=false;if(result.TcpOverrides==null)result.TcpOverrides=new Dictionary<string,string>();if(result.UdpOverrides==null)result.UdpOverrides=new Dictionary<string,string>();return result;}
  public static void Save(string directory,CustomStrategyDefinition definition,IEnumerable<ZapretStrategy> catalog){Build(definition,catalog);var all=Read(directory);all.RemoveAll(s=>s.Id==definition.Id);if(all.Count>=100)throw new InvalidOperationException("Можно сохранить до 100 своих стратегий.");all.Add(definition);string file=FileName(directory);Directory.CreateDirectory(Path.GetDirectoryName(file));string temp=file+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temp,Json.Serialize(all),new UTF8Encoding(false));if(File.Exists(file))File.Replace(temp,file,null);else File.Move(temp,file);}finally{if(File.Exists(temp))File.Delete(temp);}}
 }
}
