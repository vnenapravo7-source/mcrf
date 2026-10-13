using System;
using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  bool reportFromEditor;
  async Task PrepareTests(){if(session.Core.Running||session.Zapret.Running||session.Telegram.Running){if(!Confirm("Для независимой проверки MCRF автоматически выключит свои VPN/TUN, WARP, ByeTube, Zapret и Telegram WS. Соединение может прерваться. Другие приложения не останавливаются. После проверки модули останутся выключенными, пока вы не включите их.","Начать проверку?"))throw new OperationCanceledException();await session.PauseForTests();}SyncModules();}
 }
}
