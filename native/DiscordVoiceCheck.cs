using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
  public sealed class DiscordVoiceObservation {
    public DateTime CheckedAt{get;set;}public long Outgoing{get;set;}public long Incoming{get;set;}public int FreshPorts{get;set;}
    public bool HearOther{get;set;}public bool OtherHearsYou{get;set;}public string Detail{get;set;}public string Configuration{get;set;}
    public bool AudioConfirmed{get{return Outgoing>0&&Incoming>0&&FreshPorts>0&&HearOther&&OtherHearsYou;}}
  }
  // Passive observer. Never reinjects packets, stores payloads or reads account tokens.
  internal static class DiscordVoiceObserver {
    [DllImport("iphlpapi.dll")]static extern uint GetExtendedUdpTable(IntPtr buffer,ref int size,bool order,int family,int tableClass,uint reserved);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern IntPtr LoadLibrary(string file);
    [DllImport("kernel32.dll",CharSet=CharSet.Ansi)]static extern IntPtr GetProcAddress(IntPtr module,string name);
    [DllImport("kernel32.dll")]static extern bool FreeLibrary(IntPtr module);
    [UnmanagedFunctionPointer(CallingConvention.Winapi,CharSet=CharSet.Ansi)]delegate IntPtr Open(string filter,int layer,short priority,ulong flags);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]delegate bool Receive(IntPtr handle,byte[] packet,uint size,out uint received,byte[] address);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]delegate bool Shutdown(IntPtr handle,int how);
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]delegate bool Close(IntPtr handle);
    static T Function<T>(IntPtr library,string name){var pointer=GetProcAddress(library,name);if(pointer==IntPtr.Zero)throw new IOException("Компонент наблюдения UDP несовместим");return (T)(object)Marshal.GetDelegateForFunctionPointer(pointer,typeof(T));}
    static HashSet<int> Ports(){
      var pids=new HashSet<int>();foreach(var process in Process.GetProcesses())using(process){try{if(process.ProcessName.Equals("Discord",StringComparison.OrdinalIgnoreCase)||process.ProcessName.Equals("DiscordPTB",StringComparison.OrdinalIgnoreCase)||process.ProcessName.Equals("DiscordCanary",StringComparison.OrdinalIgnoreCase))pids.Add(process.Id);}catch{}}
      var owners=new Dictionary<int,HashSet<int>>();foreach(int family in new[]{2,23}){int size=0;uint status=GetExtendedUdpTable(IntPtr.Zero,ref size,false,family,1,0);if(status!=122&&status!=0||size<4||size>4*1024*1024)continue;var memory=Marshal.AllocHGlobal(size);try{if(GetExtendedUdpTable(memory,ref size,false,family,1,0)!=0)continue;int count=Marshal.ReadInt32(memory),rowSize=family==2?12:28,portOffset=family==2?4:20,pidOffset=family==2?8:24;if(count<0||count>(size-4)/rowSize)continue;for(int i=0;i<count;i++){var row=IntPtr.Add(memory,4+i*rowSize);int port=(Marshal.ReadByte(row,portOffset)<<8)|Marshal.ReadByte(row,portOffset+1),pid=Marshal.ReadInt32(row,pidOffset);HashSet<int> set;if(!owners.TryGetValue(port,out set))owners.Add(port,set=new HashSet<int>());set.Add(pid);}}finally{Marshal.FreeHGlobal(memory);}}
      return new HashSet<int>(owners.Where(entry=>entry.Value.Count>0&&entry.Value.All(pids.Contains)).Select(entry=>entry.Key));
    }
    internal static int PacketPort(byte[] packet,int length,bool outbound){if(length<28)return -1;int offset;if((packet[0]>>4)==4){offset=(packet[0]&15)*4;if(offset<20||offset+8>length||packet[9]!=17||(packet[6]&31)!=0||packet[7]!=0)return -1;}else if((packet[0]>>4)==6){if(length<48||packet[6]!=17)return -1;offset=40;}else return -1;offset+=outbound?0:2;return (packet[offset]<<8)|packet[offset+1];}
    internal static async Task<DiscordVoiceObservation> Observe(string libraryPath,CancellationToken token,Action<string> progress){
      var result=new DiscordVoiceObservation{CheckedAt=DateTime.Now};var baseline=Ports();IntPtr module=LoadLibrary(libraryPath);if(module==IntPtr.Zero)throw new IOException("Не удалось загрузить наблюдатель WinDivert");IntPtr handle=new IntPtr(-1);Task reader=null;Shutdown shutdown=null;Close close=null;Exception receiveError=null;HashSet<int> current=new HashSet<int>();var sync=new object();
      try{
        var open=Function<Open>(module,"WinDivertOpen");var receive=Function<Receive>(module,"WinDivertRecv");shutdown=Function<Shutdown>(module,"WinDivertShutdown");close=Function<Close>(module,"WinDivertClose");
        // SNIFF | RECV_ONLY. Independent handle; existing filters/engines are untouched.
        handle=open("udp and !loopback",0,-1000,5);if(handle==new IntPtr(-1))throw new IOException("Windows не разрешил наблюдение UDP ("+Marshal.GetLastWin32Error()+")");
        reader=Task.Run(()=>{try{var packet=new byte[65535];var address=new byte[80];uint received;while(!token.IsCancellationRequested&&receive(handle,packet,(uint)packet.Length,out received,address)){bool outbound=(address[10]&2)!=0;int port=PacketPort(packet,(int)Math.Min(received,(uint)packet.Length),outbound);lock(sync)if(current.Contains(port)){if(outbound)result.Outgoing++;else result.Incoming++;}}}catch(Exception ex){receiveError=ex;}});
        while(!token.IsCancellationRequested){var active=Ports();active.ExceptWith(baseline);lock(sync){current=active;result.FreshPorts=Math.Max(result.FreshPorts,current.Count);progress("Исходящие UDP: "+result.Outgoing+" · входящие: "+result.Incoming+"\nНовые порты Discord: "+result.FreshPorts+". Пакеты не подтверждают слышимость.");}try{await Task.Delay(500,token);}catch(OperationCanceledException){break;}}
        result.Detail=result.Outgoing>0&&result.Incoming>0?"На новых портах Discord наблюдался двусторонний UDP-обмен. Это транспорт, не доказательство передачи звука.":"Двусторонний UDP-обмен на новых портах Discord не подтверждён. Это не доказательство блокировки: возможно, звонок не начат или соединение создано до теста.";
      }finally{if(handle!=new IntPtr(-1)){if(shutdown!=null)shutdown(handle,0);if(reader!=null)reader.GetAwaiter().GetResult();if(close!=null)close(handle);}FreeLibrary(module);}
      if(receiveError!=null)throw new IOException("Наблюдение UDP завершилось с ошибкой",receiveError);return result;
    }
  }
  public sealed partial class MainForm {
    bool discordVoiceChecking;
static void RelaunchDiscord(IEnumerable<string> paths,Action<string> failure=null){if(!paths.Any()&&failure!=null)failure("Discord не найден: откройте установленный клиент вручную.");foreach(string path in paths){bool running=false;foreach(var process in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(path)))using(process){try{if(String.Equals(process.MainModule.FileName,path,StringComparison.OrdinalIgnoreCase)){running=true;break;}}catch{}}if(!running)try{Process.Start(new ProcessStartInfo(path){UseShellExecute=true});}catch(Exception ex){if(failure!=null)failure("Не удалось открыть Discord: "+ex.Message);}}}
    static async Task<string[]> StopDiscordForTrial(CancellationToken token){
var owned=new List<Process>();var paths=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
      try{
        foreach(var process in Process.GetProcesses()){
          bool keep=false;try{string name=process.ProcessName;if(name.Equals("Discord",StringComparison.OrdinalIgnoreCase)||name.Equals("DiscordPTB",StringComparison.OrdinalIgnoreCase)||name.Equals("DiscordCanary",StringComparison.OrdinalIgnoreCase)){
            string path=process.MainModule.FileName;if(File.Exists(path)&&Path.GetFileNameWithoutExtension(path).Equals(name,StringComparison.OrdinalIgnoreCase)){paths.Add(path);owned.Add(process);keep=true;}
          }}catch{}finally{if(!keep)process.Dispose();}
        }
if(paths.Count==0)foreach(string folder in new[]{"Discord","DiscordPTB","DiscordCanary"}){string root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),folder);if(!Directory.Exists(root))continue;var found=Directory.GetDirectories(root,"app-*").OrderByDescending(p=>Directory.GetLastWriteTimeUtc(p)).Select(p=>Path.Combine(p,folder+".exe")).FirstOrDefault(File.Exists);if(found!=null){paths.Add(found);break;}}
foreach(var process in owned)try{process.CloseMainWindow();}catch{}
        await Task.Delay(600,token);
        foreach(var process in owned){token.ThrowIfCancellationRequested();try{if(!process.HasExited){process.Kill();process.WaitForExit(1500);}}catch(InvalidOperationException){}}
        return paths.ToArray();
      }catch{RelaunchDiscord(paths);throw;}finally{foreach(var process in owned)process.Dispose();}
    }
    public static ZapretCheckRow[] VoiceCandidates(ZapretCheckReport report){return report==null?new ZapretCheckRow[0]:ZapretChecks.Rank(report.Rows.Where(r=>!r.Checking&&String.IsNullOrEmpty(r.Error)&&r.Access("discord")==ServiceAccess.Available)).GroupBy(r=>r.StrategyId).Select(g=>g.First()).ToArray();}
