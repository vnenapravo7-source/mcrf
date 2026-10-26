using System;
using System.Linq;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Diagnostics;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  ProfileHotkeyWindow hotkeys;readonly Dictionary<string,CheckBox> moduleChecks=new Dictionary<string,CheckBox>();bool moduleSync;
  StackPanel modulePanel;
  void InstallModules(DockPanel side){var panel=new StackPanel{Margin=new Thickness(18,10,18,10)};panel.Children.Add(WindowBehaviorControls());foreach(var entry in new[]{Tuple.Create("@zapret","Zapret"),Tuple.Create("@telegram","Telegram WS"),Tuple.Create("@warp","WARP"),Tuple.Create("@tun","TUN")}){string id=entry.Item1;var check=new CheckBox{Content=entry.Item2,Margin=new Thickness(0,6,0,6)};moduleChecks[id]=check;check.Click+=async(s,e)=>{if(!moduleSync)await ChangeModule(id,check.IsChecked==true);};panel.Children.Add(check);}modulePanel=panel;DockPanel.SetDock(panel,Dock.Bottom);side.Children.Add(panel);side.Children.Add(new Grid());LayoutUpdated+=(s,e)=>AlignModulePanel();}
  string pendingModule;bool pendingModuleValue;
  async Task ChangeModule(string id,bool? requested=null){if(id.StartsWith("@")&&!ModuleAvailable(id)){SyncModules();return;}if(id=="@tun"){QueueTunIntent(requested??!TunRequestedValue());await DrainTunIntent();return;}if(busy){SyncModules();return;}pendingModule=id;pendingModuleValue=!(id=="@zapret"?session.Zapret.Running:id=="@telegram"?session.Telegram.Running:id=="@warp"?session.Core.Running&&session.State.WarpEnabled:session.Core.Running&&session.State.VpnEnabled&&session.State.Mode=="tun");try{await Run(()=>ModuleAction(id));}finally{pendingModule=null;SyncModules();}}
  void SyncModules(){if(session==null)return;UpdateConnectionClock();moduleSync=true;foreach(var pair in moduleChecks){pair.Value.IsHitTestVisible=!busy||pair.Key=="@tun";pair.Value.Focusable=!busy||pair.Key=="@tun";pair.Value.IsChecked=pair.Key=="@tun"&&(queuedTun.HasValue||drainingTun)?tunRequested:pair.Key==pendingModule?pendingModuleValue:pair.Key=="@zapret"?session.Zapret.Running:pair.Key=="@telegram"?session.Telegram.Running:pair.Key=="@warp"?session.Core.Running&&session.State.WarpEnabled:session.Core.Running&&session.State.VpnEnabled&&session.State.Mode=="tun";}moduleSync=false;if(refreshHome!=null)refreshHome();}
  async Task ModuleAction(string id){if(id=="@telegram")await session.ToggleTelegram();else if(id=="@zapret")await session.ToggleZapret();else if(id=="@warp")await session.ToggleRoutedOutput(true,false);else if(id=="@tun"){if(session.State.Mode!="tun")throw new InvalidOperationException("Сначала выберите режим TUN на главной. Переключатель не меняет режим автоматически.");await ToggleVpnWithPresentation();}else await session.ToggleProfile(id);SyncModules();string name=id=="@telegram"?"Telegram WS":id=="@zapret"?"Zapret":id=="@warp"?"WARP":id=="@tun"?"TUN":"Профиль";bool running=id=="@telegram"?session.Telegram.Running:id=="@zapret"?session.Zapret.Running:id=="@warp"?session.Core.Running&&session.State.WarpEnabled:id=="@tun"?session.Core.Running&&session.State.VpnEnabled:session.Zapret.Running;RecordEvent(name+(running?" включён":" отключён"));}
  bool hotkeyEditing;
  void EnsureHotkeyWindow(){if(hotkeys!=null)return;hotkeys=new ProfileHotkeyWindow();hotkeys.Pressed+=id=>{if(hotkeyEditing)return;Dispatcher.BeginInvoke(new Action(async()=>{if(!hotkeyEditing)await ChangeModule(id);}));};}
  void InstallNativeHotkeys(Dictionary<string,string> bindings,bool replace){EnsureHotkeyWindow();var before=hotkeys.Snapshot();var assigned=bindings.Where(p=>p.Value!=null).ToDictionary(p=>p.Key,p=>p.Value);bool changed=before.Count!=assigned.Count||before.Any(p=>!assigned.ContainsKey(p.Key)||assigned[p.Key]!=p.Value);hotkeys.RetainChecked(replace||changed?(IEnumerable<string>)new string[0]:bindings.Keys);foreach(var item in bindings){string error=hotkeys.Set(item.Key,item.Value);if(error!=null)throw new InvalidOperationException(error);}}
  HotkeyServices NativeHotkeyServices(){return new HotkeyServices{Snapshot=()=>{EnsureHotkeyWindow();return hotkeys.Snapshot();},Install=bindings=>{InstallNativeHotkeys(bindings,true);return Task.FromResult(0);}};}
  void RegisterHotkeys(){try{InstallNativeHotkeys(HotkeySettings.Bindings(session.State),false);}catch(Exception ex){status.Text=JournalStyle.Redact(ex.Message);}}
 }
}
