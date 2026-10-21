using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Collections;
using System.Web.Script.Serialization;
using SplifyWin;
class DeploymentPanelChecks {
 static readonly BindingFlags F=BindingFlags.Public|BindingFlags.NonPublic|BindingFlags.Static|BindingFlags.Instance;
 static readonly Assembly A=typeof(NetworkCore).Assembly;static int count;
 static object New(string type){return Activator.CreateInstance(A.GetType("Mcrf.Wpf."+type),true);}
 static object Get(object o,string n){var p=o.GetType().GetProperty(n,F);return p!=null?p.GetValue(o,null):o.GetType().GetField(n,F).GetValue(o);}
 static void Set(object o,string n,object v){var p=o.GetType().GetProperty(n,F);if(p!=null)p.SetValue(o,v,null);else o.GetType().GetField(n,F).SetValue(o,v);}
 static object Call(string type,string method,params object[] args){return A.GetType("Mcrf.Wpf."+type).GetMethod(method,F).Invoke(null,args);}
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);count++;Console.WriteLine("PASS "+label);}
 static string Link(string host,int port,string name){return "csqtt://connect?v=2&host="+host+"&peer="+port+"&password=fixture&hashes="+new string('A',43)+"&name="+Uri.EscapeDataString(name);}
 static object Result(string host,string link){var r=New("DeployResult");Set(r,"panel_url","https://"+host+":28443/");Set(r,"username","fixture");Set(r,"password","fixture-panel-secret");Set(r,"certificate_sha256",new string('a',64));Set(r,"ready",true);Set(r,"connections",new[]{link});Set(r,"csqtt_line","2.1");Set(r,"provision_complete",true);return r;}
 static object Target(string host){var t=New("DeployTarget");Set(t,"Host",host);Set(t,"Port",22);Set(t,"User","root");Set(t,"Password","SSH-NOT-TO-BE-PERSISTED");Set(t,"PanelPort",28443);Set(t,"PanelUser","fixture");var o=Get(t,"Provision");Set(o,"create",true);Set(o,"relay_kind","csqtt");Set(o,"csqtt_line","2.1");Set(o,"vk_call","https://vk.ru/call/join/"+new string('A',43));return t;}
 static int Main(string[] args){try{
  A.GetType("Mcrf.Wpf.ManagedDependencies").GetMethod("Ensure",F).Invoke(null,null);
  string root=args[0];if(Directory.Exists(root))throw new Exception("Fresh fixture required");Directory.CreateDirectory(Path.Combine(root,"deployment"));
  string first=Link("192.0.2.1",46000,"Первое");var original=Result("192.0.2.1",first);string legacy=Path.Combine(root,"deployment/last-result.dat");SecretStorage.Write(legacy,new JavaScriptSerializer().Serialize(original));byte[] legacyBefore=File.ReadAllBytes(legacy);
  var registry=Call("DeploymentPanelStore","Load",root);var list=(IList)Get(registry,"Panels");var panel=Get(registry,"Selected");
  Check(list.Count==1&&panel!=null,"Legacy result migrates to a selected saved panel");
  Check((string)Get(panel,"RecipeId")=="swg"&&(string)Get(panel,"Host")=="192.0.2.1","Migration retains correct panel kind and server");
  Check(File.ReadAllBytes(legacy).SequenceEqual(legacyBefore),"Migration preserves original encrypted result");
  string path=Path.Combine(root,"deployment/panels.dat");Check(File.ReadAllText(path).StartsWith("MCRF-SECRET-1\n")&&!File.ReadAllText(path).Contains("fixture-panel-secret"),"Panel registry is DPAPI encrypted");
  var recipe=((Array)A.GetType("Mcrf.Wpf.DeployRecipe").GetField("All",F).GetValue(null)).Cast<object>().Single(r=>(string)Get(r,"Id")=="swg");
  var target=Target("192.0.2.1");string repeated=Link("192.0.2.1",46001,"Второе");Call("DeploymentPanelStore","Remember",registry,recipe,target,Result("192.0.2.1",repeated));
  Check(((string[])Get(Get(panel,"Result"),"connections")).Length==2,"Same protocol can be added repeatedly to one panel");
  Check(((string[])Get(Get(panel,"Result"),"connections"))[0]==first,"Adding a connection preserves the original link byte for byte");
  string modern=Csqtt25Link.Seal(Link("192.0.2.1",46002,"Новое2.5"));var fresh=Result("192.0.2.1",modern);Set(fresh,"csqtt_line","2.5");Call("DeploymentPanelStore","Remember",registry,recipe,target,fresh);
  var mixed=(string[])Get(Get(panel,"Result"),"connections");Check(mixed.Length==3&&!TurnProfile.Parse(mixed[0]).Csqtt25&&TurnProfile.Parse(mixed[2]).Csqtt25,"Mixed2.1/2.5 registry never reseals older links");
  Call("DeploymentPanelStore","Remember",registry,recipe,target,Result("192.0.2.1",first));Check(((string[])Get(Get(panel,"Result"),"connections")).Length==3,"Only exact duplicate links are deduplicated, not protocol kinds");
  var secondTarget=Target("192.0.2.2");Call("DeploymentPanelStore","Remember",registry,recipe,secondTarget,Result("192.0.2.2",Link("192.0.2.2",46000,"Другой сервер")));
  Check(list.Count==2,"Multiple servers with the same panel/protocol remain separate");Set(panel,"Name","Основной сервер");Set(registry,"SelectedId",Get(panel,"Id"));Call("DeploymentPanelStore","Save",root,registry);
  registry=Call("DeploymentPanelStore","Load",root);list=(IList)Get(registry,"Panels");panel=Get(registry,"Selected");
  Check(list.Count==2&&(string)Get(panel,"Name")=="Основной сервер","Panel names and selected server survive a fresh registry load");
  Check(((string[])Get(Get(panel,"Result"),"connections")).Length==3,"All connections survive restart, including mixed generations");
  Check(!SecretStorage.Read(path).Contains("SSH-NOT-TO-BE-PERSISTED"),"SSH password is never persisted");
  string operation=(string)Call("Deployment","BuildManagementScript",panel,target);
  Check(operation.Contains("configure_initial")&&!operation.Contains("installer.sh")&&!operation.Contains("systemctl restart"),"Existing-panel operation uses APIs without installer/restart");
  Set(target,"Host","192.0.2.99");bool refused=false;try{Call("Deployment","BuildManagementScript",panel,target);}catch(TargetInvocationException e){refused=e.InnerException is FormatException;}Check(refused,"A saved panel cannot send its credentials to another SSH server");
  string unknown=Path.Combine(root,"unknown");Directory.CreateDirectory(Path.Combine(unknown,"deployment"));var noProfiles=Result("192.0.2.3",first);Set(noProfiles,"connections",new string[0]);SecretStorage.Write(Path.Combine(unknown,"deployment/last-result.dat"),new JavaScriptSerializer().Serialize(noProfiles));var unresolved=Call("DeploymentPanelStore","Load",unknown);Check((string)Get(Get(unresolved,"Selected"),"RecipeId")=="unknown","Legacy empty result does not silently guess a panel type");
  Console.WriteLine("DEPLOYMENT_PANEL_CHECKS_PASS="+count);return 0;
 }catch(Exception error){Console.WriteLine(error.GetType().Name+": "+error.Message);return 1;}}
}
