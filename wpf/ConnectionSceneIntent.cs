using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  bool startingConnection;
  async Task ToggleVpnWithPresentation(){
   stoppingConnection=session.Core.Running;startingConnection=!stoppingConnection;
   if(refreshHome!=null)refreshHome();
   try{await session.ToggleVpn();}
   finally{startingConnection=false;stoppingConnection=false;if(refreshHome!=null)refreshHome();}
  }
 }
}
