using System;
using System.Linq;
using System.Text;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Web;
using System.Web.Script.Serialization;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class ServerParameterDraft {
  internal readonly Dictionary<string,string> Values=new Dictionary<string,string>();
  internal readonly Dictionary<string,string> Labels=new Dictionary<string,string>();
  readonly ServerNode original;readonly string kind;Uri uri;NameValueCollection query;Dictionary<string,object> vmess;
  internal ServerParameterDraft(ServerNode node){original=node;kind=node.Protocol;Add("host","Адрес сервера",node.Host);Add("port","Порт",node.Port.ToString());
   if(kind=="wireguard"||kind=="awg"){foreach(var pair in Links.WireGuardFields(node.Link))if(!pair.Key.Equals("Endpoint",StringComparison.OrdinalIgnoreCase))Add("wg:"+pair.Key,pair.Key,pair.Value);foreach(string key in new[]{"MTU","DNS","ListenPort","PresharedKey","PersistentKeepalive"})if(!Values.Keys.Any(x=>x.Equals("wg:"+key,StringComparison.OrdinalIgnoreCase)))Add("wg:"+key,key,"");return;}
   if(kind=="vmess"){string raw=node.Link.Substring(8).Split('#')[0].Replace('-','+').Replace('_','/');vmess=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Convert.FromBase64String(raw.PadRight((raw.Length+3)/4*4,'='))));foreach(var pair in vmess.Where(x=>x.Key!="add"&&x.Key!="port"&&x.Key!="ps"&&(x.Value==null||x.Value is IConvertible)))Add("vm:"+pair.Key,pair.Key,Convert.ToString(pair.Value));foreach(string key in new[]{"id","aid","scy","net","host","path","tls","sni","alpn","fp"})if(!Values.ContainsKey("vm:"+key))Add("vm:"+key,key,"");return;}
   uri=new Uri(node.Link);query=HttpUtility.ParseQueryString(uri.Query);string auth=Uri.UnescapeDataString(uri.UserInfo);
   if(kind=="ss"&&!auth.Contains(":")){string raw=auth.Replace('-','+').Replace('_','/');auth=Encoding.UTF8.GetString(Convert.FromBase64String(raw.PadRight((raw.Length+3)/4*4,'=')));}
   Add("auth",kind=="vless"?"UUID":kind=="ss"?"Метод:пароль":kind=="trojan"||kind=="hysteria2"||kind=="anytls"?"Пароль":"Пользователь:пароль",auth);
   if(uri.AbsolutePath!="/")Add("path","Путь подключения",uri.AbsolutePath);
   var defaults=kind=="vless"||kind=="trojan"?new[]{"security","sni","fp","type","host","path","serviceName","flow","pbk","sid","spx","alpn","allowInsecure"}:kind=="hysteria2"||kind=="hysteria"||kind=="tuic"?new[]{"sni","alpn","insecure","obfs","obfs-password","upmbps","downmbps","congestion_control","udp_relay_mode"}:new string[0];
   foreach(string key in query.AllKeys.Concat(defaults).Where(x=>x!=null).Distinct())Add("q:"+key,QueryLabel(key),query[key]??"");
  }
  void Add(string key,string label,string value){Values[key]=value??"";Labels[key]=label;}
  static string QueryLabel(string key){switch(key){case"security":return"Защита · security";case"sni":return"TLS · SNI";case"fp":return"TLS · отпечаток";case"type":return"Транспорт";case"serviceName":return"gRPC · имя сервиса";case"pbk":return"Reality · публичный ключ";case"sid":return"Reality · Short ID";case"path":return"Путь транспорта";case"host":return"Host транспорта";default:return key;}}
  static string WireGuardValue(string text,string key,string value){var expression=new System.Text.RegularExpressions.Regex(@"(?im)^[ \t]*"+System.Text.RegularExpressions.Regex.Escape(key)+@"[ \t]*=[^\r\n]*(?:\r?\n|$)");var matches=expression.Matches(text);if(matches.Count>0){var match=matches[matches.Count-1];return text.Substring(0,match.Index)+(value.Length==0?"":key+" = "+value+"\n")+text.Substring(match.Index+match.Length);}if(value.Length==0)return text;string section=new[]{"PublicKey","PresharedKey","AllowedIPs","PersistentKeepalive","Endpoint"}.Any(x=>x.Equals(key,StringComparison.OrdinalIgnoreCase))?"Peer":"Interface";var header=System.Text.RegularExpressions.Regex.Match(text,@"(?im)^[ \t]*\["+section+@"\][^\r\n]*(?:\r?\n|$)");if(!header.Success)throw new FormatException("Отсутствует секция "+section);return text.Insert(header.Index+header.Length,key+" = "+value+"\n");}
  internal ServerNode Build(string name){if(Values.Values.Any(x=>x.Any(Char.IsControl)))throw new FormatException("Параметры не должны содержать переводы строк или управляющие символы.");int port;if(!Int32.TryParse(Values["port"],out port)||port<1||port>65535)throw new FormatException("Порт должен быть от 1 до 65535.");string host=Values["host"].Trim().Trim('[',']');if(Uri.CheckHostName(host)==UriHostNameType.Unknown)throw new FormatException("Укажите корректный адрес сервера.");string link;
   if(kind=="wireguard"||kind=="awg"){link=original.Link;var previous=Links.WireGuardFields(link);foreach(var pair in Values.Where(x=>x.Key.StartsWith("wg:"))){string key=pair.Key.Substring(3),old;if(previous.TryGetValue(key,out old)&&old==pair.Value)continue;link=WireGuardValue(link,key,pair.Value);}link=WireGuardValue(link,"Endpoint",(host.Contains(":")?"["+host+"]":host)+":"+port);}
   else if(kind=="vmess"){var data=new Dictionary<string,object>(vmess);data["add"]=host;data["port"]=port;data["ps"]=name;foreach(var pair in Values.Where(x=>x.Key.StartsWith("vm:"))){string key=pair.Key.Substring(3);object previous;if(data.TryGetValue(key,out previous)&&Convert.ToString(previous)==pair.Value)continue;if(!data.ContainsKey(key)&&pair.Value.Length==0)continue;if(previous is bool){bool boolean;if(!Boolean.TryParse(pair.Value,out boolean))throw new FormatException("Ожидается true или false: "+key);data[key]=boolean;}else if(previous!=null&&!(previous is string)&&previous is IConvertible){try{data[key]=Convert.ChangeType(pair.Value,previous.GetType(),System.Globalization.CultureInfo.InvariantCulture);}catch{throw new FormatException("Некорректное число: "+key);}}else data[key]=pair.Value;}link="vmess://"+Convert.ToBase64String(Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(data)));}
   else{var q=HttpUtility.ParseQueryString(uri.Query);foreach(var pair in Values.Where(x=>x.Key.StartsWith("q:"))){string key=pair.Key.Substring(2);if(pair.Value.Length==0)q.Remove(key);else q[key]=pair.Value;}string auth=Values["auth"];link=uri.Scheme+"://"+(auth.Length==0?"":Uri.EscapeDataString(auth)+"@")+(host.Contains(":")?"["+host+"]":host)+":"+port+(Values.ContainsKey("path")?Values["path"]:uri.AbsolutePath)+(q.Count>0?"?"+q:"")+"#"+Uri.EscapeDataString(name);}
   var parsed=Links.Read(link);if(parsed.Count!=1)throw new FormatException("Параметры не образуют корректное подключение.");parsed[0].Name=name;return parsed[0];
  }
 }
}
