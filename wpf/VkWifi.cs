using System;
using System.Linq;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  DateTime vkWifiCheck=DateTime.MinValue;
  internal static bool VkWifiShouldPause(VkTurnPreferences settings,bool wifi,string ssid){return wifi&&(settings.PauseOnWifi||settings.TrustedWifiEnabled&&!String.IsNullOrEmpty(ssid)&&settings.TrustedSsids.Contains(ssid,StringComparer.Ordinal));}
  async Task PollVkWifi(){
   UpdateVkPower();
   if(diagnostic||busy||session.Core.Starting||DateTime.UtcNow<vkWifiCheck)return;vkWifiCheck=DateTime.UtcNow.AddSeconds(15);
   if(!session.Core.Running){session.State.VkWifiPaused=false;return;}
   var state=session.State;var generation=session.Core.StartGeneration;string selected=state.SelectedServerId;bool automatic=state.AutoSelect;var settings=VkTurnSettings.Load(root);bool relevant=VkTurnSettings.BsOnly(state),pause=false;
   if(relevant&&(settings.PauseOnWifi||settings.TrustedWifiEnabled))pause=await Task.Run(()=>{
    bool wifi=NetworkInterface.GetAllNetworkInterfaces().Any(n=>n.NetworkInterfaceType==NetworkInterfaceType.Wireless80211&&n.OperationalStatus==OperationalStatus.Up);string ssid=null;
    if(wifi&&settings.TrustedWifiEnabled&&!settings.PauseOnWifi){var info=new ProcessStartInfo("netsh.exe","wlan show interfaces"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true};using(var p=Process.Start(info)){if(p.WaitForExit(2000)){foreach(string line in p.StandardOutput.ReadToEnd().Split('\n')){var m=System.Text.RegularExpressions.Regex.Match(line,@"^\s*SSID\s*:\s?(.*?)\r?$");if(m.Success){ssid=m.Groups[1].Value;break;}}}else try{p.Kill();}catch{}}}
    return VkWifiShouldPause(settings,wifi,ssid);
   });
   if(closing||busy||!Object.ReferenceEquals(state,session.State)||generation!=session.Core.StartGeneration||selected!=state.SelectedServerId||automatic!=state.AutoSelect||relevant!=VkTurnSettings.BsOnly(state))return;
   if(pause!=state.VkWifiPaused)await Run(async()=>{await session.SetVkWifiPaused(pause);status.Text=pause?"БС на паузе в Wi-Fi · локальный прокси продолжает работать":"Системный VPN БС восстановлен после Wi-Fi";});
  }
 }
}
