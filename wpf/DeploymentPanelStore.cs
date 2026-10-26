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
   if(result==null)throw new FormatException("Пустой сохранённый результат установки.");
   Uri url=new Uri(result.panel_url);string recipe=result.recipe_id;if(!DeployRecipe.All.Any(r=>r.Id==recipe)){var kinds=(result.connections??new string[0]).Select(l=>Links.Read(l).Single().Protocol).ToArray();recipe=kinds.Contains("openflux")?"openflux":kinds.Any(k=>new[]{"csqtt","wdtt","qwdtt"}.Contains(k))?"swg":kinds.Contains("vless")?"3xui":"unknown";}
   var panel=new DeploymentPanel{Id=Guid.NewGuid().ToString("N"),RecipeId=recipe,Host=String.IsNullOrEmpty(result.server_host)?url.Host:result.server_host,SshPort=result.ssh_port>0?result.ssh_port:22,SshUser=String.IsNullOrEmpty(result.ssh_user)?"root":result.ssh_user,Result=result};Validate(panel);return panel;
  }
  internal static DeploymentPanelRegistry Load(string root){
   ProfileRecovery.DemandHealthy(root);
   string folder=Path.Combine(root,"deployment"),path=Path.Combine(folder,"panels.dat");DeploymentPanelRegistry registry;
   if(File.Exists(path)){if(new FileInfo(path).Length>12582912)throw new FormatException("Реестр панелей слишком большой.");registry=null;SecretStorage.ReadValidated(path,text=>{registry=Json().Deserialize<DeploymentPanelRegistry>(text);ValidateRegistry(registry);});return registry;}
   registry=new DeploymentPanelRegistry();string legacy=Path.Combine(folder,"last-result.dat");if(File.Exists(legacy)){if(new FileInfo(legacy).Length>1048576)throw new FormatException("Старый результат слишком большой.");DeploymentPanel panel=null;SecretStorage.ReadValidated(legacy,text=>{panel=Migrate(Json().Deserialize<DeployResult>(text));});registry.Panels.Add(panel);registry.SelectedId=panel.Id;Save(root,registry);}return registry;
  }
  static void ValidateRegistry(DeploymentPanelRegistry registry){if(registry==null||registry.Version!=1||registry.Panels==null||registry.Panels.Count>64||registry.Panels.Any(p=>p==null)||registry.Panels.Select(p=>p.Id).Distinct().Count()!=registry.Panels.Count)throw new FormatException("Некорректный реестр панелей.");foreach(var panel in registry.Panels)Validate(panel);if(registry.SelectedId!=null&&!registry.Panels.Any(p=>p.Id==registry.SelectedId))throw new FormatException("Выбранная панель отсутствует в реестре.");}
  internal static void Save(string root,DeploymentPanelRegistry registry){ProfileRecovery.DemandHealthy(root);ValidateRegistry(registry);string folder=Path.Combine(root,"deployment");Directory.CreateDirectory(folder);SecretStorage.Write(Path.Combine(folder,"panels.dat"),Json().Serialize(registry));}
  internal static string Identity(DeploymentPanel panel){return Json().Serialize(panel);}
  internal static void Rename(string root,DeploymentPanelRegistry registry,string id,string expected,string raw){
   if(raw==null||raw.Length>80||raw.Any(Char.IsControl))throw new FormatException("Название — до 80 символов, без управляющих символов.");
   var matches=registry.Panels.Where(p=>p.Id==id).ToArray();if(matches.Length!=1)throw new InvalidOperationException("Панель удалена или её идентификатор неоднозначен. Откройте список заново.");var current=matches[0];
   if(Identity(current)!=expected)throw new InvalidOperationException("Параметры панели изменились. Откройте переименование заново.");
   string previous=current.Name;try{current.Name=raw.Trim();Save(root,registry);}catch{current.Name=previous;throw;}
  }
  internal static void AssignRecipe(string root,DeploymentPanelRegistry registry,DeploymentPanel panel,string recipe){
   if(!registry.Panels.Contains(panel)||panel.RecipeId!="unknown"||!DeployRecipe.All.Any(r=>r.Id==recipe))throw new InvalidOperationException("Панель или её тип изменились. Откройте список заново.");
   string previous=panel.RecipeId;try{panel.RecipeId=recipe;Save(root,registry);}catch{panel.RecipeId=previous;throw;}
  }
  internal static DeploymentPanel Remember(DeploymentPanelRegistry registry,DeployRecipe recipe,DeployTarget target,DeployResult fresh){
   ValidateRegistry(registry);if(recipe==null||target==null||fresh==null)throw new FormatException("Нет результата установки панели.");
   var captured=Json().Deserialize<DeployResult>(Json().Serialize(fresh));var candidate=From(recipe,target,captured);
   var matches=registry.Panels.Where(p=>p.RecipeId==recipe.Id&&SameEndpoint(p.Result.panel_url,captured.panel_url)).ToArray();if(matches.Length>1)throw new InvalidOperationException("Найдено несколько панелей с одним адресом. Уточните сохранённые панели.");var panel=matches.FirstOrDefault();
   if(panel==null){if(registry.Panels.Count>=64)throw new FormatException("Можно сохранить до 64 панелей.");panel=candidate;registry.Panels.Add(panel);}else{
    var links=(panel.Result.connections??new string[0]).Concat(captured.connections??new string[0]).Distinct().ToArray();
    // An aggregate can contain both CSQTT generations; never reseal older links
    // using the version of the most recently created connection.
    captured.connections=links;captured.csqtt_line=null;candidate.Id=panel.Id;candidate.Name=panel.Name;Validate(candidate);panel.Result=captured;panel.Host=target.Host;panel.SshPort=target.Port;panel.SshUser=target.User;
   }Validate(panel);registry.SelectedId=panel.Id;return panel;
  }
  static bool SameEndpoint(string left,string right){var a=new Uri(left);var b=new Uri(right);return String.Equals(a.GetLeftPart(UriPartial.Authority),b.GetLeftPart(UriPartial.Authority),StringComparison.OrdinalIgnoreCase)&&a.AbsolutePath==b.AbsolutePath&&a.Query==b.Query&&a.Fragment==b.Fragment;}
 }
}
