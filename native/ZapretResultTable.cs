using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Windows.Forms;
namespace SplifyWin {
  // One owner-painted canvas. Scrolling never copies translucent native cells.
  public sealed class ZapretResultTable : Control {
    ZapretCheckRow[] rows=new ZapretCheckRow[0];string sortKey="";bool sortDescending;
    public ZapretCheckRow[] Rows{get{return rows;}set{rows=value??new ZapretCheckRow[0];SortRows();}}
    static long? Mean(ZapretCheckRow row){var total=row.SuccessfulMilliseconds;int count=row.Services.Where(v=>v.Id!="discord-voice").Sum(v=>v.SuccessfulRequests>0?v.SuccessfulRequests:v.Access==ServiceAccess.Available?2*(ZapretChecks.Services.FirstOrDefault(s=>s.Id==v.Id)==null?1:ZapretChecks.Services.First(s=>s.Id==v.Id).Urls.Length):0);return !total.HasValue||count==0?(long?)null:(long)Math.Round(total.Value/(double)count);}
    long? SortValue(ZapretCheckRow row){if(sortKey=="mean")return Mean(row);if(sortKey=="sum")return row.SuccessfulMilliseconds;var result=row.Services.FirstOrDefault(v=>v.Id==sortKey&&v.Access==ServiceAccess.Available);return result==null?(long?)null:result.Milliseconds;}
    void SortRows(){if(String.IsNullOrEmpty(sortKey))return;rows=rows.OrderBy(r=>String.IsNullOrEmpty(r.StrategyId)?0:r.Checking?1:2).ThenBy(r=>SortValue(r).HasValue?0:1).ThenBy(r=>sortDescending?-(SortValue(r)??0):(SortValue(r)??0)).ThenBy(r=>r.Name).ToArray();}
    public void SortBy(string key){sortDescending=sortKey==key&&!sortDescending;sortKey=key;SortRows();first=0;Invalidate();}
    string Heading(string text,string key){return text+(sortKey==key?(sortDescending?" ↓":" ↑"):"");}
    public ZapretService[] VisibleServices=ZapretChecks.Services;
    public Func<ZapretCheckRow,bool> IsApplied;
    public event Action<ZapretCheckRow> Apply;
    public event Action<ZapretCheckRow,ZapretService> ApplyService;
    public bool CanApply=true;public string ActionCaption="Применить";
    const int Header=44,RowHeight=42,Frozen=482,RailHeight=18;
    int horizontal,first,hover=-1;bool dragX,dragY;int grab;
    readonly ToolTip tip=new ToolTip{AutoPopDelay=16000,InitialDelay=450};string lastTip="";
    public ZapretResultTable(){Name="zapretResultsGrid";SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);BackColor=Color.FromArgb(26,43,80);ForeColor=GlassInk.White;Font=new Font("Segoe UI Semibold",14,FontStyle.Regular,GraphicsUnit.Pixel);TabStop=true;}
    static int ColumnWidth(ZapretService service){return Math.Max(100,service.CheckName.Length*9+28);}
    int Total{get{return VisibleServices.Sum(service=>ColumnWidth(service));}}
    int View{get{return Math.Max(1,Width-Frozen-16);}}
    int Maximum{get{return Math.Max(0,Total-View);}}
    int VisibleRows{get{return Math.Max(1,(Height-Header-RailHeight)/RowHeight);}}
    int MaxFirst{get{return Math.Max(0,Rows.Length-VisibleRows);}}
    public int HorizontalScrollingOffset{get{return horizontal;}set{horizontal=Math.Max(0,Math.Min(Maximum,value));Invalidate();}}
    public int FirstVisibleRow{get{return first;}set{first=Math.Max(0,Math.Min(MaxFirst,value));Invalidate();}}
    public void RevealRow(int index){if(index<first)FirstVisibleRow=index;else if(index>=first+VisibleRows)FirstVisibleRow=index-VisibleRows+1;}
    Rectangle TrackX{get{return new Rectangle(Frozen,Height-13,View,6);}}
    Rectangle ThumbX{get{var track=TrackX;int width=Math.Min(track.Width,Math.Max(40,track.Width*View/Math.Max(1,Total)));return new Rectangle(track.X+(track.Width-width)*horizontal/Math.Max(1,Maximum),track.Y,width,track.Height);}}
    Rectangle TrackY{get{return new Rectangle(Width-11,Header,5,Math.Max(1,Height-Header-RailHeight));}}
    Rectangle ThumbY{get{var track=TrackY;int height=Math.Min(track.Height,Math.Max(25,track.Height*VisibleRows/Math.Max(1,Rows.Length)));return new Rectangle(track.X,track.Y+(track.Height-height)*first/Math.Max(1,MaxFirst),track.Width,height);}}
    protected override void OnResize(EventArgs e){base.OnResize(e);horizontal=Math.Min(horizontal,Maximum);first=Math.Min(first,MaxFirst);if(Width>2&&Height>2)using(var shape=UiShape.Round(ClientRectangle,12)){var old=Region;Region=new Region(shape);if(old!=null)old.Dispose();}}
    static void DrawText(Graphics g,string text,Font font,Rectangle rect,Color color,bool center=false){TextRenderer.DrawText(g,text,font,rect,color,TextFormatFlags.PreserveGraphicsClipping|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis|TextFormatFlags.NoPrefix|(center?TextFormatFlags.HorizontalCenter:TextFormatFlags.Left));}
    static void Pill(Graphics g,Rectangle rect,Color fill){if(rect.Width<2||rect.Height<2)return;using(var path=UiShape.Round(rect,8))using(var brush=new SolidBrush(fill))g.FillPath(brush,path);}
    protected override void OnPaint(PaintEventArgs e){var g=e.Graphics;g.Clear(BackColor);g.SmoothingMode=SmoothingMode.AntiAlias;using(var header=new SolidBrush(Color.FromArgb(31,49,88)))g.FillRectangle(header,0,0,Width,Header);
      var state=g.Save();g.SetClip(new Rectangle(Frozen,0,View,Math.Max(0,Height-RailHeight)));int x=Frozen-horizontal;
      foreach(var service in VisibleServices){int width=ColumnWidth(service);DrawText(g,Heading(service.CheckName,service.Id),Font,new Rectangle(x,0,width,Header),GlassInk.Muted,true);
        for(int i=first;i<Rows.Length&&i<first+VisibleRows;i++){int y=Header+(i-first)*RowHeight;var access=Rows[i].Access(service.Id);Color fill=access==ServiceAccess.Available?Color.FromArgb(28,83,84):access==ServiceAccess.Unavailable?Color.FromArgb(84,45,69):Color.FromArgb(75,66,57);Color ink=access==ServiceAccess.Available?GlassInk.Mint:access==ServiceAccess.Unavailable?Color.FromArgb(255,160,175):Color.FromArgb(255,220,141);var voice=Rows[i].Services.FirstOrDefault(v=>v.Id==service.Id);if(service.Id=="discord-voice"&&access==ServiceAccess.Available&&voice!=null&&voice.PingQuality=="high"){fill=Color.FromArgb(75,66,57);ink=Color.FromArgb(255,220,141);}var rect=new Rectangle(x+7,y+8,width-14,RowHeight-16);Pill(g,rect,fill);var value=Rows[i].Services.FirstOrDefault(v=>v.Id==service.Id);DrawText(g,access==ServiceAccess.Available?(value==null?"—":service.Id=="discord-voice"?(value.PingQuality=="low"?"Низкий пинг":value.PingQuality=="high"?"Высокий пинг":"Подключился"):Math.Max(1,value.Milliseconds)+" мс"):access==ServiceAccess.Unavailable?"0":"—",Font,rect,ink,true);}x+=width;}
      g.Restore(state);state=g.Save();g.SetClip(new Rectangle(0,0,Frozen,Math.Max(0,Height-RailHeight)));using(var fill=new SolidBrush(BackColor))g.FillRectangle(fill,0,Header,Frozen,Math.Max(0,Height-Header));
      DrawText(g,"Конфиг",Font,new Rectangle(12,0,224,Header),GlassInk.Muted);DrawText(g,"Доп.",Font,new Rectangle(234,0,48,Header),GlassInk.Muted,true);DrawText(g,Heading("Среднее, мс","mean"),Font,new Rectangle(286,0,96,Header),GlassInk.Muted,true);DrawText(g,Heading("Сумма, мс","sum"),Font,new Rectangle(382,0,96,Header),GlassInk.Muted,true);
      for(int i=first;i<Rows.Length&&i<first+VisibleRows;i++){int y=Header+(i-first)*RowHeight;var row=Rows[i];if(i==hover||row.Checking)using(var fill=new SolidBrush(Color.FromArgb(41,58,100)))g.FillRectangle(fill,0,y,Frozen,RowHeight);DrawText(g,(row.Checking?"▶ ":"")+row.Name,Font,new Rectangle(12,y,224,RowHeight),GlassInk.White);DrawText(g,row.AdditionalPassed+"/"+VisibleServices.Count(v=>!v.Primary),Font,new Rectangle(234,y,48,RowHeight),GlassInk.Muted,true);using(var pen=new Pen(Color.FromArgb(48,68,111)))g.DrawLine(pen,0,y+RowHeight-1,Frozen,y+RowHeight-1);}
      for(int i=first;i<Rows.Length&&i<first+VisibleRows;i++){int y=Header+(i-first)*RowHeight;var time=Rows[i].SuccessfulMilliseconds;DrawText(g,time.HasValue?time.Value.ToString():"—",Font,new Rectangle(382,y,96,RowHeight),GlassInk.Cyan,true);var mean=Mean(Rows[i]);DrawText(g,mean.HasValue?mean.Value.ToString():"—",Font,new Rectangle(286,y,96,RowHeight),GlassInk.Cyan,true);}
      g.Restore(state);using(var line=new Pen(Color.FromArgb(62,86,135)))g.DrawLine(line,Frozen,0,Frozen,Height-RailHeight);
      if(Maximum>0){Pill(g,TrackX,Color.FromArgb(51,68,108));Pill(g,ThumbX,Color.FromArgb(135,157,207));}if(MaxFirst>0){Pill(g,TrackY,Color.FromArgb(51,68,108));Pill(g,ThumbY,Color.FromArgb(135,157,207));}
    }
    protected override void OnMouseDown(MouseEventArgs e){
      base.OnMouseDown(e);Focus();if(e.Button!=MouseButtons.Left)return;
      if(e.Y<Header){if(e.X>=286&&e.X<382)SortBy("mean");else if(e.X>=382&&e.X<Frozen)SortBy("sum");else if(e.X>=Frozen){int x=Frozen-horizontal;foreach(var service in VisibleServices){int width=ColumnWidth(service);if(e.X>=x&&e.X<x+width){SortBy(service.Id);break;}x+=width;}}return;}
      if(e.Y>=Height-RailHeight&&Maximum>0){dragX=true;Capture=true;grab=ThumbX.Contains(e.Location)?e.X-ThumbX.X:ThumbX.Width/2;Drag(e.Location);return;}
      if(e.X>=Width-16&&MaxFirst>0){dragY=true;Capture=true;grab=ThumbY.Contains(e.Location)?e.Y-ThumbY.Y:ThumbY.Height/2;Drag(e.Location);return;}
      int row=first+(e.Y-Header)/RowHeight;
      if(e.Y<Header||e.Y>=Height-RailHeight||row<0||row>=Rows.Length||row>=first+VisibleRows||e.X<Frozen||e.X>=Width-16)return;
      int left=Frozen-horizontal;foreach(var service in VisibleServices){int width=ColumnWidth(service);
        if(e.X>=left&&e.X<left+width){
          if(CanApply&&!Rows[row].Checking&&!String.IsNullOrEmpty(Rows[row].StrategyId)){if(ApplyService!=null){ApplyService(Rows[row],service);return;}if(Apply!=null){Apply(Rows[row]);return;}}
          var result=Rows[row].Services.FirstOrDefault(v=>v.Id==service.Id);GlassNotice.Show(this,(result==null?"Нет результата проверки.":result.Detail)+"\\n\\nСтратегия: "+Rows[row].Name,service.CheckName+" · результат проверки",MessageBoxButtons.OK,MessageBoxIcon.Information,true);return;
        }left+=width;
      }
    }
    void Drag(Point p){if(dragX)HorizontalScrollingOffset=(p.X-TrackX.X-grab)*Maximum/Math.Max(1,TrackX.Width-ThumbX.Width);if(dragY)FirstVisibleRow=(p.Y-TrackY.Y-grab)*MaxFirst/Math.Max(1,TrackY.Height-ThumbY.Height);}
    protected override void OnMouseMove(MouseEventArgs e){base.OnMouseMove(e);if(dragX||dragY){Drag(e.Location);return;}int row=e.Y<Header||e.Y>=Height-RailHeight?-1:first+(e.Y-Header)/RowHeight;if(row>=Rows.Length||row>=first+VisibleRows)row=-1;if(hover!=row){hover=row;Invalidate();}string description="";if(row>=0){var item=Rows[row];if(e.X<Frozen)description=item.Name+(String.IsNullOrEmpty(item.Error)?"":"\n"+item.Error)+"\nПроверка: "+(item.TotalMilliseconds/1000d).ToString("0.0")+" с";else{int x=Frozen-horizontal;foreach(var service in VisibleServices){int width=ColumnWidth(service);if(e.X>=x&&e.X<x+width){var result=item.Services.FirstOrDefault(s=>s.Id==service.Id);description=service.Name+"\n"+(result==null?"Не проверено":result.Detail);break;}x+=width;}}}if(description!=lastTip){tip.SetToolTip(this,description);lastTip=description;}Cursor=CanApply&&row>=0&&e.X>=Frozen&&e.X<Width-16&&Rows[row].StrategyId!=null?Cursors.Hand:Cursors.Default;}
    protected override void OnMouseUp(MouseEventArgs e){dragX=dragY=false;Capture=false;base.OnMouseUp(e);}
    protected override void OnMouseLeave(EventArgs e){hover=-1;Invalidate();base.OnMouseLeave(e);}
    protected override void OnMouseWheel(MouseEventArgs e){if((ModifierKeys&Keys.Shift)!=0)HorizontalScrollingOffset-=e.Delta/120*120;else FirstVisibleRow-=e.Delta/120*3;}
    protected override bool IsInputKey(Keys keyData){return new[]{Keys.Left,Keys.Right,Keys.Up,Keys.Down,Keys.PageUp,Keys.PageDown,Keys.Home,Keys.End}.Contains(keyData&Keys.KeyCode)||base.IsInputKey(keyData);}
    protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Left)HorizontalScrollingOffset-=100;else if(e.KeyCode==Keys.Right)HorizontalScrollingOffset+=100;else if(e.KeyCode==Keys.Up)FirstVisibleRow--;else if(e.KeyCode==Keys.Down)FirstVisibleRow++;else if(e.KeyCode==Keys.PageDown)FirstVisibleRow+=VisibleRows;else if(e.KeyCode==Keys.PageUp)FirstVisibleRow-=VisibleRows;else if(e.KeyCode==Keys.Home)FirstVisibleRow=0;else if(e.KeyCode==Keys.End)FirstVisibleRow=MaxFirst;else{base.OnKeyDown(e);return;}e.Handled=true;}
    protected override void Dispose(bool disposing){if(disposing)tip.Dispose();base.Dispose(disposing);}
  }
}
