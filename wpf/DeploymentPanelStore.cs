using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class DeploymentPanel {
  public string Id{get;set;}public string Name{get;set;}public string RecipeId{get;set;}public string Host{get;set;}public int SshPort{get;set;}public string SshUser{get;set;}public DeployResult Result{get;set;}
  public override string ToString(){var recipe=DeployRecipe.All.FirstOrDefault(r=>r.Id==RecipeId);return (String.IsNullOrWhiteSpace(Name)?(recipe==null?"Укажите тип панели":recipe.Name):Name)+" · "+Host+" · подключений: "+(Result.connections??new string[0]).Length;}
 }
 internal sealed class DeploymentPanelRegistry {
  public int Version{get;set;}public string SelectedId{get;set;}public List<DeploymentPanel> Panels{get;set;}
  public DeploymentPanelRegistry(){Version=1;Panels=new List<DeploymentPanel>();}
  public DeploymentPanel Selected{get{return Panels.FirstOrDefault(p=>p.Id==SelectedId);}}
 }
 internal static class DeploymentPanelStore {
  static JavaScriptSerializer Json(){return new JavaScriptSerializer{MaxJsonLength=8388608};}
  internal static void Validate(DeploymentPanel panel){
   Uri url;if(panel==null||String.IsNullOrEmpty(panel.Id)||panel.Id.Length>64||panel.Result==null||!Uri.TryCreate(panel.Result.panel_url,UriKind.Absolute,out url)||url.Scheme!="https"||url.UserInfo.Length!=0||url.Port<1||url.Port>65535||!System.Text.RegularExpressions.Regex.IsMatch(panel.Result.certificate_sha256??"",@"^[a-fA-F0-9]{64}$"))throw new FormatException("Некорректная сохранённая панель.");
   if((panel.Name??"").Length>80||(panel.Name??"").Any(Char.IsControl)||Uri.CheckHostName(panel.Host??"")==UriHostNameType.Unknown||panel.SshPort<1||panel.SshPort>65535||!System.Text.RegularExpressions.Regex.IsMatch(panel.SshUser??"",@"^[a-z_][a-zA-Z0-9_-]{0,31}$")||(!DeployRecipe.All.Any(r=>r.Id==panel.RecipeId)&&panel.RecipeId!="unknown")||String.IsNullOrEmpty(panel.Result.username)||String.IsNullOrEmpty(panel.Result.password))throw new FormatException("Нет данных сервера сохранённой панели.");
   var links=panel.Result.connections??new string[0];if(links.Length>256)throw new FormatException("Слишком много подключений панели.");foreach(string link in links)DeploymentConnection.Read(link);
  }
  internal static DeploymentPanel From(DeployRecipe recipe,DeployTarget target,DeployResult result){var panel=new DeploymentPanel{Id=Guid.NewGuid().ToString("N"),RecipeId=recipe.Id,Host=target.Host,SshPort=target.Port,SshUser=target.User,Result=result};Validate(panel);return panel;}
  internal static DeploymentPanel Migrate(DeployResult result){
   Uri url=new Uri(result.panel_url);string recipe=result.recipe_id;if(!DeployRecipe.All.Any(r=>r.Id==recipe)){var kinds=(result.connections??new string[0]).Select(l=>Links.Read(l).Single().Protocol).ToArray();recipe=kinds.Contains("openflux")?"openflux":kinds.Any(k=>new[]{"csqtt","wdtt","qwdtt"}.Contains(k))?"swg":kinds.Contains("vless")?"3xui":"unknown";}
   var panel=new DeploymentPanel{Id=Guid.NewGuid().ToString("N"),RecipeId=recipe,Host=String.IsNullOrEmpty(result.server_host)?url.Host:result.server_host,SshPort=result.ssh_port>0?result.ssh_port:22,SshUser=String.IsNullOrEmpty(result.ssh_user)?"root":result.ssh_user,Result=result};Validate(panel);return panel;
  }
  internal static DeploymentPanelRegistry Load(string root){
   string folder=Path.Combine(root,"deployment"),path=Path.Combine(folder,"panels.dat");DeploymentPanelRegistry registry;
   if(File.Exists(path)){if(new FileInfo(path).Length>12582912)throw new FormatException("Реестр панелей слишком большой.");registry=Json().Deserialize<DeploymentPanelRegistry>(SecretStorage.Read(path));if(registry==null||registry.Version!=1||registry.Panels==null||registry.Panels.Count>64||registry.Panels.Select(p=>p.Id).Distinct().Count()!=registry.Panels.Count)throw new FormatException("Некорректный реестр панелей.");foreach(var panel in registry.Panels)Validate(panel);return registry;}
   registry=new DeploymentPanelRegistry();string legacy=Path.Combine(folder,"last-result.dat");if(File.Exists(legacy)){if(new FileInfo(legacy).Length>1048576)throw new FormatException("Старый результат слишком большой.");var result=Json().Deserialize<DeployResult>(SecretStorage.Read(legacy));var panel=Migrate(result);registry.Panels.Add(panel);registry.SelectedId=panel.Id;Save(root,registry);}return registry;
  }
  internal static void Save(string root,DeploymentPanelRegistry registry){if(registry.Panels.Count>64)throw new FormatException("Не больше 64 сохранённых панелей.");foreach(var panel in registry.Panels)Validate(panel);string folder=Path.Combine(root,"deployment");Directory.CreateDirectory(folder);SecretStorage.Write(Path.Combine(folder,"panels.dat"),Json().Serialize(registry));}
  internal static DeploymentPanel Remember(DeploymentPanelRegistry registry,DeployRecipe recipe,DeployTarget target,DeployResult fresh){
   var panel=registry.Panels.FirstOrDefault(p=>p.RecipeId==recipe.Id&&String.Equals(p.Result.panel_url.TrimEnd('/'),fresh.panel_url.TrimEnd('/'),StringComparison.OrdinalIgnoreCase));
   if(panel==null){panel=From(recipe,target,fresh);registry.Panels.Add(panel);}else{
    var links=(panel.Result.connections??new string[0]).Concat(fresh.connections??new string[0]).Distinct().ToArray();
    // An aggregate can contain both CSQTT generations; never reseal older links
    // using the version of the most recently created connection.
    fresh.connections=links;fresh.csqtt_line=null;var candidate=new DeploymentPanel{Id=panel.Id,Name=panel.Name,RecipeId=panel.RecipeId,Host=target.Host,SshPort=target.Port,SshUser=target.User,Result=fresh};Validate(candidate);panel.Result=fresh;panel.Host=target.Host;panel.SshPort=target.Port;panel.SshUser=target.User;
   }Validate(panel);registry.SelectedId=panel.Id;return panel;
  }
 }
}
