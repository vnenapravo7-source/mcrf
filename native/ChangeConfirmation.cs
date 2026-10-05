using System;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
  public sealed partial class MainForm {
    Task<int> ChooseSettingsReset(){
      var done=new TaskCompletionSource<int>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(520,350)};veil.Controls.Add(popup);
      Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Action<int> finish=value=>{if(done.TrySetResult(value)){Controls.Remove(veil);veil.Dispose();}};
      Add(popup,L("Удалить настройки",18,true),24,22);string[] labels={"Удалить с бэкапом","Удалить безвозвратно","Восстановить из бэкапа"};for(int i=0;i<3;i++){int choice=i+1;var button=B(labels[i],(s,e)=>finish(choice),i==0);button.Name="resetChoice"+choice;button.SetBounds(24,86+i*58,472,42);popup.Controls.Add(button);}var cancel=B("Отмена",(s,e)=>finish(0));cancel.SetBounds(24,282,472,40);popup.Controls.Add(cancel);veil.Click+=(s,e)=>finish(0);veil.Disposed+=(s,e)=>done.TrySetResult(0);Controls.Add(veil);veil.BringToFront();layout();return done.Task;
    }
    Task<RouteList> ChooseWizardRoute(RouteList current){
      var done=new TaskCompletionSource<RouteList>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};
      var editor=new RouteDialog(current??new RouteList{Name="Мастер · через VPN",Target="proxy",Text=""},core,state.ZapretProfiles){TopLevel=false};veil.Controls.Add(editor);
      Action layout=()=>editor.Location=new Point((veil.Width-editor.Width)/2,(veil.Height-editor.Height)/2);veil.Resize+=(s,e)=>layout();
      editor.FormClosed+=(s,e)=>{done.TrySetResult(editor.DialogResult==DialogResult.OK?editor.Result:null);veil.Dispose();};veil.Disposed+=(s,e)=>done.TrySetResult(null);
      Controls.Add(veil);veil.BringToFront();layout();editor.Show();return done.Task;
    }
    Task<bool> ConfirmChange(string message,string action,string title="Перед изменением",string cancelCaption="Отмена"){
      var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};
      var popup=new GlassPopupPanel{Size=new Size(Math.Min(600,ClientSize.Width-60),310)};veil.Controls.Add(popup);
      Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
      Action<bool> finish=value=>{if(done.TrySetResult(value)){Controls.Remove(veil);veil.Dispose();}};
      Add(popup,L(title,18,true),26,25);var text=L(message,11,false,Muted);text.AutoSize=false;text.SetBounds(26,90,popup.Width-52,125);popup.Controls.Add(text);
      var yes=B(action,(s,e)=>finish(true),true);yes.SetBounds(26,242,310,40);popup.Controls.Add(yes);
      var no=B(cancelCaption,(s,e)=>finish(false));no.SetBounds(popup.Width-160,242,134,40);popup.Controls.Add(no);
      veil.Click+=(s,e)=>finish(false);veil.Disposed+=(s,e)=>done.TrySetResult(false);Controls.Add(veil);veil.BringToFront();layout();return done.Task;
    }
    async Task<bool> OfferStopTun(){
      if(!core.Running||state.Mode!="tun")return true;
      if(connecting||setupCancellation!=null)return false;
      if(!await ConfirmChange("Для проверки нужно временно остановить активные выходы MCRF: "+String.Join(", ",new[]{state.VpnEnabled?"VPN":null,state.WarpEnabled?"WARP":null,state.ByeTubeEnabled?"ByeTube":null}.Where(x=>x!=null))+". Это не означает, что VPN включён вместе с WARP. Настройки и маршруты останутся.","Остановить выходы и продолжить"))return false;
      connecting=true;expectedRunning=false;++connectionGeneration;verifiedThisSession=false;connectedAtUtc=DateTime.MinValue;
      try{await Task.Run(()=>{systemProxy.Restore();core.Stop();});}
      catch(Exception ex){GlassNotice.Show(this,ex.Message,"Не удалось остановить TUN");return false;}
      finally{connecting=false;if(!IsDisposed){blueDashboard.Invalidate();RefreshSettingsConnection();SyncTray();}}
      return !IsDisposed&&!core.Running;
    }
    async Task<bool> OfferStopZapret(){
      if(!zapret.Running)return true;if(zapretCancellation!=null)return false;
      if(!await ConfirmChange("Чтобы изменить конфиг, нужно остановить Zapret. После сохранения вы сможете включить профили снова.","Выключить Zapret и продолжить"))return false;
      await Task.Run(()=>zapret.Stop());blueDashboard.Invalidate();return true;
    }
  }
}
