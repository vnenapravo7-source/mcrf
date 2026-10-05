using System;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Windows.Forms;

namespace SplifyWin {
  internal sealed class LiveCheckView {
    public Control Overlay;
    public void Finish(){if(Overlay!=null&&!Overlay.IsDisposed)Overlay.Dispose();}
  }
  public sealed partial class MainForm {
    LiveCheckView OpenLiveResults(ZapretCheckReport report,string title,CancellationTokenSource cancellation){
      Panel veil=UiTheme.Simple?(Panel)new Panel{Name="mainLiveSection",Location=new Point(234,72),Size=new Size(ClientSize.Width-250,ClientSize.Height-88),Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right,BackColor=Color.FromArgb(12,17,31)}:new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};
      var popup=new GlassPopupPanel{Inline=UiTheme.Simple,Size=new Size(Math.Min(1320,ClientSize.Width-32),Math.Min(640,ClientSize.Height-32))};veil.Controls.Add(popup);
      Action layout=()=>{if(UiTheme.Simple)popup.SetBounds(0,0,veil.Width,veil.Height);else popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);};veil.Resize+=(s,e)=>layout();layout();
      var caption=L(title,18,true);caption.SetBounds(24,20,popup.Width-100,38);caption.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;popup.Controls.Add(caption);
      var table=new ZapretResultTable{CanApply=false};table.SetBounds(24,76,popup.Width-48,popup.Height-158);table.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;popup.Controls.Add(table);
      var status=L("Сейчас идёт проверка. Зелёный — ответ в мс; красный 0 — недоступно; жёлтый — неясно.",10,false,Muted);status.AutoSize=false;status.SetBounds(24,popup.Height-72,popup.Width-210,50);status.Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;popup.Controls.Add(status);
      DateTime warned=DateTime.MinValue;
      Action close=()=>{if(!report.Complete&&!cancellation.IsCancellationRequested){if((DateTime.UtcNow-warned).TotalSeconds>6){warned=DateTime.UtcNow;status.ForeColor=GlassInk.Cyan;status.Text="Проверка будет прервана. Нажмите ещё раз в течение 6 секунд, чтобы закрыть.";return;}cancellation.Cancel();}veil.Dispose();};
      var x=new GlassClose{Location=new Point(popup.Width-65,18)};x.Click+=(s,e)=>close();if(!UiTheme.Simple){popup.Controls.Add(x);veil.Click+=(s,e)=>close();}
      var cancel=B("Отменить",(s,e)=>{cancellation.Cancel();status.Text="Останавливаем проверку…";});cancel.SetBounds(popup.Width-175,popup.Height-65,150,38);cancel.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;popup.Controls.Add(cancel);
      var timer=new System.Windows.Forms.Timer{Interval=150};timer.Tick+=(s,e)=>{if(veil.IsDisposed)return;var selected=report.ServiceIds??report.Settings.CheckServices??ZapretChecks.DefaultServices.ToList();table.VisibleServices=ZapretChecks.Services.Where(v=>selected.Contains(v.Id)).ToArray();table.Rows=report.Rows.ToArray();table.Invalidate();var current=report.Rows.LastOrDefault(r=>r.Checking);if(current!=null){caption.Text=title+" · "+current.Name;table.RevealRow(Array.IndexOf(table.Rows,current));}if(report.StartedAt!=DateTime.MinValue)status.Text=(report.Complete?"Проверка завершена":"Проверка идёт")+" · "+((report.Complete?report.FinishedAt:DateTime.Now)-report.StartedAt).ToString(@"hh\:mm\:ss")+"\nЗелёный — ответ в мс; красный 0 — недоступно; жёлтый — неясно.";cancel.Enabled=!cancellation.IsCancellationRequested;};veil.Disposed+=(s,e)=>timer.Dispose();Controls.Add(veil);veil.BringToFront();layout();timer.Start();return new LiveCheckView{Overlay=veil};
    }
  }
}
