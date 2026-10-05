using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
namespace SplifyWin {
 public static class WarpOutputs {
  public static bool IsTarget(string target){return target!=null&&target.StartsWith("warp:",StringComparison.Ordinal);}
  public static string Tag(ServerNode node){return "warp:"+node.Id;}
  public static ServerNode[] Profiles(ClientState state){return state.Servers.Where(WarpPicker.IsWarp).ToArray();}
  public static ServerNode[] ActiveProfiles(ClientState state){return !state.WarpEnabled?new ServerNode[0]:Profiles(state).Where(n=>state.Lists.Any(r=>r.Enabled&&r.Target==Tag(n))).ToArray();}
  public static void Migrate(ClientState state){if(state.IndependentWarpOutputs)return;var selected=state.Servers.FirstOrDefault(n=>n.Id==state.SelectedServerId&&WarpPicker.IsWarp(n));if(selected!=null){foreach(var route in state.Lists.Where(r=>r.Target=="proxy"))route.Target=Tag(selected);if(state.ExitDns!=null)foreach(var dns in state.ExitDns.Where(d=>d.Target=="proxy"))dns.Target=Tag(selected);state.SelectedServerId=state.Servers.Where(n=>!WarpPicker.IsWarp(n)).Select(n=>n.Id).FirstOrDefault()??"";}state.IndependentWarpOutputs=true;state.WarpEnabled=false;state.VpnEnabled=true;}
  public static void Validate(ClientState state){foreach(var route in state.Lists.Where(r=>r.Enabled&&IsTarget(r.Target)))if(!Profiles(state).Any(n=>Tag(n)==route.Target))throw new InvalidOperationException("Выход WARP для списка «"+route.Name+"» удалён. Выберите другой выход");if(!state.WarpEnabled)return;var keys=new HashSet<string>();foreach(var n in ActiveProfiles(state)){var key=Links.WireGuardFields(n.Link)["PrivateKey"];if(!keys.Add(key))throw new InvalidOperationException("Два выхода WARP используют один аккаунт. Создайте отдельный WARP: одинаковые ключи нельзя включать одновременно");}}
 }
 public sealed partial class MainForm {
  internal void OpenWarpPage(){ShowPage("warp");}
  internal void OpenByeTubePage(){ShowPage("byetube");}
  async void ToggleRoutedOutput(bool warp,bool bye=false){
   if(byetubeCancellation!=null||connecting||setupCancellation!=null||zapretCancellation!=null||warpCancellation!=null||discordVoiceChecking){Toast("Дождитесь завершения текущей операции");return;}
   bool desired=!(core.Running&&(bye?state.ByeTubeEnabled:warp?state.WarpEnabled:state.VpnEnabled));if(!warp&&!bye&&desired&&!state.Servers.Any(n=>!WarpPicker.IsWarp(n)&&core.HasEngine(n))){ShowPage("servers");Toast("Сначала добавьте VPN-сервер");return;}if(warp&&desired&&WarpOutputs.Profiles(state).Length==0){ShowPage("warp");Toast("Сначала создайте WARP");return;}if(desired&&(warp||bye)&&!state.Lists.Any(r=>r.Enabled&&(bye?r.Target=="byetube":WarpOutputs.IsTarget(r.Target)))){ShowServiceConstructor();Toast("Назначьте сервисы этому выходу");return;}if(state.Mode=="tun"&&!IsAdministrator()){if(await ConfirmChange("Для TUN нужны права администратора. Приложение перезапустится с запросом Windows.","Получить права и перезапустить","Подключение"))RequestElevation(bye?"--byetube":warp?"--warp":"--connect");return;}
   bool oldVpn=core.Running&&state.VpnEnabled,oldWarp=core.Running&&state.WarpEnabled,oldBye=core.Running&&state.ByeTubeEnabled;bool oldRunning=core.Running;Exception failure=null;connecting=true;expectedRunning=false;int generation=++connectionGeneration;
   state.VpnEnabled=warp||bye?oldVpn:desired;state.WarpEnabled=warp?desired:oldWarp;state.ByeTubeEnabled=bye?desired:oldBye;
   try{if(state.VpnEnabled||state.WarpEnabled||state.ByeTubeEnabled)core.ValidateSelection(state);await Task.Run(()=>{systemProxy.Restore();core.Stop();if(state.VpnEnabled||state.WarpEnabled||state.ByeTubeEnabled)core.Start(state);});expectedRunning=core.Running;verifiedThisSession=false;connectedAtUtc=core.Running?DateTime.UtcNow:DateTime.MinValue;store.Save(state);WriteLog((bye?"ByeTube":warp?"WARP":"VPN")+": "+(desired?"включён":"выключен"));if(core.Running&&state.VpnEnabled)CheckSession(generation);}
   catch(Exception ex){core.Stop();state.VpnEnabled=oldVpn;state.WarpEnabled=oldWarp;state.ByeTubeEnabled=oldBye;failure=ex;}
   if(failure!=null){if(oldRunning)try{await Task.Run(()=>core.Start(state));expectedRunning=core.Running;}catch(Exception ex){WriteLog("Не удалось восстановить выходы: "+ex.Message);}GlassNotice.Show(this,failure.Message,"Не удалось переключить выход");}
   connecting=false;if(!IsDisposed){blueDashboard.Invalidate();RefreshSettingsConnection();SyncTray();if(page=="byetube")ShowPage("byetube");}
  }
 }
}
