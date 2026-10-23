using System;
using System.Linq;
using System.Collections.Generic;
using SplifyWin;
namespace Mcrf.Wpf {
 public static class PresetComposition {
  public static void Replace(RouteList route,IEnumerable<RoutePreset> chosen,Dictionary<string,string> loaded,string editedText){
   var old=new Dictionary<string,string>(route.PresetEntries??new Dictionary<string,string>());foreach(string name in route.PresetNames??new List<string>())if(!old.ContainsKey(name)&&loaded.ContainsKey(name))old[name]=loaded[name];
   var scratch=new RouteList{Text=editedText,CustomText=route.CustomText};string manual=AppUpdates.ReplacePresetSlice(scratch,"",String.Join("\n",old.Values),"");var selected=chosen.ToArray();var next=selected.ToDictionary(p=>p.Name,p=>loaded[p.Name],StringComparer.OrdinalIgnoreCase);
   bool wasOutside=(route.PresetNames??new List<string>()).Any(n=>n==OutsideRussiaPreset.Name||n=="Все кроме РФ");bool outside=selected.Any(p=>p.OutsideRussia);
   route.ExcludeRussia=outside||(!wasOutside&&route.ExcludeRussia);if(outside){route.Target="proxy";route.InvertAddresses=false;route.MatchMode="addresses";}
   scratch.Text=manual;route.Text=AppUpdates.ReplacePresetSlice(scratch,"","",String.Join("\n",next.Values));route.CustomText=manual;route.PresetEntries=next;route.PresetNames=selected.Select(p=>p.Name).Distinct().ToList();route.SourceUrls=selected.Where(p=>!p.OutsideRussia).SelectMany(PresetStorage.Sources).Distinct().ToList();
  }
 }
}
