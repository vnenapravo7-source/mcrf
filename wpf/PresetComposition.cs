using System;
using System.Linq;
using System.Collections.Generic;
using SplifyWin;
namespace Mcrf.Wpf {
 public static class PresetComposition {
  public static void Replace(RouteList route,IEnumerable<RoutePreset> chosen,Dictionary<string,string> loaded,string editedText){
   string issue=RoutePresets.MetadataIssue(route);if(issue!=null)throw new FormatException(issue);
   var old=(route.PresetEntries??new Dictionary<string,string>()).GroupBy(p=>p.Key,StringComparer.OrdinalIgnoreCase).ToDictionary(g=>g.Key,g=>g.First().Value,StringComparer.OrdinalIgnoreCase);foreach(string name in route.PresetNames??new List<string>()){var preset=RoutePresets.Resolve(name);string loadedText;if(!old.ContainsKey(name)&&preset!=null&&(loaded.TryGetValue(name,out loadedText)||loaded.TryGetValue(preset.Name,out loadedText)))old[name]=loadedText;}
   var unknown=old.Where(p=>RoutePresets.Resolve(p.Key)==null).ToArray();var unknownNames=(route.PresetNames??new List<string>()).Concat(unknown.Select(p=>p.Key)).Where(n=>RoutePresets.Resolve(n)==null).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
   var scratch=new RouteList{Text=editedText,CustomText=route.CustomText};string manual=AppUpdates.ReplacePresetSlice(scratch,"",String.Join("\n",old.Where(p=>RoutePresets.Resolve(p.Key)!=null).Select(p=>p.Value)),"");var selected=chosen.GroupBy(p=>p.Name,StringComparer.OrdinalIgnoreCase).Select(g=>g.First()).ToArray();var next=selected.ToDictionary(p=>p.Name,p=>loaded[p.Name],StringComparer.OrdinalIgnoreCase);foreach(var entry in unknown)next[entry.Key]=entry.Value;
   bool wasOutside=(route.PresetNames??new List<string>()).Any(n=>{var p=RoutePresets.Resolve(n);return p!=null&&p.OutsideRussia;});bool outside=selected.Any(p=>p.OutsideRussia);
   // Build and validate every replacement before changing the caller's draft.
   scratch.Text=manual;string text=AppUpdates.ReplacePresetSlice(scratch,"","",String.Join("\n",selected.Select(p=>next[p.Name])));
   var previousNames=new HashSet<string>((route.PresetNames??new List<string>()).Concat(old.Keys),StringComparer.OrdinalIgnoreCase);
   var knownSources=new HashSet<string>(RoutePresets.Groups.Where(p=>previousNames.Contains(p.Name)||previousNames.Contains(p.OriginalName??"")).Concat(selected).Where(p=>!p.OutsideRussia).SelectMany(PresetStorage.Sources),StringComparer.Ordinal);
   var sources=(route.SourceUrls??new List<string>()).Where(u=>!knownSources.Contains(u)).Concat(selected.Where(p=>!p.OutsideRussia).SelectMany(PresetStorage.Sources)).Distinct().ToList();
   string legacySource=knownSources.Contains(route.SourceUrl??"")?null:route.SourceUrl;
   route.ExcludeRussia=outside||(!wasOutside&&route.ExcludeRussia);if(outside){route.Target="proxy";route.InvertAddresses=false;route.MatchMode="addresses";}
   route.Text=text;route.CustomText=manual;route.PresetEntries=next;route.PresetNames=selected.Select(p=>p.Name).Concat(unknownNames).ToList();route.SourceUrls=sources;route.SourceUrl=legacySource;
  }
 }
}
