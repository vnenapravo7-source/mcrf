using System;
using System.Threading;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal Func<string,bool,CancellationToken,string,Task<string>> VkPageForTests;
  CancellationTokenSource vkAccountCancellation;bool vkAccountLifetimeHook,vkAccountClosed;
  Task<string> OpenVkAccountPage(string address,CancellationToken cancellation,string afterLogin){
   cancellation.ThrowIfCancellationRequested();
   return VkPageForTests!=null?VkPageForTests(address,false,cancellation,afterLogin):VkPage(address,false,cancellation,afterLogin);
  }
  void HookVkAccountLifetime(){
   if(vkAccountLifetimeHook)return;vkAccountLifetimeHook=true;
   Closing+=(s,e)=>{if(vkAccountCancellation!=null&&(closeRequested||forceExit||session==null||!session.State.CloseToTray))vkAccountCancellation.Cancel();};
   Closed+=(s,e)=>{vkAccountClosed=true;if(vkAccountCancellation!=null)vkAccountCancellation.Cancel();};
  }
  async Task<bool> LinkVkAccount(CancellationToken cancellation){
   if(vkAccountClosed||closing||closeRequested)return false;
   ProfileRecovery.DemandHealthy(root);var previous=VkTurnSettings.Load(root);
   string state=VkCalls.NewState();string response=await OpenVkAccountPage("https://vk.ru/",cancellation,VkCalls.LoginUrl(state));
   cancellation.ThrowIfCancellationRequested();if(vkAccountClosed||closing||closeRequested||response==null)return false;
   var token=VkCalls.ParseToken(response,state);
   try{
    ProfileRecovery.DemandHealthy(root);
    VkTurnSettings.Update(root,p=>{
     cancellation.ThrowIfCancellationRequested();
     if(p.Token!=previous.Token||p.TokenExpires!=previous.TokenExpires)throw new InvalidOperationException("Аккаунт VK изменился во время входа. Откройте вход заново.");
     p.Token=token.Value;p.TokenExpires=token.Expires;
    });return true;
   }finally{token.Value=null;}
  }
 }
}
