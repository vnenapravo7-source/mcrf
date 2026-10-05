using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Windows.Forms;

namespace SplifyWin {
  internal sealed partial class BlueGlassDashboard {
    Image neonGlobe;
    void DrawSimple(Graphics g){
      var s=state();areas.Clear();g.SmoothingMode=SmoothingMode.AntiAlias;
      using(var background=new LinearGradientBrush(ClientRectangle,Color.FromArgb(10,17,32),Color.FromArgb(28,20,51),LinearGradientMode.ForwardDiagonal))g.FillRectangle(background,ClientRectangle);
      if(neonGlobe==null)using(var stream=GetType().Assembly.GetManifestResourceStream("SplifyWin.UI.NeonGlobe.png"))if(stream!=null)using(var source=Image.FromStream(stream))neonGlobe=new Bitmap(source);
      if(neonGlobe!=null){int width=Math.Min(Width-260,1200),height=width*2/3;var rect=new Rectangle(Width-width-10,160,width,height);using(var attributes=new System.Drawing.Imaging.ImageAttributes()){var matrix=new System.Drawing.Imaging.ColorMatrix();matrix.Matrix33=.24f;attributes.SetColorMatrix(matrix);g.DrawImage(neonGlobe,rect,0,0,neonGlobe.Width,neonGlobe.Height,GraphicsUnit.Pixel,attributes);}}
      GlassInk.SoftGlow(g,new Rectangle(20,Height-340,530,450),GlassInk.Purple);
      using(var fill=new SolidBrush(Color.FromArgb(19,23,41)))g.FillRectangle(fill,0,0,218,Height);
      BrandArt.DrawPlane(g,new Rectangle(26,26,36,36),GlassInk.Cyan);areas["repository"]=new Rectangle(20,20,180,60);GlassInk.Text(g,"MCRF",new Rectangle(75,22,125,42),23,GlassInk.White,true);
      var keys=new[]{"home","servers","routes","exits","warp","tgws","zapret","hosts","byetube","logs","updates","settings"};
      var labels=new[]{"Главная","Серверы","Маршрутизация","Выходы и DNS","WARP","Telegram WS","Zapret","hosts","ByeTube","Журнал","Обновления","Настройки"};
      for(int i=0;i<keys.Length;i++){var r=new Rectangle(16,88+i*Math.Min(45,Math.Max(32,(Height-108)/keys.Length)),186,42);areas[keys[i]]=r;if(hover==keys[i]||(String.IsNullOrEmpty(s.Page)?"home":s.Page)==keys[i])using(var fill=new SolidBrush(Color.FromArgb(53,46,86)))using(var path=UiShape.Round(r,10))g.FillPath(fill,path);GlassInk.Text(g,labels[i],new Rectangle(r.X+22,r.Y,r.Width-32,r.Height),11,GlassInk.Muted,false,StringAlignment.Near);}
      int cx=Width-142;foreach(var key in new[]{"minimize","maximize","close"}){var r=new Rectangle(cx,18,36,36);areas[key]=r;GlassInk.Text(g,key=="minimize"?"−":key=="maximize"?"□":"×",r,16,GlassInk.Muted,false,StringAlignment.Center);cx+=42;}
      areas["design"]=new Rectangle(Width-368,18,210,36);SimpleCard(g,areas["design"]);GlassInk.Text(g,"Базовый интерфейс",areas["design"],10,GlassInk.White,false,StringAlignment.Center);
      int left=250,w=Width-left-30;GlassInk.Text(g,"Ваше подключение",new Rectangle(left,74,w,54),27,GlassInk.White,true);
      var card=new Rectangle(left,152,w,262);SimpleCard(g,card);areas["connection"]=new Rectangle(card.Right-242,card.Bottom-112,214,46);SimpleCard(g,areas["connection"],s.Running?Color.FromArgb(72,47,129):Color.FromArgb(156,250,93));GlassInk.Text(g,s.Connecting?"Подключаем…":s.Running?"Отключить":"Подключить VPN",areas["connection"],13,s.Running?GlassInk.White:Color.FromArgb(14,38,22),true,StringAlignment.Center);
      GlassInk.Text(g,s.Running?"VPN включён":"VPN выключен",new Rectangle(left+26,174,w-52,38),20,s.Running?GlassInk.Mint:GlassInk.White,true);
      ServerIdentity.Flag(g,new Rectangle(left+26,229,28,28),ServerIdentity.Country(s.Server));GlassInk.Text(g,ServerIdentity.ListName(s.Server),new Rectangle(left+66,222,w-92,40),17,GlassInk.White);
      GlassInk.Text(g,s.Protocol+" · DNS: "+s.Dns,new Rectangle(left+26,270,w-52,28),10,GlassInk.Muted);
      int moduleWidth=(w-56)/3,moduleY=card.Bottom-48;ModuleSwitch(g,"toggle-zapret","Zapret",new Rectangle(left+20,moduleY,moduleWidth,28),s.ZapretRunning,s.ZapretPicking);ModuleSwitch(g,"toggle-tg","Telegram прокси",new Rectangle(left+28+moduleWidth,moduleY,moduleWidth,28),s.TelegramRunning,false);ModuleSwitch(g,"toggle-warp","WARP",new Rectangle(left+36+moduleWidth*2,moduleY,moduleWidth,28),s.WarpRunning,s.Connecting);
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
      state.SimpleDesign=!state.SimpleDesign;state.AdvancedMode=state.SimpleDesign;state.SettingsModeChosen=true;UiTheme.Simple=state.SimpleDesign;store.Save(state);blueDashboard.RefreshTheme();ShowPage("");
    }
    void ExitsLegacyBlue(){
      var box=Box();box.Dock=DockStyle.Fill;content.Controls.Add(box);
      var title=L("Выход → подключение → DNS",15,true);title.SetBounds(22,16,box.Width-44,32);box.Controls.Add(title);
      var targets=new[]{"proxy","tgws","direct"};var names=new[]{"VPN / WARP","Telegram WS","Напрямую / Zapret"};
      var providers=DnsOptions.Providers.Where(p=>p!="Системный").ToArray();var picks=new GlassPicker[3];var inputs=new TextBox[3];
      var protocols=new GlassPicker[3];
      int dnsX=Math.Max(300,box.Width/2);
      for(int i=0;i<targets.Length;i++){
        int index=i,y=74+i*94;var name=L(names[i],11,true);name.SetBounds(22,y,180,25);box.Controls.Add(name);
        var selected=L(i==0?(Selected()==null?"Сервер не выбран":ServerIdentity.ListName(Selected().Name)):i==1?"127.0.0.1:"+telegramBridge.Port:"Без туннеля · профили Zapret",10,false,Muted);selected.AutoSize=false;selected.SetBounds(22,y+30,dnsX-46,48);box.Controls.Add(selected);
        var picker=new GlassPicker{FlatSurface=true,Name="exitDnsChoice"+targets[i],Location=new Point(dnsX,y),Width=box.Width-dnsX-22};picks[i]=picker;picker.SetItems(new[]{"Автоматически","Общий"}.Concat(providers));box.Controls.Add(picker);
        var existing=(state.ExitDns??new System.Collections.Generic.List<ExitDnsSettings>()).FirstOrDefault(d=>d.Target==targets[index]);
        var proto=new GlassPicker{Name="exitDnsProtocol"+targets[i],FlatSurface=true,Location=new Point(box.Width-177,y),Width=155};protocols[i]=proto;box.Controls.Add(proto);
        var input=T();input.Name="exitDnsCustom"+targets[i];input.BorderStyle=BorderStyle.None;input.BackColor=GlassFieldPanel.InputColor;inputs[i]=input;
        var field=new GlassFieldPanel{Location=new Point(dnsX,y+46),Size=new Size(Math.Max(100,box.Width-dnsX-22),36)};input.SetBounds(10,8,field.Width-20,24);field.Controls.Add(input);box.Controls.Add(field);
        picker.SelectedIndexChanged+=(a,b)=>{bool specific=picker.SelectedIndex>=2;proto.Visible=specific;picker.Width=specific?box.Width-dnsX-189:box.Width-dnsX-22;field.Visible=specific&&picker.SelectedItem=="Свой";if(specific){proto.SetItems(DnsOptions.Protocols(picker.SelectedItem));proto.SelectedIndex=0;}};
        picker.SelectedItem=existing==null?"Общий":existing.Provider=="Системный"?"Автоматически":existing.Provider;proto.SelectedItem=existing==null?"UDP":existing.Protocol;input.Text=existing==null?"":existing.Custom??"";
      }
      var sharedLabel=L("Общий DNS",11,true);sharedLabel.SetBounds(22,372,180,28);box.Controls.Add(sharedLabel);
      var sharedProvider=new GlassPicker{FlatSurface=true,Name="sharedDnsProvider",Location=new Point(210,368),Width=210};sharedProvider.SetItems(DnsOptions.Providers);box.Controls.Add(sharedProvider);
      var sharedProtocol=new GlassPicker{FlatSurface=true,Name="sharedDnsProtocol",Location=new Point(434,368),Width=155};box.Controls.Add(sharedProtocol);
      var sharedInput=T();sharedInput.BorderStyle=BorderStyle.None;sharedInput.BackColor=GlassFieldPanel.InputColor;var sharedField=new GlassFieldPanel{Location=new Point(603,368),Size=new Size(Math.Max(120,box.Width-625),38)};sharedInput.SetBounds(10,8,sharedField.Width-20,24);sharedField.Controls.Add(sharedInput);box.Controls.Add(sharedField);
      sharedProvider.SelectedIndexChanged+=(a,b)=>{sharedProtocol.SetItems(DnsOptions.Protocols(sharedProvider.SelectedItem));sharedProtocol.SelectedIndex=0;sharedField.Visible=sharedProvider.SelectedItem=="Свой";};sharedProvider.SelectedItem=state.DnsProvider;sharedProtocol.SelectedItem=state.DnsProtocol;sharedInput.Text=state.DnsCustom??"";
      var hint=L("Изменения DNS применятся при следующем подключении.",10,false,Muted);hint.AutoSize=false;hint.SetBounds(22,422,box.Width-44,62);box.Controls.Add(hint);
      var save=B("Сохранить выходы",async(a,b)=>{try{
        var configured=new System.Collections.Generic.List<ExitDnsSettings>();
        for(int i=0;i<3;i++){if(picks[i].SelectedIndex==1)continue;if(picks[i].SelectedIndex<0)throw new InvalidOperationException("Выберите DNS каждого выхода");var item=new ExitDnsSettings{Target=targets[i],Provider=picks[i].SelectedIndex==0?"Системный":picks[i].SelectedItem,Protocol=picks[i].SelectedIndex==0?"Системный":protocols[i].SelectedItem,Custom=inputs[i].Text.Trim()};DnsOptions.Build(new ClientState{DnsProvider=item.Provider,DnsProtocol=item.Protocol,DnsCustom=item.Custom});configured.Add(item);}
        DnsOptions.Build(new ClientState{DnsProvider=sharedProvider.SelectedItem,DnsProtocol=sharedProtocol.SelectedItem,DnsCustom=sharedInput.Text.Trim()});
        if(!await OfferStopTun())return;var old=state.ExitDns;string oldProvider=state.DnsProvider,oldProtocol=state.DnsProtocol,oldCustom=state.DnsCustom;
        try{state.ExitDns=configured;state.DnsProvider=sharedProvider.SelectedItem;state.DnsProtocol=sharedProtocol.SelectedItem;state.DnsCustom=sharedInput.Text.Trim();store.Save(state);}catch{state.ExitDns=old;state.DnsProvider=oldProvider;state.DnsProtocol=oldProtocol;state.DnsCustom=oldCustom;throw;}
        Toast("DNS выходов сохранён. Применится при следующем подключении.");
      }catch(Exception ex){GlassNotice.Show(this,ex.Message,"Выходы и DNS");}},true);save.SetBounds(22,box.Height-54,200,38);save.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(save);
      var modules=B("WARP и Telegram WS",(a,b)=>ShowPage("modules"));modules.SetBounds(234,box.Height-54,220,38);modules.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(modules);
      var hosts=B("Исправления hosts",(a,b)=>ShowPage("hosts"));hosts.SetBounds(466,box.Height-54,210,38);hosts.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(hosts);
    }
  }
}

