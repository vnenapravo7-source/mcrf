using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Diagnostics;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using SplifyWin;
class HotkeyCheck {
 const BindingFlags Flags=BindingFlags.Instance|BindingFlags.NonPublic;
 [DllImport("user32.dll")]static extern IntPtr SendMessage(IntPtr h,int m,IntPtr w,IntPtr l);
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);Console.WriteLine("PASS "+label);}
 static T Field<T>(object o,string n){return (T)o.GetType().GetField(n,Flags).GetValue(o);}
 static void Pump(){for(int i=0;i<40;i++)Application.DoEvents();}
 static IEnumerable<Control> All(Control root){return root.Controls.Cast<Control>().SelectMany(c=>new[]{c}.Concat(All(c)));}
 [STAThread]static int Main(string[] args){try{
  if(args[0]=="--child"){using(var gate=new SingleInstance(args[1]))return gate.Acquire()?2:0;}
  string root=Path.GetFullPath(args[0]);Directory.CreateDirectory(root);Environment.SetEnvironmentVariable("MCRF_DATA_DIR",root);Environment.SetEnvironmentVariable("SPLIFY_WIN_DATA_DIR",root);Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_SETUP","1");Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_UPDATES","1");
  foreach(var combo in new[]{"Ctrl+P","Shift+P","Alt+P","Ctrl+Shift+P","Ctrl+Alt+P","Shift+Alt+P","Ctrl+Shift+Alt+P"})Check(ProfileHotkey.Parse(combo).Key==Keys.P,"parse "+combo);
  foreach(var bad in new[]{"P","Ctrl","Ctrl+Ctrl+P","Ctrl+F12","Ctrl+P+Q"}){bool rejected=false;try{ProfileHotkey.Parse(bad);}catch(FormatException){rejected=true;}Check(rejected,"reject "+bad);}
  using(var a=new ProfileHotkeyWindow())using(var b=new ProfileHotkeyWindow()){
   Check(a.Set("a","Ctrl+Shift+Alt+F23")==null,"register global Windows combination");Check(b.Set("b","Ctrl+Shift+Alt+F23")!=null,"detect external conflict");Check(b.Set("b","Ctrl+Shift+Alt+F24")==null,"assign independent combination");Check(a.Set("a","Ctrl+Shift+Alt+F24")!=null,"reject replacement without removing original");int called=0;a.Pressed+=id=>called++;SendMessage(a.Handle,0x312,new IntPtr(100),IntPtr.Zero);Check(called==1,"WM_HOTKEY dispatch");a.Set("a",null);Check(b.Set("b","Ctrl+Shift+Alt+F23")==null,"cleared binding releases Windows registration");
  }
  string suffix=".test."+Guid.NewGuid();using(var gate=new SingleInstance(suffix)){Check(gate.Acquire(),"first instance acquires mutex");using(var child=Process.Start(new ProcessStartInfo(Application.ExecutablePath,"--child "+suffix){UseShellExecute=false,CreateNoWindow=true})){Check(child.WaitForExit(10000)&&child.ExitCode==0,"second process cannot acquire mutex");}}
  using(var gate=new SingleInstance(suffix))Check(gate.Acquire(),"mutex releases on exit");
  var noServers=new ClientState();foreach(var row in new[]{new UpdateItem{Id="singbox",Kind="link"},new UpdateItem{Id="engine:csqtt",Kind="optional"},new UpdateItem{Id="optional",Kind="catalog"}}){Check(!MainForm.ShouldNotifyUpdate(row,noServers),"no unused engine notification "+row.Id);noServers.Servers.Add(new ServerNode());Check(MainForm.ShouldNotifyUpdate(row,noServers),"notification with configured server "+row.Id);noServers.Servers.Clear();}Check(MainForm.ShouldNotifyUpdate(new UpdateItem{Id="app",Kind="app"},noServers),"application updates still notify");
  var profile=new ZapretProfile{Name="Hotkey test",Enabled=false};var store=new StateStore();store.Save(new ClientState{SetupSeen=true,ZapretRoutingMigrated=true,ZapretProfiles=new List<ZapretProfile>{profile}});
  using(var form=new MainForm(false,false)){
   form.Show();Pump();var save=typeof(MainForm).GetMethod("SaveProfileHotkey",Flags);save.Invoke(form,new object[]{profile.Id,"Ctrl+Shift+Alt+F23"});Check(store.Load().ZapretProfiles[0].Hotkey=="Ctrl + Shift + Alt + F23","profile combination persisted");bool conflict=false;try{save.Invoke(form,new object[]{"@warp","Ctrl+Shift+Alt+F23"});}catch(TargetInvocationException){conflict=true;}Check(conflict,"cross-action duplicate rejected");save.Invoke(form,new object[]{"@warp","Ctrl+Shift+Alt+F24"});Check(store.Load().GlobalHotkeys["@warp"]=="Ctrl + Shift + Alt + F24","settings combination persisted");
   Field<ClientState>(form,"state").ZapretProfiles[0].Enabled=true;form.Hide();var action=(System.Threading.Tasks.Task)typeof(MainForm).GetMethod("RunGlobalHotkey",Flags).Invoke(form,new object[]{profile.Id});for(int i=0;i<100&&!action.IsCompleted;i++){Pump();System.Threading.Thread.Sleep(10);}Check(action.IsCompleted&&!action.IsFaulted&&!store.Load().ZapretProfiles[0].Enabled,"global profile action toggles and autosaves while hidden");form.Show();
   typeof(MainForm).GetMethod("ShowPage",Flags).Invoke(form,new object[]{"zapret"});Pump();Check(All(form).Any(c=>c.Name=="zapretHotkey_"+profile.Id),"profile hotkey control exists");using(var bitmap=new System.Drawing.Bitmap(Field<Control>(form,"popupOverlay").Width,Field<Control>(form,"popupOverlay").Height)){Field<Control>(form,"popupOverlay").DrawToBitmap(bitmap,new System.Drawing.Rectangle(0,0,bitmap.Width,bitmap.Height));bitmap.Save(Path.Combine(root,"hotkey-table.png"));}
   typeof(MainForm).GetMethod("ShowPage",Flags).Invoke(form,new object[]{"settings"});Pump();Check(All(form).Any(c=>c.Name=="settingsHotkeys"),"settings hotkeys entry exists");typeof(MainForm).GetMethod("ShowGlobalHotkeys",Flags).Invoke(form,null);Pump();Check(All(form).Count(c=>c.Name.StartsWith("globalHotkey_"))==3,"all three global actions configurable");
   form.Hide();SendMessage(form.Handle,(int)SingleInstance.ActivationMessage,IntPtr.Zero,IntPtr.Zero);Pump();Check(form.Visible&&form.WindowState!=FormWindowState.Minimized,"second-launch notification restores hidden window");save.Invoke(form,new object[]{profile.Id,null});save.Invoke(form,new object[]{"@warp",null});Check(store.Load().ZapretProfiles[0].Hotkey==null&&!store.Load().GlobalHotkeys.ContainsKey("@warp"),"optional keys can be removed");Check(!Field<NetworkCore>(form,"core").Running&&!Field<ZapretRuntime>(form,"zapret").Running,"tests do not start networking engines");form.Close();
  }
  return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
