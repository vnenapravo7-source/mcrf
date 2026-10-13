using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
namespace SplifyWin {
 public static class StrategyParameterEditor {
  public const string TokenPattern=@"--[a-z0-9-]+(?:=(?:""[^""\r\n]*""|[^\s""\r\n]+))?";
  public static string[] Parameters(string help){return Regex.Matches(help??"",@"(?m)^\s*(--[a-z0-9-]+)").Cast<Match>().Select(m=>m.Groups[1].Value).Where(IsEditable).Concat(new[]{"--dpi-desync","--dpi-desync-fooling","--dpi-desync-fake-tls","--dpi-desync-fake-quic"}).Distinct().OrderBy(s=>s,StringComparer.Ordinal).ToArray();}
  public static bool IsEditable(string flag){return Regex.IsMatch(flag??"",@"^--(?:dpi-desync(?:-[a-z0-9-]+)?|dup(?:-[a-z0-9-]+)?|ip-id|filter-(?:tcp|udp|l7|l3)|orig-[a-z0-9-]+|wsize|wssize(?:-[a-z0-9-]+)?|synack-split|hostcase|hostspell|hostnospace|domcase|methodeol|skip)$");}
  public static string[] Tokens(string block){return Regex.Matches(block??"",TokenPattern).Cast<Match>().Select(m=>m.Value).ToArray();}
  public static string Flag(string token){int at=token.IndexOf('=');return at<0?token:token.Substring(0,at);}
  public static string Value(string token){int at=token.IndexOf('=');return at<0?"":token.Substring(at+1);}
  public static string Change(string block,string flag,string value,bool remove){
   if(!IsEditable(flag))throw new FormatException("Этот параметр управляется маршрутами MCRF, а не конструктором.");
   var tokens=Tokens(block).ToList();int index=tokens.FindIndex(t=>Flag(t)==flag);tokens.RemoveAll(t=>Flag(t)==flag);
   if(!remove){value=(value??"").Trim();if(value.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new FormatException("Значение должно быть одной строкой.");string token=flag+(value.Length==0?"":"="+value);if(Tokens(token).Length!=1||Tokens(token)[0]!=token)throw new FormatException("Пробелы в значении нужно заключить в двойные кавычки.");tokens.Insert(index<0?tokens.Count:Math.Min(index,tokens.Count),token);}
   return String.Join(" ",tokens);
  }
  public static string Compose(string[] blocks){return String.Join("\r\n--new\r\n",blocks.Select(b=>String.Join(" ^\r\n",Tokens(b))));}
  public static string Describe(string flag,string help){
   string advice=flag=="--filter-tcp"?"Выбирает TCP-порты для этого блока. Через запятую задавайте порты или диапазоны, например 80,443 или 1024-65535. Не назначает домены или выход.":flag=="--filter-udp"?"Выбирает UDP-порты для этого блока. Через запятую задавайте порты или диапазоны; UDP 443 обычно используется QUIC. Не включает обработку TCP.":flag=="--dpi-desync-repeats"?"Сколько раз посылать поддельный пакет. Увеличение может помочь DPI увидеть фейк, но добавляет трафик; сравнивайте доступность и задержку.":flag=="--dpi-desync"?"Тип подмены. Можно сочетать методы через запятую; порядок и протокол важны. Начните с основы и проверяйте одно изменение за раз.":flag.Contains("fooling")?"Как сделать поддельный пакет непригодным для сервера. Несколько методов задаются через запятую. Не все сочетания совместимы с TCP/UDP.":flag.Contains("fake-")&&!flag.EndsWith("-mod")?"Содержимое поддельного пакета. Выберите .bin из папки движка либо задайте путь/HEX в формате справки. Файл должен соответствовать протоколу.":flag.Contains("split")?"Разбиение данных: имеет смысл с соответствующим режимом dpi-desync. Позиции могут быть числами или маркерами. Само добавление параметра не включает режим разбиения.":flag.Contains("ttl")?"TTL/лимит переходов пакета. Слишком малое значение не достигнет DPI, слишком большое может достичь сервера. Сравнивайте на своём подключении.":flag=="--dpi-desync-cutoff"?"Порог прекращения подмены: n — пакеты, d — пакеты с данными, s — последовательность байтов. Удаление аргумента возвращает поведение движка по умолчанию.":"Значение можно изменить или удалить весь аргумент. Пустое значение сохраняет флаг без =. Сохранение не означает, что стратегия работает: проверьте копию.";
   var lines=(help??"").Split('\n');int index=Array.FindIndex(lines,l=>Regex.IsMatch(l,@"^\s*"+Regex.Escape(flag)+@"(?:=|\s|$)"));if(index<0)return advice;var detail=new List<string>{lines[index].Trim()};for(int i=index+1;i<lines.Length&&detail.Count<5&&!Regex.IsMatch(lines[i],@"^\s*--");i++)if(lines[i].Trim().Length>0)detail.Add(lines[i].Trim());return advice+"\r\n"+String.Join("\r\n",detail);
  }
  public static string[] PayloadFiles(string directory){return Directory.Exists(directory)?Directory.GetFiles(directory,"*.bin",SearchOption.TopDirectoryOnly).OrderBy(Path.GetFileName,StringComparer.OrdinalIgnoreCase).ToArray():new string[0];}
  public static string ChoiceAdvice(string flag,string value){
   if(String.IsNullOrEmpty(value)||value=="Ввести значение вручную")return "Ручной ввод: для своего значения или сочетания через запятую. Сверяйтесь со справкой движка. Начните со значения основы; меняйте один параметр и проверяйте копию.";
   var advice=new Dictionary<string,string>{
    {"fake","Посылает поддельный пакет. Имеет смысл пробовать, когда DPI распознаёт начало соединения; нужен подходящий способ отбрасывания фейка сервером."},
    {"fakeknown","Фейк только для распознанного протокола. Пробуйте, если не хотите воздействовать на неизвестный трафик."},
    {"multisplit","Делит TCP-данные на части. Пробуйте для TCP; точки разделения задаёт split-pos. Для UDP не подходит."},
    {"multidisorder","TCP-части отправляются в изменённом порядке. Сравните с multisplit, если обычное разделение не помогает; возможны повторные передачи."},
    {"fakedsplit","TCP-разделение с дополнительными фейками. Пробуйте после простого разделения: больше вмешательства и настроек."},
    {"fakeddisorder","Изменённый порядок TCP-частей и фейки. Альтернатива fakedsplit; проверяйте задержки и стабильность."},
    {"hostfakesplit","TCP-разделение с фейковым именем хоста. Для HTTP/TLS; требует подходящих параметров имени и разделения."},
    {"none","Отключает этот способ модификации. Это значение аргумента, а не удаление аргумента: для удаления есть отдельная кнопка."},
    {"md5sig","Добавляет TCP MD5 в фейк, чтобы сервер его отверг. Только TCP; зависит от обработки опции по пути."},
    {"badseq","Меняет TCP sequence у фейка. Только TCP; сравните с md5sig, если фейк не должен приниматься сервером."},
    {"badsum","Неверная контрольная сумма фейка. Может не дойти до DPI из-за проверки промежуточным оборудованием."},
    {"datanoack","Фейк TCP без ACK. Только TCP; совместимость зависит от сети и сервера."},
    {"ts","Модификация TCP timestamps фейка. Только TCP; проверяйте поддержку и результат на своём подключении."},
    {"hopbyhop","IPv6 hop-by-hop. Только для IPv6; для IPv4 выберите другой метод."},
    {"hopbyhop2","Двойной IPv6 hop-by-hop. Только IPv6; возможны отбрасывания промежуточными узлами."},
    {"destopt","IPv6 destination options. Только IPv6; не является заменой TCP-разделению в IPv4."},
    {"ipfrag1","IP-фрагментация на раннем этапе. Проверяйте поддержку протоколом и сетью: фрагменты могут отбрасываться."},
    {"ipfrag2","IP-фрагментация на втором этапе. Сравнивайте отдельно от TCP-разделения; возможны проблемы сборки фрагментов."},
    {"udplen","Изменяет длину UDP-пакета. Только UDP; сервер и сеть могут отвергать изменённые пакеты."},
    {"tamper","Меняет содержимое UDP для поддерживаемого протокола. Только UDP; не используйте как универсальную подмену любого трафика."},
    {"synack","Воздействует на начальный TCP handshake. Пробуйте отдельно, если проблема возникает ещё при установлении TCP-соединения."},
    {"syndata","Добавляет данные на этапе TCP SYN. Требует проверки совместимости; не начинайте с него для уже работающей основы."},
    {"rst","Отправляет фейковый TCP RST. Только TCP; неверная настройка может разорвать соединение."},
    {"rstack","Фейковый TCP RST с ACK. Только TCP; сначала проверьте копию, поскольку принятие пакета сервером может прервать соединение."},
    {"rnd","Рандомизирует TLS-фейк. Используйте с TLS-фейком; сравнивайте несколько запусков, а не один успешный запрос."},
    {"rndsni","Меняет SNI TLS-фейка на случайное имя. Используйте для TLS, если хотите сравнить с фиксированным именем."},
    {"dupsid","Копирует session ID в TLS-фейк. Зависит от исходного TLS; проверяйте совместимость выбранного фейка."},
    {"padencap","Дополняет/инкапсулирует TLS-фейк. Размер и формат важны; пробуйте после рабочего базового варианта."}
   };
   return String.Join("\r\n",value.Split(',').Select(v=>advice.ContainsKey(v)?advice[v]:v.StartsWith("sni=")?"Фиксированный SNI TLS-фейка. Сравните с SNI основы; это не меняет адрес назначения соединения.":"Проверьте смысл и совместимость этого значения в справке движка."))+"\r\nНет универсально лучшего варианта. Сохраняйте копию и сравнивайте доступность, стабильность и задержки.";
  }
 }
}
