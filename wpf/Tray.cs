using System;
using System.Reflection;
using SplifyWin;
using System.Windows;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  System.Windows.Forms.NotifyIcon tray;bool startupEnabled,forceExit;WindowState restoredWindowState=WindowState.Normal;
  void RequestExit(){forceExit=true;Close();}
  void InstallTray(){if(tray!=null)return;var trayIcon=BrandArt.CreateIcon(Math.Max(16,System.Windows.Forms.SystemInformation.SmallIconSize.Width));tray=new System.Windows.Forms.NotifyIcon{Icon=trayIcon,Text="MCRF",Visible=true};tray.Disposed+=(s,e)=>trayIcon.Dispose();var menu=new System.Windows.Forms.ContextMenuStrip();menu.Items.Add("Открыть MCRF",null,(s,e)=>Dispatcher.BeginInvoke(new Action(RestoreWindow)));foreach(string key in new[]{"@zapret","@telegram","@warp","@tun"}){string id=key;var item=menu.Items.Add(key=="@zapret"?"Zapret":key=="@telegram"?"Telegram WS":key=="@warp"?"WARP":"TUN",null,(s,e)=>Dispatcher.BeginInvoke(new Action(async()=>await ChangeModule(id))));item.Tag=id;}menu.Opening+=(s,e)=>{foreach(System.Windows.Forms.ToolStripItem item in menu.Items)if(item.Tag is string)item.Visible=ModuleAvailable((string)item.Tag);};menu.Items.Add("Выход",null,(s,e)=>Dispatcher.BeginInvoke(new Action(RequestExit)));tray.ContextMenuStrip=menu;tray.DoubleClick+=(s,e)=>Dispatcher.BeginInvoke(new Action(RestoreWindow));StateChanged+=(s,e)=>{if(WindowState!=WindowState.Minimized){restoredWindowState=WindowState;return;}if(!closing&&session!=null&&!session.State.MinimizeToTaskbar)Hide();};Application.Current.SessionEnding+=(s,e)=>forceExit=true;}
  void RestoreWindow(){Show();if(WindowState==WindowState.Minimized)WindowState=restoredWindowState;Activate();}
 }
}
