using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class HotkeyServices {
  internal Func<Dictionary<string,string>> Snapshot;
  internal Func<Dictionary<string,string>,Task> Install;
 }
 internal static class HotkeySettings {
  internal static readonly string[] Globals={"@zapret","@warp","@tun"};
  internal static Dictionary<string,string> Bindings(ClientState state,bool includeHidden=false){
   var result=new Dictionary<string,string>();var targets=(includeHidden||state.CompactAdvancedFeatures?state.ZapretProfiles.Select(p=>p.Id):new string[0]).Concat(Globals.Where(id=>includeHidden||state.CompactAdvancedFeatures||id=="@tun"));
   foreach(string id in targets){if(String.IsNullOrEmpty(id)||result.ContainsKey(id))throw new InvalidOperationException("Повторяющийся идентификатор действия горячей клавиши.");string value=null;if(id.StartsWith("@")){if(state.GlobalHotkeys!=null)state.GlobalHotkeys.TryGetValue(id,out value);}else value=state.ZapretProfiles.First(p=>p.Id==id).Hotkey;var chord=ProfileHotkey.Parse(value);result.Add(id,chord==null?null:chord.ToString());}
   var assigned=result.Values.Where(v=>v!=null).ToArray();if(assigned.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=assigned.Length)throw new InvalidOperationException("Это сочетание уже назначено другому действию MCRF.");return result;
  }
  internal static void Stage(ClientState state,Dictionary<string,string> draft){
   var normalized=new Dictionary<string,string>();foreach(var item in draft){if(item.Key.StartsWith("@")?!Globals.Contains(item.Key):!state.ZapretProfiles.Any(p=>p.Id==item.Key))throw new InvalidOperationException("Действие или профиль уже удалён. Откройте горячие клавиши заново.");var chord=ProfileHotkey.Parse(item.Value);normalized.Add(item.Key,chord==null?null:chord.ToString());}
   foreach(var item in normalized){if(item.Key.StartsWith("@")){if(state.GlobalHotkeys==null)state.GlobalHotkeys=new Dictionary<string,string>();if(item.Value==null)state.GlobalHotkeys.Remove(item.Key);else state.GlobalHotkeys[item.Key]=item.Value;}else state.ZapretProfiles.First(p=>p.Id==item.Key).Hotkey=item.Value;}
  }
 }
}
