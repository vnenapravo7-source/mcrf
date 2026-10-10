using System;
using System.Collections.Generic;
using System.Linq;
namespace SplifyWin {
  public static class ZapretRoutes {
    public static bool IsZapret(RouteList list){return list!=null&&list.Target=="zapret";}
    public static void Validate(RouteList list,IEnumerable<ZapretProfile> profiles){if(!IsZapret(list))return;if(!profiles.Any(p=>p.Id==list.ZapretProfileId))throw new InvalidOperationException("Выберите существующий профиль Zapret");var fields=ZapretScope.Validate(list);if(fields["ip_cidr"].Any(x=>x.EndsWith("/0")))throw new InvalidOperationException("Глобальная сеть /0 для Zapret запрещена");}
    public static RouteList[] Lists(ClientState state,string id){return state.Lists.Where(l=>IsZapret(l)&&l.Enabled&&l.ZapretProfileId==id).ToArray();}
public static ZapretSettings Resolve(ClientState state,ZapretProfile profile){var settings=ZapretChecks.CopySettings(profile.Settings);settings.ExcludedApplications=ApplicationExclusions.Normalize(state.ExcludedApplications).ToList();var lists=Lists(state,profile.Id);settings.ScopeText=lists.Length==0?"":ZapretScope.Merge(lists);settings.MatchMode="any";settings.ScopeRules=lists.Select(l=>new RouteList{Id=l.Id,Name=l.Name,Text=l.Text,MatchMode=l.MatchMode,GameFilterTcp=l.GameTcpEnabled,GameFilterUdp=l.GameUdpEnabled}).ToList();settings.ScopeSources=lists.Select(l=>"list:"+l.Id).ToList();settings.CustomScopeText="";if(settings.Hosts!=null&&settings.Hosts.Count>0){string entries=String.Join("\n",settings.Hosts.SelectMany(h=>new[]{h.Domain,h.Address}).Distinct());settings.ScopeRules.Add(new RouteList{Text=entries,MatchMode="addresses"});settings.ScopeText+="\n"+entries;}return settings;}
    public static ZapretProfile[] Active(ClientState state){foreach(var l in state.Lists.Where(l=>IsZapret(l)&&l.Enabled))Validate(l,state.ZapretProfiles);return state.ZapretProfiles.Where(p=>p.Enabled&&Lists(state,p.Id).Length>0).Select(p=>new ZapretProfile{Id=p.Id,Name=p.Name,Enabled=true,Settings=Resolve(state,p)}).ToArray();}
    // Keep old scopes, strategies and identifiers. Migration runs once; future routing edits are authoritative.
    public static void Migrate(ClientState state){if(state.ZapretRoutingMigrated)return;foreach(var p in state.ZapretProfiles){var sources=p.Settings.ScopeSources;if(String.IsNullOrEmpty(p.Settings.Strategy)&&sources==null)continue;var referenced=(sources??new List<string>()).Where(x=>x.StartsWith("list:")).Select(x=>state.Lists.FirstOrDefault(l=>l.Id==x.Substring(5))).Where(l=>l!=null).ToArray();foreach(var list in referenced){if(IsZapret(list)&&list.ZapretProfileId!=p.Id)state.Lists.Add(new RouteList{Name=list.Name+" · "+p.Name,Text=list.Text,SourceUrl=list.SourceUrl,MatchMode=list.MatchMode,Target="zapret",ZapretProfileId=p.Id,Enabled=list.Enabled});else{list.Target="zapret";list.ZapretProfileId=p.Id;}}
        string extra=sources==null?p.Settings.ScopeText:(sources.Contains("custom")?p.Settings.CustomScopeText??p.Settings.ScopeText:"");if(sources!=null&&sources.Contains("youtube"))extra=(extra??"")+"\n"+new ZapretSettings().ScopeText;if(sources!=null&&sources.Contains("services"))extra=(extra??"")+"\n"+ZapretSettings.ServiceScope;if(!String.IsNullOrWhiteSpace(extra))state.Lists.Add(new RouteList{Name="Zapret · "+p.Name,Text=extra,Target="zapret",ZapretProfileId=p.Id,MatchMode="addresses"});}
      state.ZapretRoutingMigrated=true;
    }
  }
}
