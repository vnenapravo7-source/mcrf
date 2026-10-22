using System;
using System.Threading.Tasks;
using System.Windows.Threading;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  bool? queuedTun;bool tunRequested,drainingTun;
  bool TunRequestedValue(){return queuedTun.HasValue||drainingTun?tunRequested:session.Core.Running&&session.State.VpnEnabled&&session.State.Mode=="tun";}
  void QueueTunIntent(bool enabled){queuedTun=enabled;tunRequested=enabled;if(!enabled&&session.Core.Starting)CancelCoreStartup();SyncModules();}
  void ScheduleTunIntent(){if(!closing&&queuedTun.HasValue&&!drainingTun)Dispatcher.BeginInvoke(DispatcherPriority.Background,new Action(async()=>await DrainTunIntent()));}
  async Task DrainTunIntent(){if(busy||closing||drainingTun||!queuedTun.HasValue)return;drainingTun=true;try{while(queuedTun.HasValue&&!closing){bool enabled=queuedTun.Value;queuedTun=null;await Run(async()=>{if(session.State.Mode!="tun"&&enabled)throw new InvalidOperationException("Сначала выберите режим TUN на главной.");status.Text=enabled?"Включаем TUN…":"Останавливаем TUN…";await session.SetVpnEnabled(enabled);status.Text=enabled?"TUN включён":"TUN отключён";});}}finally{drainingTun=false;SyncModules();}}
 }
}
