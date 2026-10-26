using System;
using System.Linq;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class VkTurnEditorIdentity {
  internal readonly string Id;readonly string original;
  internal VkTurnEditorIdentity(ServerNode node){Id=node.Id;original=Fingerprint(node);}
  static string Fingerprint(ServerNode node){return new JavaScriptSerializer().Serialize(new{node.Name,node.Protocol,node.Link,node.CsqttGeneration,node.Host,node.Port,node.TurnPreferences});}
  internal bool Matches(ServerNode node){return node.Id==Id&&Fingerprint(node)==original;}
 }
 public sealed partial class ClientSession {
  internal async Task<bool> SaveVkTurnEditor(VkTurnEditorIdentity identity,ServerNode candidate,VkTurnServerPreferences draft,string name,Func<bool> cancelled){
   if(identity==null||candidate==null||draft==null)throw new ArgumentException("Не заданы параметры подключения.");
   var json=new JavaScriptSerializer();var captured=json.Deserialize<VkTurnServerPreferences>(json.Serialize(draft));string link=candidate.Link,generation=candidate.CsqttGeneration,protocol=candidate.Protocol;string rawName=name??"";name=rawName.Trim();
   if(name.Length==0||rawName.Length>256||rawName.Any(Char.IsControl))throw new FormatException("Укажите название подключения до 256 символов.");
   VkTurnOptions.Validate(new ServerNode{Link=link,CsqttGeneration=generation,Protocol=protocol},captured);
   await operations.WaitAsync();try{
    if(cancelled())return false;ProfileRecovery.DemandHealthy(Store.Root);var matches=State.Servers.Where(n=>n.Id==identity.Id).ToArray();if(matches.Length>1)throw new InvalidOperationException("Найдено несколько серверов с одним идентификатором. Сначала исправьте список подключений.");var node=matches.FirstOrDefault();if(node==null)throw new InvalidOperationException("Сервер удалён. Закройте окно параметров.");
    if(!identity.Matches(node)||node.Protocol!=protocol)throw new InvalidOperationException("Параметры сервера изменились, пока окно было открыто. Закройте его и откройте заново.");
    string oldName=node.Name,oldLink=node.Link,oldGeneration=node.CsqttGeneration;var previous=node.TurnPreferences;int oldLatency=node.Latency;
    var preferences=VkTurnOptions.ForNode(VkTurnSettings.Load(Store.Root),node);string oldHost=VkTurnOptions.ServerHost(VkTurnSettings.Server(preferences,node.Id),node.Host),newHost=VkTurnOptions.ServerHost(captured,node.Host);
    try{node.Name=name;node.Link=link;node.CsqttGeneration=generation;node.TurnPreferences=captured;if(!String.Equals(oldHost,newHost,StringComparison.OrdinalIgnoreCase))node.Latency=-1;await Save();return true;}
    catch{node.Name=oldName;node.Link=oldLink;node.CsqttGeneration=oldGeneration;node.TurnPreferences=previous;node.Latency=oldLatency;throw;}
   }finally{operations.Release();}
  }
 }
}
