using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
namespace SplifyWin {
 public sealed class TurnProfile {
  public string Protocol,Name,Host,Password,VkToken;public int Version,Port,WireGuardPort,LocalPort,Workers;public string[] Hashes;public bool Csqtt25;
  static string Required(Dictionary<string,string> f,string k){string v;if(!f.TryGetValue(k,out v)||String.IsNullOrWhiteSpace(v))throw new FormatException("В ссылке туннеля отсутствует параметр: "+k);return v;}
  static int Number(Dictionary<string,string> f,string k,int min,int max){int n;if(!Int32.TryParse(Required(f,k),out n)||n<min||n>max)throw new FormatException("Некорректный параметр туннеля: "+k);return n;}
  public static string NormalizeLegacy(string link){
   if(link==null||!link.StartsWith("csqtt://",StringComparison.OrdinalIgnoreCase))return link;
   var old=Regex.Match(link,@"^csqtt://connect\?v=2host=(.+?)peer=([0-9]+)password=(.+)$",RegexOptions.IgnoreCase);
   if(old.Success)return "csqtt://connect?v=2&host="+old.Groups[1].Value+"&peer="+old.Groups[2].Value+"&password="+old.Groups[3].Value;
   Uri u;if(Uri.TryCreate(link,UriKind.Absolute,out u)&&u.UserInfo.Length>0&&u.Port>0)return "csqtt://connect?v=2&host="+Uri.EscapeDataString(u.Host.Trim('[',']'))+"&peer="+u.Port+"&password="+Uri.EscapeDataString(Uri.UnescapeDataString(u.UserInfo))+u.Fragment;
   return link;
  }
  public static TurnProfile Parse(string link){
   bool sealed25=Csqtt25Link.IsSealed(link);link=sealed25?Csqtt25Link.Open(link):NormalizeLegacy(link);
   Uri uri;if(!Uri.TryCreate(link,UriKind.Absolute,out uri))throw new FormatException("Некорректная ссылка туннеля");string protocol=uri.Scheme.ToLowerInvariant();
   if(protocol!="csqtt"&&protocol!="wdtt"&&protocol!="qwdtt")throw new FormatException("Неизвестный формат туннеля");
   if(uri.Host!="connect"||uri.UserInfo.Length>0||!uri.IsDefaultPort)throw new FormatException("Ожидается ссылка туннеля connect с параметрами");
   var fields=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);string rawHashes=null;
   foreach(var item in uri.Query.TrimStart('?').Replace("&amp;","&").Split(protocol=="csqtt"?new[]{'&',';'}:new[]{'&'},StringSplitOptions.RemoveEmptyEntries)){var p=item.Split(new[]{'='},2);if(p.Length!=2)throw new FormatException("Некорректные параметры ссылки туннеля");string key=Uri.UnescapeDataString(p[0]);if(fields.ContainsKey(key))throw new FormatException("Повторяющийся параметр туннеля: "+key);fields.Add(key,Uri.UnescapeDataString(p[1]));if(key.Equals("hashes",StringComparison.OrdinalIgnoreCase))rawHashes=p[1];}
   int version=Number(fields,"v",1,2);if(version!=(protocol=="csqtt"?2:1))throw new FormatException("Неподдерживаемая версия ссылки туннеля");
   string host=Required(fields,"host").Trim('[',']');if(Uri.CheckHostName(host)==UriHostNameType.Unknown||host.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new FormatException("Некорректный адрес туннеля");
   string password;if(!fields.TryGetValue("password",out password)){if(protocol!="csqtt")throw new FormatException("Не задан пароль туннеля");password="";}if(password.Length>4096||protocol=="csqtt"&&password.Any(Char.IsWhiteSpace)||password.Any(Char.IsControl))throw new FormatException("Некорректный пароль туннеля");
   string[] hashes;if(rawHashes==null){if(protocol!="csqtt")throw new FormatException("В ссылке туннеля отсутствует hashes");hashes=new string[0];}else{if(rawHashes.Length==0)throw new FormatException("Пустой набор звонков VK");hashes=(protocol=="csqtt"?rawHashes:Uri.UnescapeDataString(rawHashes)).Split(protocol=="csqtt"?new[]{',','+'}:new[]{','},StringSplitOptions.RemoveEmptyEntries).Select(h=>{string value=Uri.UnescapeDataString(h).Trim();return value.StartsWith("https://",StringComparison.OrdinalIgnoreCase)?VkCalls.CallHash(value):value;}).ToArray();}
   if(rawHashes!=null&&hashes.Length==0||hashes.Length>(protocol=="csqtt"?6:32))throw new FormatException("Пустой набор или слишком много звонков VK для этого транспорта");
   if(protocol=="csqtt"){if(hashes.Distinct(StringComparer.Ordinal).Count()!=hashes.Length||hashes.Any(h=>!Regex.IsMatch(h,@"^[A-Za-z0-9_=/+\-]{16,2048}$")))throw new FormatException("Некорректный хеш звонка CSQTT");}
   else foreach(string h in hashes){try{string b=h.Replace('-','+').Replace('_','/');if(Convert.FromBase64String(b.PadRight((b.Length+3)/4*4,'=')).Length!=32)throw new FormatException();}catch{throw new FormatException("Некорректный хеш туннеля");}}
   string vkToken=null;if(fields.TryGetValue("token",out vkToken)&&(protocol!="csqtt"||String.IsNullOrEmpty(vkToken)||vkToken.Length>4096||vkToken.Any(Char.IsWhiteSpace)||vkToken.Any(Char.IsControl)||hashes.Length>0))throw new FormatException("Некорректный токен VK или одновременно заданы токен и звонки.");
   if(sealed25)hashes=hashes.Select(h=>h.TrimEnd('=').Replace('+','-').Replace('/','_')).ToArray();
   string name=Uri.UnescapeDataString(uri.Fragment.TrimStart('#'));if(String.IsNullOrWhiteSpace(name))fields.TryGetValue("name",out name);
   return new TurnProfile{Protocol=protocol,Version=version,Csqtt25=sealed25,Host=host,Name=String.IsNullOrWhiteSpace(name)?(sealed25?"CSQTT 2.5 / Rebirth":protocol.ToUpperInvariant())+" · "+host:name,Password=password,VkToken=vkToken,Hashes=hashes,Workers=fields.ContainsKey("workers")?Number(fields,"workers",sealed25?3:9,sealed25?100:126):18,Port=Number(fields,protocol=="csqtt"?"peer":"dtls",1,65535),WireGuardPort=protocol=="csqtt"?0:Number(fields,"wg",1,65535),LocalPort=protocol=="csqtt"?0:Number(fields,"local",1,65535)};
  }
 }
}
