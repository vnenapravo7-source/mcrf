using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed class WardogsMatchTracker {
  readonly HashSet<string> peers=new HashSet<string>();bool logMatch;DateTime candidate=DateTime.MinValue;
  public bool InMatch(DateTime now){return logMatch||(peers.Count>0&&candidate!=DateTime.MinValue&&now-candidate>=TimeSpan.FromSeconds(2));}
  public void Reset(){peers.Clear();logMatch=false;candidate=DateTime.MinValue;}
  public static bool MatchPeer(string ip,int port){IPAddress address;if(!IPAddress.TryParse(ip,out address)||address.AddressFamily!=System.Net.Sockets.AddressFamily.InterNetwork)return false;var bytes=address.GetAddressBytes();return bytes[0]==85&&bytes[1]==236&&bytes[2]>=96&&bytes[2]<=103&&port>=7000&&port<=8000;}
  public void Observe(IEnumerable<Dictionary<string,string>> events,DateTime now){foreach(var item in events){string network,ip,rawPort,kind;int port;if(!item.TryGetValue("network",out network)||network!="udp"||!item.TryGetValue("destinationIP",out ip)||!item.TryGetValue("destinationPort",out rawPort)||!Int32.TryParse(rawPort,out port)||!MatchPeer(ip,port))continue;string key=ip+":"+port;if(item.TryGetValue("event",out kind)&&kind=="closed"){if(peers.Remove(key)&&peers.Count==0){logMatch=false;candidate=DateTime.MinValue;}}else if(peers.Add(key)&&candidate==DateTime.MinValue)candidate=now;}}
  public void LogLine(string line){
   if(Regex.IsMatch(line,@"(?i)LogNet:.*(?:Welcomed by server|Join succeeded)")){logMatch=true;return;}
   if(Regex.IsMatch(line,@"(?i)(?:DestroyNamedNetDriver.*GameNetDriver|LogNet:.*(?:Close|Disconnect).*GameNetDriver|ReturnToMainMenu|Browse:.*(?:MainMenu|Frontend|FrontEnd))"))Reset();
  }
 }
 public sealed class WardogsLogReader {
  readonly Dictionary<string,long> offsets=new Dictionary<string,long>(StringComparer.OrdinalIgnoreCase);
  public string[] Read(DateTime processBorn){var lines=new List<string>();string local=Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);foreach(string folder in new[]{"WardogsClient","Wardogs","WardogsClient-Win64-Shipping"}){string directory=Path.Combine(local,folder,"Saved","Logs");if(!Directory.Exists(directory))continue;foreach(string path in Directory.GetFiles(directory,"*.log").OrderByDescending(File.GetLastWriteTimeUtc).Take(2)){try{if(File.GetLastWriteTimeUtc(path)<processBorn)continue;using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)){long offset;if(!offsets.TryGetValue(path,out offset)||offset>stream.Length)offset=Math.Max(0,stream.Length-65536);stream.Position=offset;using(var reader=new StreamReader(stream,Encoding.UTF8,true,4096,true)){string chunk=reader.ReadToEnd();offsets[path]=stream.Position;lines.AddRange(chunk.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries));}}}catch(IOException){}catch(UnauthorizedAccessException){}}}return lines.ToArray();}
  public void Reset(){offsets.Clear();}
 }
 public sealed partial class MainForm {
  readonly System.Windows.Forms.Timer wardogsPauseTimer=new System.Windows.Forms.Timer{Interval=1000};
  readonly WardogsMatchTracker wardogsMatch=new WardogsMatchTracker();readonly WardogsLogReader wardogsLogs=new WardogsLogReader();
  GameFlowObserver wardogsObserver;Task wardogsObserverCleanup;CancellationTokenSource wardogsPauseCancellation=new CancellationTokenSource();bool wardogsPauseBusy;DateTime wardogsRetryAfter;string wardogsProcessIdentity="";
  void InitializeWardogsAutopause(){zapret.Stopped+=()=>wardogsPauseCancellation.Cancel();wardogsPauseTimer.Tick+=async(s,e)=>await WardogsPauseTick();wardogsPauseTimer.Start();FormClosing+=(s,e)=>{wardogsPauseTimer.Stop();wardogsPauseCancellation.Cancel();if(wardogsObserver!=null)wardogsObserver.Dispose();wardogsPauseTimer.Dispose();};}
  async Task WardogsPauseTick(){
   if(wardogsPauseBusy||IsDisposed||DateTime.UtcNow<wardogsRetryAfter||profileChangeBusy||zapretCancellation!=null||setupCancellation!=null||discordVoiceChecking)return;wardogsPauseBusy=true;
   try{
    if(wardogsObserverCleanup!=null){await wardogsObserverCleanup;wardogsObserverCleanup=null;}
    var eligible=state.Lists.Where(r=>r.Enabled&&r.Target=="zapret"&&r.OriginalEngine&&r.WardogsAutoPause&&state.ZapretProfiles.Any(p=>p.Id==r.ZapretProfileId&&p.Enabled)).Select(r=>r.ZapretProfileId).Distinct().ToArray();
    if(!zapret.Running||eligible.Length==0){if(wardogsObserver!=null){var previous=wardogsObserver;wardogsObserver=null;await Task.Run(()=>previous.Dispose());}wardogsMatch.Reset();wardogsLogs.Reset();wardogsProcessIdentity="";if(zapret.Running&&state.ZapretProfiles.Any(p=>zapret.IsAutoPaused(p.Id)))await zapret.UpdateWardogsPause(ZapretRoutes.Active(state),new string[0],CancellationToken.None);return;}
    if(wardogsPauseCancellation.IsCancellationRequested){wardogsPauseCancellation.Dispose();wardogsPauseCancellation=new CancellationTokenSource();}
    var token=wardogsPauseCancellation.Token;
    if(wardogsObserver==null){var observer=new GameFlowObserver{IncludeClosedEvents=true};try{await Task.Run(()=>{zapret.Prepare();observer.Start(zapret.DirectoryPath,new GamePreset{Name="Wardogs",Processes=new[]{"WardogsClient-Win64-Shipping.exe"}});},token);token.ThrowIfCancellationRequested();if(IsDisposed)throw new OperationCanceledException();wardogsObserver=observer;}catch{observer.Dispose();throw;}}
    DateTime born=DateTime.MaxValue;var identity=new List<string>();foreach(var process in Process.GetProcessesByName("WardogsClient-Win64-Shipping"))using(process){try{var started=process.StartTime.ToUniversalTime();if(started<born)born=started;identity.Add(process.Id+":"+started.Ticks);}catch(InvalidOperationException){}catch(System.ComponentModel.Win32Exception){}}
    string current=String.Join("|",identity.OrderBy(x=>x));if(current!=wardogsProcessIdentity){wardogsMatch.Reset();wardogsLogs.Reset();wardogsProcessIdentity=current;}
    if(identity.Count==0)wardogsMatch.Reset();else{wardogsMatch.Observe(wardogsObserver.Drain(),DateTime.UtcNow);foreach(string line in await Task.Run(()=>wardogsLogs.Read(born),token))wardogsMatch.LogLine(line);}
    if(!wardogsObserver.Running)throw new IOException(wardogsObserver.Error??"Наблюдение Wardogs остановлено");
    bool match=wardogsMatch.InMatch(DateTime.UtcNow);bool wasPaused=eligible.Any(zapret.IsAutoPaused);await zapret.UpdateWardogsPause(ZapretRoutes.Active(state),match?eligible:new string[0],token);
    if(!IsDisposed&&wasPaused!=match){foreach(var label in Controls.Find("zapretStatus",true).OfType<Label>())label.Text=zapret.Status;blueDashboard.Invalidate();}
   }catch(OperationCanceledException){}catch(Exception ex){WriteLog("Wardogs: автопауза не применена · "+ex.Message);wardogsRetryAfter=DateTime.UtcNow.AddSeconds(15);if(wardogsObserver!=null){var broken=wardogsObserver;wardogsObserver=null;wardogsObserverCleanup=Task.Run(()=>broken.Dispose());}}finally{wardogsPauseBusy=false;}
  }
 }
}
