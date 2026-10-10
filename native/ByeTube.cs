using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public static class ByeTube {
  public const int Port=19072;
  public static string[] Strategies {get{using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.ByeTube.strategies.txt"))using(var reader=new StreamReader(stream))return reader.ReadToEnd().Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries);}}
  public static void Validate(string options){if(!Strategies.Contains(options))throw new InvalidOperationException("Выберите доступную Windows-стратегию ByeTube");}
  public static Process Start(string executable,string options,Action<string> log,CancellationToken token,int port=Port){Validate(options);var reservation=new TcpListener(IPAddress.Loopback,port);try{reservation.Start();}catch(SocketException){throw new InvalidOperationException("Локальный порт ByeTube занят другой программой");}finally{reservation.Stop();}var process=OwnedJob.Start(new ProcessStartInfo(executable,"-i 127.0.0.1 -p "+port+" -U -T 3 "+options){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true});try{process.ErrorDataReceived+=(s,e)=>{if(e.Data!=null)log("ByeTube: "+e.Data);};process.OutputDataReceived+=(s,e)=>{if(e.Data!=null)log("ByeTube: "+e.Data);};process.BeginErrorReadLine();process.BeginOutputReadLine();for(int i=0;i<60;i++){token.ThrowIfCancellationRequested();if(process.HasExited)throw new InvalidOperationException("Движок ByeTube завершился при запуске стратегии");using(var client=new TcpClient()){try{var pending=client.ConnectAsync(IPAddress.Loopback,port);if(pending.Wait(30)&&client.Connected)return process;}catch{}}Thread.Sleep(25);}throw new TimeoutException("ByeTube не открыл локальный порт");}catch{try{if(!process.HasExited){process.Kill();process.WaitForExit(1500);}}catch{}process.Dispose();throw;}}
 }
 public sealed partial class MainForm {
  void ByeTubeBlue(Panel host=null){
   var box=host??Box();if(host==null){box.Dock=DockStyle.Fill;content.Controls.Add(box);}var label=L("ByeTube · резервный способ обхода YouTube, если Zapret не помог",16,true);label.AutoSize=false;label.SetBounds(24,22,box.Width-48,52);label.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(label);var strategies=ByeTube.Strategies;var picker=new GlassPicker{Name="byeTubeStrategy",FlatSurface=true};picker.SetBounds(24,84,box.Width-48,40);picker.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;picker.SetItems(strategies.Select((s,i)=>"Стратегия "+(i+1)+" · "+s));picker.SelectedIndex=Math.Max(0,Array.IndexOf(strategies,state.ByeTubeStrategy));box.Controls.Add(picker);
   var hint=L("Локальный обход через ByeDPI, не VPN. Выберите стратегию вручную или запустите подбор ниже.",11,false,Muted);hint.AutoSize=false;hint.SetBounds(24,146,box.Width-48,62);hint.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(hint);
   var save=B("Сохранить стратегию",async(s,e)=>{if(core.Running&&!await OfferStopTun())return;state.ByeTubeStrategy=strategies[picker.SelectedIndex];store.Save(state);Toast("Стратегия ByeTube сохранена");},true);save.SetBounds(24,228,230,38);box.Controls.Add(save);var toggle=B(core.Running&&state.ByeTubeEnabled?"Выключить ByeTube":"Включить ByeTube",(s,e)=>{state.ByeTubeStrategy=strategies[picker.SelectedIndex];ToggleRoutedOutput(false,true);});toggle.Name="byeTubeToggle";toggle.SetBounds(270,228,230,38);box.Controls.Add(toggle);var routes=B("Назначить сервисы",(s,e)=>ShowServiceConstructor());routes.SetBounds(24,280,230,38);box.Controls.Add(routes);
   AddByeTubeScanControls(box);
  }
 }
}
