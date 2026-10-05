using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace SplifyWin {
  public enum JournalTone {Info, Success, Warning, Error, Debug}
  public static class JournalStyle {
    public static string Redact(string text){
      text=text??"";
      text=Regex.Replace(text,@"(?i)(https?://)[^/\s@]+@","$1[учётные данные скрыты]@");
      text=Regex.Replace(text,@"(?im)^(\s*(?:authorization|proxy-authorization|cookie|set-cookie)\s*:\s*).*$","$1[скрыто]");
      text=Regex.Replace(text,@"(?i)(\b(?:Bearer|Basic)\s+)[A-Za-z0-9+/=_\-.]+","$1[скрыто]");
      text=Regex.Replace(text,@"(?i)([\""'](?:password|private[_-]?key|pre[_-]?shared[_-]?key|token|access[_-]?token|refresh[_-]?token|secret|uuid|api[_-]?key)[\""']\s*:\s*[\""'])([^\""']*)([\""'])","$1[скрыто]$3");
      text=Regex.Replace(text,@"\b(vless|vmess|trojan|ss|csqtt|wdtt|qwdtt|openflux|awg|wg|wireguard|tuic|hysteria2?|hy2|anytls|naive(?:\+https)?)://[^\s\""<>]+","[профиль скрыт]",RegexOptions.IgnoreCase);
      text=Regex.Replace(text,@"(?i)(password|private[_-]?key|preshared[_-]?key|token|secret|authorization|uuid)([\""']?\s*[=:]\s*[\""']?)([^\s,;\""']+)","$1$2[скрыто]");
      return Regex.Replace(text,@"https?://[^\s\""<>]+",m=>m.Value.Contains("?")?m.Value.Substring(0,m.Value.IndexOf('?'))+"?[параметры скрыты]":m.Value,RegexOptions.IgnoreCase);
    }
    public static JournalTone Tone(string line){
      string text=line??"";
      if(Regex.IsMatch(text,@"\b(ERROR|FATAL|PANIC)\b|ошиб|не удалось|не запуст|не подтверд|недоступ",RegexOptions.IgnoreCase))return JournalTone.Error;
      if(Regex.IsMatch(text,@"\bWARN(?:ING)?\b|внимание|неясно|неоднознач|отмен|проверка.*прервана",RegexOptions.IgnoreCase))return JournalTone.Warning;
      if(Regex.IsMatch(text,@"\b(DEBUG|TRACE)\b|hostlist check|ipset check|^.*\[Zapret\].*(?:packet|profile [0-9]|desync|retrans|seq=)",RegexOptions.IgnoreCase))return JournalTone.Debug;
      if(Regex.IsMatch(text,@"запущ|поднят|подтвержден|подтверждён|проверены|применён|применен|успеш|проверки пройдены",RegexOptions.IgnoreCase))return JournalTone.Success;
      return JournalTone.Info;
    }
    public static string Label(JournalTone tone){return tone==JournalTone.Error?"ОШИБКА":tone==JournalTone.Warning?"ВНИМАНИЕ":tone==JournalTone.Success?"ГОТОВО":tone==JournalTone.Debug?"ОТЛАДКА":"ИНФО";}
  }
  public sealed class JournalSource {
    public string Name,Text;public DateTime WrittenAt;
    public JournalSource(string name,string text,DateTime writtenAt){Name=name;Text=text??"";WrittenAt=writtenAt;}
  }
  public sealed class JournalEntry {
    public DateTimeOffset Time;public string Source,Message;public bool InferredDate;
    public override string ToString(){return Time.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")+" ["+Source+"] "+Message;}
  }
  // Cap the merged chronological timeline, never the concatenated files.
  // Legacy Zapret records have only a clock: reconstruct their day backwards
  // from the file date, including midnight crossings, and flag that inference.
  public static class ClientJournal {
    static readonly Regex FullTime=new Regex(@"^(?:(?<offset>[+-]\d{4})\s+)?(?<stamp>\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})(?:\.\d{1,7})?\s*",RegexOptions.Compiled);
    static readonly Regex ClockTime=new Regex(@"^(?<clock>\d{2}:\d{2}:\d{2})\s+",RegexOptions.Compiled);
    static readonly Regex Ansi=new Regex("\u001b\\[[0-9;]*[A-Za-z]",RegexOptions.Compiled);
    static DateTimeOffset LocalTime(DateTime time){return new DateTimeOffset(DateTime.SpecifyKind(time,DateTimeKind.Unspecified),TimeZoneInfo.Local.GetUtcOffset(time));}
    public static string Stamp(string line){line=JournalStyle.Redact(line);if(FullTime.IsMatch(line??""))return line;return DateTime.Now.ToString(ClockTime.IsMatch(line??"")?"yyyy-MM-dd ":"yyyy-MM-dd HH:mm:ss ")+line;}
    static IEnumerable<JournalEntry> Parse(JournalSource source){
      var lines=source.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Select(line=>Ansi.Replace(line,"")).ToArray();
      var entries=new JournalEntry[lines.Length];DateTime day=source.WrittenAt.Date;TimeSpan previous=source.WrittenAt.TimeOfDay;
      for(int index=lines.Length-1;index>=0;index--){
        var full=FullTime.Match(lines[index]);DateTime wall;
        if(full.Success&&DateTime.TryParseExact(full.Groups["stamp"].Value.Replace('T',' '),"yyyy-MM-dd HH:mm:ss",CultureInfo.InvariantCulture,DateTimeStyles.None,out wall)){
          DateTimeOffset time=LocalTime(wall);string offset=full.Groups["offset"].Value;
          if(offset.Length==5){int hours=Int32.Parse(offset.Substring(1,2)),minutes=Int32.Parse(offset.Substring(3,2)),sign=offset[0]=='-'?-1:1;if(hours>14||minutes>59||hours==14&&minutes!=0)continue;time=new DateTimeOffset(wall,new TimeSpan(hours*sign,minutes*sign,0));}
          entries[index]=new JournalEntry{Time=time,Source=source.Name,Message=lines[index].Substring(full.Length)};day=time.LocalDateTime.Date;previous=time.LocalDateTime.TimeOfDay;continue;
        }
        var clock=ClockTime.Match(lines[index]);TimeSpan value;
        if(clock.Success&&TimeSpan.TryParseExact(clock.Groups["clock"].Value,@"hh\:mm\:ss",CultureInfo.InvariantCulture,out value)){
          if(value>previous)day=day.AddDays(-1);previous=value;
          entries[index]=new JournalEntry{Time=LocalTime(day+value),Source=source.Name,Message=lines[index].Substring(clock.Length),InferredDate=true};
        }
      }
      var first=entries.FirstOrDefault(entry=>entry!=null);DateTimeOffset anchor=first==null?LocalTime(source.WrittenAt):first.Time;bool inferred=first==null||first.InferredDate;
      for(int index=0;index<entries.Length;index++){
        if(entries[index]!=null){anchor=entries[index].Time;inferred=entries[index].InferredDate;}
        else entries[index]=new JournalEntry{Time=anchor,Source=source.Name,Message=lines[index],InferredDate=inferred};
      }
      return entries;
    }
    public static JournalEntry[] Merge(int limit,params JournalSource[] sources){
      if(limit<1)throw new ArgumentOutOfRangeException("limit");var entries=sources.Where(source=>source!=null).SelectMany(Parse).OrderBy(entry=>entry.Time).ToArray();return entries.Skip(Math.Max(0,entries.Length-limit)).ToArray();
    }
    static JournalSource ReadSource(string name,string path){
      try{var written=File.GetLastWriteTime(path);
        using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)){
          bool partial=stream.Length>400000;stream.Seek(Math.Max(0,stream.Length-400000),SeekOrigin.Begin);using(var reader=new StreamReader(stream)){if(partial)reader.ReadLine();return new JournalSource(name,reader.ReadToEnd(),written);}
        }
      }catch(FileNotFoundException){return new JournalSource(name,"",DateTime.Now);}catch(DirectoryNotFoundException){return new JournalSource(name,"",DateTime.Now);}catch(UnauthorizedAccessException){return new JournalSource(name,"Нет доступа к журналу.",DateTime.Now);}catch(IOException){return new JournalSource(name,"Журнал занят другим процессом. Нажмите «Обновить».",DateTime.Now);}
    }
    public static string DiagnosticBundle(string root,string configuration){
      var output=new System.Text.StringBuilder("MCRF · полный диагностический набор · "+DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")+Environment.NewLine+"Секреты удалены. IP, домены, имена серверов и маршруты остаются для диагностики."+Environment.NewLine);
      output.AppendLine("=== Настройки приложения ===");output.AppendLine(JournalStyle.Redact(configuration));
      foreach(var name in new[]{"client.log","core.log","zapret.log","tgws.log","warp.log","byetube.log","sing-box.json","zapret-results.json","discord-voice-result.json","byetube-results/zapret-results.json"}){
        output.AppendLine("=== "+name+" ===");string path=Path.Combine(root,name);
        try{using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)){
          if(stream.Length>128L*1024*1024)throw new IOException("Файл "+name+" больше 128 МБ. Откройте папку; набор не скопирован частично.");
          using(var reader=new StreamReader(stream)){string text=reader.ReadToEnd();if(text.Length+output.Length>128*1024*1024)throw new IOException("Набор больше 128 МБ. Откройте папку; набор не скопирован частично.");output.AppendLine(JournalStyle.Redact(text));}
        }}catch(FileNotFoundException){output.AppendLine("Файл ещё не создан.");}catch(DirectoryNotFoundException){output.AppendLine("Папка ещё не создана.");}
      }
      return output.ToString();
    }
    public static string ReadEngineLog(string root){
      string path=Path.Combine(root,"core.log");
      try{using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)){
        if(stream.Length>32L*1024*1024)throw new IOException("Журнал движка больше 32 МБ. Откройте папку журнала; файл не будет скопирован частично.");
        using(var reader=new StreamReader(stream)){var text=new System.Text.StringBuilder();var buffer=new char[8192];int count;
          while((count=reader.Read(buffer,0,buffer.Length))>0){if(text.Length+count>32*1024*1024)throw new IOException("Журнал слишком большой для буфера обмена.");text.Append(buffer,0,count);}
          if(text.Length==0)throw new IOException("Журнал движка пока пуст.");
          return "MCRF · журнал сетевого движка · "+DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")+Environment.NewLine+"Секреты скрыты; адреса оставлены для диагностики."+Environment.NewLine+JournalStyle.Redact(text.ToString());
        }
      }}catch(FileNotFoundException){throw new IOException("Журнала движка пока нет. Сначала запустите VPN.");}catch(DirectoryNotFoundException){throw new IOException("Папка журнала движка пока не создана.");}
    }
    public static JournalEntry[] Read(string root,int limit){return Merge(limit,ReadSource("Клиент",Path.Combine(root,"client.log")),ReadSource("Сеть",Path.Combine(root,"core.log")),ReadSource("Zapret",Path.Combine(root,"zapret.log")),ReadSource("ByeTube",Path.Combine(root,"byetube.log")),ReadSource("Telegram WS",Path.Combine(root,"tgws.log")),ReadSource("WARP",Path.Combine(root,"warp.log")));}
    public static string Format(JournalEntry[] entries){return (entries.Any(entry=>entry.InferredDate)?"Для старых записей без даты день определён по файлу."+Environment.NewLine:"")+String.Join(Environment.NewLine,entries.Select(entry=>JournalStyle.Redact(entry.ToString())));}
  }
}
