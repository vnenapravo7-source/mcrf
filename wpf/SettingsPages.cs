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
  void InstallModules(DockPanel side){var panel=new StackPanel{Margin=new Thickness(18,10,18,10)};foreach(var entry in new[]{Tuple.Create("@zapret","Zapret"),Tuple.Create("@telegram","Telegram WS"),Tuple.Create("@warp","WARP"),Tuple.Create("@tun","TUN")}){string id=entry.Item1;var check=new CheckBox{Content=entry.Item2,Margin=new Thickness(0,6,0,6)};moduleChecks[id]=check;check.Click+=async(s,e)=>{if(!moduleSync)await Run(()=>ModuleAction(id));};panel.Children.Add(check);}modulePanel=panel;DockPanel.SetDock(panel,Dock.Bottom);side.Children.Add(panel);side.Children.Add(new Grid());LayoutUpdated+=(s,e)=>AlignModulePanel();}
  void SyncModules(){if(session==null)return;UpdateConnectionClock();moduleSync=true;foreach(var pair in moduleChecks){pair.Value.IsEnabled=!busy;pair.Value.IsChecked=pair.Key=="@zapret"?session.Zapret.Running:pair.Key=="@telegram"?session.Telegram.Running:pair.Key=="@warp"?session.Core.Running&&session.State.WarpEnabled:session.Core.Running&&session.State.VpnEnabled&&session.State.Mode=="tun";}moduleSync=false;}
  async Task ModuleAction(string id){if(id=="@telegram")await session.ToggleTelegram();else if(id=="@zapret")await session.ToggleZapret();else if(id=="@warp")await session.ToggleRoutedOutput(true,false);else if(id=="@tun"){if(session.State.Mode!="tun")throw new InvalidOperationException("Сначала выберите режим TUN на главной. Переключатель не меняет режим автоматически.");await session.ToggleVpn();}else await session.ToggleProfile(id);SyncModules();string name=id=="@telegram"?"Telegram WS":id=="@zapret"?"Zapret":id=="@warp"?"WARP":id=="@tun"?"TUN":"Профиль";bool running=id=="@telegram"?session.Telegram.Running:id=="@zapret"?session.Zapret.Running:id=="@warp"?session.Core.Running&&session.State.WarpEnabled:id=="@tun"?session.Core.Running&&session.State.VpnEnabled:session.Zapret.Running;RecordEvent(name+(running?" включён":" отключён"));}
  void RegisterHotkeys(){if(hotkeys==null){hotkeys=new ProfileHotkeyWindow();hotkeys.Pressed+=id=>Dispatcher.BeginInvoke(new Action(async()=>await Run(()=>ModuleAction(id))));}var targets=session.State.ZapretProfiles.Select(p=>p.Id).Concat(new[]{"@telegram","@warp","@tun","@zapret"}).ToArray();hotkeys.Retain(targets);foreach(string id in targets){string chord=null;if(id.StartsWith("@")){if(session.State.GlobalHotkeys!=null)session.State.GlobalHotkeys.TryGetValue(id,out chord);}else chord=session.State.ZapretProfiles.First(p=>p.Id==id).Hotkey;string error=hotkeys.Set(id,chord);if(error!=null)status.Text=error;}}
 }
}
