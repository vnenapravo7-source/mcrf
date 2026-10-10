using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace SplifyWin {
  internal sealed class GlassState {
    public bool AdvancedMode,Running, Verified, CoreReady, AutoSelect, Connecting, Switching, TelegramRunning,ZapretRunning,ZapretPicking,WarpRunning,StartupEnabled;
    public string Mode, Server, Protocol, Dns, ProxyAddress,Page;
    public int Latency, ServerCount, ActiveRoutes;
    public DateTime ConnectedAt;
    public ServerNode[] Recent;
    public string[] LogLines;
  }

  // Counts only live connections whose Clash route includes a VPN node.
  // A short connection between samples can be missed, so this is labelled
  // observed rather than claiming to be a billing-grade total.
  internal sealed class VpnTraffic {
    readonly int apiPort;readonly string apiSecret;readonly object sync=new object();
    readonly Queue<float> download=new Queue<float>(), upload=new Queue<float>();readonly Queue<DateTime> sampleTimes=new Queue<DateTime>();
    readonly Dictionary<string,long[]> seen=new Dictionary<string,long[]>();
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    DateTime lastTime=DateTime.MinValue;
    bool available;
    public float DownKbps {get;private set;} public float UpKbps {get;private set;}
    public long DownBytes {get;private set;} public long UpBytes {get;private set;}
    public bool Available {get{return available;}}
    public VpnTraffic(int port,string secret){apiPort=port;apiSecret=secret;}
    public float[] DownSamples {get{lock(sync)return download.ToArray();}}
    public float[] UpSamples {get{lock(sync)return upload.ToArray();}}
    public DateTime[] SampleTimes {get{lock(sync)return sampleTimes.ToArray();}}
    internal static int WindowStart(DateTime[] times,int seconds,DateTime end){var cutoff=end.AddSeconds(-seconds);for(int i=0;i<times.Length;i++)if(times[i]>=cutoff)return i;return times.Length;}
    void KeepSample(float down,float up,DateTime time){download.Enqueue(down);upload.Enqueue(up);sampleTimes.Enqueue(time);while(download.Count>3600)download.Dequeue();while(upload.Count>3600)upload.Dequeue();while(sampleTimes.Count>3600)sampleTimes.Dequeue();}
    public void Reset(){lock(sync){seen.Clear();DownBytes=UpBytes=0;DownKbps=UpKbps=0;lastTime=DateTime.MinValue;available=false;}}
    static long Number(Dictionary<string,object> data,string key){object raw;return data.TryGetValue(key,out raw)?Convert.ToInt64(raw):0;}
    public Action ActiveProbe;
    public void Sample(bool running){if(running&&ActiveProbe!=null)ActiveProbe();SampleCore(running);}
    void SampleCore(bool running){
      if(!running){lock(sync){available=false;DownKbps=UpKbps=0;lastTime=DateTime.MinValue;seen.Clear();if(sampleTimes.Count>0)KeepSample(0,0,DateTime.Now);}return;}
      try{
        var request=(HttpWebRequest)WebRequest.Create("http://127.0.0.1:"+apiPort+"/connections");request.Timeout=350;request.ReadWriteTimeout=350;request.Proxy=null;request.Headers[HttpRequestHeader.Authorization]="Bearer "+apiSecret;
        string body;using(var response=request.GetResponse())using(var reader=new StreamReader(response.GetResponseStream()))body=reader.ReadToEnd();
        lock(sync){var root=json.DeserializeObject(body) as Dictionary<string,object>;if(root==null)throw new FormatException("Нет статистики");
        var connections=root["connections"] as object[];if(connections==null)throw new FormatException("Нет списка соединений");
        long downDelta=0,upDelta=0;var active=new HashSet<string>();
        foreach(var raw in connections){var row=raw as Dictionary<string,object>;if(row==null)continue;var chains=row.ContainsKey("chains")?row["chains"] as object[]:null;if(chains==null||!chains.Any(x=>Convert.ToString(x).StartsWith("node-",StringComparison.Ordinal)))continue;
          string id=Convert.ToString(row["id"]);long down=Number(row,"download"),up=Number(row,"upload");long[] previous;if(!seen.TryGetValue(id,out previous))previous=new long[]{0,0};downDelta+=Math.Max(0,down-previous[0]);upDelta+=Math.Max(0,up-previous[1]);seen[id]=new[]{down,up};active.Add(id);
        }
        foreach(var id in seen.Keys.Where(x=>!active.Contains(x)).ToArray())seen.Remove(id);
        var now=DateTime.UtcNow;var seconds=lastTime==DateTime.MinValue?1:Math.Max(.3,(now-lastTime).TotalSeconds);DownBytes+=downDelta;UpBytes+=upDelta;DownKbps=(float)(downDelta/seconds/1024.0);UpKbps=(float)(upDelta/seconds/1024.0);KeepSample(DownKbps,UpKbps,now.ToLocalTime());lastTime=now;available=true;
        }
      }catch{available=false;DownKbps=UpKbps=0;}
    }
  }

  internal static class ServerIdentity {
    static readonly Dictionary<string,string[]> names=new Dictionary<string,string[]>{
      {"LV",new[]{"латв","latvia","riga"}},{"SE",new[]{"швец","sweden","stockholm"}},{"DE",new[]{"герман","germany","frankfurt","берлин"}},{"NL",new[]{"нидерланд","netherlands","amsterdam","амстердам"}},{"US",new[]{"сша","united states","new york","нью-йорк"}},{"FR",new[]{"франц","france","paris","париж"}},{"GB",new[]{"британ","united kingdom","london","лондон"}},{"JP",new[]{"япон","japan","tokyo","токио"}},{"SG",new[]{"сингапур","singapore"}},{"CA",new[]{"канада","canada","toronto"}},{"AU",new[]{"австрал","australia","sydney"}},{"RU",new[]{"росси","russia","moscow","москва"}}};
    public static string Country(string raw){var value=(raw??"").ToLowerInvariant();foreach(var pair in names)if(pair.Value.Any(value.Contains))return pair.Key;foreach(var pair in names)if(System.Text.RegularExpressions.Regex.IsMatch(raw??"",@"(?<![A-Za-z])"+pair.Key+@"(?![A-Za-z])",System.Text.RegularExpressions.RegexOptions.IgnoreCase))return pair.Key;for(int i=0;i+3<(raw??"").Length;i++){if(Char.IsLowSurrogate(raw[i]))continue;int a=Char.ConvertToUtf32(raw,i);if(a<0x1F1E6||a>0x1F1FF)continue;int b=Char.ConvertToUtf32(raw,i+2);if(b>=0x1F1E6&&b<=0x1F1FF)return new string(new[]{(char)('A'+a-0x1F1E6),(char)('A'+b-0x1F1E6)});}return "";}
    public static string CountryName(string raw){switch(Country(raw)){case "LV":return "Латвия";case "SE":return "Швеция";case "DE":return "Германия";case "NL":return "Нидерланды";case "US":return "США";case "FR":return "Франция";case "GB":return "Великобритания";case "JP":return "Япония";case "SG":return "Сингапур";case "CA":return "Канада";case "AU":return "Австралия";case "RU":return "Россия";default:return "Страна не определена";}}
    public static string ListName(string raw){return System.Text.RegularExpressions.Regex.Replace(raw??"", @"(?:\uD83C[\uDDE6-\uDDFF]){2}", "").Trim();}
    public static string Display(string raw){if(String.IsNullOrWhiteSpace(raw))return "Сервер не выбран";string value=ListName(raw);while(value.Length>0&&(Char.IsSurrogate(value[0])||Char.IsSymbol(value[0])||Char.IsWhiteSpace(value[0])))value=value.Substring(1).TrimStart();if(value.Length>=3&&Country(value.Substring(0,2))!=""&& !Char.IsLetter(value[2]))value=value.Substring(2).TrimStart(' ','·','-','_');value=System.Text.RegularExpressions.Regex.Replace(value,@"^\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?\s+","");value=System.Text.RegularExpressions.Regex.Replace(value,@"\s+(?:WIREGUARD|AWG|VLESS|VMESS|HYSTERIA2?|TROJAN|TUIC|SOCKS|OPENFLUX)\b.*$","",System.Text.RegularExpressions.RegexOptions.IgnoreCase);return value.Length==0?raw:value;}
    public static void Flag(Graphics g,Rectangle r,string code){
      var saved=g.Save();g.SmoothingMode=SmoothingMode.AntiAlias;
      using(var clip=UiShape.Round(r,r.Height/2)){
        g.SetClip(clip);Color red=Color.FromArgb(210,43,63),blue=Color.FromArgb(35,80,170),yellow=Color.FromArgb(255,208,40);
        using(var b=new SolidBrush(Color.White))g.FillRectangle(b,r);
        if(String.IsNullOrEmpty(code)){using(var b=new SolidBrush(Color.FromArgb(50,72,111)))g.FillRectangle(b,r);using(var p=new Pen(Color.FromArgb(200,224,247),1.2f)){g.DrawEllipse(p,r.X+r.Width*.28f,r.Y+1,r.Width*.44f,r.Height-2);g.DrawLine(p,r.Left,r.Y+r.Height*.5f,r.Right,r.Y+r.Height*.5f);}}
        else if(code=="JP"){using(var b=new SolidBrush(red))g.FillEllipse(b,r.X+r.Width*.28f,r.Y+r.Height*.28f,r.Width*.44f,r.Height*.44f);}
        else if(code=="SE"){using(var b=new SolidBrush(blue))g.FillRectangle(b,r);using(var b=new SolidBrush(yellow)){g.FillRectangle(b,r.X+r.Width*.32f,r.Y,r.Width*.16f,r.Height);g.FillRectangle(b,r.X,r.Y+r.Height*.42f,r.Width,r.Height*.16f);}}
        else if(code=="US"){using(var b=new SolidBrush(red))for(int i=0;i<13;i+=2)g.FillRectangle(b,r.X,r.Y+i*r.Height/13f,r.Width,r.Height/13f+1);using(var b=new SolidBrush(blue))g.FillRectangle(b,r.X,r.Y,r.Width*.50f,r.Height*.53f);using(var b=new SolidBrush(Color.White))for(int y=0;y<3;y++)for(int x=0;x<3;x++)g.FillEllipse(b,r.X+3+x*3,r.Y+3+y*3,1.5f,1.5f);}
        else if(code=="GB"||code=="AU"){using(var b=new SolidBrush(blue))g.FillRectangle(b,r);using(var p=new Pen(Color.White,Math.Max(3,r.Height/5))){g.DrawLine(p,r.Left,r.Top,r.Right,r.Bottom);g.DrawLine(p,r.Right,r.Top,r.Left,r.Bottom);}using(var p=new Pen(red,Math.Max(2,r.Height/8))){g.DrawLine(p,r.Left,r.Y+r.Height/2,r.Right,r.Y+r.Height/2);g.DrawLine(p,r.X+r.Width/2,r.Top,r.X+r.Width/2,r.Bottom);}}
        else if(code=="CA"){using(var b=new SolidBrush(red)){g.FillRectangle(b,r.X,r.Y,r.Width*.25f,r.Height);g.FillRectangle(b,r.X+r.Width*.75f,r.Y,r.Width*.25f,r.Height);g.FillPolygon(b,new[]{new PointF(r.X+r.Width*.5f,r.Y+r.Height*.2f),new PointF(r.X+r.Width*.63f,r.Y+r.Height*.47f),new PointF(r.X+r.Width*.54f,r.Y+r.Height*.66f),new PointF(r.X+r.Width*.46f,r.Y+r.Height*.66f),new PointF(r.X+r.Width*.37f,r.Y+r.Height*.47f)});}}
        else if(code=="SG"){using(var b=new SolidBrush(red))g.FillRectangle(b,r.X,r.Y,r.Width,r.Height/2);using(var b=new SolidBrush(Color.White))g.FillEllipse(b,r.X+5,r.Y+3,6,6);using(var b=new SolidBrush(red))g.FillEllipse(b,r.X+7,r.Y+2,6,6);}
        else if(code=="FR"){using(var b=new SolidBrush(blue))g.FillRectangle(b,r.X,r.Y,r.Width/3f,r.Height);using(var b=new SolidBrush(red))g.FillRectangle(b,r.X+r.Width*2/3f,r.Y,r.Width/3f+1,r.Height);}
        else {Color[] colors=code=="DE"?new[]{Color.Black,red,yellow}:code=="LV"?new[]{Color.FromArgb(153,35,60),Color.White,Color.FromArgb(153,35,60)}:code=="RU"?new[]{Color.White,blue,red}:code=="NL"?new[]{red,Color.White,blue}:new[]{Color.FromArgb(63,103,172),Color.FromArgb(158,192,239),Color.FromArgb(63,103,172)};for(int i=0;i<3;i++)using(var b=new SolidBrush(colors[i]))g.FillRectangle(b,r.X,r.Y+i*r.Height/3f,r.Width,r.Height/3f+1);}
        g.Restore(saved);using(var p=new Pen(Color.FromArgb(150,226,243,255)))g.DrawPath(p,clip);
      }
    }
  }

  internal static class GlassInk {
    public static readonly Color White=Color.FromArgb(240,248,255),Muted=Color.FromArgb(207,224,247),Cyan=Color.FromArgb(78,231,252),Purple=Color.FromArgb(183,141,255),Mint=Color.FromArgb(107,250,216);
    public static void SoftGlow(Graphics g,Rectangle area,Color color){using(var path=new GraphicsPath()){path.AddEllipse(area);using(var brush=new PathGradientBrush(path)){brush.CenterColor=Color.FromArgb(42,color);brush.SurroundColors=new[]{Color.FromArgb(0,color)};g.FillPath(brush,path);}}}
    public static void Text(Graphics g,string value,RectangleF rect,float size,Color color,bool bold=false,StringAlignment alignment=StringAlignment.Near){
      g.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
      using(var font=new Font("Segoe UI Semibold",Math.Max(16f,size*4f/3f),bold?FontStyle.Bold:FontStyle.Regular,GraphicsUnit.Pixel))
      using(var brush=new SolidBrush(color))
      using(var format=new StringFormat{Alignment=alignment,LineAlignment=StringAlignment.Center,Trimming=StringTrimming.EllipsisCharacter,FormatFlags=StringFormatFlags.NoWrap})g.DrawString(value??"",font,brush,rect,format);
    }
    public static void WrapText(Graphics g,string value,RectangleF rect,Font font,Color color){g.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;using(var brush=new SolidBrush(color))using(var format=new StringFormat{LineAlignment=StringAlignment.Center,Trimming=StringTrimming.EllipsisCharacter})g.DrawString(value??"",font,brush,rect,format);}
    public static void Card(Graphics g,Rectangle r,Color accent){
      if(r.Width<3||r.Height<3)return;
      using(var path=UiShape.Round(r,22)){
        using(var fill=new LinearGradientBrush(r,Color.FromArgb(132,34,52,91),Color.FromArgb(153,18,26,58),LinearGradientMode.ForwardDiagonal))g.FillPath(fill,path);
        var saved=g.Save();g.SetClip(path);SoftGlow(g,new Rectangle(r.Right-270,r.Bottom-220,360,310),accent);g.Restore(saved);
      }
    }
    public static void Bubble(Graphics g,Rectangle r,Color color){
      using(var path=UiShape.Round(r,Math.Min(16,Math.Max(9,r.Height/4)))){using(var fill=new LinearGradientBrush(r,Color.FromArgb(80,color),Color.FromArgb(35,39,57,122),LinearGradientMode.ForwardDiagonal))g.FillPath(fill,path);}
    }
    public static void Shield(Graphics g,Rectangle r,Color color){
      g.SmoothingMode=SmoothingMode.AntiAlias;
      var points=new[]{new PointF(r.Left+r.Width*.50f,r.Top+r.Height*.06f),new PointF(r.Left+r.Width*.84f,r.Top+r.Height*.20f),new PointF(r.Left+r.Width*.78f,r.Top+r.Height*.66f),new PointF(r.Left+r.Width*.50f,r.Top+r.Height*.94f),new PointF(r.Left+r.Width*.22f,r.Top+r.Height*.66f),new PointF(r.Left+r.Width*.16f,r.Top+r.Height*.20f)};
      using(var outline=new Pen(color,2.4f)){outline.LineJoin=LineJoin.Round;g.DrawPolygon(outline,points);g.DrawLines(outline,new[]{new PointF(r.Left+r.Width*.35f,r.Top+r.Height*.51f),new PointF(r.Left+r.Width*.46f,r.Top+r.Height*.62f),new PointF(r.Left+r.Width*.67f,r.Top+r.Height*.39f)});}
    }
    public static void Ring(Graphics g,Rectangle r,Color color){
      using(var pen=new Pen(Color.FromArgb(55,color),13))g.DrawEllipse(pen,r);
      using(var pen=new Pen(color,3.8f))g.DrawEllipse(pen,r);
      using(var glow=new SolidBrush(Color.FromArgb(25,color)))g.FillEllipse(glow,r);
    }
    public static void Power(Graphics g,Rectangle r,Color color){
      using(var pen=new Pen(color,3.8f)){pen.StartCap=LineCap.Round;pen.EndCap=LineCap.Round;g.DrawArc(pen,r,135,270);g.DrawLine(pen,r.Left+r.Width/2,r.Top-1,r.Left+r.Width/2,r.Top+r.Height*.48f);}
    }
    public static void Signal(Graphics g,Rectangle r,Color color){
      using(var b=new SolidBrush(color))for(int i=0;i<4;i++){int h=6+i*5,w=4,x=r.X+i*7;using(var path=UiShape.Round(new Rectangle(x,r.Bottom-h,w,h),2))g.FillPath(b,path);}
    }
    public static void NetworkBackdrop(Graphics g,Rectangle r){
      var saved=g.Save();g.SetClip(r);g.SmoothingMode=SmoothingMode.AntiAlias;
      float diameter=Math.Min(146,r.Height+38),cx=r.X+r.Width*.61f,cy=r.Y+r.Height*.46f;var globe=new RectangleF(cx-diameter/2,cy-diameter/2,diameter,diameter);
      using(var wire=new Pen(Color.FromArgb(33,128,203,250),1.1f))using(var orbit=new Pen(Color.FromArgb(25,168,156,255),1.2f))using(var node=new SolidBrush(Color.FromArgb(60,111,235,228))){
        g.DrawEllipse(wire,globe);g.DrawEllipse(wire,cx-diameter*.22f,globe.Y,diameter*.44f,diameter);g.DrawEllipse(wire,cx-diameter*.38f,globe.Y,diameter*.76f,diameter);
        g.DrawEllipse(wire,globe.X,cy-diameter*.18f,diameter,diameter*.36f);g.DrawEllipse(wire,globe.X,cy-diameter*.34f,diameter,diameter*.68f);
        g.DrawLine(wire,globe.X,cy,globe.Right,cy);
        g.DrawArc(orbit,cx-diameter*.72f,cy-diameter*.29f,diameter*1.44f,diameter*.58f,12,310);
        for(int i=0;i<3;i++){float x=cx+(i-1)*diameter*.38f,y=cy+(i%2==0?-.18f:.29f)*diameter;g.FillEllipse(node,x-2.5f,y-2.5f,5,5);}
        for(int side=-1;side<=1;side+=2){float x=cx+side*diameter*.89f-18,y=cy-25;g.DrawLine(orbit,cx+side*diameter*.5f,cy,x+(side<0?36:0),y+22);
          for(int row=0;row<3;row++){var rack=new Rectangle((int)x,(int)y+row*17,36,12);using(var path=UiShape.Round(rack,3))g.DrawPath(wire,path);g.FillEllipse(node,x+5,y+row*17+4,3,3);g.DrawLine(wire,x+14,y+row*17+6,x+29,y+row*17+6);}
        }
        using(var lockPen=new Pen(Color.FromArgb(45,124,240,221),1.5f)){g.DrawArc(lockPen,cx-8,cy-13,16,18,180,180);using(var path=UiShape.Round(new Rectangle((int)cx-12,(int)cy-4,24,21),5))g.DrawPath(lockPen,path);g.DrawLine(lockPen,cx,cy+3,cx,cy+10);}
      }
      g.Restore(saved);
    }
    public static void WorldMap(Graphics g,Rectangle r){
      float[][] land={
        new float[]{.03f,.24f,.13f,.10f,.25f,.15f,.30f,.29f,.23f,.36f,.20f,.52f,.11f,.48f,.07f,.37f},
        new float[]{.25f,.54f,.35f,.49f,.43f,.61f,.39f,.76f,.34f,.94f,.27f,.78f},
        new float[]{.42f,.24f,.52f,.14f,.60f,.20f,.61f,.30f,.53f,.36f,.48f,.32f},
        new float[]{.48f,.39f,.61f,.35f,.67f,.49f,.61f,.83f,.54f,.78f,.51f,.57f},
        new float[]{.63f,.22f,.78f,.10f,.96f,.18f,.92f,.44f,.79f,.51f,.70f,.40f},
        new float[]{.78f,.66f,.91f,.65f,.96f,.80f,.82f,.84f}
      };
      using(var dots=new SolidBrush(Color.FromArgb(59,123,186,255)))using(var edge=new Pen(Color.FromArgb(31,142,204,255),1))foreach(var shape in land){var points=new PointF[shape.Length/2];for(int i=0;i<points.Length;i++)points[i]=new PointF(r.X+shape[2*i]*r.Width,r.Y+shape[2*i+1]*r.Height);using(var polygon=new GraphicsPath()){polygon.AddPolygon(points);for(int y=r.Y;y<r.Bottom;y+=7)for(int x=r.X;x<r.Right;x+=7)if(polygon.IsVisible(x,y))g.FillEllipse(dots,x,y,2,2);}g.DrawPolygon(edge,points);}
    }
    public static void Chart(Graphics g,Rectangle r,float[] down,float[] up){
      if(r.Width<4||r.Height<4)return;
      var save=g.Save();using(var clip=UiShape.Round(r,14)){g.SmoothingMode=SmoothingMode.AntiAlias;g.SetClip(clip,CombineMode.Intersect);using(var shade=new SolidBrush(Color.FromArgb(52,4,19,54)))g.FillPath(shade,clip);}
      using(var grid=new Pen(Color.FromArgb(58,146,186,228),1))for(int i=0;i<4;i++)g.DrawLine(grid,r.X,r.Y+i*r.Height/3,r.Right,r.Y+i*r.Height/3);
      using(var grid=new Pen(Color.FromArgb(30,146,186,228),1))for(int i=0;i<7;i++)g.DrawLine(grid,r.X+i*r.Width/6,r.Y,r.X+i*r.Width/6,r.Bottom);
      float max=Math.Max(1,down.Concat(up).DefaultIfEmpty(0).Max()*1.2f);
      Plot(g,r,down,max,Cyan);Plot(g,r,up,max,Purple);
      g.Restore(save);
    }
    static void Plot(Graphics g,Rectangle r,float[] values,float max,Color color){
      if(values.Length<2)return;
      var pts=new PointF[values.Length];for(int i=0;i<pts.Length;i++)pts[i]=new PointF(r.Left+(float)i/(Math.Max(1,pts.Length-1))*r.Width,r.Bottom-Math.Max(0,values[i])/max*r.Height);
      using(var curve=new GraphicsPath()){
        for(int i=0;i<pts.Length-1;i++){
          float dx=(pts[i+1].X-pts[i].X)/3,low=Math.Min(pts[i].Y,pts[i+1].Y),high=Math.Max(pts[i].Y,pts[i+1].Y);
          float slope1=i==0?0:(pts[i+1].Y-pts[i-1].Y)/6,slope2=i+2>=pts.Length?0:(pts[i+2].Y-pts[i].Y)/6;
          curve.AddBezier(pts[i],new PointF(pts[i].X+dx,Math.Max(low,Math.Min(high,pts[i].Y+slope1))),new PointF(pts[i+1].X-dx,Math.Max(low,Math.Min(high,pts[i+1].Y-slope2))),pts[i+1]);
        }
        using(var area=(GraphicsPath)curve.Clone()){area.AddLine(pts[pts.Length-1],new PointF(r.Right,r.Bottom));area.AddLine(new PointF(r.Right,r.Bottom),new PointF(r.Left,r.Bottom));area.CloseFigure();using(var fill=new LinearGradientBrush(r,Color.FromArgb(70,color),Color.FromArgb(3,color),LinearGradientMode.Vertical))g.FillPath(fill,area);}
        using(var halo=new Pen(Color.FromArgb(47,color),7)){halo.LineJoin=LineJoin.Round;halo.StartCap=halo.EndCap=LineCap.Round;g.DrawPath(halo,curve);}
        using(var line=new Pen(color,2.4f)){line.LineJoin=LineJoin.Round;line.StartCap=line.EndCap=LineCap.Round;g.DrawPath(line,curve);}
      }
    }
    public static void TimeAxis(Graphics g,Rectangle r,DateTime[] times){
      if(times.Length==0){Text(g,"История появится после подключения",r,9,Muted);return;}
      for(int i=0;i<4;i++){int index=(times.Length-1)*i/3;float x=r.Left+(r.Width-90)*i/3f;Text(g,times[index].ToString("HH:mm:ss"),new RectangleF(x,r.Y,90,r.Height),9,Muted,false,i==3?StringAlignment.Far:StringAlignment.Near);}
    }
    public static string Rate(float kbps){return kbps>=1024?(kbps/1024f).ToString("0.0")+" МБ/с":kbps.ToString("0.0")+" КБ/с";}
    public static string Bytes(long value){return value>=1073741824?(value/1073741824d).ToString("0.00")+" ГБ":(value/1048576d).ToString("0.0")+" МБ";}
  }

  internal sealed partial class BlueGlassDashboard : Control {
    readonly Func<GlassState> state;readonly Action<string> action;readonly VpnTraffic traffic;readonly Image wallpaper;
    readonly Timer refresh=new Timer{Interval=1000};readonly Dictionary<string,Rectangle> areas=new Dictionary<string,Rectangle>();string hover="";bool sampling;int sizingEdge;Point sizingOrigin;Rectangle sizingBounds;bool resized;readonly ToolTip tips=new ToolTip{InitialDelay=500,ReshowDelay=100,AutoPopDelay=12000};
    public BlueGlassDashboard(Func<GlassState> stateProvider,VpnTraffic trafficMonitor,Action<string> onAction){state=stateProvider;traffic=trafficMonitor;action=onAction;DoubleBuffered=true;TabStop=true;BackColor=Color.FromArgb(8,22,55);Dock=DockStyle.Fill;using(var stream=typeof(BlueGlassDashboard).Assembly.GetManifestResourceStream("SplifyWin.UI.BlueGlassBackdrop.png"))if(stream!=null)using(var original=Image.FromStream(stream))wallpaper=new Bitmap(original,new Size(Math.Max(1,original.Width/32),Math.Max(1,original.Height/32)));refresh.Tick+=async(s,e)=>{if(sampling)return;sampling=true;bool running=state().Running;try{await System.Threading.Tasks.Task.Run(()=>traffic.Sample(running));}finally{sampling=false;if(!IsDisposed)Invalidate();}};refresh.Start();Disposed+=(s,e)=>{refresh.Dispose();tips.Dispose();if(wallpaper!=null)wallpaper.Dispose();};}
    Bitmap backdropCache;
    internal void RefreshTheme(){ClearBackdrop();Invalidate(true);}
    void ClearBackdrop(){if(backdropCache!=null){backdropCache.Dispose();backdropCache=null;}}
    protected override void OnResize(EventArgs e){ClearBackdrop();base.OnResize(e);}
    protected override void Dispose(bool disposing){if(disposing){ClearBackdrop();if(neonGlobe!=null)neonGlobe.Dispose();}base.Dispose(disposing);}
    protected override void OnPaintBackground(PaintEventArgs e){
      if(Width<2||Height<2)return;if(UiTheme.Simple){e.Graphics.Clear(Color.FromArgb(12,16,28));return;}
      if(backdropCache==null){backdropCache=new Bitmap(Width,Height);using(var g=Graphics.FromImage(backdropCache)){DrawBackground(g);LayoutCards();foreach(var pair in areas)GlassInk.Card(g,pair.Value,pair.Key=="traffic"?GlassInk.Cyan:GlassInk.Purple);var a=areas["servers"];GlassInk.NetworkBackdrop(g,new Rectangle(a.X+180,a.Y+14,Math.Max(120,a.Width-385),116));}}
      e.Graphics.DrawImageUnscaled(backdropCache,0,0);
    }
    void DrawBackground(Graphics g){g.SmoothingMode=SmoothingMode.AntiAlias;if(wallpaper!=null){g.InterpolationMode=InterpolationMode.HighQualityBicubic;g.DrawImage(wallpaper,ClientRectangle);using(var veil=new SolidBrush(Color.FromArgb(105,92,133,190)))g.FillRectangle(veil,ClientRectangle);GlassInk.SoftGlow(g,new Rectangle(-330,-190,930,670),Color.FromArgb(103,183,255));GlassInk.SoftGlow(g,new Rectangle(Width-630,Height-500,930,720),Color.FromArgb(143,114,255));return;}using(var bg=new LinearGradientBrush(ClientRectangle,Color.FromArgb(4,35,96),Color.FromArgb(13,11,56),LinearGradientMode.ForwardDiagonal))g.FillRectangle(bg,ClientRectangle);
      GlassInk.SoftGlow(g,new Rectangle(Width-610,-300,970,700),Color.FromArgb(10,108,255));
      GlassInk.SoftGlow(g,new Rectangle(-350,Height-480,820,640),Color.FromArgb(26,211,252));
      GlassInk.SoftGlow(g,new Rectangle(Width-590,Height-430,790,750),Color.FromArgb(127,48,255));
    }
    void LayoutCards(){
      areas.Clear();areas["repository"]=new Rectangle(24,16,50,50);int pad=30,gap=18,width=Width-2*pad,col=(width-2*gap)/3,y=82;
      int space=Height-y-pad-2*gap,top=Math.Max(206,(int)(space*.36)),bottom=Math.Max(112,(int)(space*.21)),middle=space-top-bottom;
      if(!state().AdvancedMode){int mainHeight=Math.Max(390,(int)((Height-y-pad-gap)*.68)),side=(width-col-2*gap)/2;areas["connection"]=new Rectangle(pad,y,col,mainHeight);areas["servers"]=new Rectangle(pad+col+gap,y,side,mainHeight);areas["logs"]=new Rectangle(pad+col+side+2*gap,y,width-col-side-2*gap,mainHeight);int tileY=y+mainHeight+gap,basicTileGap=12,basicTileWidth=(width-3*basicTileGap)/4;string[] basicTiles={"constructor","warp","zapret","setup"};for(int i=0;i<4;i++)areas[basicTiles[i]]=new Rectangle(pad+i*(basicTileWidth+basicTileGap),tileY,i==3?width-3*(basicTileWidth+basicTileGap):basicTileWidth,Math.Max(100,Height-tileY-pad));return;}
      areas["connection"]=new Rectangle(pad,y,col,top);
      areas["servers"]=new Rectangle(pad+col+gap,y,width-col-gap,top);
      y+=top+gap;int midHalf=(width-gap)/2;if(state().AdvancedMode){areas["traffic"]=new Rectangle(pad,y,midHalf,middle);areas["logs"]=new Rectangle(pad+midHalf+gap,y,width-midHalf-gap,middle);}else{areas["module-switches"]=new Rectangle(pad,y,midHalf,middle);areas["logs"]=new Rectangle(pad+midHalf+gap,y,width-midHalf-gap,middle);}
      y+=middle+gap;int tileGap=12;string[] tiles=state().AdvancedMode?new[]{"routes","dns","modules","zapret","strategy-workshop"}:new[]{"constructor","warp","zapret","tgws"};int tileWidth=(width-(tiles.Length-1)*tileGap)/tiles.Length;for(int i=0;i<tiles.Length;i++)areas[tiles[i]]=new Rectangle(pad+i*(tileWidth+tileGap),y,i==tiles.Length-1?width-(tiles.Length-1)*(tileWidth+tileGap):tileWidth,bottom);
    }
    protected override void OnPaint(PaintEventArgs e){
      base.OnPaint(e);if(UiTheme.Simple){DrawSimple(e.Graphics);return;}var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;g.TextRenderingHint=System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;LayoutCards();var s=state();
      if(hover=="repository")using(var path=UiShape.Round(areas["repository"],12))using(var brush=new SolidBrush(Color.FromArgb(22,GlassInk.Cyan)))g.FillPath(brush,path);
      BrandArt.DrawPlane(g,new Rectangle(32,24,34,34),GlassInk.Cyan);
      GlassInk.Text(g,AppBrand.Name,new RectangleF(80,12,165,35),22,GlassInk.White,true);
      if(Width>=1100)GlassInk.Text(g,AppBrand.FullName,new RectangleF(80,48,Width-300,22),10,GlassInk.Muted);
      bool compactHeader=Width<1100;int chromeX=Width-150;areas["minimize"]=new Rectangle(chromeX,21,36,36);areas["maximize"]=new Rectangle(chromeX+44,21,36,36);areas["close"]=new Rectangle(chromeX+88,21,36,36);
areas["reset-settings"]=new Rectangle(chromeX-(compactHeader?466:606),21,compactHeader?154:170,36);using(var path=UiShape.Round(areas["reset-settings"],16))using(var brush=new SolidBrush(Color.FromArgb(42,106,147,221)))g.FillPath(brush,path);GlassInk.Text(g,"Удалить настройки",areas["reset-settings"],10,GlassInk.White,false,StringAlignment.Center);

areas["startup"]=new Rectangle(chromeX-(compactHeader?300:424),21,compactHeader?128:180,36);using(var path=UiShape.Round(areas["startup"],16))using(var brush=new SolidBrush(Color.FromArgb(42,106,147,221)))g.FillPath(brush,path);GlassInk.Text(g,"Автозапуск: "+(s.StartupEnabled?"вкл":"выкл"),areas["startup"],10,s.StartupEnabled?GlassInk.Mint:GlassInk.White,false,StringAlignment.Center);
areas["design"]=new Rectangle(chromeX-(compactHeader?164:230),21,compactHeader?152:216,36);using(var path=UiShape.Round(areas["design"],16))using(var brush=new SolidBrush(Color.FromArgb(42,106,147,221)))g.FillPath(brush,path);GlassInk.Text(g,compactHeader?"Расширенный режим":"Расширенный интерфейс",areas["design"],10,GlassInk.White,false,StringAlignment.Center);
      using(var pen=new Pen(GlassInk.Muted,1.6f)){pen.StartCap=LineCap.Round;pen.EndCap=LineCap.Round;foreach(var key in new[]{"minimize","maximize","close"}){var r=areas[key];float x=r.X+r.Width/2f,y=r.Y+r.Height/2f;if(key=="minimize")g.DrawLine(pen,x-7,y,x+7,y);else if(key=="maximize")g.DrawRectangle(pen,x-5,y-5,10,10);else{g.DrawLine(pen,x-5,y-5,x+5,y+5);g.DrawLine(pen,x+5,y-5,x-5,y+5);}}}
      var a=areas["connection"];bool stackedModules=a.Width<350;int cx=a.X+a.Width/2,ring=Math.Min(92,a.Height-(stackedModules?166:158)),cy=a.Y+24+ring/2;var statusColor=s.Running?GlassInk.Mint:GlassInk.Purple;
      GlassInk.Ring(g,new Rectangle(cx-ring/2,cy-ring/2,ring,ring),statusColor);GlassInk.Power(g,new Rectangle(cx-19,cy-19,38,38),statusColor);
      int textY=cy+ring/2+12;
      GlassInk.Text(g,s.Connecting?(s.Switching?"Переключаем…":"Запускаем…"):s.Mode=="proxy"?(s.Running?"Прокси запущен":"Запустить прокси"):s.Running?"VPN включён":"Подключить VPN",new RectangleF(a.X+18,textY,a.Width-36,30),17,GlassInk.White,true,StringAlignment.Center);
      GlassInk.Text(g,s.Mode=="proxy"?"HTTP / SOCKS5 · "+s.ProxyAddress:s.Running?(s.Verified?"Соединение проверено":"Нажмите, чтобы отключить"):"Ваш трафик пока не защищён",new RectangleF(a.X+18,textY+30,a.Width-36,22),9,GlassInk.Muted,false,StringAlignment.Center);
      string duration=s.Running&&s.ConnectedAt!=DateTime.MinValue?DateTimeDuration(DateTime.UtcNow-s.ConnectedAt):"00:00:00";
      GlassInk.Text(g,"Время сессии  ·  "+duration,new RectangleF(a.X+18,a.Bottom-(stackedModules?29:34),a.Width-36,22),10,s.Running?GlassInk.Mint:GlassInk.Muted,false,StringAlignment.Center);
      if(!s.AdvancedMode){int half=(a.Width-44)/2,buttonY=textY+70;ModuleSwitch(g,"toggle-zapret","Zapret",new Rectangle(a.X+18,buttonY,half,42),s.ZapretRunning,s.ZapretPicking);ModuleSwitch(g,"toggle-warp","WARP",new Rectangle(a.X+26+half,buttonY,half,42),s.WarpRunning,s.Connecting);ModuleSwitch(g,"toggle-tg","Telegram прокси",new Rectangle(a.X+18,buttonY+64,a.Width-36,42),s.TelegramRunning,false);NeonButton(g,"telegram-copy","Копировать TG",new Rectangle(a.X+18,buttonY+118,half,36),GlassInk.Cyan,false);NeonButton(g,"telegram-open","Подключить TG",new Rectangle(a.X+26+half,buttonY+118,half,36),GlassInk.Cyan,false);}
      a=areas["servers"];int split=s.AdvancedMode?a.Right-270:a.Right-18;
      ServerIdentity.Flag(g,new Rectangle(a.X+28,a.Y+28,34,34),ServerIdentity.Country(s.Server));
      GlassInk.Text(g,"Текущий сервер"+(s.Protocol=="—"?"":" · "+s.Protocol),new RectangleF(a.X+80,a.Y+22,split-a.X-106,24),9,GlassInk.Muted);
      GlassInk.Text(g,ServerIdentity.Display(s.Server),new RectangleF(a.X+80,a.Y+51,split-a.X-106,34),18,GlassInk.White,true);
      GlassInk.Text(g,s.AutoSelect?"● Автовыбор включён":"Автовыбор выключен",s.AdvancedMode?ServerStatusBounds(a,93,24):new RectangleF(a.X+22,a.Y+167,a.Width-44,24),10,s.AutoSelect?GlassInk.Mint:GlassInk.Muted,true,StringAlignment.Center);
      GlassInk.Text(g,"Задержка",s.AdvancedMode?ServerStatusBounds(a,20,24):new RectangleF(a.X+22,a.Y+103,a.Width-44,24),9,GlassInk.Muted,false,StringAlignment.Center);
      GlassInk.Text(g,s.Latency>=0?s.Latency+" мс":"—",s.AdvancedMode?ServerStatusBounds(a,48,38):new RectangleF(a.X+22,a.Y+128,a.Width-44,38),23,GlassInk.Mint,true,StringAlignment.Center);
      int quickRows=s.AdvancedMode?(a.Height>=320?2:1):Math.Min(3,Math.Max(1,(a.Height-242)/44)),recentY=a.Bottom-(quickRows*44+10),chipW=s.AdvancedMode?(a.Width-72)/3:a.Width-56;
      GlassInk.Text(g,"Самые низкие по пингу · нажмите для переключения",new RectangleF(a.X+28,recentY-28,a.Width-56,24),9,GlassInk.Muted);
      if(s.Recent==null||s.Recent.Length==0)GlassInk.Text(g,"Проверьте задержку в списке серверов",new RectangleF(a.X+28,recentY,a.Width-56,36),9,GlassInk.Muted);
      if(s.Recent!=null)for(int i=0;i<Math.Min(quickRows*(s.AdvancedMode?3:1),s.Recent.Length);i++){var node=s.Recent[i];var row=new Rectangle(a.X+28+(s.AdvancedMode?i%3:0)*(chipW+8),recentY+(s.AdvancedMode?i/3:i)*44,chipW,42);areas["recent:"+node.Id]=row;
        if(hover=="recent:"+node.Id)using(var path=UiShape.Round(row,8))using(var brush=new SolidBrush(Color.FromArgb(55,144,137,213)))g.FillPath(brush,path);
        ServerIdentity.Flag(g,new Rectangle(row.X+4,row.Y+9,20,20),ServerIdentity.Country(node.Name));GlassInk.Text(g,ServerIdentity.Display(node.Name),new RectangleF(row.X+31,row.Y,row.Width-35,21),9,GlassInk.White);GlassInk.Text(g,node.TransportLabel+" · "+node.Latency+" мс",new RectangleF(row.X+31,row.Y+21,row.Width-35,21),9,GlassInk.Muted);
      }
      if(s.AdvancedMode){a=areas["traffic"];GlassInk.Text(g,"Трафик через VPN",new RectangleF(a.X+22,a.Y+17,a.Width-126,28),12,GlassInk.White,true);GlassInk.Text(g,traffic.Available?GlassInk.Bytes(traffic.DownBytes+traffic.UpBytes):"—",new RectangleF(a.Right-113,a.Y+18,90,26),12,GlassInk.White,true,StringAlignment.Far);
      var mainDown=traffic.DownSamples;var mainUp=traffic.UpSamples;var stamps=traffic.SampleTimes;int tail=VpnTraffic.WindowStart(stamps,120,DateTime.Now);var graph=new Rectangle(a.X+22,a.Y+60,a.Width-44,Math.Max(40,a.Height-145));GlassInk.Chart(g,graph,mainDown.Skip(tail).ToArray(),mainUp.Skip(tail).ToArray());GlassInk.TimeAxis(g,new Rectangle(graph.X,graph.Bottom+7,graph.Width,24),stamps.Skip(tail).ToArray());
      GlassInk.Text(g,traffic.Available?"↓ "+GlassInk.Rate(traffic.DownKbps):s.Running?"Ожидаем статистику":"VPN выключен",new RectangleF(a.X+22,a.Bottom-42,a.Width/2-18,24),9,GlassInk.Cyan,true);
      GlassInk.Text(g,traffic.Available?"↑ "+GlassInk.Rate(traffic.UpKbps):"",new RectangleF(a.X+a.Width/2,a.Bottom-42,a.Width/2-22,24),9,GlassInk.Purple,true);
      }
      a=areas["logs"];GlassInk.Text(g,"Журнал",new RectangleF(a.X+22,a.Y+17,a.Width-44,30),14,GlassInk.White,true);
      var lines=s.LogLines??new string[0];int rowH=36,capacity=LogRowsForHeight(a.Height);
      if(lines.Length==0)GlassInk.Text(g,"Здесь появятся события подключения",new RectangleF(a.X+22,a.Y+61,a.Width-44,30),10,GlassInk.Muted);
      for(int i=0;i<Math.Min(capacity,lines.Length);i++){int ly=a.Y+59+i*rowH;string line=lines[i];int dateEnd=line.IndexOf("  ");if(dateEnd>=0)line=line.Substring(dateEnd+2);
        var tone=JournalStyle.Tone(line);Color color=tone==JournalTone.Error?Color.FromArgb(255,140,161):tone==JournalTone.Warning?Color.FromArgb(255,201,114):tone==JournalTone.Success?GlassInk.Mint:GlassInk.Cyan;
        using(var b=new SolidBrush(color))g.FillEllipse(b,a.X+22,ly+10,5,5);GlassInk.Text(g,line,new RectangleF(a.X+36,ly,a.Width-58,26),9,tone==JournalTone.Info?GlassInk.White:color);
      }
      if(s.AdvancedMode)ModuleTile(g,"routes","Маршруты",s.ActiveRoutes+" списков","↗",GlassInk.Purple);else ModuleTile(g,"constructor","Конструктор","Сервисы и выходы","↗",GlassInk.Purple);
      if(s.AdvancedMode)ModuleTile(g,"dns","DNS / сеть",areas["dns"].Width<210?s.Dns.Split('·')[0].Trim():s.Dns,"✦",GlassInk.Cyan);
      if(s.AdvancedMode)ModuleTile(g,"modules","WARP · Telegram","Настроить выходы","W",GlassInk.Mint);else ModuleTile(g,"warp","WARP","Настроить WARP","W",GlassInk.Mint);
      ModuleTile(g,"zapret","Zapret",s.ZapretRunning?"Включён":"Выключен","Z",s.ZapretRunning?GlassInk.Mint:GlassInk.Purple);
      if(s.AdvancedMode)ModuleTile(g,"strategy-workshop","Тест - создать стратегию","Мастер создания","↗",GlassInk.Cyan);else ModuleTile(g,"setup","Мастер настройки","Настроить по шагам","✦",GlassInk.Cyan);
    }
    void ModuleTile(Graphics g,string key,string title,string subtitle,string glyph,Color accent){
      var r=areas[key];if(hover==key)using(var path=UiShape.Round(r,22))using(var brush=new SolidBrush(Color.FromArgb(22,accent)))g.FillPath(brush,path);
      int cx=r.X+r.Width/2,top=r.Y+(r.Height-94)/2;GlassInk.Bubble(g,new Rectangle(cx-18,top,36,36),accent);
      GlassInk.Text(g,glyph,new RectangleF(cx-17,top,34,36),16,GlassInk.White,true,StringAlignment.Center);
      GlassInk.Text(g,title,new RectangleF(r.X+12,top+42,r.Width-24,25),12,GlassInk.White,true,StringAlignment.Center);
      GlassInk.Text(g,subtitle,new RectangleF(r.X+12,top+70,r.Width-24,24),9,GlassInk.Muted,false,StringAlignment.Center);
    }
    internal Bitmap BlurSnapshot(){var bitmap=new Bitmap(Math.Max(1,Width/32),Math.Max(1,Height/32));using(var graphics=Graphics.FromImage(bitmap)){graphics.ScaleTransform((float)bitmap.Width/Width,(float)bitmap.Height/Height);var args=new PaintEventArgs(graphics,ClientRectangle);OnPaintBackground(args);OnPaint(args);}return bitmap;}
    static string DateTimeDuration(TimeSpan t){if(t<TimeSpan.Zero)t=TimeSpan.Zero;return ((int)t.TotalHours).ToString("00")+":"+t.Minutes.ToString("00")+":"+t.Seconds.ToString("00");}
    internal static RectangleF ServerStatusBounds(Rectangle card,int y,int height){return new RectangleF(card.Right-270,card.Y+y,240,height);}
    void ModuleSwitch(Graphics g,string key,string label,Rectangle area,bool enabled,bool busy){
      NeonButton(g,key,label+(busy?" · …":enabled?" · вкл":" · выкл"),area,key=="toggle-zapret"?GlassInk.Purple:key=="toggle-warp"?GlassInk.Mint:GlassInk.Cyan,enabled);
    }
    void NeonButton(Graphics g,string key,string label,Rectangle area,Color accent,bool enabled){areas[key]=area;using(var path=UiShape.Round(area,14))using(var fill=new SolidBrush(Color.FromArgb(enabled?58:hover==key?38:20,accent)))using(var pen=new Pen(Color.FromArgb(enabled?210:105,accent),enabled?1.8f:1f)){g.FillPath(fill,path);g.DrawPath(pen,path);}GlassInk.Text(g,label,new RectangleF(area.X+8,area.Y,area.Width-16,area.Height),10,enabled?accent:GlassInk.White,false,StringAlignment.Center);}
    internal static string ModuleStatuses(GlassState state){return "Zapret: "+(state.ZapretPicking?"подбор…":state.ZapretRunning?"включён":"выключен")+"  ·  TG WS: "+(state.TelegramRunning?"включён":"выключен");}
    public static int LogRowsForHeight(int height){return Math.Max(0,(height-76)/36);}
    protected override void OnMouseMove(MouseEventArgs e){base.OnMouseMove(e);if(sizingEdge!=0){var form=FindForm();var delta=new Size(MousePosition.X-sizingOrigin.X,MousePosition.Y-sizingOrigin.Y);int left=sizingBounds.Left,top=sizingBounds.Top,right=sizingBounds.Right,bottom=sizingBounds.Bottom;if(sizingEdge==10||sizingEdge==13||sizingEdge==16)left=Math.Min(right-form.MinimumSize.Width,left+delta.Width);if(sizingEdge==11||sizingEdge==14||sizingEdge==17)right=Math.Max(left+form.MinimumSize.Width,right+delta.Width);if(sizingEdge==12||sizingEdge==13||sizingEdge==14)top=Math.Min(bottom-form.MinimumSize.Height,top+delta.Height);if(sizingEdge==15||sizingEdge==16||sizingEdge==17)bottom=Math.Max(top+form.MinimumSize.Height,bottom+delta.Height);form.Bounds=Rectangle.FromLTRB(left,top,right,bottom);resized=true;return;}int edge=GlassWindow.ResizeHit(this,e.Location);if(edge!=0){Cursor=edge==10||edge==11?Cursors.SizeWE:edge==12||edge==15?Cursors.SizeNS:edge==13||edge==17?Cursors.SizeNWSE:Cursors.SizeNESW;return;}string hit=areas.Where(x=>x.Value.Contains(e.Location)).OrderBy(x=>x.Value.Width*x.Value.Height).FirstOrDefault().Key??"";if(hit!=hover){string previous=hover;hover=hit;Cursor=hit.Length>0?Cursors.Hand:Cursors.Default;var data=state();string hint=hit=="repository"?"MCRF на GitHub · github.com/vnenapravo7-source/mcrf":hit=="servers"?data.Server:hit=="traffic"?"Статистика только VPN-соединений. Очень короткие соединения между замерами могут не учитываться.":hit.StartsWith("recent:")?(data.Recent??new ServerNode[0]).Where(n=>n.Id==hit.Substring(7)).Select(n=>n.Name).FirstOrDefault():"";tips.SetToolTip(this,hint??"");Rectangle dirty;if(areas.TryGetValue(previous,out dirty))Invalidate(dirty);if(areas.TryGetValue(hit,out dirty))Invalidate(dirty);}}
    protected override void OnMouseClick(MouseEventArgs e){base.OnMouseClick(e);if(e.Button!=MouseButtons.Left)return;if(resized){resized=false;return;}string hit=areas.Where(x=>x.Value.Contains(e.Location)).OrderBy(x=>x.Value.Width*x.Value.Height).FirstOrDefault().Key;if(hit!=null)action(hit);}
    protected override void OnMouseDown(MouseEventArgs e){base.OnMouseDown(e);if(e.Button==MouseButtons.Left&&GlassWindow.ResizeHit(this,e.Location)!=0){sizingEdge=GlassWindow.ResizeHit(this,e.Location);sizingOrigin=MousePosition;sizingBounds=FindForm().Bounds;Capture=true;return;}if(e.Button==MouseButtons.Left&&e.Y<74&&!areas.Any(x=>x.Value.Contains(e.Location))){var window=FindForm();if(window!=null){GlassWindow.ReleaseCapture();GlassWindow.SendMessage(window.Handle,0xA1,new IntPtr(2),IntPtr.Zero);}}}
    protected override void OnMouseUp(MouseEventArgs e){sizingEdge=0;Capture=false;base.OnMouseUp(e);}
    protected override void OnKeyDown(KeyEventArgs e){base.OnKeyDown(e);if(e.KeyCode==Keys.Enter||e.KeyCode==Keys.Space){action(hover.Length>0?hover:"connection");e.Handled=true;}}
  }

  internal static class GlassWindow {
    internal static int ResizeHit(Control control,Point point){var form=control.FindForm();if(form==null||form.WindowState!=FormWindowState.Normal)return 0;point=form.PointToClient(control.PointToScreen(point));bool left=point.X<11,right=point.X>=form.ClientSize.Width-11,top=point.Y<11,bottom=point.Y>=form.ClientSize.Height-11;return top?(left?13:right?14:12):bottom?(left?16:right?17:15):left?10:right?11:0;}
    internal static void Drag(Control control){var form=control.FindForm();if(form==null||form.WindowState!=FormWindowState.Normal)return;ReleaseCapture();SendMessage(form.Handle,0xA1,new IntPtr(2),IntPtr.Zero);}
    [DllImport("user32.dll")]internal static extern bool ReleaseCapture();
    [DllImport("user32.dll")]internal static extern IntPtr SendMessage(IntPtr window,int message,IntPtr wParam,IntPtr lParam);
  }

  internal sealed class GlassBackdrop : Panel {
    readonly Bitmap blurred;Bitmap rendered;bool suppressClick;
    public Bitmap Blurred {get{return blurred;}}
    internal Bitmap SnapshotArea(Rectangle area){var bitmap=new Bitmap(Math.Max(1,area.Width),Math.Max(1,area.Height));using(var g=Graphics.FromImage(bitmap)){g.TranslateTransform(-area.X,-area.Y);OnPaintBackground(new PaintEventArgs(g,ClientRectangle));}return bitmap;}
    public GlassBackdrop(BlueGlassDashboard dashboard){DoubleBuffered=true;BackColor=Color.FromArgb(8,22,55);blurred=dashboard.BlurSnapshot();}
    protected override void OnMouseDown(MouseEventArgs e){suppressClick=e.Button==MouseButtons.Left&&e.Y<74;if(suppressClick){GlassWindow.Drag(this);return;}base.OnMouseDown(e);}
    protected override void OnClick(EventArgs e){if(suppressClick){suppressClick=false;return;}base.OnClick(e);}
    protected override void OnResize(EventArgs e){if(rendered!=null){rendered.Dispose();rendered=null;}base.OnResize(e);}
    protected override void OnPaintBackground(PaintEventArgs e){if(Width<1||Height<1)return;if(rendered==null){rendered=new Bitmap(Width,Height);using(var g=Graphics.FromImage(rendered)){g.Clear(Color.FromArgb(7,18,41));if(blurred!=null){g.InterpolationMode=InterpolationMode.HighQualityBicubic;g.DrawImage(blurred,ClientRectangle);using(var dark=new SolidBrush(Color.FromArgb(127,4,11,35)))g.FillRectangle(dark,ClientRectangle);}}}e.Graphics.DrawImageUnscaled(rendered,0,0);}
    protected override void Dispose(bool disposing){if(disposing){if(rendered!=null)rendered.Dispose();if(blurred!=null)blurred.Dispose();}base.Dispose(disposing);}
  }

  internal sealed class GlassPopupPanel : Panel {
    public bool Inline;
    public bool OpaqueSurface;
    Bitmap rendered;
    public GlassPopupPanel(){SetStyle(ControlStyles.SupportsTransparentBackColor,true);DoubleBuffered=true;BackColor=Color.Transparent;}
    protected override void OnMouseDown(MouseEventArgs e){if(!Inline&&e.Button==MouseButtons.Left&&e.Y<64){GlassWindow.Drag(this);return;}base.OnMouseDown(e);}
    protected override void OnControlAdded(ControlEventArgs e){base.OnControlAdded(e);if(e.Control is Label)e.Control.MouseDown+=(s,m)=>{if(!Inline&&m.Button==MouseButtons.Left&&e.Control.Top<64)GlassWindow.Drag(this);};}
    protected override void OnResize(EventArgs e){if(rendered!=null){rendered.Dispose();rendered=null;}base.OnResize(e);}
    protected override void Dispose(bool disposing){if(disposing&&rendered!=null)rendered.Dispose();base.Dispose(disposing);}
    protected override void OnPaintBackground(PaintEventArgs e){if(Width<2||Height<2)return;if(rendered==null){rendered=new Bitmap(Width,Height);using(var graphics=Graphics.FromImage(rendered)){if(Parent!=null){graphics.TranslateTransform(-Left,-Top);InvokePaintBackground(Parent,new PaintEventArgs(graphics,Parent.ClientRectangle));graphics.ResetTransform();}DrawSurface(graphics);}}e.Graphics.DrawImageUnscaled(rendered,0,0);}
    void DrawSurface(Graphics g){if(Width<2||Height<2)return;if(Inline){g.Clear(Color.FromArgb(12,17,31));return;}if(UiTheme.Simple){using(var path=UiShape.Round(new Rectangle(1,1,Width-3,Height-3),20))using(var fill=new SolidBrush(Color.FromArgb(24,29,47)))g.FillPath(fill,path);return;}g.SmoothingMode=SmoothingMode.AntiAlias;
      using(var path=UiShape.Round(new Rectangle(1,1,Width-3,Height-3),25)){
        var save=g.Save();g.SetClip(path);var backdrop=Parent as GlassBackdrop;if(!OpaqueSurface&&backdrop!=null&&backdrop.Blurred!=null){g.InterpolationMode=InterpolationMode.HighQualityBicubic;g.DrawImage(backdrop.Blurred,new Rectangle(-Left,-Top,backdrop.Width,backdrop.Height));}g.Restore(save);
        using(var fill=new LinearGradientBrush(ClientRectangle,Color.FromArgb(OpaqueSurface?255:218,30,43,78),Color.FromArgb(OpaqueSurface?255:230,19,23,55),LinearGradientMode.ForwardDiagonal))g.FillPath(fill,path);
        save=g.Save();g.SetClip(path);GlassInk.SoftGlow(g,new Rectangle(-220,-260,630,430),Color.FromArgb(60,175,255));GlassInk.SoftGlow(g,new Rectangle(Width-390,Height-320,620,520),Color.FromArgb(102,62,255));g.Restore(save);
      }

    }
  }

  // One composed surface: backdrop and popup must become visible atomically.
  internal sealed class GlassMotionLayer : Form {
    [StructLayout(LayoutKind.Sequential)]struct Blend {public byte operation,flags,alpha,format;}
    [DllImport("user32.dll")]static extern IntPtr GetDC(IntPtr window);
    [DllImport("user32.dll")]static extern int ReleaseDC(IntPtr window,IntPtr dc);
    [DllImport("gdi32.dll")]static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")]static extern IntPtr SelectObject(IntPtr dc,IntPtr value);
    [DllImport("gdi32.dll")]static extern bool DeleteObject(IntPtr value);
    [DllImport("gdi32.dll")]static extern bool DeleteDC(IntPtr dc);
    [StructLayout(LayoutKind.Sequential)]struct BitmapInfo{public uint size;public int width,height;public ushort planes,bits;public uint compression,imageSize;public int xppm,yppm;public uint used,important;}
    [DllImport("gdi32.dll")]static extern IntPtr CreateDIBSection(IntPtr dc,ref BitmapInfo info,uint usage,out IntPtr bits,IntPtr section,uint offset);
    [DllImport("user32.dll",SetLastError=true)]static extern bool UpdateLayeredWindow(IntPtr window,IntPtr destination,ref Point position,ref Size size,IntPtr source,ref Point origin,int key,ref Blend blend,int flags);
    [DllImport("user32.dll")]static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
    [DllImport("winmm.dll")]static extern uint timeBeginPeriod(uint period);
    [DllImport("winmm.dll")]static extern uint timeEndPeriod(uint period);
    internal Action CancelTransition;int generation;readonly bool inputShield;
    readonly object surfaceLock=new object();IntPtr surfaceDc,surfaceBitmap,surfacePrevious,surfaceBits,screenDc,windowHandle;Size surfaceSize;byte[] surfaceBytes;
    internal static readonly List<double> FrameTimes=new List<double>(),AppliedTimes=new List<double>();
    System.Diagnostics.Stopwatch animationClock;
    protected override void WndProc(ref Message m){base.WndProc(ref m);if(m.Msg==0x47&&animationClock!=null)lock(AppliedTimes)AppliedTimes.Add(animationClock.Elapsed.TotalMilliseconds);}
    [DllImport("dwmapi.dll")]static extern int DwmSetWindowAttribute(IntPtr window,int attribute,ref int value,int size);
    protected override void OnHandleCreated(EventArgs e){base.OnHandleCreated(e);BrandArt.ApplyFramelessChrome(Handle);int disabled=1;DwmSetWindowAttribute(Handle,3,ref disabled,4);}
    public GlassMotionLayer(bool inputShield=false){this.inputShield=inputShield;BackColor=Color.FromArgb(18,27,56);FormBorderStyle=FormBorderStyle.None;ShowInTaskbar=false;StartPosition=FormStartPosition.Manual;}
    protected override bool ShowWithoutActivation{get{return true;}}
    protected override CreateParams CreateParams{get{var p=base.CreateParams;p.Style&=~0x00C40000;p.ExStyle&=~0x00020301;p.ClassStyle&=~0x00020000;p.ExStyle|=0x80000|0x80|0x8000000;if(!inputShield)p.ExStyle|=0x20;return p;}}
    internal void Upload(Bitmap image,Point position){
      lock(surfaceLock){if(surfaceDc==IntPtr.Zero){windowHandle=Handle;screenDc=GetDC(IntPtr.Zero);surfaceDc=CreateCompatibleDC(screenDc);var info=new BitmapInfo{size=40,width=image.Width,height=-image.Height,planes=1,bits=32};surfaceBitmap=CreateDIBSection(screenDc,ref info,0,out surfaceBits,IntPtr.Zero,0);if(surfaceBitmap==IntPtr.Zero)throw new System.ComponentModel.Win32Exception();surfacePrevious=SelectObject(surfaceDc,surfaceBitmap);surfaceSize=image.Size;surfaceBytes=new byte[image.Width*image.Height*4];}
        if(surfaceSize!=image.Size)throw new InvalidOperationException("Размер слоя анимации изменился");
        var data=image.LockBits(new Rectangle(Point.Empty,image.Size),System.Drawing.Imaging.ImageLockMode.ReadOnly,System.Drawing.Imaging.PixelFormat.Format32bppPArgb);
        try{Marshal.Copy(data.Scan0,surfaceBytes,0,surfaceBytes.Length);Marshal.Copy(surfaceBytes,0,surfaceBits,surfaceBytes.Length);}finally{image.UnlockBits(data);}
        var size=image.Size;var zero=Point.Empty;var blend=new Blend{alpha=255,format=1};if(!UpdateLayeredWindow(windowHandle,screenDc,ref position,ref size,surfaceDc,ref zero,0,ref blend,2))throw new System.ComponentModel.Win32Exception();
      }
    }
    static void Compose(Graphics graphics,Bitmap backdrop,Bitmap popup,Rectangle bounds,double t,bool closing){
      double ease=t*t*(3-2*t);graphics.CompositingMode=CompositingMode.SourceCopy;graphics.DrawImageUnscaled(backdrop,0,0);graphics.CompositingMode=CompositingMode.SourceOver;
      var at=new Rectangle(bounds.X,bounds.Y+(int)Math.Round(20*(closing?ease:1-ease)),popup.Width,popup.Height);
      using(var attributes=new System.Drawing.Imaging.ImageAttributes()){var matrix=new System.Drawing.Imaging.ColorMatrix();matrix.Matrix33=(float)(closing?1-ease:ease);attributes.SetColorMatrix(matrix);graphics.DrawImage(popup,at,0,0,popup.Width,popup.Height,GraphicsUnit.Pixel,attributes);}
    }
    internal void StartComposition(Bitmap backdrop,Bitmap popup,Rectangle bounds,Point origin,bool closing){using(var frame=new Bitmap(backdrop.Width,backdrop.Height,System.Drawing.Imaging.PixelFormat.Format32bppPArgb)){using(var g=Graphics.FromImage(frame))Compose(g,backdrop,popup,bounds,0,closing);Upload(frame,origin);}}
    internal void AnimateComposition(bool closing,Point origin,Bitmap backdrop,Bitmap popup,Rectangle bounds,Action finished){
      int token=++generation;FrameTimes.Clear();AppliedTimes.Clear();animationClock=System.Diagnostics.Stopwatch.StartNew();
      System.Threading.Tasks.Task.Run(()=>{
        timeBeginPeriod(1);var clock=System.Diagnostics.Stopwatch.StartNew();double next=0;
        try{using(var frame=new Bitmap(backdrop.Width,backdrop.Height,System.Drawing.Imaging.PixelFormat.Format32bppPArgb))using(var graphics=Graphics.FromImage(frame)){
          while(token==System.Threading.Volatile.Read(ref generation)){double t=Math.Min(1,clock.Elapsed.TotalMilliseconds/220);Compose(graphics,backdrop,popup,bounds,t,closing);Upload(frame,origin);lock(FrameTimes)FrameTimes.Add(clock.Elapsed.TotalMilliseconds);lock(AppliedTimes)AppliedTimes.Add(clock.Elapsed.TotalMilliseconds);if(t>=1)break;next+=1000.0/60;while(clock.Elapsed.TotalMilliseconds<next&&token==System.Threading.Volatile.Read(ref generation))System.Threading.Thread.Sleep(1);}
        }}catch(System.ComponentModel.Win32Exception){}catch(ObjectDisposedException){}
        finally{timeEndPeriod(1);backdrop.Dispose();popup.Dispose();}
        if(token==System.Threading.Volatile.Read(ref generation)&&!IsDisposed)try{BeginInvoke(new Action(()=>{if(token==generation)finished();}));}catch(InvalidOperationException){}
      });
    }
    internal void Animate(bool closing,Point origin,Action finished){
      int token=++generation;var window=Handle;FrameTimes.Clear();AppliedTimes.Clear();animationClock=System.Diagnostics.Stopwatch.StartNew();
      System.Threading.Tasks.Task.Run(()=>{
        timeBeginPeriod(1);var clock=System.Diagnostics.Stopwatch.StartNew();double next=0;
        try{
        while(token==System.Threading.Volatile.Read(ref generation)){
          double t=Math.Min(1,clock.Elapsed.TotalMilliseconds/220),ease=t*t*(3-2*t);
          int y=origin.Y+(int)Math.Round(24*(closing?ease:1-ease));
          SetWindowPos(window,IntPtr.Zero,origin.X,y,0,0,0x401D);
          lock(FrameTimes)FrameTimes.Add(clock.Elapsed.TotalMilliseconds);
          if(t>=1)break;
          next+=1000.0/120;
          while(clock.Elapsed.TotalMilliseconds<next&&token==System.Threading.Volatile.Read(ref generation))System.Threading.Thread.Sleep(1);
        }
        }finally{timeEndPeriod(1);}
        if(token==System.Threading.Volatile.Read(ref generation)&&!IsDisposed)try{BeginInvoke(new Action(()=>{if(token==generation)finished();}));}catch(InvalidOperationException){}
      });
    }
    internal void Cancel(){System.Threading.Interlocked.Increment(ref generation);}
    protected override void Dispose(bool disposing){Cancel();lock(surfaceLock){if(surfaceDc!=IntPtr.Zero){SelectObject(surfaceDc,surfacePrevious);DeleteObject(surfaceBitmap);DeleteDC(surfaceDc);ReleaseDC(IntPtr.Zero,screenDc);surfaceDc=IntPtr.Zero;surfaceBytes=null;}}base.Dispose(disposing);}
  }
  internal static class GlassTransition {
    internal static double LastSwapMilliseconds,LastRevealMilliseconds,LastCleanupMilliseconds;
    static readonly Dictionary<Control,GlassMotionLayer> active=new Dictionary<Control,GlassMotionLayer>();
    [DllImport("winmm.dll")]static extern uint timeBeginPeriod(uint period);
    [DllImport("winmm.dll")]static extern uint timeEndPeriod(uint period);
    static void PaintRichText(Control root,Graphics graphics,Point offset){
      foreach(Control child in root.Controls){var at=new Point(offset.X+child.Left,offset.Y+child.Top);var rich=child as RichTextBox;
        if(rich!=null&&rich.Visible){var saved=graphics.Save();graphics.SetClip(new Rectangle(at,rich.Size));using(var fill=new SolidBrush(rich.BackColor))graphics.FillRectangle(fill,new Rectangle(at,rich.Size));int selection=rich.SelectionStart,length=rich.SelectionLength;
          GlassWindow.SendMessage(rich.Handle,11,IntPtr.Zero,IntPtr.Zero);
          try{var lines=rich.Lines;for(int line=rich.GetLineFromCharIndex(rich.GetCharIndexFromPosition(Point.Empty));line<lines.Length;line++){int index=rich.GetFirstCharIndexFromLine(line);if(index<0)break;var p=rich.GetPositionFromCharIndex(index);if(p.Y>rich.Height)break;var text=lines[line];int sourceEnd=text.IndexOf(']')+1;var spans=rich.Name=="clientJournal"&&sourceEnd>20&&text.Length>sourceEnd+1?new[]{0,20,sourceEnd+1,text.Length}:new[]{0,text.Length};for(int part=0;part<spans.Length-1;part++){int from=spans[part],count=spans[part+1]-from;if(count<1)continue;rich.Select(index+from,1);var position=rich.GetPositionFromCharIndex(index+from);using(var brush=new SolidBrush(rich.SelectionColor))graphics.DrawString(text.Substring(from,count),rich.Font,brush,new PointF(at.X+position.X,at.Y+position.Y),StringFormat.GenericTypographic);}}}
          finally{rich.Select(selection,length);GlassWindow.SendMessage(rich.Handle,11,new IntPtr(1),IntPtr.Zero);}graphics.Restore(saved);
        }else PaintRichText(child,graphics,at);
      }
    }
    static Size edgeSize;static byte[] edgeCoverage;
    static void PaintSettingsSnapshot(Control root,Graphics graphics,Point offset){
      foreach(Control child in root.Controls){if(!child.Visible)continue;var at=new Point(offset.X+child.Left,offset.Y+child.Top);var button=child as ModernButton;var input=child as TextBox;
        if(button!=null)button.PaintSnapshot(graphics,at);
        else if(input!=null){var saved=graphics.Save();graphics.SetClip(new Rectangle(at,input.Size),CombineMode.Intersect);using(var fill=new SolidBrush(input.BackColor))graphics.FillRectangle(fill,new Rectangle(at,input.Size));using(var brush=new SolidBrush(input.Enabled?input.ForeColor:GlassInk.Muted))using(var format=new StringFormat{LineAlignment=StringAlignment.Center,Trimming=StringTrimming.EllipsisCharacter,FormatFlags=StringFormatFlags.NoWrap})graphics.DrawString(input.Text,input.Font,brush,new RectangleF(at.X,at.Y,input.Width,input.Height),format);graphics.Restore(saved);}
        else PaintSettingsSnapshot(child,graphics,at);
      }
    }
    static byte[] RoundedCoverage(Size size){
      if(edgeCoverage!=null&&edgeSize==size)return edgeCoverage;
      var values=new byte[size.Width*54];
      using(var mask=new Bitmap(size.Width,54,System.Drawing.Imaging.PixelFormat.Format32bppArgb)){
        using(var g=Graphics.FromImage(mask)){
          g.Clear(Color.Transparent);g.SmoothingMode=SmoothingMode.AntiAlias;
          using(var path=UiShape.Round(new Rectangle(1,1,size.Width-3,size.Height-3),25)){
            var state=g.Save();g.SetClip(new Rectangle(0,0,size.Width,27));g.FillPath(Brushes.White,path);g.Restore(state);
            g.SetClip(new Rectangle(0,27,size.Width,27));g.TranslateTransform(0,54-size.Height);g.FillPath(Brushes.White,path);
          }
        }
        var bits=mask.LockBits(new Rectangle(0,0,size.Width,54),System.Drawing.Imaging.ImageLockMode.ReadOnly,System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        try{var row=new byte[size.Width*4];for(int y=0;y<54;y++){Marshal.Copy(IntPtr.Add(bits.Scan0,y*bits.Stride),row,0,row.Length);for(int x=0;x<size.Width;x++)values[y*size.Width+x]=row[x*4+3];}}
        finally{mask.UnlockBits(bits);}
      }
      edgeSize=size;edgeCoverage=values;return values;
    }
    internal static Bitmap Capture(Control popup){
      var result=new Bitmap(Math.Max(1,popup.Width),Math.Max(1,popup.Height),System.Drawing.Imaging.PixelFormat.Format32bppPArgb);
      try{
        popup.DrawToBitmap(result,new Rectangle(Point.Empty,result.Size));using(var g=Graphics.FromImage(result)){PaintRichText(popup,g,Point.Empty);if(popup.Controls.Find("dnsCustom",true).Length>0)PaintSettingsSnapshot(popup,g,Point.Empty);}
        // Only 54 edge rows need a rounded mask, not another full-window bitmap.
        var alpha=RoundedCoverage(result.Size);
        var pixels=result.LockBits(new Rectangle(Point.Empty,result.Size),System.Drawing.Imaging.ImageLockMode.ReadWrite,System.Drawing.Imaging.PixelFormat.Format32bppPArgb);
        try{
          var row=new byte[result.Width*4];var empty=new byte[8];
          for(int y=0;y<result.Height;y++){
            if(y>=27&&y<result.Height-27){Marshal.Copy(empty,0,IntPtr.Add(pixels.Scan0,y*pixels.Stride),4);Marshal.Copy(empty,0,IntPtr.Add(pixels.Scan0,y*pixels.Stride+(result.Width-2)*4),8);continue;}
            Marshal.Copy(IntPtr.Add(pixels.Scan0,y*pixels.Stride),row,0,row.Length);
            int maskY=y<27?y:27+y-(result.Height-27);
            for(int x=0;x<result.Width;x++){int coverage=alpha[maskY*result.Width+x],offset=x*4;if(coverage==255)continue;for(int channel=0;channel<4;channel++)row[offset+channel]=(byte)((row[offset+channel]*coverage+127)/255);}
            Marshal.Copy(row,0,IntPtr.Add(pixels.Scan0,y*pixels.Stride),row.Length);
          }
        }finally{result.UnlockBits(pixels);}
        return result;
      }catch{result.Dispose();throw;}
    }
    public static void Play(Control popup,bool closing,Action done=null){
      if(popup.IsDisposed)return;var parent=popup.Parent;if(parent==null){if(done!=null)done();return;}
      if(active.ContainsKey(popup)){var previous=active[popup];if(previous.CancelTransition!=null)previous.CancelTransition();}
      popup.Visible=true;popup.PerformLayout();
      var image=Capture(popup);
      var bounds=popup.Bounds;var root=parent.TopLevelControl as Form;var layer=new GlassMotionLayer(true);
      var staged=Convert.ToString(parent.Tag)=="staged";var origin=staged?root.PointToScreen(Point.Empty):parent.PointToScreen(Point.Empty);
      var backdrop=parent as GlassBackdrop;Bitmap background=null;
      if(backdrop!=null)background=backdrop.SnapshotArea(parent.ClientRectangle);else{background=new Bitmap(parent.Width,parent.Height);using(var graphics=Graphics.FromImage(background))graphics.Clear(parent.BackColor);}
      layer.Bounds=new Rectangle(origin,background.Size);if(root!=null&&root.Region!=null&&root.ClientSize==background.Size)layer.Region=root.Region.Clone();
      layer.StartComposition(background,image,bounds,origin,closing);layer.Show(root);layer.Update();
      // Reveal/paint the real controls only beneath the already-visible full
      // composition. No bare rectangle, second shield window or offscreen move
      // of child HWNDs can be presented between backdrop and popup.
      if(staged){parent.Tag=null;parent.Location=Point.Empty;parent.Dock=DockStyle.Fill;}
      popup.Invalidate(true);popup.Update();parent.Update();
      bool finished=false,swapping=false;EventHandler disposed=null;
      Action cleanup=()=>{if(finished)return;finished=true;popup.Disposed-=disposed;active.Remove(popup);layer.CancelTransition=null;if(!layer.IsDisposed){layer.Cancel();layer.Close();layer.Dispose();}};
      disposed=(s,e)=>{if(!swapping)cleanup();};popup.Disposed+=disposed;layer.CancelTransition=cleanup;active[popup]=layer;
      layer.AnimateComposition(closing,origin,background,image,bounds,()=>{if(finished)return;var swap=System.Diagnostics.Stopwatch.StartNew();swapping=true;try{if(!popup.IsDisposed){if(done!=null)done();else if(closing)popup.Visible=false;}if(closing&&root!=null&&!root.IsDisposed){root.Invalidate(true);root.Update();}LastRevealMilliseconds=swap.Elapsed.TotalMilliseconds;}finally{var disposal=System.Diagnostics.Stopwatch.StartNew();cleanup();LastCleanupMilliseconds=disposal.Elapsed.TotalMilliseconds;swap.Stop();LastSwapMilliseconds=swap.Elapsed.TotalMilliseconds;}});
    }
  }
  internal sealed class GlassProfileTabs : Panel {
    int offset;
    public GlassProfileTabs(){SetStyle(ControlStyles.Selectable|ControlStyles.SupportsTransparentBackColor,true);DoubleBuffered=true;TabStop=true;BackColor=Color.Transparent;}
    protected override void OnControlAdded(ControlEventArgs e){base.OnControlAdded(e);e.Control.MouseEnter+=(s,a)=>Focus();Reflow();}
    protected override void OnSizeChanged(EventArgs e){base.OnSizeChanged(e);Reflow();}
    protected override void OnMouseWheel(MouseEventArgs e){offset=Math.Max(0,offset-e.Delta/3);Reflow();base.OnMouseWheel(e);}
    void Reflow(){int total=Controls.Cast<Control>().Sum(c=>c.Width+8);offset=Math.Min(offset,Math.Max(0,total-Width));int x=-offset;foreach(Control c in Controls){c.Location=new Point(x,0);x+=c.Width+8;}Invalidate();}
    protected override void OnPaint(PaintEventArgs e){base.OnPaint(e);int total=Controls.Cast<Control>().Sum(c=>c.Width+8);if(total>Width&&Width>0){using(var pen=new Pen(GlassInk.Muted,2))e.Graphics.DrawLine(pen,Width*offset/(float)total,39,Width*(offset+Width)/(float)total,39);}}
  }
  internal sealed class GlassToast : Control {
    readonly Timer timer=new Timer{Interval=1800};
    protected override void OnSizeChanged(EventArgs e){base.OnSizeChanged(e);if(Width<4||Height<4)return;using(var path=UiShape.Round(new Rectangle(0,0,Width,Height),14)){var old=Region;Region=new Region(path);if(old!=null)old.Dispose();}}
    public GlassToast(string text){SetStyle(ControlStyles.SupportsTransparentBackColor,true);DoubleBuffered=true;BackColor=Color.Transparent;Text=text;Size=new Size(410,52);timer.Tick+=(s,e)=>Dispose();timer.Start();}
    protected override void OnPaint(PaintEventArgs e){var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;using(var p=UiShape.Round(new Rectangle(1,1,Width-3,Height-3),14)){using(var b=new SolidBrush(Color.FromArgb(235,30,31,67)))g.FillPath(b,p);}GlassInk.Text(g,Text,new RectangleF(15,0,Width-30,Height),10,GlassInk.White,false,StringAlignment.Center);}
    protected override void Dispose(bool disposing){if(disposing)timer.Dispose();base.Dispose(disposing);}
  }
  internal sealed class GlassClose : Control {
    public GlassClose(){SetStyle(ControlStyles.SupportsTransparentBackColor,true);DoubleBuffered=true;BackColor=Color.Transparent;Cursor=Cursors.Hand;Size=new Size(42,42);}
    protected override void OnPaint(PaintEventArgs e){var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;var r=new Rectangle(2,2,Width-5,Height-5);using(var p=UiShape.Round(r,r.Width/2)){using(var b=new SolidBrush(Color.FromArgb(55,117,136,206)))g.FillPath(b,p);}using(var pen=new Pen(GlassInk.White,2.1f)){pen.StartCap=LineCap.Round;pen.EndCap=LineCap.Round;g.DrawLine(pen,Width/2-5,Height/2-5,Width/2+5,Height/2+5);g.DrawLine(pen,Width/2+5,Height/2-5,Width/2-5,Height/2+5);}}
  }
  internal sealed class GlassToggle : Control {
    bool value;public event EventHandler CheckedChanged;
    public bool Checked {get{return value;}set{if(this.value==value)return;this.value=value;Invalidate();if(CheckedChanged!=null)CheckedChanged(this,EventArgs.Empty);}}
    public GlassToggle(){SetStyle(ControlStyles.SupportsTransparentBackColor,true);DoubleBuffered=true;BackColor=Color.Transparent;Cursor=Cursors.Hand;Size=new Size(520,30);TabStop=true;}
    protected override void OnClick(EventArgs e){Checked=!Checked;base.OnClick(e);}
    protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Space){Checked=!Checked;e.Handled=true;}base.OnKeyDown(e);}
    protected override void OnPaint(PaintEventArgs e){var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;int top=Math.Max(0,(Height-21)/2);var rect=new Rectangle(1,top,35,21);using(var path=UiShape.Round(rect,10)){using(var brush=new SolidBrush(Checked?Color.FromArgb(77,196,174):Color.FromArgb(69,88,130)))g.FillPath(brush,path);}using(var brush=new SolidBrush(GlassInk.White))g.FillEllipse(brush,Checked?19:4,top+3,15,15);if(!String.IsNullOrEmpty(Text))GlassInk.Text(g,Text+(Checked?" · ВКЛЮЧЁН":" · ВЫКЛЮЧЕН"),new RectangleF(48,0,Width-48,Height),10,Checked?GlassInk.Mint:GlassInk.White,true);}
  }

  internal sealed class GlassPresetOverlay : Form {
    readonly Bitmap snapshot;
    public GlassPresetOverlay(Control origin){var owner=origin.FindForm();if(owner==null)return;snapshot=new Bitmap(owner.ClientSize.Width,owner.ClientSize.Height);owner.DrawToBitmap(snapshot,owner.ClientRectangle);Control panel=origin.Parent;while(panel!=null&&!(panel is GlassPopupPanel))panel=panel.Parent;if(panel!=null)using(var image=GlassTransition.Capture(panel))using(var g=Graphics.FromImage(snapshot))g.DrawImageUnscaled(image,owner.PointToClient(panel.PointToScreen(Point.Empty)));DoubleBuffered=true;}
    protected override void OnPaintBackground(PaintEventArgs e){if(snapshot==null){base.OnPaintBackground(e);return;}e.Graphics.DrawImageUnscaled(snapshot,0,0);using(var shade=new SolidBrush(Color.FromArgb(95,5,12,30)))e.Graphics.FillRectangle(shade,ClientRectangle);}
    protected override void OnMouseDown(MouseEventArgs e){base.OnMouseDown(e);if(e.Button==MouseButtons.Left)DialogResult=DialogResult.OK;}
    protected override void Dispose(bool disposing){if(disposing&&snapshot!=null)snapshot.Dispose();base.Dispose(disposing);}
  }
  internal sealed class GlassPicker : Control {
    readonly List<string> choices=new List<string>();int selected=-1;ContextMenuStrip menu;
    readonly HashSet<int> checkedIndices=new HashSet<int>();
    public bool FullItemText{get;set;}public bool FlatSurface{get;set;}public bool MultiSelect{get;set;} public Func<int,string> ItemHint{get;set;} public Func<int,string> ItemSource{get;set;} public Func<int,Color> ItemColor{get;set;}public Func<Color> SurfaceColor{get;set;}
    public bool ShowAllSelections{get;set;} public string EmptySelectionText="Выберите списки"; public string SelectionCaption="Выбрано";
    public Func<string> SelectionTextOverride{get;set;}
    public string DisplaySelection {get{return SelectionTextOverride!=null?SelectionTextOverride():MultiSelect?(checkedIndices.Count==0?EmptySelectionText:ItemSource!=null&&checkedIndices.Count>1?"Несколько":checkedIndices.Count>2&&!ShowAllSelections?SelectionCaption+": "+checkedIndices.Count:String.Join(" + ",SelectedIndices.Select(x=>choices[x]))):(selected<0?EmptySelectionText:SelectedItem);}}
    public int[] SelectedIndices{get{return checkedIndices.OrderBy(x=>x).ToArray();}}
    public event EventHandler CheckedItemsChanged;
    public void SetChecked(int index,bool value){if(index<0||index>=choices.Count)return;bool changed=value?checkedIndices.Add(index):checkedIndices.Remove(index);if(!changed)return;Invalidate();if(CheckedItemsChanged!=null)CheckedItemsChanged(this,EventArgs.Empty);}
    public void SetSelection(IEnumerable<int> indices){var next=new HashSet<int>(indices.Where(i=>i>=0&&i<choices.Count));if(checkedIndices.SetEquals(next))return;checkedIndices.Clear();checkedIndices.UnionWith(next);Invalidate();if(CheckedItemsChanged!=null)CheckedItemsChanged(this,EventArgs.Empty);}
    void ShowPresetTable(){using(var popup=new GlassPresetOverlay(this){Text="Пресеты",Name="presetSelectionTable",AutoScaleMode=AutoScaleMode.None,FormBorderStyle=FormBorderStyle.None,StartPosition=FormStartPosition.CenterParent,Size=new Size(680,520),MinimumSize=new Size(520,360),BackColor=Color.FromArgb(24,42,79),ForeColor=GlassInk.White,Font=Font,MinimizeBox=false,MaximizeBox=false,ShowInTaskbar=false}){
      var grid=new GlassGrid{Name="presetTable",Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right,Location=new Point(20,60),Size=new Size(popup.Width-40,popup.Height-132),ColumnHeadersHeight=36,CellBorderStyle=DataGridViewCellBorderStyle.SingleHorizontal,SelectionMode=DataGridViewSelectionMode.FullRowSelect,AllowUserToAddRows=false,AllowUserToDeleteRows=false,RowHeadersVisible=false,AutoSizeColumnsMode=DataGridViewAutoSizeColumnsMode.Fill,BackgroundColor=popup.BackColor,BorderStyle=BorderStyle.None,EnableHeadersVisualStyles=false};grid.Font=new Font("Segoe UI",14,GraphicsUnit.Pixel);grid.DefaultCellStyle.Font=grid.Font;grid.ColumnHeadersDefaultCellStyle.Font=grid.Font;grid.RowTemplate.Height=34;grid.DefaultCellStyle.BackColor=popup.BackColor;grid.DefaultCellStyle.ForeColor=GlassInk.White;grid.DefaultCellStyle.SelectionBackColor=Color.FromArgb(59,76,121);grid.DefaultCellStyle.SelectionForeColor=GlassInk.White;grid.ColumnHeadersDefaultCellStyle.BackColor=Color.FromArgb(29,39,64);grid.ColumnHeadersDefaultCellStyle.ForeColor=GlassInk.White;grid.Columns.Add(new DataGridViewCheckBoxColumn{Name="selected",HeaderText="Выбрать",AutoSizeMode=DataGridViewAutoSizeColumnMode.None,Width=104});grid.Columns.Add(new DataGridViewTextBoxColumn{Name="name",HeaderText="Название",ReadOnly=true,FillWeight=110});grid.Columns.Add(new DataGridViewTextBoxColumn{Name="source",HeaderText="Источник",ReadOnly=true,AutoSizeMode=DataGridViewAutoSizeColumnMode.None,Width=124});grid.Columns["selected"].HeaderCell.Style.Alignment=DataGridViewContentAlignment.MiddleCenter;grid.Columns["source"].DefaultCellStyle.Alignment=DataGridViewContentAlignment.MiddleCenter;grid.Columns["source"].HeaderCell.Style.Alignment=DataGridViewContentAlignment.MiddleCenter;grid.Columns["source"].DefaultCellStyle.Font=new Font("Segoe UI",13,GraphicsUnit.Pixel);foreach(DataGridViewColumn column in grid.Columns)column.SortMode=DataGridViewColumnSortMode.NotSortable;
      for(int i=0;i<choices.Count;i++){int row=grid.Rows.Add(MultiSelect?checkedIndices.Contains(i):selected==i,choices[i],ItemSource(i));grid.Rows[row].Height=34;if(ItemHint!=null)grid.Rows[row].Cells[1].ToolTipText=ItemHint(i);if(ItemColor!=null)grid.Rows[row].Cells[1].Style.ForeColor=grid.Rows[row].Cells[1].Style.SelectionForeColor=ItemColor(i);if(choices[i].Contains("Популярные сервисы 7")){grid.Rows[row].Cells[1].Style.ForeColor=grid.Rows[row].Cells[1].Style.SelectionForeColor=Color.Gold;grid.Rows[row].Cells[1].Style.Font=new Font(grid.Font,FontStyle.Bold);}}
      grid.CurrentCellDirtyStateChanged+=(s,e)=>{if(grid.IsCurrentCellDirty)grid.CommitEdit(DataGridViewDataErrorContexts.Commit);};if(!MultiSelect)grid.CellValueChanged+=(s,e)=>{if(e.ColumnIndex!=0||e.RowIndex<0||!Convert.ToBoolean(grid.Rows[e.RowIndex].Cells[0].Value))return;foreach(DataGridViewRow row in grid.Rows)if(row.Index!=e.RowIndex)row.Cells[0].Value=false;};
      var surface=new GlassPopupPanel{Size=popup.Size,Inline=false,OpaqueSurface=true};popup.Controls.Add(surface);var footer=new Panel{BackColor=Color.Transparent,Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right,Location=new Point(4,popup.Height-66),Size=new Size(popup.Width-8,60)};var done=new ModernButton{Text="Готово",Primary=true,Size=new Size(150,38),Location=new Point(16,10),DialogResult=DialogResult.OK,ForeColor=GlassInk.White};var cancel=new ModernButton{Text="Отмена",Size=new Size(150,38),Location=new Point(182,10),DialogResult=DialogResult.Cancel,ForeColor=GlassInk.White};footer.Controls.Add(done);footer.Controls.Add(cancel);surface.Controls.Add(grid);surface.Controls.Add(footer);var title=new Label{Text="Пресеты",BackColor=Color.Transparent,Font=new Font("Segoe UI Semibold",20,GraphicsUnit.Pixel),Dock=DockStyle.Top,Height=58,TextAlign=ContentAlignment.MiddleLeft,Padding=new Padding(16,0,0,0)};surface.Controls.Add(title);popup.AcceptButton=done;popup.CancelButton=cancel;var owner=FindForm();if(owner!=null){popup.StartPosition=FormStartPosition.Manual;popup.Bounds=new Rectangle(owner.PointToScreen(Point.Empty),owner.ClientSize);surface.Size=new Size(Math.Min(680,popup.Width-40),Math.Min(520,popup.Height-40));surface.Location=new Point((popup.Width-surface.Width)/2,(popup.Height-surface.Height)/2);if(owner.Region!=null)popup.Region=owner.Region.Clone();}else surface.Dock=DockStyle.Fill;grid.Height=grid.ColumnHeadersHeight+Math.Max(1,(grid.Height-grid.ColumnHeadersHeight)/34)*34;if(popup.ShowDialog(FindForm())==DialogResult.OK){grid.EndEdit();var indices=grid.Rows.Cast<DataGridViewRow>().Where(r=>Convert.ToBoolean(r.Cells[0].Value)).Select(r=>r.Index).ToArray();if(MultiSelect)SetSelection(indices);else SelectedIndex=indices.Length==0?-1:indices[0];}
    }}
    public event EventHandler SelectedIndexChanged;
    public int SelectedIndex {get{return selected;}set{if(value<-1||value>=choices.Count)return;if(selected==value)return;selected=value;Invalidate();if(SelectedIndexChanged!=null)SelectedIndexChanged(this,EventArgs.Empty);}}
    public string SelectedItem {get{return selected>=0&&selected<choices.Count?choices[selected]:"";}set{SelectedIndex=choices.IndexOf(value);}}
    public void SetItems(IEnumerable<string> values){choices.Clear();choices.AddRange(values);selected=-1;checkedIndices.Clear();TabStop=MultiSelect||choices.Count>1;Cursor=TabStop?Cursors.Hand:Cursors.Default;Invalidate();}
    public GlassPicker(){SetStyle(ControlStyles.SupportsTransparentBackColor,true);BackColor=Color.Transparent;DoubleBuffered=true;Cursor=Cursors.Hand;Height=42;Font=new Font("Segoe UI Semibold",16f,FontStyle.Regular,GraphicsUnit.Pixel);TabStop=true;}
    protected override void OnClick(EventArgs e){base.OnClick(e);if(!Enabled||choices.Count==0||(!MultiSelect&&choices.Count<2))return;if(ItemSource!=null){ShowPresetTable();return;}if(menu!=null){menu.Dispose();menu=null;}int menuWidth=FullItemText?Math.Min(Screen.FromControl(this).WorkingArea.Width-24,Math.Max(Width,choices.Max(value=>TextRenderer.MeasureText(value,Font).Width)+64)):Width;int menuHeight=12;menu=new GlassDropDown{BackColor=Color.FromArgb(24,42,79),ForeColor=GlassInk.White,Font=Font,ShowImageMargin=false,ShowCheckMargin=false,AutoSize=false,Width=menuWidth,Padding=new Padding(6),Renderer=new GlassMenuRenderer()};for(int i=0;i<choices.Count;i++){int index=i;var value=choices[i];var item=new ToolStripMenuItem(value){ForeColor=ItemColor==null?GlassInk.White:ItemColor(index),BackColor=Color.FromArgb(24,42,79),AutoSize=false,Width=menuWidth-12,Height=FullItemText?Math.Max(40,TextRenderer.MeasureText(value,Font,new Size(menuWidth-56,0),TextFormatFlags.WordBreak|TextFormatFlags.NoPrefix).Height+16):40,Tag=FullItemText,Checked=MultiSelect?checkedIndices.Contains(i):value==SelectedItem};if(value.Contains("Популярные сервисы 7")){item.ForeColor=Color.Gold;item.Font=new Font(Font,FontStyle.Bold);}if(ItemHint!=null)item.ToolTipText=ItemHint(index);item.Click+=(s,args)=>{if(MultiSelect){SetChecked(index,!checkedIndices.Contains(index));item.Checked=checkedIndices.Contains(index);}else SelectedItem=value;};menu.Items.Add(item);menuHeight+=item.Height;}menu.Closing+=(s,args)=>{if(MultiSelect&&args.CloseReason==ToolStripDropDownCloseReason.ItemClicked)args.Cancel=true;};menu.Height=Math.Min(menuHeight,Screen.FromControl(this).WorkingArea.Height-32);menu.Closed+=(s,args)=>Invalidate();menu.Show(this,new Point(0,Height+5));Invalidate();}
    protected override void OnKeyDown(KeyEventArgs e){base.OnKeyDown(e);if(e.KeyCode==Keys.Space||e.KeyCode==Keys.Enter||e.KeyCode==Keys.Down){OnClick(EventArgs.Empty);e.Handled=true;}}
    protected override void Dispose(bool disposing){if(disposing&&menu!=null){menu.Dispose();menu=null;}base.Dispose(disposing);}
    protected override void OnPaint(PaintEventArgs e){var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;var r=new Rectangle(1,1,Width-3,Height-3);using(var p=UiShape.Round(r,12)){if(FlatSurface||UiTheme.Simple){using(var fill=new SolidBrush(SurfaceColor==null?Color.FromArgb(29,39,64):SurfaceColor()))g.FillPath(fill,p);using(var edge=new Pen(Color.FromArgb(61,75,103)))g.DrawPath(edge,p);}else using(var fill=new LinearGradientBrush(r,Color.FromArgb(43,76,132),Color.FromArgb(24,39,87),LinearGradientMode.Horizontal))g.FillPath(fill,p);}string text=DisplaySelection;GlassInk.Text(g,text,new RectangleF(15,4,Width-54,Height-8),10,Enabled?(text.Contains("Популярные сервисы 7")?Color.Gold:ItemColor!=null&&selected>=0?ItemColor(selected):GlassInk.White):GlassInk.Muted,text.Contains("Популярные сервисы 7"));if(MultiSelect||choices.Count>1)using(var arrow=new Pen(GlassInk.Muted,1.8f)){arrow.StartCap=LineCap.Round;arrow.EndCap=LineCap.Round;arrow.LineJoin=LineJoin.Round;float x=Width-25,y=Height/2f;g.DrawLines(arrow,new[]{new PointF(x-4,y-2),new PointF(x,y+2),new PointF(x+4,y-2)});}}
  }

  internal sealed class GlassDropDown : ContextMenuStrip {
    ToolStripItem[] scrollItems;int offset;
    public GlassDropDown(){DropShadowEnabled=false;}
    protected override void OnOpening(System.ComponentModel.CancelEventArgs e){
      if(scrollItems==null&&Items.Count>7){scrollItems=Items.Cast<ToolStripItem>().ToArray();Height=252;Shift(0);}
      base.OnOpening(e);
    }
    void Shift(int delta){if(scrollItems==null)return;offset=Math.Max(0,Math.Min(scrollItems.Length-6,offset+delta));SuspendLayout();try{for(int i=0;i<scrollItems.Length;i++)scrollItems[i].Available=i>=offset&&i<offset+6;}finally{ResumeLayout(true);}Invalidate();}
    protected override void OnMouseWheel(MouseEventArgs e){if(scrollItems!=null){Shift((e.Delta>0?-1:1)*Math.Max(1,Math.Abs(e.Delta)/120)*3);return;}base.OnMouseWheel(e);}
    protected override CreateParams CreateParams{get{var p=base.CreateParams;p.Style&=~0x00800000;p.ExStyle&=~0x00000301;p.ClassStyle&=~0x00020000;return p;}}
    protected override void OnSizeChanged(EventArgs e){base.OnSizeChanged(e);if(Width<2||Height<2)return;using(var path=UiShape.Round(new Rectangle(0,0,Width,Height),12)){var old=Region;Region=new Region(path);if(old!=null)old.Dispose();}}
  }
  internal sealed class GlassMenuRenderer : ToolStripProfessionalRenderer {
    protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e){}
    protected override void OnRenderToolStripBackground(ToolStripRenderEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;using(var path=UiShape.Round(e.AffectedBounds,12))using(var b=new LinearGradientBrush(e.AffectedBounds,Color.FromArgb(34,53,94),Color.FromArgb(22,31,65),90f))e.Graphics.FillPath(b,path);}
    protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e){var item=e.Item as ToolStripMenuItem;if(!e.Item.Selected&&(item==null||!item.Checked))return;e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;using(var p=UiShape.Round(new Rectangle(0,1,e.Item.Width,e.Item.Height-2),8))using(var b=new SolidBrush(e.Item.Selected?Color.FromArgb(120,89,116,190):Color.FromArgb(70,82,107,177)))e.Graphics.FillPath(b,p);}
    protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e){}
    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e){
      var item=e.Item as ToolStripMenuItem;bool current=item!=null&&item.Checked;
      TextRenderer.DrawText(e.Graphics,e.Text,e.TextFont,new Rectangle(32,0,e.Item.Width-44,e.Item.Height),e.Item.ForeColor,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|(e.Item.Tag is bool&&(bool)e.Item.Tag?TextFormatFlags.WordBreak:TextFormatFlags.SingleLine|TextFormatFlags.EndEllipsis)|TextFormatFlags.NoPrefix);
      if(current){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;using(var pen=new Pen(GlassInk.Mint,2)){pen.StartCap=LineCap.Round;pen.EndCap=LineCap.Round;float y=e.Item.Height/2f;e.Graphics.DrawLines(pen,new[]{new PointF(10,y),new PointF(14,y+4),new PointF(22,y-5)});}}
    }
  }

  internal sealed class GlassFieldPanel : Panel {
    public static readonly Color InputColor=Color.FromArgb(29,46,80);
    public GlassFieldPanel(){SetStyle(ControlStyles.SupportsTransparentBackColor,true);DoubleBuffered=true;BackColor=Color.Transparent;}
    protected override void OnPaintBackground(PaintEventArgs e){base.OnPaintBackground(e);var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;if(Width<4||Height<4)return;using(var path=UiShape.Round(new Rectangle(1,1,Width-3,Height-3),12)){using(var fill=new SolidBrush(InputColor))g.FillPath(fill,path);}}
  }

  internal sealed class BlueTrafficDetails : Control {
    readonly VpnTraffic traffic;readonly Func<GlassState> state;
    readonly Timer repaint=new Timer{Interval=1000};int historyOffset,windowSamples=120;bool dragging;Rectangle historyRail;
    internal int HistoryOffset{get{return historyOffset;}}
    void Seek(int x){int max=Math.Max(0,traffic.SampleTimes.Length-windowSamples);historyOffset=(int)(max*(1-Math.Max(0,Math.Min(1,(x-historyRail.Left)/(float)Math.Max(1,historyRail.Width)))));Invalidate();}
    protected override void OnMouseWheel(MouseEventArgs e){historyOffset=Math.Max(0,Math.Min(Math.Max(0,traffic.SampleTimes.Length-windowSamples),historyOffset+(e.Delta>0?30:-30)));Invalidate();}
    protected override void OnMouseDown(MouseEventArgs e){base.OnMouseDown(e);if(e.Y>=117&&e.Y<=151&&e.X>=Width-330){int choice=Math.Min(2,Math.Max(0,(e.X-(Width-330))/104));windowSamples=new[]{120,300,900}[choice];historyOffset=0;Invalidate();return;}if(historyRail.Contains(e.Location)){dragging=true;Capture=true;Seek(e.X);}}
    protected override void OnMouseMove(MouseEventArgs e){base.OnMouseMove(e);if(dragging)Seek(e.X);}
    protected override void OnMouseUp(MouseEventArgs e){dragging=false;Capture=false;base.OnMouseUp(e);}
    public BlueTrafficDetails(VpnTraffic source,Func<GlassState> provider){SetStyle(ControlStyles.SupportsTransparentBackColor,true);traffic=source;state=provider;DoubleBuffered=true;Dock=DockStyle.Fill;BackColor=Color.Transparent;repaint.Tick+=(s,e)=>Invalidate();repaint.Start();Disposed+=(s,e)=>repaint.Dispose();}
    protected override void OnPaint(PaintEventArgs e){
      base.OnPaint(e);var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;var s=state();int w=Width,h=Height;
      int labelWidth=(int)(w*.30);
      GlassInk.Text(g,s.Running?"VPN включён":"VPN выключен",new RectangleF(18,10,labelWidth-24,34),18,s.Running?GlassInk.Mint:GlassInk.White,true);
      ServerIdentity.Flag(g,new Rectangle(19,56,25,25),ServerIdentity.Country(s.Server));
      GlassInk.Text(g,ServerIdentity.Display(s.Server),new RectangleF(55,53,labelWidth-64,30),11,GlassInk.Muted);
      int metricWidth=(w-labelWidth-42)/3;
      string[] labels={"Загрузка","Отдача","За сессию"},values={GlassInk.Rate(traffic.DownKbps),GlassInk.Rate(traffic.UpKbps),GlassInk.Bytes(traffic.DownBytes+traffic.UpBytes)};
      Color[] colors={GlassInk.Cyan,GlassInk.Purple,GlassInk.White};
      for(int i=0;i<3;i++){var r=new Rectangle(labelWidth+i*(metricWidth+10),8,metricWidth,86);GlassInk.Bubble(g,r,colors[i]);GlassInk.Text(g,labels[i],new RectangleF(r.X+16,r.Y+10,r.Width-32,23),10,GlassInk.Muted);GlassInk.Text(g,values[i],new RectangleF(r.X+16,r.Y+35,r.Width-32,35),18,colors[i],true);}
      GlassInk.Text(g,"Активность соединения",new RectangleF(18,117,w-36,30),13,GlassInk.White,true);
      for(int i=0;i<3;i++){var button=new Rectangle(w-330+i*104,117,96,32);using(var shape=UiShape.Round(button,10))using(var brush=new SolidBrush(windowSamples==new[]{120,300,900}[i]?Color.FromArgb(145,85,66,148):Color.FromArgb(65,68,83,121)))g.FillPath(brush,shape);GlassInk.Text(g,new[]{"2 мин","5 мин","15 мин"}[i],button,10,GlassInk.White,false,StringAlignment.Center);}
      var down=traffic.DownSamples;var up=traffic.UpSamples;var times=traffic.SampleTimes;int count=Math.Min(down.Length,Math.Min(up.Length,times.Length));historyOffset=Math.Min(historyOffset,Math.Max(0,count-windowSamples));DateTime endTime=historyOffset==0?DateTime.Now:times[Math.Max(0,count-historyOffset-1)];int start=VpnTraffic.WindowStart(times,windowSamples,endTime),length=Math.Max(0,count-historyOffset-start);
      var chart=new Rectangle(18,161,w-36,Math.Max(40,h-295));GlassInk.Chart(g,chart,down.Skip(start).Take(length).ToArray(),up.Skip(start).Take(length).ToArray());
      GlassInk.TimeAxis(g,new Rectangle(chart.X,chart.Bottom+6,chart.Width,25),times.Skip(start).Take(length).ToArray());
      historyRail=new Rectangle(chart.X,chart.Bottom+39,chart.Width,16);
      using(var shape=UiShape.Round(historyRail,8))using(var brush=new SolidBrush(Color.FromArgb(65,111,130,178)))g.FillPath(brush,shape);
      float fraction=count<=windowSamples?1:windowSamples/(float)count;int thumb=Math.Max(36,(int)(historyRail.Width*fraction)),max=Math.Max(0,count-windowSamples);
      int thumbX=historyRail.X+(int)((historyRail.Width-thumb)*(max==0?1:1-historyOffset/(float)max));
      using(var shape=UiShape.Round(new Rectangle(thumbX,historyRail.Y+3,thumb,10),5))using(var brush=new SolidBrush(GlassInk.Purple))g.FillPath(brush,shape);
      GlassInk.Text(g,historyOffset==0?"Сейчас · колесо мыши и полоса — просмотр истории":"История · прокрутите вправо, чтобы вернуться к текущему",new RectangleF(18,historyRail.Bottom+4,w-36,24),9,GlassInk.Muted);
      if(!traffic.Available&&length==0)GlassInk.Text(g,s.Running?"Ожидаем данные VPN":"Подключите VPN, чтобы увидеть статистику",new RectangleF(chart.X+20,chart.Y+chart.Height/2-20,chart.Width-40,40),12,GlassInk.Muted,false,StringAlignment.Center);
      GlassInk.Text(g,"↓ Получено "+GlassInk.Bytes(traffic.DownBytes),new RectangleF(18,h-45,w/2-18,29),11,GlassInk.Cyan,true);
      GlassInk.Text(g,"↑ Отправлено "+GlassInk.Bytes(traffic.UpBytes),new RectangleF(w/2,h-45,w/2-18,29),11,GlassInk.Purple,true);
    }
  }
}