public static ZapretSettings VoiceTrialSettings(ZapretCheckReport report,ZapretStrategy strategy){if(report==null||report.Settings==null||strategy==null)throw new ArgumentException("Нет исходной области проверки");var settings=ZapretChecks.CopySettings(report.Settings);settings.Strategy=strategy.Id;settings.Family=strategy.Family;settings.ScopeText=String.IsNullOrEmpty(settings.DiscordScopeText)?ZapretChecks.Services.First(s=>s.Id=="discord").Domains:settings.DiscordScopeText;settings.ScopeRules=null;settings.MatchMode="addresses";settings.DiscordVoiceEnabled=true;var baseline=report.Rows.Where(r=>!String.IsNullOrEmpty(r.StrategyId)&&!r.Checking&&String.IsNullOrEmpty(r.Error)&&r.Access("discord")==ServiceAccess.Available).OrderBy(r=>r.Services.First(s=>s.Id=="discord").Milliseconds).FirstOrDefault();settings.DiscordInterfaceStrategy=!String.IsNullOrEmpty(report.Settings.DiscordInterfaceStrategy)&&report.Rows.Any(r=>r.StrategyId==report.Settings.DiscordInterfaceStrategy&&r.Access("discord")==ServiceAccess.Available)?report.Settings.DiscordInterfaceStrategy:baseline==null?strategy.Id:baseline.StrategyId;return settings;}
    public static string VoiceTrialResult(bool skipped,bool stillApplied,bool hearOther,bool otherHearsYou){return skipped||!stillApplied?"unknown":hearOther&&otherHearsYou?"user-confirmed":"user-unavailable";}
    Task ShowDiscordVoiceCheck(){return RunDiscordVoiceCheck();}
    async Task RunDiscordVoiceCheck(ZapretCheckReport draftReport=null,Action<ZapretCheckRow> chosen=null,CancellationToken wizardToken=default(CancellationToken)){
      if(discordVoiceChecking||connecting||warpCancellation!=null||zapretCancellation!=null||setupCancellation!=null&&draftReport==null){Toast("Дождитесь окончания текущей операции");return;}
      EnsureZapretReport();var report=draftReport??zapretReport;var rows=VoiceCandidates(report);if(rows.Length==0){Toast("Нет стратегий с успешной проверкой «Discord: интерфейс»");return;}
      if(!IsAdministrator()){if(await ConfirmChange("Для последовательной проверки стратегий нужны права Windows.","Получить права и перезапустить","Проверка голоса"))RequestElevation("--zapret");return;}
if(!await OfferStopTun())return;if(String.IsNullOrWhiteSpace(report.Settings.DiscordScopeText)){report.Settings.DiscordScopeText=await DiscordLists.Load(core.DownloadTextAsync);wizardToken.ThrowIfCancellationRequested();}
      bool wasRunning=zapret.Running,touched=false,busy=false;int index=-1;ZapretCheckRow active=null;var results=new List<Dictionary<string,object>>();
      discordVoiceChecking=true;var cancellation=CancellationTokenSource.CreateLinkedTokenSource(wizardToken);var closed=new TaskCompletionSource<bool>();Task operation=Task.FromResult(0);
      var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(840,ClientSize.Width-40),470)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
      Add(popup,L("Discord · стратегии для голоса",18,true),24,20);
      var explanation=L("Переподключайтесь к голосовому каналу после смены стратегии. Отметьте, работает ли голос и какой пинг. Таймера нет.",11,false,Muted);explanation.Visible=!UiTheme.Simple;explanation.AutoSize=false;explanation.SetBounds(24,76,popup.Width-48,90);popup.Controls.Add(explanation);
      var status=L("Готово к проверке: "+rows.Length+" стратегий",11,true,GlassInk.Cyan);status.AutoSize=false;status.SetBounds(24,180,popup.Width-48,75);popup.Controls.Add(status);
      var hear=new CheckBox{Text="Я слышу собеседника",AutoSize=true,Location=new Point(24,278),ForeColor=Ink,BackColor=Color.Transparent,Enabled=false};
      var heard=new CheckBox{Text="Собеседник слышит меня",AutoSize=true,Location=new Point(24,318),ForeColor=Ink,BackColor=Color.Transparent,Enabled=false};
      Button next=null,skip=null,unknown=null;CancellationTokenSource udpCancellation=null;Task<DiscordVoiceObservation> udpTask=null;string udpError=null;string pingQuality="";Button enough=null;
      var restart=new CheckBox{Name="discordVoiceRestart",Text="Перезапускать Discord",AutoSize=true,Location=new Point(24,255),BackColor=Color.Transparent,ForeColor=Ink,Checked=true};popup.Controls.Add(restart);
      Func<Task<DiscordVoiceObservation>> stopUdp=async()=>{if(udpCancellation==null)return null;udpCancellation.Cancel();DiscordVoiceObservation observation=null;try{observation=await udpTask;}catch(Exception ex){udpError=JournalStyle.Redact(ex.Message);}udpCancellation.Dispose();udpCancellation=null;udpTask=null;return observation;};
      Action save=()=>{DiscordVoiceScores.Apply(report,results);if(draftReport!=null)ZapretChecks.Save(Path.Combine(store.Root,"setup-results"),report);else PersistZapretReport(report);File.WriteAllText(Path.Combine(store.Root,"discord-voice-result.json"),new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(new{Version=2,ReportStartedAt=report.StartedAt,Results=results}));};
      Func<bool,Task> advance=async skipped=>{
        busy=true;next.Enabled=skip.Enabled=unknown.Enabled=false;hear.Enabled=heard.Enabled=false;
        try{
          var udp=await stopUdp();if(active!=null){bool applied=zapret.ProfileApplied(null,active.StrategyId);results.Add(new Dictionary<string,object>{{"StrategyId",active.StrategyId},{"Name",active.Name},{"InterfaceStrategyId",VoiceTrialSettings(report,ZapretCatalog.Load(zapret.DirectoryPath).First(s=>s.Id==active.StrategyId)).DiscordInterfaceStrategy},{"CheckedAt",DateTime.Now},{"HearOther",hear.Checked},{"OtherHearsYou",heard.Checked},{"Result",VoiceTrialResult(skipped,applied,hear.Checked,heard.Checked)},{"PingQuality",pingQuality},{"StrategyStillApplied",applied},{"UdpOutgoing",udp==null?0:udp.Outgoing},{"UdpIncoming",udp==null?0:udp.Incoming},{"UdpPorts",udp==null?0:udp.FreshPorts},{"UdpDetail",udp==null?udpError??"Наблюдение не запущено":udp.Detail},{"Basis","Отметка пользователя, не автоматическая RTC-проверка"}});save();active=null;}
          hear.Checked=heard.Checked=false;pingQuality="";if(enough!=null)enough.Enabled=results.Any(r=>Convert.ToString(r["Result"])=="user-confirmed");
          while(++index<rows.Length){
            cancellation.Token.ThrowIfCancellationRequested();var candidate=rows[index];status.Text=(index+1)+" / "+rows.Length+" · запускаем "+candidate.Name;
            var strategy=ZapretCatalog.Load(zapret.DirectoryPath).FirstOrDefault(x=>x.Id==candidate.StrategyId&&x.Available);
            if(strategy==null){results.Add(new Dictionary<string,object>{{"StrategyId",candidate.StrategyId},{"Result","unknown"},{"Detail","Стратегия больше недоступна"}});save();continue;}
var settings=VoiceTrialSettings(report,strategy);if(draftReport!=null){settings.ScopeRules=null;settings.MatchMode="addresses";settings.CheckServices=new List<string>{"discord"};}WriteLog("Голос Discord: интерфейс "+settings.DiscordInterfaceStrategy+" + UDP "+strategy.Id+"; оба списка ds-domains/ds-ip применяются.");
string[] restartPaths=new string[0];try{if(restart.Checked)restartPaths=await StopDiscordForTrial(cancellation.Token);touched=true;await zapret.Start(settings,strategy,cancellation.Token);active=candidate;status.Text=(index+1)+" / "+rows.Length+" · "+candidate.Name+"\nПереподключитесь к каналу и отметьте слышимость.";hear.Enabled=heard.Enabled=true;next.Text="Подключился, пинг низкий (30–60)";skip.Text="Подключение не удалось";udpError=null;udpCancellation=CancellationTokenSource.CreateLinkedTokenSource(cancellation.Token);udpTask=DiscordVoiceObserver.Observe(Path.Combine(zapret.DirectoryPath,"WinDivert.dll"),udpCancellation.Token,message=>{if(!status.IsDisposed&&active!=null)status.Text=(index+1)+" / "+rows.Length+" · "+active.Name+"\n"+message;});RelaunchDiscord(restartPaths,message=>WriteLog(message));restartPaths=new string[0];return;}
            catch(OperationCanceledException){throw;}catch(Exception ex){results.Add(new Dictionary<string,object>{{"StrategyId",candidate.StrategyId},{"Result","unknown"},{"Detail",JournalStyle.Redact(ex.Message)}});save();}finally{RelaunchDiscord(restartPaths);}
          }
          veil.Dispose();
        }catch(OperationCanceledException){}catch(Exception ex){if(!status.IsDisposed)status.Text="Проверка остановлена: "+ex.Message;}
        finally{busy=false;if(!next.IsDisposed)next.Enabled=index<rows.Length&&!cancellation.IsCancellationRequested;if(!skip.IsDisposed)skip.Enabled=active!=null&&!cancellation.IsCancellationRequested;if(!unknown.IsDisposed)unknown.Enabled=active!=null&&!cancellation.IsCancellationRequested;if(!IsDisposed)blueDashboard.Invalidate();}
      };
      next=B("Начать",(s,e)=>{if(!busy){pingQuality="low";hear.Checked=heard.Checked=true;operation=advance(false);}},true);next.Name="discordVoiceNext";next.SetBounds(24,294,380,40);popup.Controls.Add(next);
      skip=B("Не удалось",(s,e)=>{if(!busy){hear.Checked=heard.Checked=false;operation=advance(false);}});((ModernButton)skip).FillColor=Color.FromArgb(170,45,65);skip.Name="discordVoiceSkip";skip.Enabled=false;skip.SetBounds(24,344,270,40);popup.Controls.Add(skip);
      unknown=B("Подключился, пинг высокий",(s,e)=>{if(!busy){pingQuality="high";hear.Checked=heard.Checked=true;operation=advance(false);}});((ModernButton)unknown).FillColor=Color.FromArgb(205,162,40);unknown.ForeColor=Color.FromArgb(25,28,38);unknown.Name="discordVoiceHighPing";unknown.Enabled=false;unknown.SetBounds(416,294,Math.Max(220,popup.Width-440),40);popup.Controls.Add(unknown);
      enough=B("Мне хватит проверок, выбрать из успешных",(s,e)=>{if(!busy&&results.Any(r=>Convert.ToString(r["Result"])=="user-confirmed"))veil.Dispose();});enough.Name="discordVoiceEnough";enough.Enabled=false;enough.SetBounds(306,344,popup.Width-330,40);popup.Controls.Add(enough);
      var cancel=B("Закрыть",(s,e)=>veil.Dispose());cancel.SetBounds(popup.Width-170,398,146,40);popup.Controls.Add(cancel);
      veil.Disposed+=(s,e)=>{cancellation.Cancel();closed.TrySetResult(true);};Controls.Add(veil);veil.BringToFront();layout();var cancelRegistration=wizardToken.Register(()=>{if(!veil.IsDisposed)veil.BeginInvoke(new Action(()=>veil.Dispose()));});
      try{await closed.Task;await operation;await stopUdp();}finally{cancelRegistration.Dispose();cancellation.Cancel();cancellation.Dispose();if(touched)zapret.Stop();}
      if(wasRunning&&touched&&!IsDisposed)try{await StartSavedZapretProfiles(CancellationToken.None);}catch(Exception ex){WriteLog("Не удалось восстановить Zapret после голосовой проверки: "+ex.Message);GlassNotice.Show(this,ex.Message,"Восстановление Zapret");}
      discordVoiceChecking=false;if(!IsDisposed&&!wizardToken.IsCancellationRequested){blueDashboard.Invalidate();SyncTray();if(results.Count>0){var selected=await ShowDiscordVoiceResults(report,results,draftReport!=null);if(chosen!=null)chosen(selected);}else if(chosen!=null)chosen(null);}
    }

  }
}
