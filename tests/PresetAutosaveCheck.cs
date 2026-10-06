using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;
using System.Collections.Generic;
using SplifyWin;
class PresetAutosaveCheck {
 const BindingFlags Flags=BindingFlags.Instance|BindingFlags.NonPublic;
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);}
 static void Pump(){for(int i=0;i<30;i++)Application.DoEvents();}
 static T Field<T>(object owner,string name){return (T)owner.GetType().GetField(name,Flags).GetValue(owner);}
 static IEnumerable<Control> All(Control root){return root.Controls.Cast<Control>().SelectMany(c=>new[]{c}.Concat(All(c)));}
 [STAThread] static int Main(string[] args){try{
  string root=Path.GetFullPath(args[0]);Environment.SetEnvironmentVariable("MCRF_DATA_DIR",root);Environment.SetEnvironmentVariable("SPLIFY_WIN_DATA_DIR",root);Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_SETUP","1");Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_UPDATES","1");
  var store=new StateStore();var core=new NetworkCore(store);var profile=new ZapretProfile{Name="Test",Enabled=false};profile.Settings.Strategy="flowseal:general (ALT12).bat";
  var presets=RoutePresets.Groups.ToArray();int youtube=Array.FindIndex(presets,p=>p.Name=="YouTube"),discord=Array.FindIndex(presets,p=>p.Name=="Discord");Check(youtube>=0&&discord>=0,"test presets available locally");
  using(var dialog=new RouteDialog(null,core,new[]{profile})){
   dialog.Show();Field<TextBox>(dialog,"name").Text="test";var pick=(GlassPicker)dialog.Controls.Find("routePresets",true).Single();pick.SetSelection(new[]{youtube,discord});var target=(GlassPicker)dialog.Controls.Find("routeTarget",true).Single();target.SelectedIndex=4;
   ((Button)All(dialog).Single(c=>c is Button&&c.Text=="Сохранить")).PerformClick();Pump();Check(dialog.Result!=null&&dialog.DialogResult==DialogResult.OK,"empty manual field plus multiple presets saves successfully");Check(dialog.Result.Target=="zapret"&&dialog.Result.PresetNames.Count==2&&RouteCompiler.Parse(dialog.Result.Text).Values.Sum(v=>v.Count)>0,"selected presets materialize into scoped Zapret route");
  }
  bool blocked=false;try{ZapretScope.Validate(new RouteList{Text="",MatchMode="any"});}catch(InvalidOperationException){blocked=true;}Check(blocked,"truly empty global Zapret still rejected");
  store.Save(new ClientState{SetupSeen=true,ZapretRoutingMigrated=true,Lists=new List<RouteList>(),ZapretProfiles=new List<ZapretProfile>{profile}});
  using(var form=new MainForm(false,false)){
   form.Show();var show=typeof(MainForm).GetMethod("ShowPage",Flags);show.Invoke(form,new object[]{"zapret"});Pump();var overlay=Field<Control>(form,"popupOverlay");Check(!All(overlay).Any(c=>c is Button&&(c.Text=="Пресеты сохранены"||c.Text=="Сохранить изменения")),"no redundant presets Save button");
   var pick=(GlassPicker)All(overlay).Single(c=>c.Name=="zapretOutputPresets_"+profile.Id);var available=RoutePresets.Groups.Where(p=>!p.OutsideRussia).ToArray();pick.SetSelection(new[]{Array.FindIndex(available,p=>p.Name=="YouTube"),Array.FindIndex(available,p=>p.Name=="Discord")});Pump();var saved=store.Load();Check(saved.Lists.Count==1&&saved.Lists[0].PresetNames.Count==2&&saved.Lists[0].Target=="zapret"&&saved.Lists[0].ZapretProfileId==profile.Id,"preset selection autosaves without button");
   overlay=Field<Control>(form,"popupOverlay");var strategy=(GlassPicker)All(overlay).Single(c=>c.Name=="zapretOutputStrategy_"+profile.Id);strategy.SelectedIndex=0;Pump();saved=store.Load();Check(String.IsNullOrEmpty(saved.ZapretProfiles[0].Settings.Strategy)&&!saved.ZapretProfiles[0].Enabled,"strategy autosaves and choose later disables profile");
   overlay=Field<Control>(form,"popupOverlay");pick=(GlassPicker)All(overlay).Single(c=>c.Name=="zapretOutputPresets_"+profile.Id);Check(pick.SelectedIndices.SequenceEqual(new[]{0}),"autosaved list remains selected after refresh");pick.SetSelection(new int[0]);Pump();saved=store.Load();Check(saved.Lists.Count==1&&saved.Lists[0].Target=="direct"&&!saved.ZapretProfiles[0].Enabled,"deselect autosaves without deleting list");
   var currentState=Field<ClientState>(form,"state");currentState.ZapretProfiles[0].Enabled=true;store.Save(currentState);show.Invoke(form,new object[]{"zapret"});Pump();overlay=Field<Control>(form,"popupOverlay");var toggle=(GlassToggle)All(overlay).Single(c=>c.Name=="zapretOutputToggle_"+profile.Id);toggle.Checked=false;Pump();Check(!store.Load().ZapretProfiles[0].Enabled,"toggle off autosaves without a separate button");
   overlay=Field<Control>(form,"popupOverlay");using(var bitmap=new System.Drawing.Bitmap(overlay.Width,overlay.Height)){overlay.DrawToBitmap(bitmap,new System.Drawing.Rectangle(0,0,overlay.Width,overlay.Height));bitmap.Save(Path.Combine(root,"autosave-table.png"));}Console.WriteLine("IMAGE "+Path.Combine(root,"autosave-table.png"));
   var runtime=Field<ZapretRuntime>(form,"zapret");Check(!runtime.Running&&!core.Running,"tests never started VPN or Zapret driver");form.Close();
  }
  return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
