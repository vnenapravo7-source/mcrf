using System;
using System.IO;
using System.Reflection;
using System.Windows.Forms;
using SplifyWin;
class TrayRestoreCheck {
 static BindingFlags flags=BindingFlags.NonPublic|BindingFlags.Instance;
 static void Pump(){for(int i=0;i<6;i++)Application.DoEvents();}
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);}
 [STAThread]static int Main(string[] args){try{
  var root=Path.GetFullPath(args[0]);Environment.SetEnvironmentVariable("MCRF_DATA_DIR",root);Environment.SetEnvironmentVariable("SPLIFY_WIN_DATA_DIR",root);Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_SETUP","1");Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_UPDATES","1");new StateStore().Save(new ClientState{SimpleDesign=true,SetupSeen=true});
  using(var form=new MainForm(false,false)){form.Show();Pump();var restore=typeof(MainForm).GetMethod("RestoreWindow",flags);
   for(int i=0;i<3;i++){form.WindowState=FormWindowState.Minimized;Pump();Check(!form.Visible,"minimize hides window "+i);restore.Invoke(form,null);Pump();Check(form.Visible&&form.WindowState==FormWindowState.Normal,"restore normal window "+i);}
   form.WindowState=FormWindowState.Maximized;Pump();form.WindowState=FormWindowState.Minimized;Pump();restore.Invoke(form,null);Pump();Check(form.Visible&&form.WindowState==FormWindowState.Maximized,"preserves maximized state");
   form.WindowState=FormWindowState.Normal;Pump();form.WindowState=FormWindowState.Minimized;Pump();var tray=(NotifyIcon)typeof(MainForm).GetField("tray",flags).GetValue(form);typeof(NotifyIcon).GetMethod("OnMouseClick",flags).Invoke(tray,new object[]{new MouseEventArgs(MouseButtons.Left,1,0,0,0)});Pump();Check(form.Visible&&form.WindowState==FormWindowState.Normal,"single left tray click restores window");form.Close();
  }return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
