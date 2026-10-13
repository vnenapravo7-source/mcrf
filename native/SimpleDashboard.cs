using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Windows.Forms;

namespace SplifyWin {
  internal sealed partial class BlueGlassDashboard {
    Image neonGlobe;
    Panel sidebarSwitches;GlassToggle[] sidebarToggles;bool sidebarSynchronizing;
    void UpdateSidebarSwitches(){
      var current=state();bool visible=UiTheme.Simple&&current.AdvancedMode;
      if(sidebarSwitches==null&&visible){
        sidebarSwitches=new Panel{Name="advancedSidebarSwitches",BackColor=Color.FromArgb(19,23,41)};Controls.Add(sidebarSwitches);
        string[] labels={"Zapret","Telegram WS","WARP","TUN"},commands={"toggle-zapret","toggle-tg","toggle-warp","toggle-tun"};sidebarToggles=new GlassToggle[4];
        for(int i=0;i<4;i++){string command=commands[i];var toggle=new GlassToggle{Name="sidebar"+new[]{"Zapret","Telegram","Warp","Tun"}[i],Text=labels[i],ShowStateText=false};toggle.SetBounds(8,4+i*40,180,32);toggle.CheckedChanged+=(sender,args)=>{if(!sidebarSynchronizing)action(command);};sidebarToggles[i]=toggle;sidebarSwitches.Controls.Add(toggle);}
      }
      if(sidebarSwitches==null)return;sidebarSwitches.Visible=visible;if(!visible)return;
      sidebarSwitches.SetBounds(12,Height-180,194,164);sidebarSynchronizing=true;try{
        bool[] enabled={current.ZapretRunning,current.TelegramRunning,current.WarpRunning,current.TunRunning};for(int i=0;i<4;i++){sidebarToggles[i].Checked=enabled[i];sidebarToggles[i].Enabled=!current.Connecting&&!current.ZapretPicking&&(i!=3||current.Mode=="tun");}
      }finally{sidebarSynchronizing=false;}
    }
    void DrawSimple(Graphics g){
      var s=state();bool sectionVisible=s.AdvancedMode&&!String.IsNullOrEmpty(s.Page);areas.Clear();g.SmoothingMode=SmoothingMode.AntiAlias;
      using(var background=new LinearGradientBrush(ClientRectangle,Color.FromArgb(10,17,32),Color.FromArgb(28,20,51),LinearGradientMode.ForwardDiagonal))g.FillRectangle(background,ClientRectangle);
      if(neonGlobe==null)using(var stream=GetType().Assembly.GetManifestResourceStream("SplifyWin.UI.NeonGlobe.png"))if(stream!=null)using(var source=Image.FromStream(stream))neonGlobe=new Bitmap(source);
      if(!sectionVisible&&neonGlobe!=null){int width=Math.Min(Width-260,1200),height=width*2/3;var rect=new Rectangle(Width-width-10,160,width,height);using(var attributes=new System.Drawing.Imaging.ImageAttributes()){var matrix=new System.Drawing.Imaging.ColorMatrix();matrix.Matrix33=.24f;attributes.SetColorMatrix(matrix);g.DrawImage(neonGlobe,rect,0,0,neonGlobe.Width,neonGlobe.Height,GraphicsUnit.Pixel,attributes);}}
      if(!sectionVisible)GlassInk.SoftGlow(g,new Rectangle(20,Height-340,530,450),GlassInk.Purple);
      using(var fill=new SolidBrush(Color.FromArgb(19,23,41)))g.FillRectangle(fill,0,0,218,Height);
      BrandArt.DrawLogo(g,new Rectangle(26,26,36,36),GlassInk.Cyan);areas["repository"]=new Rectangle(20,20,180,60);GlassInk.Text(g,"MCRF",new Rectangle(75,22,125,42),23,GlassInk.White,true);
      var keys=state().AdvancedMode?new[]{"home","servers","routes","warp","tgws","zapret","hosts","logs","updates"}:new[]{"home","servers","routes","warp","tgws","zapret","hosts","logs","updates","settings"};
      var labels=state().AdvancedMode?new[]{"Главная","Серверы VPN","Маршрутизация","WARP","Telegram WS","Zapret","hosts","Журнал","Обновления"}:new[]{"Главная","Серверы VPN","Маршрутизация","WARP","Telegram WS","Zapret","hosts","Журнал","Обновления","Настройки"};
      int navStep=Math.Min(45,Math.Max(32,(Height-108-(s.AdvancedMode?188:0))/keys.Length));for(int i=0;i<keys.Length;i++){var r=new Rectangle(16,88+i*navStep,186,Math.Min(42,navStep-2));areas[keys[i]]=r;if(hover==keys[i]||(String.IsNullOrEmpty(s.Page)?"home":s.Page)==keys[i])using(var fill=new SolidBrush((String.IsNullOrEmpty(s.Page)?"home":s.Page)==keys[i]?Color.FromArgb(53,46,86):Color.FromArgb(30,36,57)))using(var path=UiShape.Round(r,10))g.FillPath(fill,path);GlassInk.Text(g,labels[i],new Rectangle(r.X+22,r.Y,r.Width-32,r.Height),labels[i].Length>18?10:11,GlassInk.Muted,false,StringAlignment.Near);}
      int cx=Width-142;foreach(var key in new[]{"minimize","maximize","close"}){var r=new Rectangle(cx,18,36,36);areas[key]=r;GlassInk.Text(g,key=="minimize"?"−":key=="maximize"?"□":"×",r,16,GlassInk.Muted,false,StringAlignment.Center);cx+=42;}
      areas["design"]=new Rectangle(Width-368,18,210,36);SimpleCard(g,areas["design"]);GlassInk.Text(g,"Базовый интерфейс",areas["design"],10,GlassInk.White,false,StringAlignment.Center);
      if(sectionVisible)return;int left=250,w=Width-left-30;GlassInk.Text(g,"Ваше подключение",new Rectangle(left,74,w,54),27,GlassInk.White,true);
      var card=new Rectangle(left,152,w,262);SimpleCard(g,card);areas["connection"]=new Rectangle(card.Right-242,card.Bottom-112,214,46);SimpleCard(g,areas["connection"],s.Running?Color.FromArgb(72,47,129):Color.FromArgb(156,250,93));GlassInk.Text(g,s.Connecting?"Подключаем…":s.Running?"Отключить":"Подключить VPN",areas["connection"],13,s.Running?GlassInk.White:Color.FromArgb(14,38,22),true,StringAlignment.Center);
      GlassInk.Text(g,s.Running?"VPN включён":"VPN выключен",new Rectangle(left+26,174,w-52,38),20,s.Running?GlassInk.Mint:GlassInk.White,true);
      ServerIdentity.Flag(g,new Rectangle(left+26,229,28,28),ServerIdentity.Country(s.Server));GlassInk.Text(g,ServerIdentity.ListName(s.Server),new Rectangle(left+66,222,w-92,40),17,GlassInk.White);
      GlassInk.Text(g,s.Protocol+" · DNS: "+s.Dns,new Rectangle(left+26,270,w-52,28),10,GlassInk.Muted);
      if(!s.AdvancedMode){int moduleWidth=(w-56)/3,moduleY=card.Bottom-48;ModuleSwitch(g,"toggle-zapret","Zapret",new Rectangle(left+20,moduleY,moduleWidth,28),s.ZapretRunning,s.ZapretPicking);ModuleSwitch(g,"toggle-tg","Telegram прокси",new Rectangle(left+28+moduleWidth,moduleY,moduleWidth,28),s.TelegramRunning,false);ModuleSwitch(g,"toggle-warp","WARP",new Rectangle(left+36+moduleWidth*2,moduleY,moduleWidth,28),s.WarpRunning,s.Connecting);}
      if(s.AdvancedMode){var chart=new Rectangle(left,434,Math.Max(430,(int)(w*.65)),Math.Max(145,Height-538));SimpleCard(g,chart);areas["traffic"]=chart;
      GlassInk.Text(g,"Трафик через VPN",new Rectangle(chart.X+22,chart.Y+12,chart.Width-44,32),14,GlassInk.White,true);
      int sampleStart=VpnTraffic.WindowStart(traffic.SampleTimes,120,DateTime.Now);GlassInk.Chart(g,new Rectangle(chart.X+22,chart.Y+58,chart.Width-44,Math.Max(50,chart.Height-82)),traffic.DownSamples.Skip(sampleStart).ToArray(),traffic.UpSamples.Skip(sampleStart).ToArray());
      }
    }
    static void SimpleCard(Graphics g,Rectangle r,Color? color=null){using(var path=UiShape.Round(r,17)){using(var fill=new SolidBrush(color??Color.FromArgb(222,27,32,51)))g.FillPath(fill,path);using(var edge=new Pen(Color.FromArgb(62,66,99)))g.DrawPath(edge,path);}}
  }
  public sealed partial class MainForm {
    void ToggleDesign(){
      if(discordVoiceChecking||connecting||setupCancellation!=null||zapretCancellation!=null||warpCancellation!=null){Toast("Дождитесь завершения или отмените текущую проверку перед сменой оформления.");return;}
      state.SimpleDesign=!state.SimpleDesign;state.AdvancedMode=state.SimpleDesign;state.SettingsModeChosen=true;UiTheme.Simple=state.SimpleDesign;store.Save(state);blueDashboard.RefreshTheme();ShowPage(state.AdvancedMode?"home":"");
    }
  }
}
