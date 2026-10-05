using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
namespace SplifyWin {
  public sealed class ExitDnsSettings {
    public string Target{get;set;}public string Provider{get;set;}public string Protocol{get;set;}public string Custom{get;set;}
  }
  public static class ExitDnsRouting {
    public static string Target(RouteList list){return list.Target=="zapret"&&!String.IsNullOrEmpty(list.ZapretProfileId)?"zapret:"+list.ZapretProfileId:list.Target=="zapret"?"direct":list.Target;}
    public static bool Active(ClientState state,string target){return target=="proxy"?(!state.IndependentWarpOutputs||state.VpnEnabled):WarpOutputs.IsTarget(target)?state.WarpEnabled&&state.Lists.Any(r=>r.Enabled&&r.Target==target):target=="byetube"?state.ByeTubeEnabled:true;}
    static string DnsTag(Dictionary<string,string> tags,RouteList list){string tag;return tags.TryGetValue(Target(list),out tag)?tag:list.Target=="zapret"&&tags.TryGetValue("direct",out tag)?tag:"chosen";}
    public static bool AllInternet(RouteList list){
      if(list.InvertAddresses||list.MatchMode=="apps"||list.MatchMode=="in-apps"||list.MatchMode=="except-apps")return false;
      var fields=RouteCompiler.Parse(list.Text);
      return fields["ip_cidr"].Contains("0.0.0.0/0")&&fields["ip_cidr"].Contains("::/0")&&fields["process_name"].Count==0&&fields["process_path"].Count==0;
    }
    static Dictionary<string,object> Domains(string text){
      var fields=RouteCompiler.Parse(text);var parts=new List<Dictionary<string,object>>();
      foreach(string key in new[]{"domain","domain_suffix"})if(fields[key].Count>0)parts.Add(new Dictionary<string,object>{{key,fields[key].ToArray()}});
      return parts.Count==0?null:parts.Count==1?parts[0]:new Dictionary<string,object>{{"type","logical"},{"mode","or"},{"rules",parts.ToArray()}};
    }
    public static List<object> Add(ClientState state,List<object> servers,string proxy){
      var tags=new Dictionary<string,string>();var rules=new List<object>();
      foreach(var entry in state.ExitDns??new List<ExitDnsSettings>()){
        if(entry.Target!="direct"&&entry.Target!="proxy"&&entry.Target!="tgws"&&entry.Target!="byetube"&&!(WarpOutputs.IsTarget(entry.Target)&&WarpOutputs.Profiles(state).Any(n=>WarpOutputs.Tag(n)==entry.Target))&&!(entry.Target!=null&&entry.Target.StartsWith("zapret:")&&(state.ZapretProfiles??new List<ZapretProfile>()).Any(p=>"zapret:"+p.Id==entry.Target)))throw new FormatException("Неизвестный выход в настройках DNS.");
        if(!Active(state,entry.Target))continue;
        if(tags.ContainsKey(entry.Target))throw new FormatException("Повторяющаяся настройка DNS выхода.");
        var dns=DnsOptions.Build(new ClientState{DnsProvider=entry.Provider,DnsProtocol=entry.Protocol,DnsCustom=entry.Custom});
        string tag="dns-exit-"+entry.Target;dns["tag"]=tag;
        if(entry.Target=="proxy"&&proxy!="block"&&Convert.ToString(dns["type"])!="local")dns["detour"]=proxy;
        if(WarpOutputs.IsTarget(entry.Target)&&Convert.ToString(dns["type"])!="local")dns["detour"]=state.WarpEnabled?entry.Target:"block";
        if(entry.Target=="byetube"&&Convert.ToString(dns["type"])!="local")dns["detour"]=state.ByeTubeEnabled?"byetube":"block";
        servers.Add(dns);tags.Add(entry.Target,tag);
      }
      if(tags.Count==0)return rules;
      bool exclusionsAdded=false;
      foreach(var list in state.Lists.Where(l=>l.Enabled)){
        if(!Active(state,list.Target))continue;
        if(list.Target=="proxy"&&!exclusionsAdded){
          exclusionsAdded=true;string directTag;directTag=tags.TryGetValue("direct",out directTag)?directTag:"chosen";
          var vpn=state.Lists.Where(l=>l.Enabled&&l.Target=="proxy").ToArray();
          if(vpn.Any(l=>l.ExcludeRussia))rules.Add(new Dictionary<string,object>{{"rule_set",new[]{VpnPresetRules.RussiaTags[1],VpnPresetRules.RussiaTags[2]}},{"action","route"},{"server",directTag}});
          foreach(var excluded in vpn.Where(l=>!String.IsNullOrWhiteSpace(l.ExcludedText))){var no=Domains(excluded.ExcludedText);if(no!=null){no["action"]="route";no["server"]=directTag;rules.Add(no);}}
        }
        if(list.Target=="block")continue;
        // Windows often emits app DNS through its shared DNS service. Never
        // broaden app-constrained routing into a global domain DNS rule.
        if(list.MatchMode=="apps"||list.MatchMode=="in-apps"||list.MatchMode=="except-apps")continue;
        var rule=Domains(list.Text);
        if(AllInternet(list)){rules.Add(new Dictionary<string,object>{{"action","route"},{"server",DnsTag(tags,list)}});break;}
        if(rule==null)continue;
        if(list.InvertAddresses)rule["invert"]=true;
        rule["action"]="route";rule["server"]=DnsTag(tags,list);rules.Add(rule);
      }
      return rules;
    }
  }
  public sealed partial class MainForm {
    string exitDnsSelection="direct";
    void ExitDnsBlue(){
      var box=Box();box.Dock=DockStyle.Fill;content.Controls.Add(box);
      var info=L("Отдельный DNS для доменных списков в TUN. Приоритет — как в маршрутизации.\nДля IP DNS не требуется. Правила только по приложениям и встроенный DoH браузера сюда не относятся.",11,false,Muted);info.AutoSize=false;info.SetBounds(22,18,box.Width-44,75);box.Controls.Add(info);
      var exits=new GlassPicker{Name="exitDnsTarget",Location=new Point(22,104),Width=box.Width-44};exits.SetItems(new[]{"Напрямую / Zapret","VPN / WARP","Telegram WS"});box.Controls.Add(exits);var targets=new[]{"direct","proxy","tgws"};
      var shared=new GlassToggle{Name="exitDnsShared",Text="Использовать общий DNS",Location=new Point(22,165),Width=box.Width-44};box.Controls.Add(shared);
      var provider=new GlassPicker{Name="exitDnsProvider",Location=new Point(22,226),Width=300};provider.SetItems(DnsOptions.Providers);box.Controls.Add(provider);
      var protocol=new GlassPicker{Name="exitDnsProtocol",Location=new Point(346,226),Width=200};box.Controls.Add(protocol);
      var custom=T();custom.Name="exitDnsCustom";custom.SetBounds(22,291,box.Width-44,38);box.Controls.Add(custom);
      var hint=L("DNS для VPN запрашивается через VPN; для прямого выхода и Telegram — напрямую.\nИзменения применятся при следующем подключении. Общий DNS может оставаться системным;\nпри отдельном DNS его доменные запросы будут перехватываться в TUN.",10,false,Muted);hint.AutoSize=false;hint.SetBounds(22,354,box.Width-44,70);box.Controls.Add(hint);
      Action enabled=()=>{provider.Enabled=protocol.Enabled=!shared.Checked;custom.Enabled=!shared.Checked&&provider.SelectedItem=="Свой";};
      provider.SelectedIndexChanged+=(s,e)=>{protocol.SetItems(DnsOptions.Protocols(provider.SelectedItem));protocol.SelectedIndex=0;enabled();};shared.CheckedChanged+=(s,e)=>enabled();
      exits.SelectedIndexChanged+=(s,e)=>{var existing=(state.ExitDns??new List<ExitDnsSettings>()).FirstOrDefault(d=>d.Target==targets[exits.SelectedIndex]);shared.Checked=existing==null;provider.SelectedItem=existing==null?"Cloudflare":existing.Provider;protocol.SetItems(DnsOptions.Protocols(provider.SelectedItem));protocol.SelectedItem=existing==null?"DoH":existing.Protocol;custom.Text=existing==null?"":existing.Custom??"";enabled();};exits.SelectedIndex=Math.Max(0,Array.IndexOf(targets,exitDnsSelection));
      var save=B("Сохранить DNS выхода",async(s,e)=>{try{if(!await OfferStopTun())return;if(!shared.Checked){DnsOptions.Build(new ClientState{DnsProvider=provider.SelectedItem,DnsProtocol=protocol.SelectedItem,DnsCustom=custom.Text.Trim()});}var settings=state.ExitDns??new List<ExitDnsSettings>();settings.RemoveAll(d=>d.Target==targets[exits.SelectedIndex]);if(!shared.Checked)settings.Add(new ExitDnsSettings{Target=targets[exits.SelectedIndex],Provider=provider.SelectedItem,Protocol=protocol.SelectedItem,Custom=custom.Text.Trim()});state.ExitDns=settings;store.Save(state);Toast("DNS выхода сохранён. Применится при следующем подключении.");}catch(Exception ex){GlassNotice.Show(this,ex.Message,"DNS выхода",MessageBoxButtons.OK,MessageBoxIcon.Warning);}},true);save.Name="exitDnsSave";save.SetBounds(22,box.Height-62,260,40);save.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(save);
    }
  }
}
