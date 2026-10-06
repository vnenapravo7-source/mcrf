using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows.Forms;
using SplifyWin;
class EngineReturnCheck {
 static BindingFlags flags=BindingFlags.Instance|BindingFlags.NonPublic;
 static IEnumerable<Control> Kids(Control root){foreach(Control c in root.Controls){yield return c;foreach(var nested in Kids(c))yield return nested;}}
 static object Call(MainForm f,string name,params object[] args){return typeof(MainForm).GetMethod(name,flags).Invoke(f,args);}
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);}
 [STAThread]static int Main(string[] args){try{
  Environment.SetEnvironmentVariable("MCRF_DATA_DIR",Path.GetFullPath(args[0]));Environment.SetEnvironmentVariable("SPLIFY_WIN_DATA_DIR",Path.GetFullPath(args[0]));Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_SETUP","1");Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_UPDATES","1");
  new StateStore().Save(new ClientState{SimpleDesign=true,SetupSeen=true});
  using(var form=new MainForm(false,false)){form.Show();Application.DoEvents();Call(form,"ShowPage","setup");
   var mode=Kids(form).OfType<GlassPicker>().First(p=>p.Name=="setupVpnChoice");mode.SelectedIndex=0;
   Kids(form).OfType<Button>().Single(b=>b.Name=="setupNext").PerformClick();
   var input=Kids(form).Single(c=>c.Name=="setupVpnLink");input.Text="openflux://v1/test-draft";
   Call(form,"OpenRequiredEngines",new object[]{new[]{"openflux"}});
   Check(!input.IsDisposed,"wizard input retained while downloading");Check(Kids(form).Any(c=>c.Name=="engineReturn"&&c.Visible),"download return button visible");
   Call(form,"ShowPage","");Check(input.Visible&&input.Text=="openflux://v1/test-draft","outside close restores exact wizard step and input");
   Call(form,"OpenSetupServers");var serverInput=Kids(form).OfType<GlassLogBox>().First(c=>!c.ReadOnly);serverInput.Text="csqtt://draft";
   Call(form,"OpenRequiredEngines",new object[]{new[]{"csqtt","wdtt"}});Call(form,"ReturnFromEngines");
   Check(serverInput.Visible&&serverInput.Text=="csqtt://draft","server entry restored without losing draft");
   Call(form,"ShowPage","");Check(input.Visible&&!input.IsDisposed,"nested servers returns to original wizard");
   var task=(Task<bool>)Call(form,"OfferEngineDownload",new object[]{new[]{new ServerNode{Protocol="openflux",Link="openflux://v1/draft"}}});
   Kids(form).OfType<Button>().Last(b=>b.Text=="Отмена"&&b.Visible).PerformClick();Application.DoEvents();Check(task.IsCompleted&&task.Result,"cancelled offer blocks import without download");
   Check(input.Visible&&input.Text=="openflux://v1/test-draft","cancel preserves wizard draft");
   foreach(var protocol in new[]{"csqtt","wdtt","openflux"}){
    string hash=Uri.EscapeDataString(Convert.ToBase64String(new byte[32]));
    string link=protocol=="openflux"?"openflux://v1/test-draft":protocol+"://connect?v="+(protocol=="csqtt"?"2":"1")+"&host=example.com&peer=56005&dtls=56002&wg=56001&local=19080&password=test&hashes="+hash;
    input.Text=link;var imported=(Task<bool>)Call(form,"ImportText",link,false);
    Kids(form).OfType<Button>().Last(b=>b.Text=="Перейти к скачиванию"&&b.Visible).PerformClick();Application.DoEvents();
    Check(imported.IsCompleted&&!imported.Result,"missing "+protocol+" offers download before import");
    Call(form,"ReturnFromEngines");Check(input.Visible&&input.Text==link,"missing "+protocol+" retains draft and origin");
   }
   form.Close();
  }return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
