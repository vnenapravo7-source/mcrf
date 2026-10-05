using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public static class ByeTubeScan {
  public static async Task Run(NetworkCore core,ZapretCheckReport report,CancellationToken token,Action<string> progress,Action<ZapretCheckReport> save,Func<bool,int,string,CancellationToken,Task<ZapretEndpointResult>> networkProbe=null){
   token.ThrowIfCancellationRequested();Directory.CreateDirectory(Path.Combine(core.DataRoot,"byetube-results"));
   var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();Process process=null;VideoProbe video=null;
   Action stop=()=>{if(video!=null){video.Dispose();video=null;}if(process!=null){try{if(!process.HasExited){process.Kill();process.WaitForExit(1500);}}catch{}process.Dispose();process=null;}};
   Func<string,CancellationToken,Task<ZapretEndpointResult>> probe=async(url,ct)=>{
    bool proxy=process!=null;if(networkProbe!=null)return await networkProbe(proxy,port,url,ct);string options=proxy?"--noproxy \"\" --proxy socks5h://127.0.0.1:"+port:"--noproxy \"*\"";
    if(url==DiscordInterfaceProbe.Target)return await DiscordInterfaceProbe.Run(proxy,ct,port);
    if(ServiceBrowserProbe.Supports(url))return await ServiceBrowserProbe.Run(Path.Combine(core.DataRoot,"core"),url,proxy?"socks5://127.0.0.1:"+port:null,ct);
    if(url==VideoProbe.Target||url==VideoProbe.PornhubTarget){if(video==null)video=new VideoProbe(core.CurlExecutable,options);return await video.RunMediaFor(url,ct);}
    string args="--silent --show-error --location --max-redirs 3 --proto =https --proto-redir =https --http1.1 --compressed --dump-header - --header \"Connection: close\" --user-agent \"Mozilla/5.0\" "+options+" --connect-timeout 3 --max-time 5 --cacert "+ZapretRuntime.Quote(Path.Combine(core.DataRoot,"core","curl-ca-bundle.crt"))+" --write-out \"\\nMCRF_HTTP:%{http_code}\" "+ZapretRuntime.Quote(url);
    return await BoundedProbe.Run(core.CurlExecutable,args,url,ct);
   };
   object logLock=new object();Action<string> record=message=>{lock(logLock)File.AppendAllText(Path.Combine(core.DataRoot,"byetube.log"),ClientJournal.Stamp(message)+Environment.NewLine);};
   var strategies=ByeTube.Strategies.Select((s,i)=>new ZapretStrategy{Id="byetube:"+i,Name="ByeTube · "+(i+1),Family="ByeTube",Tcp=s}).ToArray();
   var runner=new ZapretCheckRunner(async(s,c,ct)=>{process=await Task.Run(()=>ByeTube.Start(Path.Combine(core.DataRoot,"core","ciadpi.exe"),c.Tcp,record,ct,port));},stop,probe,record);
   try{await runner.Run(report,strategies,token,progress,save);}finally{stop();}
  }
 }
 public sealed partial class MainForm {
  CancellationTokenSource byetubeCancellation;ZapretCheckReport byetubeReport;
  void AddByeTubeScanControls(Panel box){
   if(byetubeReport==null){try{byetubeReport=ZapretChecks.Load(Path.Combine(store.Root,"byetube-results"));}catch(Exception ex){WriteLog("ByeTube · сохранённые результаты не прочитаны: "+ex.Message);}}
   var title=L("Подбор стратегий",14,true);title.SetBounds(24,342,box.Width-48,30);box.Controls.Add(title);
   var services=ZapretChecks.Services.Where(ZapretChecks.CanCheckZapret).ToArray();var picker=new GlassPicker{Name="byeTubeServices",MultiSelect=true,EmptySelectionText="Выберите сервисы",SelectionCaption="Сервисов"};picker.SetItems(services.Select(s=>s.CheckName));picker.SetChecked(0,true);picker.SetBounds(24,382,box.Width-48,40);box.Controls.Add(picker);
   var status=L("",10,false,Muted);status.AutoSize=false;status.SetBounds(24,486,box.Width-48,60);box.Controls.Add(status);
   var cancel=B("Отменить",(s,e)=>{if(byetubeCancellation!=null)byetubeCancellation.Cancel();});cancel.SetBounds(264,434,140,40);cancel.Visible=false;box.Controls.Add(cancel);
   var results=B("Результаты",(s,e)=>ShowByeTubeResults());results.SetBounds(box.Width-200,434,176,40);results.Enabled=byetubeReport!=null;box.Controls.Add(results);
   var start=B("Начать подбор",async(s,e)=>{if(connecting||byetubeCancellation!=null||setupCancellation!=null||zapretCancellation!=null||warpCancellation!=null||discordVoiceChecking||zapret.Running){Toast("Остановите Zapret и дождитесь текущих операций");return;}var selected=picker.SelectedIndices.Select(i=>services[i]).ToArray();if(selected.Length==0){Toast("Выберите сервисы");return;}if(!await OfferStopTun())return;var cts=new CancellationTokenSource();byetubeCancellation=cts;var button=(Control)s;button.Enabled=picker.Enabled=false;cancel.Visible=true;byetubeReport=new ZapretCheckReport{Settings=new ZapretSettings{ScopeText=String.Join("\n",selected.Select(v=>v.Domains)),TestUrls=String.Join("\n",selected.SelectMany(v=>v.Urls)),MatchMode="addresses",CheckServices=selected.Select(v=>v.Id).ToList()}};results.Enabled=false;
    try{await ByeTubeScan.Run(core,byetubeReport,cts.Token,message=>{if(!status.IsDisposed)status.Text=message;},report=>ZapretChecks.Save(Path.Combine(store.Root,"byetube-results"),report));if(!status.IsDisposed)status.Text="Проверка завершена · "+(byetubeReport.FinishedAt-byetubeReport.StartedAt).TotalSeconds.ToString("0.0")+" с. Откройте результаты и выберите стратегию.";}
    catch(OperationCanceledException){if(!status.IsDisposed)status.Text="Подбор отменён; готовые результаты сохранены.";}catch(Exception ex){if(!IsDisposed)GlassNotice.Show(this,ex.Message,"Подбор ByeTube");}
    finally{byetubeCancellation=null;cts.Dispose();if(!box.IsDisposed){button.Enabled=picker.Enabled=results.Enabled=true;cancel.Visible=false;}}
   },true);start.Name="byeTubeScanStart";start.SetBounds(24,434,224,40);box.Controls.Add(start);box.Disposed+=(s,e)=>{if(byetubeCancellation!=null)byetubeCancellation.Cancel();};
  }
  void ShowByeTubeResults(){
   if(byetubeReport==null)return;var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(1300,ClientSize.Width-40),Math.Min(640,ClientSize.Height-40))};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Add(popup,L("ByeTube · результаты стратегий",18,true),24,18);
   var table=new ZapretResultTable{VisibleServices=ZapretChecks.Selected(byetubeReport.ServiceIds??byetubeReport.Settings.CheckServices),CanApply=byetubeCancellation==null,ActionCaption="Выбрать",Rows=byetubeReport.Rows.ToArray()};table.SetBounds(24,74,popup.Width-48,popup.Height-140);popup.Controls.Add(table);table.Apply+=row=>{if(byetubeCancellation!=null||row.Checking||String.IsNullOrEmpty(row.StrategyId)||!row.StrategyId.StartsWith("byetube:")||!String.IsNullOrEmpty(row.Error))return;int index;if(!Int32.TryParse(row.StrategyId.Substring(8),out index)||index<0||index>=ByeTube.Strategies.Length)return;state.ByeTubeStrategy=ByeTube.Strategies[index];store.Save(state);veil.Dispose();ShowPage("byetube");Toast("Стратегия сохранена. Назначьте сервисы выходу ByeTube и включите его.");};
   var legend=L(byetubeReport.MediaProbeRevision<2?"Сохранён старый видеотест — запустите подбор заново. Нажмите ячейку для подробностей.":"Зелёный — подтверждено · красный — ошибка · жёлтый — не подтверждено. Нажмите ячейку для подробностей.",10,false,Muted);legend.AutoSize=false;legend.SetBounds(24,popup.Height-62,popup.Width-230,48);popup.Controls.Add(legend);var close=B("Закрыть",(s,e)=>veil.Dispose());close.SetBounds(popup.Width-190,popup.Height-52,166,38);popup.Controls.Add(close);Controls.Add(veil);veil.BringToFront();layout();
  }
 }
}
