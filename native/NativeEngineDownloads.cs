using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
  sealed class EngineProgress : Control {
    public int Percent;
    public EngineProgress(){SetStyle(ControlStyles.SupportsTransparentBackColor|ControlStyles.UserPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.AllPaintingInWmPaint,true);BackColor=Color.Transparent;}
    protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;using(var path=UiShape.Round(new Rectangle(0,4,Math.Max(1,Width),8),4))using(var brush=new SolidBrush(Color.FromArgb(55,133,164,218)))e.Graphics.FillPath(brush,path);int width=Width*Math.Max(0,Math.Min(100,Percent))/100;if(width>1)using(var path=UiShape.Round(new Rectangle(0,4,width,8),4))using(var brush=new SolidBrush(GlassInk.Cyan))e.Graphics.FillPath(brush,path);}
  }
  public sealed partial class MainForm {
    Panel engineReturnOverlay,engineReturnContent;
    string engineReturnPage;
    string[] requestedEngines=new string[0];
    async Task<bool> OfferEngineDownload(ServerNode[] nodes){
      var missing=nodes.Where(n=>new[]{"openflux","csqtt","wdtt"}.Contains(n.Protocol)&&!core.HasEngine(n)).Select(n=>n.Protocol).Distinct().ToArray();
      if(missing.Length==0)return false;
      if(await ConfirmChange("Для этого подключения нужно скачать: "+String.Join(", ",missing.Select(x=>x.ToUpperInvariant()))+".\nПосле установки вернём вас к добавлению подключения. Введённые данные сохранятся.","Перейти к скачиванию","Нужен дополнительный движок"))OpenRequiredEngines(missing);
      return true;
    }
    void OpenRequiredEngines(string[] ids){
      if(engineReturnOverlay!=null||page=="engines")return;
      requestedEngines=ids;engineReturnOverlay=popupOverlay;engineReturnContent=content;engineReturnPage=page;
      popupOverlay=null;content=null;page="";ShowPage("engines");
    }
    void ReturnFromEngines(){
      if(engineReturnOverlay==null){ShowPage("servers");return;}
      if(popupOverlay!=null)popupOverlay.Dispose();
      popupOverlay=engineReturnOverlay;content=engineReturnContent;page=engineReturnPage;
      engineReturnOverlay=null;engineReturnContent=null;engineReturnPage=null;requestedEngines=new string[0];
      popupOverlay.BringToFront();RefreshServers();if(page=="setup"&&setupServerRefresh!=null)setupServerRefresh();
    }
    void EnginesBlue(){
      var body=Box();body.Dock=DockStyle.Fill;content.Controls.Add(body);
      var intro=L("Дополнительные движки скачиваются один раз. После установки профиль готов к подключению — перезапуск не нужен. Загрузка продолжится, если закрыть это окно.",10,false,Muted);intro.AutoSize=false;intro.SetBounds(22,14,body.Width-44,58);intro.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;body.Controls.Add(intro);
      int row=0;var timer=new Timer{Interval=160};
      foreach(var package in core.Engines.Packages){var p=package;int y=82+row++*112;var name=L(p.Name,15,true);name.SetBounds(22,y,145,26);body.Controls.Add(name);
        var description=L(p.Id=="csqtt"?"TURN/RTP · включает локальный мост WDTT · некоммерческая лицензия":p.Id=="wdtt"?"WireGuard через DTLS/TURN · локальный SOCKS5":"TCP-туннель с переключаемыми транспортами",9,false,Muted);description.AutoSize=false;description.SetBounds(172,y,body.Width-194,28);description.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;body.Controls.Add(description);
        var status=L("",9,false,Muted);status.Name="engineStatus_"+p.Id;status.AutoSize=false;status.SetBounds(22,y+34,body.Width-350,40);status.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;body.Controls.Add(status);
        var bar=new EngineProgress{Name="engineProgress_"+p.Id,Location=new Point(22,y+76),Size=new Size(body.Width-350,16),Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right};body.Controls.Add(bar);
        var download=B("Скачать",async(s,e)=>{if(core.Engines.State(p.Id).Busy){core.Engines.Cancel(p.Id);return;}await DownloadEngine(p.Id);},true);download.Name="engineDownload_"+p.Id;download.SetBounds(body.Width-300,y+38,160,38);download.Anchor=AnchorStyles.Top|AnchorStyles.Right;body.Controls.Add(download);
        var size=L((p.Size/1000000d).ToString("0.0")+" МБ",9,false,Muted);size.SetBounds(body.Width-130,y+46,108,28);size.Anchor=AnchorStyles.Top|AnchorStyles.Right;body.Controls.Add(size);
        Action refresh=()=>{if(body.IsDisposed)return;var current=core.Engines.State(p.Id);bool installed=core.Engines.Installed(p.Id);status.Text=current.Text;status.ForeColor=current.Error?Red:installed?GlassInk.Mint:Muted;download.Text=current.Busy?"Отменить":installed?"Установлен":"Скачать";download.Enabled=current.Busy||!installed;bar.Percent=current.Percent;bar.Visible=current.Busy;bar.Invalidate();};timer.Tick+=(s,e)=>refresh();refresh();
      }
      var back=B(engineReturnOverlay==null?"Назад к серверам":"Назад к подключению",(s,e)=>ReturnFromEngines());back.Name="engineReturn";back.SetBounds(22,body.Height-48,230,38);back.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;body.Controls.Add(back);
      timer.Start();body.Disposed+=(s,e)=>timer.Dispose();
    }
    async Task DownloadEngine(string id){try{var candidate=updates==null?null:updates.Items.FirstOrDefault(x=>x.Id=="engine:"+id);if(candidate!=null&&candidate.Package!=null&&AppUpdates.CompareVersion(candidate.Package.Version,core.Engines.Package(id).Version)>=0)await core.Engines.InstallUpdate(candidate.Package);else await core.Engines.Install(id);if(IsDisposed)return;WriteLog("Движок "+core.Engines.Package(id).Name+" скачан, проверен и установлен");RefreshServers();Toast("Движок установлен — можно подключаться");if(page=="engines"&&engineReturnOverlay!=null&&requestedEngines.All(x=>core.Engines.Installed(x)))ReturnFromEngines();}catch(OperationCanceledException){if(!IsDisposed)WriteLog("Загрузка движка отменена");}catch(Exception ex){if(!IsDisposed){WriteLog("Ошибка загрузки движка: "+ex.Message);Toast(ex.Message);}}}
    async void OfferMissingEngines(){if(!IsDisposed)await OfferEngineDownload(state.Servers.ToArray());}
    protected override void OnFormClosing(FormClosingEventArgs e){base.OnFormClosing(e);if(!e.Cancel){core.Engines.CancelAll();if(engineReturnOverlay!=null){engineReturnOverlay.Dispose();engineReturnOverlay=null;engineReturnContent=null;}}}
  }
}
