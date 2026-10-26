using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class ClientSession : IDisposable {
  public Func<Task<bool>> BeforeVpnStart;
  internal string LastProfileResetWarning{get;private set;}
  public StateStore Store{get;private set;}public ClientState State{get;private set;}public NetworkCore Core{get;private set;}public ZapretRuntime Zapret{get;private set;}public TelegramBridge Telegram{get;private set;}public AppUpdates Updates{get;private set;}public WardogsObserver Wardogs{get;private set;}SystemProxy proxy;readonly SemaphoreSlim operations=new SemaphoreSlim(1,1);
  public static Task<ClientSession> Open(string root){return Task.Run(()=>{var session=new ClientSession();session.Store=new StateStore(root);session.State=session.Store.Load();if(session.State.InterfaceMode<0||session.State.InterfaceMode>1){session.State.InterfaceMode=0;session.Store.Save(session.State);}WarpOutputs.Migrate(session.State);if(session.State.ZapretProfiles==null||session.State.ZapretProfiles.Count==0)session.State.ZapretProfiles=new System.Collections.Generic.List<ZapretProfile>{new ZapretProfile{Name="Основной",Settings=new ZapretSettings()}};foreach(var profile in session.State.ZapretProfiles){if(profile.Settings==null)profile.Settings=new ZapretSettings();if(String.IsNullOrEmpty(profile.Settings.Strategy))profile.Enabled=false;}session.Core=new NetworkCore(session.Store);session.Zapret=new ZapretRuntime(session.Core);session.proxy=new SystemProxy(root);session.proxy.Recover();session.Telegram=new TelegramBridge();session.Telegram.SetPort(session.State.TelegramPort>=1024?session.State.TelegramPort:TelegramBridge.DefaultPort);session.Updates=new AppUpdates(session.Core);session.Wardogs=new WardogsObserver(session);return session;});}
  public async Task ResetProfile(int mode,string folder,bool includeHosts=false){await ResetProfileChecked(mode,folder,includeHosts,()=>false);}
  internal async Task<bool> ResetProfileChecked(int mode,string folder,bool includeHosts,Func<bool> cancelled){
   if(mode<0||mode>2)throw new ArgumentOutOfRangeException("mode");
   await operations.WaitAsync();try{if(cancelled())return false;LastProfileResetWarning=null;bool applied=await Task.Run(()=>{
    ProfileReset.PreparedRestore prepared=mode==2?ProfileReset.PrepareRestore(Store,folder):null;
    try{if(cancelled())return false;Core.Stop();Zapret.Stop();Telegram.Stop();if(prepared!=null)ProfileReset.RestorePrepared(Store,prepared,includeHosts);else{try{ProfileReset.Reset(Store,includeHosts,mode==0);}catch(ProfileReset.ResetFailure ex){if(!ex.Applied)throw;LastProfileResetWarning=JournalStyle.Redact(ex.Message);}}State=new StateStore(Store.Root).Load();if(State.ZapretProfiles==null||State.ZapretProfiles.Count==0)State.ZapretProfiles=new System.Collections.Generic.List<ZapretProfile>{new ZapretProfile{Name="Основной",Enabled=false,Settings=new ZapretSettings()}};return true;}
    finally{if(prepared!=null){try{prepared.Dispose();}catch(ProfileReset.RestoreFailure ex){if(!ex.Applied)throw;LastProfileResetWarning=JournalStyle.Redact(ex.Message);}}}
   });if(applied)InvalidateStrategies();return applied;}finally{operations.Release();}
  }
  public async Task PauseForTests(){await operations.WaitAsync();try{await Task.Run(()=>{Zapret.Stop();Core.Stop();Telegram.Stop();});}finally{operations.Release();}}
  public ClientState ConfigurationSnapshot(){var json=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};return json.Deserialize<ClientState>(json.Serialize(State));}
  public async Task ProbeServers(){await operations.WaitAsync();try{await Core.ProbeAll(State);await Save();}finally{operations.Release();}}
  public async Task ProbeServer(ServerNode node){await operations.WaitAsync();try{if(!State.Servers.Contains(node))return;if(AutomaticServers.ExcludeMeasurement(node)){await Save();return;}if(node.Protocol=="wireguard"||node.Protocol=="awg"||node.Protocol=="hysteria"||node.Protocol=="hysteria2"||node.Protocol=="tuic"||node.Protocol=="qwdtt"||TurnEngines.IsTurn(node)){node.Latency=await Core.ProbeIcmp(node);}else if(node.Protocol=="openflux"){node.Latency=-1;}else node.Latency=await Core.Probe(node);await Save();}finally{operations.Release();}}
  public async Task Save(){await Task.Run(()=>Store.Save(State));}
  internal async Task<bool> SaveWindowBehavior(bool? minimizeToTaskbar,bool? closeToTray,Func<bool> cancelled){
   await operations.WaitAsync();try{if(cancelled())return false;bool previousMinimize=State.MinimizeToTaskbar,previousClose=State.CloseToTray;
    if(minimizeToTaskbar.HasValue)State.MinimizeToTaskbar=minimizeToTaskbar.Value;if(closeToTray.HasValue)State.CloseToTray=closeToTray.Value;
    try{await Save();return true;}catch{State.MinimizeToTaskbar=previousMinimize;State.CloseToTray=previousClose;throw;}
   }finally{operations.Release();}
  }
  internal async Task<string> BackupProfile(Func<bool> cancelled){
   await operations.WaitAsync();try{if(cancelled())throw new OperationCanceledException();await Save();if(cancelled())throw new OperationCanceledException();return await Task.Run(()=>ProfileReset.Backup(Store,false));}finally{operations.Release();}
  }
  internal async Task<bool> SaveHotkeys(Dictionary<string,string> draft,HotkeyServices services,Func<bool> cancelled){
   await operations.WaitAsync();try{
    if(cancelled())return false;var beforeGlobal=State.GlobalHotkeys==null?null:new Dictionary<string,string>(State.GlobalHotkeys);var beforeProfiles=State.ZapretProfiles.ToDictionary(p=>p.Id,p=>p.Hotkey);var beforeBindings=new Dictionary<string,string>(services.Snapshot());bool attempted=false;Exception failure=null;
    try{HotkeySettings.Stage(State,draft);HotkeySettings.Bindings(State,true);var bindings=HotkeySettings.Bindings(State);if(cancelled())throw new OperationCanceledException();attempted=true;await services.Install(bindings);if(cancelled())throw new OperationCanceledException();await Save();return true;}catch(Exception ex){failure=ex;}
    State.GlobalHotkeys=beforeGlobal;foreach(var profile in State.ZapretProfiles)if(beforeProfiles.ContainsKey(profile.Id))profile.Hotkey=beforeProfiles[profile.Id];Exception recovery=null;if(attempted){try{await services.Install(beforeBindings);}catch(Exception ex){recovery=ex;}}
    if(recovery!=null)throw new AggregateException("Назначения не сохранены; прежние горячие клавиши не удалось восстановить. Перезапустите MCRF.",failure,recovery);throw failure;
   }finally{operations.Release();}
  }
  public async Task<bool> RenameServer(string id,string name,Func<bool> cancelled){
   await operations.WaitAsync();try{
    if(cancelled())return false;
    var node=State.Servers.FirstOrDefault(n=>n.Id==id);if(node==null)return false;
    string previous=node.Name;if(previous==name)return false;node.Name=name;
    try{await Save();return true;}catch{node.Name=previous;throw;}
   }finally{operations.Release();}
  }
  readonly object catalogGate=new object();Task<ZapretStrategy[]> catalogTask;Task<string> helpTask;
  public void InvalidateStrategies(){lock(catalogGate){catalogTask=null;helpTask=null;}}
  public async Task ChangeConfiguration(Func<Task> change,bool startZapret=false){await operations.WaitAsync();try{await ChangeConfigurationOwned(change,startZapret);}finally{operations.Release();}}
  async Task ChangeConfigurationOwned(Func<Task> change,bool startZapret=false){var json=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};var previous=json.Deserialize<ClientState>(json.Serialize(State));previous.VkWifiPaused=State.VkWifiPaused;
   bool coreRunning=Core.Running,zapretRunning=Zapret.Running;Exception failure=null;try{await change();if(zapretRunning)await Task.Run(()=>Zapret.Stop());if(coreRunning)await Task.Run(()=>Core.Stop());if(coreRunning)await Task.Run(()=>Core.Start(State));if(zapretRunning||startZapret){var active=ZapretRoutes.Active(State);if(active.Length==0&&startZapret)throw new InvalidOperationException("Назначьте маршруты профилю перед применением стратегии.");if(active.Length>0)await Zapret.StartProfiles(active,CancellationToken.None);}await Save();}catch(Exception ex){failure=ex;}if(failure!=null){State=previous;await Task.Run(()=>{Core.Stop();Zapret.Stop();});Exception recovery=null;try{if(coreRunning)await Task.Run(()=>Core.Start(State));if(zapretRunning){var active=ZapretRoutes.Active(State);if(active.Length>0)await Zapret.StartProfiles(active,CancellationToken.None);}}catch(Exception ex){recovery=ex;}if(recovery!=null)throw new AggregateException("Изменение отменено; прежнее соединение не удалось восстановить.",failure,recovery);throw failure;}}
  public Task ChangeConfiguration(Action change,bool startZapret=false){return ChangeConfiguration(()=>{change();return Task.FromResult(0);},startZapret);}
  internal async Task<bool> ChangeConfigurationIf(Func<bool> guard,Action change){
   await operations.WaitAsync();try{if(!guard())return false;await ChangeConfigurationOwned(()=>{change();return Task.FromResult(0);});return true;}finally{operations.Release();}
  }
  internal async Task<bool> SetAutoSelectionMark(string id,bool enabled,Func<bool> cancelled){
   await operations.WaitAsync();try{
    if(cancelled())return false;var node=State.Servers.FirstOrDefault(n=>n.Id==id);if(node==null)return false;
    if(!AutomaticServers.Allowed(node))throw new InvalidOperationException("Этот сервер доступен только для ручного подключения.");
    if(node.UseInAutoSelection==enabled)return false;
    await ChangeConfigurationOwned(()=>{node.UseInAutoSelection=enabled;return Task.FromResult(0);});return true;
   }finally{operations.Release();}
  }
  void RememberServer(string id){
   var node=State.Servers.FirstOrDefault(n=>n.Id==id);if(node==null)throw new InvalidOperationException("Сервер уже удалён.");
   if(!AutomaticServers.Allowed(node)){State.AutoSelect=false;State.AutoDetectBs=false;}
   State.SelectedServerId=id;State.AutoBsActive=BsServers.Is(node);
   if(State.RecentServerIds==null)State.RecentServerIds=new System.Collections.Generic.List<string>();
   State.RecentServerIds.RemoveAll(value=>value==id);State.RecentServerIds.Insert(0,id);
   if(State.AutoBsActive)State.LastBsServerId=id;else State.LastVpnServerId=id;
  }
  internal async Task<ServerSelectionResult> SelectServer(string id,Func<bool> cancelled,ServerSelectionRuntime runtime=null){
   await operations.WaitAsync();try{
    if(cancelled())return ServerSelectionResult.Cancelled;
    var node=State.Servers.FirstOrDefault(n=>n.Id==id);if(node==null)throw new InvalidOperationException("Сервер уже удалён.");
    runtime=runtime??ServerSelectionRuntime.For(Core);
    bool running=runtime.Running();string previousActive=runtime.Active();
    bool already=running&&previousActive==id;
    if(!already&&(!AutomaticServers.Allowed(node)||!running||!runtime.CanSelect(id)||BsServers.Is(node)!=State.AutoBsActive)){
     await ChangeConfigurationOwned(()=>{if(cancelled())throw new OperationCanceledException();var selected=State.Servers.FirstOrDefault(n=>n.Id==id);bool automatic=AutomaticServers.Allowed(selected);bool recent=State.AutoSelect&&State.AutoSelectMode=="recent"&&automatic;RememberServer(id);State.AutoSelect=recent;if(!automatic)State.AutoDetectBs=false;return Task.FromResult(0);});
     return ServerSelectionResult.Reconfigured;
    }
    var previous=ConfigurationSnapshot();previous.VkWifiPaused=State.VkWifiPaused;bool attempted=false;Exception failure=null;
    try{
     if(!already){attempted=true;if(!await runtime.Select(id))throw new InvalidOperationException("Не удалось переключить активный сервер.");}
     if(cancelled())throw new OperationCanceledException();
     RememberServer(id);await Save();
     return already?ServerSelectionResult.AlreadyActive:ServerSelectionResult.Switched;
    }catch(Exception ex){failure=ex;}
    State=previous;
    if(attempted)try{
     if(String.IsNullOrEmpty(previousActive)||!runtime.CanSelect(previousActive)||!await runtime.Select(previousActive))throw new InvalidOperationException("Прежний активный сервер не восстановлен.");
    }catch(Exception recovery){throw new AggregateException("Выбор не сохранён; прежнее активное подключение не удалось восстановить.",failure,recovery);}
    throw failure;
   }finally{operations.Release();}
  }
  public async Task SetVkWifiPaused(bool paused){await operations.WaitAsync();try{if(!Core.Running||State.Mode!="tun"){State.VkWifiPaused=false;return;}if(paused&&!VkTurnSettings.BsOnly(State))return;bool previous=State.VkWifiPaused;if(previous==paused)return;Exception failure=null;try{await Task.Run(()=>{Core.Stop();State.VkWifiPaused=paused;Core.Start(State);});}catch(Exception error){failure=error;}if(failure!=null){State.VkWifiPaused=previous;await Task.Run(()=>{Core.Stop();Core.Start(State);});throw failure;}}finally{operations.Release();}}
  public Task ToggleVpn(){return ToggleRoutedOutput(false,false);}
  public Task SetVpnEnabled(bool enabled){return ToggleRoutedOutput(false,false,enabled);}
  public async Task ToggleZapret(){await operations.WaitAsync();try{if(Zapret.Running)await Task.Run(()=>Zapret.Stop());else{var profiles=ZapretRoutes.Active(State);if(profiles.Length==0)throw new InvalidOperationException("Сначала назначьте стратегию профилю и добавьте маршрут Zapret.");await Zapret.StartProfiles(profiles,CancellationToken.None);}}finally{operations.Release();}}
  public Task<ZapretStrategy[]> Strategies(){lock(catalogGate){if(catalogTask==null||catalogTask.IsFaulted||catalogTask.IsCanceled)catalogTask=Task.Run(()=>{Zapret.Prepare();return ZapretCatalog.Load(Zapret.DirectoryPath).Where(s=>s.Available).ToArray();});return catalogTask;}}
  public Task<string> EngineHelp(){lock(catalogGate){if(helpTask==null||helpTask.IsFaulted||helpTask.IsCanceled)helpTask=Task.Run(()=>{using(var process=System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Zapret.AppExecutable(),"--help"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){var output=process.StandardOutput.ReadToEndAsync();var error=process.StandardError.ReadToEndAsync();if(!process.WaitForExit(5000)){process.Kill();return "";}Task.WaitAll(output,error);return output.Result+"\n"+error.Result;}});return helpTask;}}
  public async Task ToggleTelegram(){await operations.WaitAsync();try{if(Telegram.Running)await Task.Run(()=>Telegram.Stop());else await Task.Run(()=>Telegram.Start(Core.TelegramExecutable));}finally{operations.Release();}}
  public async Task PollWardogs(){if(!await operations.WaitAsync(0))return;try{await Wardogs.Poll();}finally{operations.Release();}}
  public async Task ToggleProfile(string id){await operations.WaitAsync();try{var profile=State.ZapretProfiles.First(p=>p.Id==id);bool previous=profile.Enabled,wasRunning=Zapret.Running;Exception failure=null;try{if(wasRunning)await Task.Run(()=>Zapret.Stop());profile.Enabled=!previous;var active=ZapretRoutes.Active(State);if(active.Length>0)await Zapret.StartProfiles(active,CancellationToken.None);await Save();}catch(Exception ex){profile.Enabled=previous;failure=ex;}if(failure!=null){if(wasRunning)await Zapret.StartProfiles(ZapretRoutes.Active(State),CancellationToken.None);throw failure;}}finally{operations.Release();}}
  public async Task ToggleRoutedOutput(bool warp,bool bye,bool? desiredOutput=null){await operations.WaitAsync();try{bool wasRunning=Core.Running,oldWarp=State.WarpEnabled,oldBye=State.ByeTubeEnabled,oldVpn=State.VpnEnabled;string oldDefault=State.DefaultRouteTarget;bool vpn=!warp&&!bye;bool desired=desiredOutput??!(wasRunning&&(vpn?oldVpn:bye?oldBye:oldWarp));if(vpn&&desired&&BeforeVpnStart!=null&&!await BeforeVpnStart())return;if(vpn&&desired&&!State.Servers.Any(n=>!WarpPicker.IsWarp(n)))throw new InvalidOperationException("Сначала добавьте VPN-сервер.");if(desired&&!vpn&&!State.Lists.Any(r=>r.Enabled&&(bye?r.Target=="byetube":WarpOutputs.IsTarget(r.Target))))throw new InvalidOperationException("Сначала назначьте маршруты этому выходу.");if(warp&&desired&&WarpOutputs.Profiles(State).Length==0)throw new InvalidOperationException("Сначала создайте WARP.");State.VpnEnabled=vpn?desired:wasRunning&&oldVpn;State.WarpEnabled=warp?desired:wasRunning&&oldWarp;State.ByeTubeEnabled=bye?desired:wasRunning&&oldBye;if(vpn&&!desired&&State.DefaultRouteTarget=="proxy")State.DefaultRouteTarget=null;if(bye&&!desired&&State.DefaultRouteTarget=="byetube")State.DefaultRouteTarget=null;Exception failure=null;try{await Task.Run(()=>{proxy.Restore();Core.Stop();if(State.VpnEnabled||State.WarpEnabled||State.ByeTubeEnabled)Core.Start(State);});}catch(Exception ex){Core.Stop();State.WarpEnabled=oldWarp;State.ByeTubeEnabled=oldBye;State.VpnEnabled=oldVpn;State.DefaultRouteTarget=oldDefault;failure=ex;}if(failure!=null){if(wasRunning)await Task.Run(()=>{Core.Start(State);});throw failure;}await Save();}finally{operations.Release();}}
  public void Dispose(){var errors=new System.Collections.Generic.List<Exception>();foreach(Action cleanup in new Action[]{()=>{if(proxy!=null)proxy.Restore();},()=>{if(Wardogs!=null)Wardogs.Dispose();},()=>{if(Telegram!=null)Telegram.Dispose();},()=>{if(Updates!=null)Updates.Dispose();},()=>{if(Zapret!=null)Zapret.Dispose();},()=>{if(Core!=null)Core.Stop();}})try{cleanup();}catch(Exception ex){errors.Add(ex);}if(errors.Count>0)throw new AggregateException("Не все модули завершились без ошибки.",errors);}
 }
}
