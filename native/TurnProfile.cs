using System;
using System.Collections.Generic;
using System.Linq;

namespace SplifyWin {
  // Share-link parsing is separate from engine availability. Never interpret
  // the URI authority "connect" as an upstream or silently drop pinned hashes.
  public sealed class TurnProfile {
    public string Protocol,Name,Host,Password;public int Version,Port,WireGuardPort,LocalPort;public string[] Hashes;
    static string Required(Dictionary<string,string> fields,string key){string value;if(!fields.TryGetValue(key,out value)||String.IsNullOrWhiteSpace(value))throw new FormatException("В ссылке туннеля отсутствует обязательный параметр: "+key);return value;}
    static int Number(Dictionary<string,string> fields,string key,int min,int max){int value;if(!Int32.TryParse(Required(fields,key),out value)||value<min||value>max)throw new FormatException("Некорректный параметр ссылки туннеля: "+key);return value;}
    public static TurnProfile Parse(string link){
      Uri uri;if(!Uri.TryCreate(link,UriKind.Absolute,out uri))throw new FormatException("Некорректная ссылка туннеля");var protocol=uri.Scheme.ToLowerInvariant();
      if(protocol!="csqtt"&&protocol!="wdtt"&&protocol!="qwdtt")throw new FormatException("Неизвестный формат туннеля");
      if(uri.Host!="connect"||uri.UserInfo.Length>0||!uri.IsDefaultPort)throw new FormatException("Ожидается ссылка туннеля connect с параметрами");
      var fields=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
      foreach(var item in uri.Query.TrimStart('?').Split(new[]{'&'},StringSplitOptions.RemoveEmptyEntries)){var parts=item.Split(new[]{'='},2);if(parts.Length!=2)throw new FormatException("Некорректные параметры ссылки туннеля");var key=Uri.UnescapeDataString(parts[0]);if(fields.ContainsKey(key))throw new FormatException("Повторяющийся параметр ссылки туннеля: "+key);fields.Add(key,Uri.UnescapeDataString(parts[1]));}
      var version=Number(fields,"v",1,2);if(version!=(protocol=="csqtt"?2:1))throw new FormatException("Неподдерживаемая версия ссылки туннеля");
      var host=Required(fields,"host").Trim('[',']');if(Uri.CheckHostName(host)==UriHostNameType.Unknown||host.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new FormatException("Некорректный адрес туннеля");
      var hashes=Required(fields,"hashes").Split(new[]{',','+'},StringSplitOptions.RemoveEmptyEntries).Select(x=>x.Trim()).ToArray();if(hashes.Length==0||hashes.Length>32)throw new FormatException("Некорректный набор хешей туннеля");
      foreach(var hash in hashes){try{var value=hash.Replace('-','+').Replace('_','/');if(Convert.FromBase64String(value.PadRight((value.Length+3)/4*4,'=')).Length!=32)throw new FormatException();}catch{throw new FormatException("Некорректный хеш туннеля");}}
      string name=Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));if(String.IsNullOrWhiteSpace(name))fields.TryGetValue("name",out name);return new TurnProfile{Protocol=protocol,Version=version,Host=host,Name=String.IsNullOrWhiteSpace(name)?protocol.ToUpperInvariant()+" · "+host:name,Port=Number(fields,protocol=="csqtt"?"peer":"dtls",1,65535),WireGuardPort=protocol=="csqtt"?0:Number(fields,"wg",1,65535),LocalPort=protocol=="csqtt"?0:Number(fields,"local",1,65535),Password=Required(fields,"password"),Hashes=hashes};
    }
  }
}
