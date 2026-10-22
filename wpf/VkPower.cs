using System;
using System.Runtime.InteropServices;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  [DllImport("kernel32.dll")]static extern uint SetThreadExecutionState(uint flags);
  bool vkPowerActive,vkPowerHook;DateTime vkPowerCheck=DateTime.MinValue;VkTurnPreferences vkPowerPreferences;
  internal static bool VkPowerRequired(bool enabled,bool connected,bool bsOnly,bool wifiPaused){return enabled&&connected&&bsOnly&&!wifiPaused;}
  void UpdateVkPower(){if(diagnostic||session==null)return;if(!vkPowerHook){vkPowerHook=true;Closed+=(s,e)=>{if(vkPowerActive){SetThreadExecutionState(0x80000000);vkPowerActive=false;}};}bool running=session.Core.Running&&!session.Core.Starting;if(running&&(vkPowerPreferences==null||DateTime.UtcNow>=vkPowerCheck)){vkPowerPreferences=VkTurnSettings.Load(root);vkPowerCheck=DateTime.UtcNow.AddSeconds(15);}bool wanted=VkPowerRequired(vkPowerPreferences!=null&&vkPowerPreferences.PreventSleep,running,VkTurnSettings.BsOnly(session.State),session.State.VkWifiPaused);if(wanted==vkPowerActive)return;if(SetThreadExecutionState(wanted?0x80000001u:0x80000000u)==0)throw new InvalidOperationException("Windows не приняла запрос удержания БС во время сна.");vkPowerActive=wanted;}
 }
}
