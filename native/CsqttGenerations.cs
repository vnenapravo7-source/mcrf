using System;
using System.Linq;
using System.Collections.Generic;
namespace SplifyWin {
 public sealed class CsqttGeneration {
  public string Id,Label,Package,Executable,Revision,Source;
 }
 // A link-format version is not a wire-protocol revision. Never trial-connect
 // device-bound credentials to discover their generation.
 public static class CsqttGenerations {
  public static readonly CsqttGeneration[] All={
   new CsqttGeneration{Id="2.0",Label="CSQTT 2.0",Package="csqtt20",Executable="csqtt20-client.exe",Revision="2ff8a1a4fbe031fb76b799e172dce25cfe1ca942",Source="amurcanov/csqtt"},
   new CsqttGeneration{Id="2.1.5",Label="CSQTT 2.1.5",Package="csqtt215",Executable="csqtt215-client.exe",Revision="de7afc23cec7481f98845bf24fc8943af19b4cf8",Source="amurcanov/csqtt"},
   new CsqttGeneration{Id="2.1.9",Label="SWG 2.1 · CSQTT 2.1.9 / WIRE-3",Package="csqtt",Executable="csqtt-client.exe",Revision="446293aa2e873ac5323ef6fd2316d9b81d966c11",Source="amurcanov/csqtt"},
   new CsqttGeneration{Id="2.5",Label="SWG 2.5.1 · CSQTT 2.5 / Rebirth",Package="csqtt25",Executable="csqtt25-client.exe",Revision="fd936ef087c34cc46f26111dc2361f43b8b03c07",Source="ttrroyy/csqtt-rebirth"}
  };
  public static string Normalize(string id){if(String.IsNullOrWhiteSpace(id))return null;id=id.Trim().ToLowerInvariant();if(id=="rebirth"||id=="2.5.0"||id=="2.5.0-legacy")return "2.5";if(id=="2.0.0")return "2.0";if(!All.Any(g=>g.Id==id))throw new FormatException("Неизвестное поколение CSQTT. Выберите 2.0, 2.1.5, 2.1.9 или 2.5 / Rebirth.");return id;}
  public static CsqttGeneration Get(string id){return All.Single(g=>g.Id==Normalize(id));}
  public static string ChangeGenerationLink(string link,string generation){if(Csqtt25Link.IsSealed(link))return link;string id=Normalize(generation);return System.Text.RegularExpressions.Regex.Replace(link,@"([?&])mcrf_generation=[^&#]*",m=>m.Groups[1].Value+"mcrf_generation="+Uri.EscapeDataString(id));}
  public static string Hint(string link){if(Csqtt25Link.IsSealed(link))return "2.5";Uri uri;if(!Uri.TryCreate(link,UriKind.Absolute,out uri))return null;var query=System.Web.HttpUtility.ParseQueryString(uri.Query);return Normalize(query["mcrf_generation"]);}
  public static CsqttGeneration For(ServerNode node){string hint=Hint(node.Link),id=Normalize(node.CsqttGeneration)??hint??"2.1.9";if(Csqtt25Link.IsSealed(node.Link)&&id!="2.5")throw new FormatException("Зашифрованная ссылка CSQTT 2.5 несовместима с выбранным старым поколением.");if(hint!=null&&hint!=id)throw new FormatException("Поколение в ссылке CSQTT не совпадает с настройкой сервера.");return Get(id);}
  public static bool NeedsChoice(ServerNode node){return node.Protocol=="csqtt"&&String.IsNullOrEmpty(node.CsqttGeneration)&&Hint(node.Link)==null;}
  public static void ValidateBatch(string input){foreach(string raw in (input??"").Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)){string link=raw.Trim();if(link.StartsWith("mcrf-turn://",StringComparison.Ordinal)){try{VkTurnOptions.ReadEnvelope(link);}catch{throw new FormatException("Повреждённый профиль MCRF TURN. Импорт отменён целиком.");}continue;}if(!link.StartsWith("csqtt://",StringComparison.OrdinalIgnoreCase))continue;try{TurnProfile.Parse(link);Hint(link);}catch(FormatException){throw new FormatException("Повреждённая ссылка CSQTT или неизвестное поколение. Импорт отменён без добавления части списка.");}}}
  public static string Share(ServerNode node){if(node.Protocol!="csqtt"||Csqtt25Link.IsSealed(node.Link))return node.Link;string link=TurnProfile.NormalizeLegacy(node.Link);int fragment=link.IndexOf('#');string suffix=fragment<0?"":link.Substring(fragment);if(fragment>=0)link=link.Substring(0,fragment);if(Hint(link)!=null)return node.Link;return link+(link.Contains("?")?"&":"?")+"mcrf_generation="+Uri.EscapeDataString(For(node).Id)+suffix;}
  public static string[] RequiredPackages(NetworkCore core,IEnumerable<ServerNode> nodes){var ids=new List<string>();foreach(var node in nodes){if(node.Protocol=="qwdtt"&&QwdttEngine.Mode(node,VkTurnSettings.Load(core.DataRoot))=="raw"&&core.CsqttBridgeExecutable==null)ids.Add("csqtt-bridge");if(node.Protocol=="csqtt"){var gen=For(node);if(core.CSQTTExecutableFor(node)==null)ids.Add(gen.Package);if(core.CsqttBridgeExecutable==null)ids.Add("csqtt-bridge");}else if(new[]{"wdtt","qwdtt","openflux"}.Contains(node.Protocol)&&!core.HasEngine(node))ids.Add(node.Protocol);}return ids.Distinct().ToArray();}
 }
}
