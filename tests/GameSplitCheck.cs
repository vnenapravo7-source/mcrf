using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Reflection;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using SplifyWin;
class GameSplitCheck {
 static BindingFlags flags=BindingFlags.Instance|BindingFlags.NonPublic;
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);}
 static void Pump(){for(int i=0;i<15;i++)Application.DoEvents();}
 static void Dry(string exe,string args){using(var p=System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe,args+" --dry-run"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=Path.GetDirectoryName(exe)})){var output=p.StandardOutput.ReadToEndAsync();var error=p.StandardError.ReadToEndAsync();if(!p.WaitForExit(10000)){p.Kill();throw new Exception("validation timeout");}System.Threading.Tasks.Task.WaitAll(output,error);Check(p.ExitCode==0,"engine dry-run "+error.Result);}}
 [STAThread]static int Main(string[] args){try{
  var root=Path.GetFullPath(args[0]);Environment.SetEnvironmentVariable("MCRF_DATA_DIR",root);Environment.SetEnvironmentVariable("SPLIFY_WIN_DATA_DIR",root);Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_SETUP","1");Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_UPDATES","1");
  var json=new JavaScriptSerializer();var legacy=json.Deserialize<RouteList>("{\"GameFilter\":true}");Check(legacy.GameTcpEnabled&&legacy.GameUdpEnabled,"old checkbox preserves both protocols");legacy.GameFilterTcp=false;Check(!legacy.GameTcpEnabled&&legacy.GameUdpEnabled,"explicit TCP off overrides legacy true");Check(!new RouteList().GameTcpEnabled&&!new RouteList().GameUdpEnabled,"new rules default both off");
  var core=new NetworkCore(new StateStore());using(var runtime=new ZapretRuntime(core)){
   runtime.Prepare();var catalog=ZapretCatalog.Load(runtime.DirectoryPath);var strategy=catalog.First(s=>s.Id=="flowseal:general (ALT12).bat");var profile=new ZapretProfile{Name="Wardogs ALT12"};profile.Settings.Strategy=strategy.Id;
   var route=new RouteList{Name="Wardogs",Target="zapret",ZapretProfileId=profile.Id,MatchMode="in-apps",Text="54.115.0.0/16\napp:WardogsClient-Win64-Shipping.exe"};var state=new ClientState{SetupSeen=true,ZapretRoutingMigrated=true,Lists=new List<RouteList>{route},ZapretProfiles=new List<ZapretProfile>{profile}};
   for(int mode=0;mode<4;mode++){
    route.GameFilterTcp=(mode&1)!=0;route.GameFilterUdp=(mode&2)!=0;
    var command=runtime.BuildProfileArguments(ZapretRoutes.Active(state),catalog,Path.Combine(root,"mode-"+mode));var capture=command.Substring(0,command.IndexOf(" --debug="));Check(capture.Contains("--wf-tcp=80,443,1024-65535")==route.GameTcpEnabled,"TCP capture mode "+mode);Check(capture.Contains("--wf-udp=443,1024-65535")==route.GameUdpEnabled,"UDP capture mode "+mode);
    Check(command.Contains("--filter-tcp=1024-65535")==route.GameTcpEnabled,"TCP game profile mode "+mode);Check(command.Contains("--filter-udp=1024-65535")==route.GameUdpEnabled,"UDP game profile mode "+mode);
    foreach(var part in command.Split(new[]{" --new "},StringSplitOptions.None).Where(p=>p.Contains("--filter-tcp=1024")||p.Contains("--filter-udp=1024")))Check(part.Contains("--ipset=")&&part.Contains("--mcrf-apps="),"IP/process restriction preserved");Dry(runtime.AppExecutable(),command);
   }
   route.GameFilterTcp=false;route.GameFilterUdp=true;var copied=json.Deserialize<RouteList>(json.Serialize(route));Check(!copied.GameTcpEnabled&&copied.GameUdpEnabled,"UDP-only survives JSON save");new StateStore().Save(state);
  }
  using(var form=new MainForm(false,false)){
   form.Show();typeof(MainForm).GetMethod("ToggleDesign",flags).Invoke(form,null);typeof(MainForm).GetMethod("ShowPage",flags).Invoke(form,new object[]{"routes"});Pump();var grid=(DataGridView)typeof(MainForm).GetField("routesGrid",flags).GetValue(form);Check(grid.Columns["gameFilterTcp"] is DataGridViewCheckBoxColumn&&grid.Columns["gameFilterUdp"] is DataGridViewCheckBoxColumn,"two game checkboxes visible");Check(!(bool)grid.Rows[0].Cells[5].Value&&(bool)grid.Rows[0].Cells[6].Value,"UDP-only renders independently");
   var click=typeof(DataGridView).GetMethod("OnCellContentClick",flags);click.Invoke(grid,new object[]{new DataGridViewCellEventArgs(5,0)});Pump();var saved=new StateStore().Load().Lists[0];Check(saved.GameTcpEnabled&&saved.GameUdpEnabled,"TCP on keeps UDP on");click.Invoke(grid,new object[]{new DataGridViewCellEventArgs(6,0)});Pump();saved=new StateStore().Load().Lists[0];Check(saved.GameTcpEnabled&&!saved.GameUdpEnabled,"UDP off keeps TCP on");
   click.Invoke(grid,new object[]{new DataGridViewCellEventArgs(5,0)});Pump();click.Invoke(grid,new object[]{new DataGridViewCellEventArgs(6,0)});Pump();saved=new StateStore().Load().Lists[0];Check(!saved.GameTcpEnabled&&saved.GameUdpEnabled,"UI restores UDP-only");
   var panel=(Control)typeof(MainForm).GetField("popupOverlay",flags).GetValue(form);using(var bitmap=new System.Drawing.Bitmap(panel.Width,panel.Height)){panel.DrawToBitmap(bitmap,new System.Drawing.Rectangle(0,0,panel.Width,panel.Height));bitmap.Save(Path.Combine(root,"split-checkboxes.png"));}Console.WriteLine("IMAGE "+Path.Combine(root,"split-checkboxes.png"));
   typeof(MainForm).GetMethod("ShowPage",flags).Invoke(form,new object[]{"games"});Pump();var content=(Control)typeof(MainForm).GetField("content",flags).GetValue(form);var target=content.Controls.Find("gameTarget",true).First();var count=target.GetType().GetProperty("SelectedIndex");count.SetValue(target,2,null);Check((int)count.GetValue(target,null)==2,"recording offers Zapret profile without VPN");Check(!core.Running,"checks did not start VPN");form.Close();
  }
  using(var observer=new GameFlowObserver())Check(!observer.Running&&observer.Drain().Length==0,"observer starts idle without driver activation");
  return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
