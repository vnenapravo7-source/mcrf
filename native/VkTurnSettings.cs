using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Concurrent;
namespace SplifyWin {
 public sealed class VkTurnServerPreferences {public int Workers{get;set;}public bool Masque{get;set;}public string TurnSni{get;set;}public bool AutoCalls{get;set;}public int CallCount{get;set;}public string CaptchaMode{get;set;}public string QwdttMode{get;set;}public Dictionary<string,string> Options{get;set;}public VkTurnServerPreferences(){CallCount=6;CaptchaMode="auto";Options=new Dictionary<string,string>();}}
 public sealed class VkTurnPreferences {
  public string Token{get;set;}public DateTime TokenExpires{get;set;}
  public List<string> Calls{get;set;}public bool SupplementAll{get;set;}
  public Dictionary<string,int> Streams{get;set;}
  public Dictionary<string,VkTurnServerPreferences> Servers{get;set;}public bool MasqueAccepted{get;set;}
  public Dictionary<string,List<string>> CallGroups{get;set;}
  public bool PreventSleep{get;set;}
  public bool PauseOnWifi{get;set;}public bool TrustedWifiEnabled{get;set;}public List<string> TrustedSsids{get;set;}
  public VkTurnPreferences(){Calls=new List<string>();Streams=new Dictionary<string,int>();TrustedSsids=new List<string>();Servers=new Dictionary<string,VkTurnServerPreferences>();CallGroups=new Dictionary<string,List<string>>();}
  public bool Linked{get{return !String.IsNullOrEmpty(Token)&&TokenExpires>DateTime.UtcNow;}}
 }
 public static class VkTurnSettings {
  public const int ExtendedCallLimit=32;
  public static int MaximumCalls(ServerNode node){return node.Protocol=="csqtt"&&CsqttGenerations.For(node).Id!="2.5"?6:ExtendedCallLimit;}
  static readonly object gate=new object();
  static string PathFor(string root){return Path.Combine(root,"vk-turn.dat");}
  public static VkTurnPreferences Load(string root){lock(gate){var file=PathFor(root);if(!File.Exists(file))return new VkTurnPreferences();VkTurnPreferences p=null;SecretStorage.ReadValidated(file,text=>{p=new JavaScriptSerializer().Deserialize<VkTurnPreferences>(text);Validate(p);ValidateExtended(p);p.TokenExpires=p.TokenExpires.ToUniversalTime();});return p;}}
  public static void Update(string root,Action<VkTurnPreferences> change){lock(gate){var p=Load(root);change(p);Validate(p);ValidateExtended(p);Directory.CreateDirectory(root);SecretStorage.Write(PathFor(root),new JavaScriptSerializer().Serialize(p));}}
  static void ValidateExtended(VkTurnPreferences p){if(p.Servers==null||p.CallGroups==null||p.Servers.Count>4096||p.CallGroups.Count>4096)throw new FormatException("Некорректные настройки подключений VK.");foreach(var s in p.Servers.Values)if(s==null||s.Workers<0||s.Workers>162||s.CallCount<1||s.CallCount>32||!new[]{"","auto","manual","rjs"}.Contains(s.CaptchaMode??"")||!new[]{"","raw","wireguard"}.Contains(s.QwdttMode??""))throw new FormatException("Некорректные параметры VK TURN.");foreach(var group in p.CallGroups.Values){if(group==null||group.Count>32)throw new FormatException("Некорректная группа звонков VK.");foreach(var call in group)VkCalls.CallHash(call);}}
  public static void Validate(VkTurnPreferences p){if(p==null||p.Calls==null||p.Streams==null||p.TrustedSsids==null)throw new FormatException("Настройки VK TURN повреждены.");if(p.Calls.Count>2048)throw new FormatException("Слишком много звонков VK.");foreach(var call in p.Calls)VkCalls.CallHash(call);foreach(var item in p.Streams)if(!Values(item.Key).Contains(item.Value))throw new FormatException("Некорректное число потоков VK TURN.");if(p.TrustedSsids.Count>32||p.TrustedSsids.Any(s=>String.IsNullOrWhiteSpace(s)||System.Text.Encoding.UTF8.GetByteCount(s)>32||s.Any(Char.IsControl)))throw new FormatException("Укажите до 32 точных имён Wi-Fi, не более 32 байт каждое.");if((p.Token??"").Length>8192||(p.Token??"").Any(Char.IsControl))throw new FormatException("Некорректная сессия VK.");}
  public static int[] Values(string kind){int step=kind=="csqtt25"?10:9,max=kind=="csqtt25"?100:kind=="csqtt"?126:kind=="wdtt"?108:kind=="qwdtt"?162:0;if(max==0)throw new FormatException("Неизвестный транспорт VK TURN.");var values=Enumerable.Range(1,max/step).Select(i=>i*step);return (kind=="csqtt25"?values.Concat(new[]{18}):values).OrderBy(n=>n).ToArray();}
  public static string[] Merge(IEnumerable<string> existing,IEnumerable<string> extra,int maximum){return existing.Select(h=>h.Trim().Replace('+','-').Replace('/','_')).Concat(extra.Select(VkCalls.CallHash)).Distinct(StringComparer.Ordinal).Take(maximum).ToArray();}
  public static int Workers(VkTurnPreferences p,string kind,int calls,int original){int value;if(!p.Streams.TryGetValue(kind,out value))return original;int maximum=kind=="csqtt25"?Math.Min(100,Math.Max(1,calls)*10):Math.Min(Values(kind).Last(),Math.Max(1,calls)*27);return Math.Min(value,maximum);}
  public static TurnProfile Apply(TurnProfile profile,VkTurnPreferences p){if(p.SupplementAll)profile.Hashes=Merge(profile.Hashes,p.Calls,profile.Protocol=="wdtt"||profile.Csqtt25?ExtendedCallLimit:6);return profile;}
  public static QwdttProfile Apply(QwdttProfile profile,VkTurnPreferences p){if(p.SupplementAll)profile.Hashes=Merge(profile.Hashes,p.Calls,32);profile.Workers=Workers(p,"qwdtt",profile.Hashes.Length,profile.Workers);return profile;}
  public static VkTurnServerPreferences Server(VkTurnPreferences p,string id){VkTurnServerPreferences v;return p.Servers!=null&&p.Servers.TryGetValue(id,out v)?v:new VkTurnServerPreferences();}
  public static bool BsOnly(ClientState state){var nodes=state.Servers.Where(n=>state.AutoSelect||n.Id==state.SelectedServerId).ToArray();return state.Mode=="tun"&&state.VpnEnabled&&!state.WarpEnabled&&nodes.Length>0&&nodes.All(n=>TurnEngines.IsTurn(n)||n.Protocol=="qwdtt");}
  public static int ServerWorkers(VkTurnPreferences p,string id,string kind,int calls,int original){var setting=Server(p,id);int value=setting.Workers==0?Workers(p,kind,calls,original):setting.Workers;bool account=VkTurnOptions.Value(setting,"vk_hash_mode")=="auto_js",redistribute=VkTurnOptions.Flag(setting,"allow_hash_redistribution");if((kind=="csqtt25"?(value<3||value>100):!Values(kind).Contains(value))||(!account&&!redistribute&&value>Math.Max(1,calls)*(kind=="csqtt25"?10:27)&&!(kind=="csqtt25"&&setting.Workers==0&&value==18)))throw new FormatException("Число потоков сервера превышает доступное для его звонков.");return value;}
  static readonly ConcurrentDictionary<string,SemaphoreSlim> callGates=new ConcurrentDictionary<string,SemaphoreSlim>();
  public static async Task<string[]> ResolveCalls(string root,string id,string protocol,string[] original,bool probe,Func<string,CancellationToken,Task<string>> captcha,CancellationToken cancellation,string linkToken=null,VkTurnServerPreferences connection=null,int maximumCalls=0){
   int limit=maximumCalls>0?maximumCalls:protocol=="csqtt"?6:ExtendedCallLimit;if(limit<1||limit>ExtendedCallLimit)throw new FormatException("Некорректный предел звонков.");var settings=Load(root);var server=connection??Server(settings,id);bool tokenMode=!String.IsNullOrEmpty(linkToken);int requested=tokenMode?6:server.CallCount;if(!server.AutoCalls&&!tokenMode)return settings.SupplementAll?Merge(original,settings.Calls,limit):original;
   int maximum=limit;if(requested<1||requested>maximum)throw new FormatException("Некорректное число звонков для этого транспорта.");if(!settings.Linked&&!tokenMode)throw new InvalidOperationException("Привяжите VK в разделе VK TURN для автоматических звонков.");
   var mutex=callGates.GetOrAdd(Path.GetFullPath(root)+"\n"+id,key=>new SemaphoreSlim(1,1));await mutex.WaitAsync(cancellation);try{settings=Load(root);List<string> cached;var calls=settings.CallGroups.TryGetValue(id,out cached)?cached.Select(VkCalls.CallHash).Distinct().ToList():new List<string>();if(probe&&calls.Count<requested)throw new InvalidOperationException("Сначала подключите этот сервер вручную: фоновая проверка звонки VK не создаёт.");
    var token=new VkLoginToken{Value=tokenMode?linkToken:settings.Token,Expires=tokenMode?DateTime.UtcNow.AddMinutes(5):settings.TokenExpires};try{while(calls.Count<requested){cancellation.ThrowIfCancellationRequested();string call=VkCalls.CallHash(await VkCalls.CreateCall(token,url=>captcha==null?Task.FromResult<string>(null):captcha(url,cancellation),cancellation));if(calls.Contains(call))throw new InvalidOperationException("VK вернул повторный звонок; автоматический повтор не выполняется.");calls.Add(call);Update(root,p=>p.CallGroups[id]=calls.ToList());if(calls.Count<requested)await Task.Delay(500,cancellation);}return calls.Take(requested).ToArray();}finally{token.Value=null;}
   }finally{mutex.Release();}
  }
 }
}
