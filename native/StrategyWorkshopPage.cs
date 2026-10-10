using System;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
namespace SplifyWin {
 internal sealed class HomeScrollSurface : Panel {
  readonly Panel canvas=new Panel{BackColor=Color.FromArgb(12,17,31)};readonly HomeScrollRail rail;int offset;
  public Panel Canvas{get{return canvas;}}
  public int Offset{get{return offset;}}
  public int Range{get{return Math.Max(0,canvas.Height-ClientSize.Height);}}
  public HomeScrollSurface(){DoubleBuffered=true;AutoScroll=false;BackColor=Color.Transparent;rail=new HomeScrollRail(this);Controls.Add(canvas);Controls.Add(rail);}
  public void MoveTo(int value){int next=Math.Max(0,Math.Min(Range,value));bool moved=offset!=next;offset=next;canvas.Top=-offset;if(moved){canvas.Invalidate(true);Invalidate();}rail.Invalidate();}
  public void WireWheel(Control root){root.MouseWheel+=(s,e)=>{MoveTo(offset-e.Delta/120*60);var handled=e as HandledMouseEventArgs;if(handled!=null)handled.Handled=true;};foreach(Control child in root.Controls)WireWheel(child);}
  public void UpdateLayout(){canvas.Width=Math.Max(1,ClientSize.Width-18);rail.SetBounds(ClientSize.Width-12,0,12,ClientSize.Height);rail.Visible=Range>0;rail.BringToFront();MoveTo(offset);}
  protected override void OnResize(EventArgs e){base.OnResize(e);if(rail!=null)UpdateLayout();}
  protected override void OnMouseWheel(MouseEventArgs e){MoveTo(offset-e.Delta/120*60);}
 }
 internal sealed class HomeScrollRail : Control {
  readonly HomeScrollSurface owner;bool dragging;int grab;
  public HomeScrollRail(HomeScrollSurface surface){owner=surface;DoubleBuffered=true;SetStyle(ControlStyles.SupportsTransparentBackColor,true);BackColor=Color.Transparent;Cursor=Cursors.Hand;}
  int ThumbHeight{get{return Math.Min(Height,Math.Max(32,Height*Height/Math.Max(1,owner.Canvas.Height)));}}
  int ThumbTop{get{return owner.Offset*Math.Max(0,Height-ThumbHeight)/Math.Max(1,owner.Range);}}
  protected override void OnPaint(PaintEventArgs e){if(owner.Range==0)return;e.Graphics.SmoothingMode=System.Drawing.Drawing2D.SmoothingMode.AntiAlias;using(var path=UiShape.Round(new Rectangle(3,ThumbTop,5,ThumbHeight),2))using(var brush=new SolidBrush(Color.FromArgb(130,151,173,222)))e.Graphics.FillPath(brush,path);}
  void Slide(int y){owner.MoveTo((y-grab)*owner.Range/Math.Max(1,Height-ThumbHeight));}
  protected override void OnMouseDown(MouseEventArgs e){base.OnMouseDown(e);if(e.Button!=MouseButtons.Left)return;grab=e.Y>=ThumbTop&&e.Y<=ThumbTop+ThumbHeight?e.Y-ThumbTop:ThumbHeight/2;dragging=true;Capture=true;Slide(e.Y);}
  protected override void OnMouseMove(MouseEventArgs e){base.OnMouseMove(e);if(dragging)Slide(e.Y);}
  protected override void OnMouseUp(MouseEventArgs e){dragging=false;Capture=false;base.OnMouseUp(e);}
  protected override void OnMouseWheel(MouseEventArgs e){owner.MoveTo(owner.Offset-e.Delta/120*60);}
 }
 public sealed partial class MainForm {
  void AdvancedHomeBlue(){
   var scroll=new HomeScrollSurface{Name="advancedHomeScroll",Dock=DockStyle.Fill};content.Controls.Add(scroll);
   var home=new Panel{Name="advancedHomeSummary",Height=480,BackColor=Color.Transparent};var settings=new Panel{Name="advancedHomeSettings",Top=496,Height=440,BackColor=Color.Transparent};scroll.Canvas.Height=936;scroll.Canvas.Controls.Add(home);scroll.Canvas.Controls.Add(settings);
   var connection=Box();connection.Name="homeConnectionGroup";((CardPanel)connection).OpaqueSurface=true;home.Controls.Add(connection);var hero=new Panel{Name="homeConnectionCard",BackColor=Color.Transparent};var node=new Panel{Name="homeServerCard",BackColor=Color.Transparent};connection.Controls.Add(hero);connection.Controls.Add(node);
   var diagnostic=Box();diagnostic.Name="homeDiagnosticsCard";((CardPanel)diagnostic).OpaqueSurface=true;settings.Controls.Add(diagnostic);
   Action layout=()=>{scroll.UpdateLayout();home.Width=settings.Width=scroll.Canvas.Width;connection.SetBounds(8,8,home.Width-16,464);int width=(connection.Width-16)/2;hero.SetBounds(0,0,width,464);node.SetBounds(width+16,0,connection.Width-width-16,464);diagnostic.SetBounds(8,8,settings.Width-16,424);};scroll.Resize+=(s,e)=>layout();layout();
   Add(hero,L("СОСТОЯНИЕ СОЕДИНЕНИЯ",10,true,Muted),24,22);var status=L("",26,true);status.AutoSize=false;status.SetBounds(24,65,hero.Width-48,46);status.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;hero.Controls.Add(status);
   var sub=L("",10,false,Muted);sub.AutoSize=false;sub.SetBounds(24,117,hero.Width-48,60);sub.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;hero.Controls.Add(sub);
   bool synchronizing=true;var zapretSwitch=new GlassToggle{Name="homeZapretToggle",Text="Zapret"};var warpSwitch=new GlassToggle{Name="homeWarpToggle",Text="WARP"};var tgSwitch=new GlassToggle{Name="homeTelegramToggle",Text="Telegram WS"};int switchY=200;
   foreach(var module in new[]{zapretSwitch,warpSwitch,tgSwitch}){module.SetBounds(24,switchY,hero.Width-48,34);module.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;hero.Controls.Add(module);switchY+=52;}
   zapretSwitch.CheckedChanged+=(s,e)=>{if(!synchronizing)ToggleMainZapret();};warpSwitch.CheckedChanged+=(s,e)=>{if(!synchronizing)ToggleRoutedOutput(true);};tgSwitch.CheckedChanged+=(s,e)=>{if(!synchronizing)ToggleMainTelegram();};
   var connect=B("Подключиться",ToggleConnection,true);connect.Name="homeConnect";connect.SetBounds(24,392,hero.Width-48,44);connect.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;hero.Controls.Add(connect);
   Add(node,L("ТЕКУЩИЙ СЕРВЕР VPN",10,true,Muted),24,22);var name=L("",12,true);name.Name="homeServerName";name.AutoSize=false;name.AutoEllipsis=false;name.SetBounds(65,62,node.Width-89,62);name.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;node.Controls.Add(name);
   var flag=new Panel{BackColor=Color.Transparent,Location=new Point(24,68),Size=new Size(28,28)};flag.Paint+=(s,e)=>{var selected=Selected();ServerIdentity.Flag(e.Graphics,new Rectangle(0,0,28,28),selected==null?"":ServerIdentity.Country(selected.Name));};node.Controls.Add(flag);
   var identity=L("",10,false,Muted);identity.AutoSize=false;identity.SetBounds(24,126,node.Width-48,32);identity.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;node.Controls.Add(identity);
   Add(node,L("Три сервера с самым низким пингом",10,false,Muted),24,162);var recent=new GlassPicker{Name="homeFastestServers",FullItemText=true};recent.SetBounds(24,194,node.Width-48,38);recent.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;node.Controls.Add(recent);
   ServerNode[] fastest=new ServerNode[0];string signature=null;recent.SelectedIndexChanged+=(s,e)=>{if(!synchronizing&&recent.SelectedIndex>0&&recent.SelectedIndex<=fastest.Length)SelectServer(fastest[recent.SelectedIndex-1]);};
   var mode=new GlassPicker{Name="modePicker"};mode.SetItems(new[]{"TUN — по выбранным маршрутам","Локальный прокси"});mode.SelectedIndex=state.Mode=="tun"?0:1;mode.SetBounds(24,250,node.Width-48,38);mode.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;node.Controls.Add(mode);
   var master=B("Мастер настройки",(s,e)=>ShowPage("setup"));master.Name="homeSetup";master.SetBounds(24,246,diagnostic.Width-48,38);master.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;diagnostic.Controls.Add(master);
   var keys=B("Глобальные горячие клавиши",(s,e)=>ShowGlobalHotkeys());keys.Name="settingsHotkeys";keys.SetBounds(24,294,diagnostic.Width-48,38);keys.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;diagnostic.Controls.Add(keys);
   var servers=B("Управление серверами VPN",(s,e)=>ShowPage("servers"));servers.Name="homeServers";servers.SetBounds(24,310,node.Width-48,44);servers.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;node.Controls.Add(servers);
   Add(diagnostic,L("Параметры",16,true),24,18);var ready=L(core.Executable==null?"Движок не найден":"Движок готов",10,true,core.Executable==null?Red:GlassInk.Mint);ready.Name="settingsEngineStatus";Add(diagnostic,ready,24,65);
   Add(diagnostic,L(IsAdministrator()?"Права администратора есть":"Для TUN нужны права администратора",10,false,Muted),24,100);
   var note=L("Проверка VPN проверяет запрос через выбранный выход, а не только пинг сервера.",10,false,Muted);note.AutoSize=false;note.SetBounds(24,136,diagnostic.Width-48,56);note.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;diagnostic.Controls.Add(note);
   var startup=new GlassToggle{Name="windowsStartup",Text="Автозапуск Windows",Checked=windowsStartupEnabled};startup.SetBounds(24,194,diagnostic.Width-48,32);startup.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;startup.CheckedChanged+=(s,e)=>{if(startup.Checked!=windowsStartupEnabled)SetWindowsStartup(startup.Checked);};diagnostic.Controls.Add(startup);
   var verify=B("Проверить VPN",(s,e)=>RunTunTest(),true);verify.Name="settingsVerify";var rights=B("Запросить права",(s,e)=>RequestElevation(""));rights.Name="settingsRights";rights.Visible=!IsAdministrator();var reset=B("Удалить настройки",(s,e)=>ResetSettings());reset.Name="settingsReset";diagnostic.Controls.Add(verify);diagnostic.Controls.Add(rights);diagnostic.Controls.Add(reset);
   Action diagnosticLayout=()=>{int width=(diagnostic.Width-64)/3;master.Anchor=keys.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;master.SetBounds(24,246,diagnostic.Width-48,38);keys.SetBounds(24,294,diagnostic.Width-48,38);verify.SetBounds(24,364,width,40);rights.SetBounds(32+width,364,width,40);reset.SetBounds(diagnostic.Width-width-24,364,width,40);};diagnostic.Resize+=(s,e)=>diagnosticLayout();diagnosticLayout();
   mode.SelectedIndexChanged+=(s,e)=>{if(synchronizing)return;state.Mode=mode.SelectedIndex==0?"tun":"proxy";store.Save(state);verify.Enabled=state.Mode=="tun";blueDashboard.Invalidate();};
   Action refresh=()=>{synchronizing=true;try{status.Text=connecting?"Подключаем…":core.Running?"Подключено":"Отключено";status.ForeColor=core.Running?GlassInk.Mint:Ink;sub.Text=core.Running?(verifiedThisSession?"Удалённый выход проверен":"Движок запущен — проверьте выход"):"Готово к подключению";connect.Text=core.Running?"Отключить":"Подключиться";connect.Enabled=!connecting&&(core.Running||core.Executable!=null&&state.Servers.Count>0);zapretSwitch.Checked=zapret.Running;warpSwitch.Checked=core.Running&&state.WarpEnabled;tgSwitch.Checked=telegramBridge.Running;bool busy=connecting||setupCancellation!=null||zapretCancellation!=null||byetubeCancellation!=null||warpCancellation!=null||discordVoiceChecking;zapretSwitch.Enabled=warpSwitch.Enabled=tgSwitch.Enabled=!busy;mode.Enabled=!core.Running&&!busy;mode.SelectedIndex=state.Mode=="tun"?0:1;verify.Enabled=state.Mode=="tun"&&!busy;rights.Enabled=!core.Running&&!busy;var selected=Selected();name.Text=selected==null?"Сервер не выбран":ServerIdentity.ListName(selected.Name);identity.Text=selected==null?"Добавьте сервер или подписку":ServerIdentity.CountryName(selected.Name)+" · "+selected.TransportLabel;flag.Invalidate();var available=state.Servers.Where(n=>!WarpPicker.IsWarp(n)&&n.Latency>=0).OrderBy(n=>n.Latency).ThenBy(n=>n.Name).Take(3).ToArray();string current=String.Join("|",available.Select(n=>n.Id+":"+n.Latency+":"+n.Name));if(signature!=current){signature=current;fastest=available;recent.SetItems(new[]{fastest.Length==0?"Пинг ещё не измерен":"Выберите сервер"}.Concat(fastest.Select(n=>ServerIdentity.ListName(n.Name)+" · "+n.Latency+" мс")));}recent.SelectedIndex=selected==null?0:Math.Max(0,Array.FindIndex(fastest,n=>n.Id==selected.Id)+1);recent.Enabled=fastest.Length>0&&!busy;}finally{synchronizing=false;}};refresh();var timer=new Timer{Interval=750};timer.Tick+=(s,e)=>refresh();scroll.Disposed+=(s,e)=>{timer.Stop();timer.Dispose();};timer.Start();scroll.WireWheel(scroll.Canvas);
  }
  string zapretDisplayedSet="Zapret";
  void RenderZapretWorkspace(Panel box){
   var sets=new GlassPicker{Name="zapretStrategySet"};sets.SetItems(new[]{"Zapret","ByeTube"});sets.SelectedItem=zapretDisplayedSet;sets.SetBounds(22,12,Math.Min(380,box.Width-44),38);box.Controls.Add(sets);
   var note=L("Интерфейс движка",10,false,Muted);note.SetBounds(sets.Right+15,16,Math.Max(200,box.Width-sets.Right-37),40);box.Controls.Add(note);
   sets.SelectedIndexChanged+=(s,e)=>{if(zapretCancellation!=null||byetubeCancellation!=null){Toast("Дождитесь завершения проверки");sets.SelectedItem=zapretDisplayedSet;return;}zapretDisplayedSet=sets.SelectedItem;ShowPage("zapret");};
   var body=new Panel{Location=new Point(22,64),Size=new Size(box.Width-44,box.Height-84),Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right,BackColor=Color.Transparent};box.Controls.Add(body);
   if(zapretDisplayedSet=="ByeTube"){var surface=Color.FromArgb(20,30,50);((CardPanel)box).SurfaceColor=surface;var scroll=new HomeScrollSurface{Name="byeTubeScroll",Dock=DockStyle.Fill,BackColor=surface};body.Controls.Add(scroll);var inner=scroll.Canvas;inner.BackColor=surface;inner.Height=570;scroll.UpdateLayout();ByeTubeBlue(inner);scroll.WireWheel(inner);}
   else ZapretBlue(body);
  }
  void StrategyWorkshopBlue(){
   if(!state.AdvancedMode){ShowPage("home");return;}EnsureZapretProfiles();zapret.Prepare();
   var box=Box();box.Dock=DockStyle.Fill;((CardPanel)box).OpaqueSurface=true;content.Controls.Add(box);
   var title=L("1 / 3 · Что должно работать?",17,true);title.SetBounds(24,20,box.Width-48,40);box.Controls.Add(title);
   var note=L("Выберите сервисы. MCRF сам создаст варианты из разных основ, изменит параметры подмены и проверит их как в мастере. Непроверенные варианты не применяются. Голос и игровые матчи этим тестом не подтверждаются.",10,false,Muted);note.AutoSize=false;note.SetBounds(24,70,box.Width-48,76);note.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(note);
   var selectedServices=ZapretChecks.Services.Where(ZapretChecks.CanCheckZapret).ToArray();
   var services=new GlassPicker{Name="customStrategyServices",MultiSelect=true,EmptySelectionText="Выберите сервисы",SelectionCaption="Сервисов"};services.SetItems(selectedServices.Select(s=>s.CheckName));for(int i=0;i<selectedServices.Length;i++)services.SetChecked(i,selectedServices[i].Id=="youtube"||selectedServices[i].Id=="discord");box.Controls.Add(services);
   var profiles=state.ZapretProfiles.ToArray();var profile=new GlassPicker{Name="customStrategyProfile"};profile.SetItems(profiles.Select(p=>"Профиль: "+p.Name));profile.SelectedIndex=Math.Max(0,Array.FindIndex(profiles,p=>p.Id==state.SelectedZapretProfileId));box.Controls.Add(profile);
   var name=T();name.Name="customStrategyName";name.Text="Автоподбор";name.MaxLength=48;name.BorderStyle=BorderStyle.None;name.BackColor=GlassFieldPanel.InputColor;var nameHost=new GlassFieldPanel{Name="customStrategyNameSurface"};name.SetBounds(14,11,300,24);name.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;nameHost.Controls.Add(name);box.Controls.Add(nameHost);
   var budget=new GlassPicker{Name="customStrategyBudget"};budget.SetItems(new[]{"Короткий поиск · до 12 вариантов","Расширенный поиск · до 24 вариантов"});budget.SelectedIndex=0;box.Controls.Add(budget);
   var journal=new GlassLogBox{Name="customStrategyJournal",ReadOnly=true,BorderStyle=BorderStyle.None,BackColor=GlassFieldPanel.InputColor,ForeColor=Muted,Font=Font,WordWrap=true};var journalHost=new GlassFieldPanel{Name="customStrategyJournalSurface"};journalHost.Controls.Add(journal);box.Controls.Add(journalHost);journal.InstallRails(journalHost);journalHost.Visible=false;
   var next=B("Далее",null,true);next.Name="customStrategyNext";box.Controls.Add(next);var back=B("На главную",null);back.Name="customStrategyBack";box.Controls.Add(back);var results=B("Результаты",null);results.Name="customStrategyResults";results.Visible=false;box.Controls.Add(results);
   Action layout=()=>{int width=box.Width-48;services.SetBounds(24,152,width,38);profile.SetBounds(24,200,width,38);nameHost.SetBounds(24,248,width,44);name.Width=nameHost.Width-28;budget.SetBounds(24,304,width,38);journalHost.SetBounds(24,152,width,Math.Max(80,box.Height-232));next.SetBounds(24,box.Height-64,280,40);results.SetBounds(316,box.Height-64,150,40);back.SetBounds(box.Width-204,box.Height-64,180,40);};box.Resize+=(s,e)=>layout();layout();
   int step=0;bool busy=false;System.Threading.CancellationTokenSource running=null;ZapretCheckReport report=null;
   System.Collections.Generic.List<CustomStrategyDefinition> generated=null;ZapretStrategy[] catalog=null;ZapretService[] chosen=null;ZapretProfile target=null;
   Action<string> say=message=>{WriteLog("Автогенерация: "+message);if(!journal.IsDisposed){journal.AppendText(Environment.NewLine+DateTime.Now.ToString("HH:mm:ss")+" · "+message);journal.SelectionStart=journal.TextLength;journal.ScrollToCaret();}};
   Action render=()=>{bool choosing=step==0;services.Visible=profile.Visible=nameHost.Visible=budget.Visible=choosing;journalHost.Visible=!choosing;title.Text=choosing?"1 / 3 · Что должно работать?":step==1?"2 / 3 · Готовы к проверке":"3 / 3 · Проверка вариантов";next.Text=choosing?"Далее":step==1?"Сгенерировать и проверить":"Новый поиск";back.Text=busy?"Остановить":step==1?"Назад":"На главную";results.Visible=step==2&&report!=null;results.Enabled=!busy&&report!=null&&report.Rows.Any(r=>!String.IsNullOrEmpty(r.StrategyId));next.Enabled=!busy;};
   box.Disposed+=(s,e)=>{if(running!=null)running.Cancel();};
   back.Click+=(s,e)=>{if(busy){if(running!=null)running.Cancel();back.Enabled=false;return;}if(step==1){step=0;render();}else ShowPage("home");};
   results.Click+=(s,e)=>{if(!busy&&report!=null){zapretReport=report;reportLoaded=true;ShowPage("zapret-results");}};
   next.Click+=async(s,e)=>{
    if(busy)return;
    if(step==2){step=0;render();return;}
    if(step==0){try{
     chosen=services.SelectedIndices.Select(i=>selectedServices[i]).ToArray();if(chosen.Length==0)throw new InvalidOperationException("Выберите хотя бы один сервис.");if(profile.SelectedIndex<0)throw new InvalidOperationException("Выберите профиль для будущего применения.");target=profiles[profile.SelectedIndex];
     catalog=ZapretCatalog.Load(zapret.DirectoryPath).ToArray();generated=CustomStrategies.Generate(catalog,name.Text,budget.SelectedIndex==0?12:24,target.Settings.Strategy);
     if(CustomStrategies.Read(zapret.DirectoryPath).Count+generated.Count>100)throw new InvalidOperationException("Недостаточно места: лимит 100 своих стратегий. Старые стратегии автоматически не удаляются.");
     journal.Clear();say("Сервисов: "+chosen.Length+"; профиль: "+target.Name+"; вариантов: "+generated.Count+".");say("Разные основы: "+String.Join(", ",generated.Select(d=>catalog.First(c=>c.Id==d.BaseId).Name).Distinct())+".");say("Будут проверены исходные рецепты и новые сочетания повторов, длительности подмены, точек разбиения и TTL (где эти параметры есть у основы). Голосовой блок сохраняется.");say("На время проверки текущий VPN и Zapret будут остановлены после подтверждения. По завершении Zapret останется выключенным; выбор и применение — в результатах.");
     step=1;render();
    }catch(Exception ex){GlassNotice.Show(this,ex.Message,"Автогенерация");}return;}
    if(connecting||setupCancellation!=null||zapretCancellation!=null||byetubeCancellation!=null||warpCancellation!=null||discordVoiceChecking){Toast("Дождитесь завершения текущей операции");return;}
    busy=true;render();bool canStart=false;
    try{if(!IsAdministrator()){GlassNotice.Show(this,"Перезапустите MCRF от имени администратора для проверки Zapret.","Нужны права Windows");}else if(await OfferStopTun()&&await OfferStopZapret())canStart=true;}
    catch(Exception ex){GlassNotice.Show(this,ex.Message,"Проверка не запущена");}
    if(!canStart||box.IsDisposed){busy=false;if(!box.IsDisposed)render();return;}
    running=new System.Threading.CancellationTokenSource();var cancellation=running;zapretCancellation=cancellation;step=2;
    var settings=new ZapretSettings{ScopeText=String.Join("\n",chosen.Select(c=>c.Domains)),MatchMode="addresses",TestUrls=String.Join("\n",chosen.SelectMany(c=>c.Urls)),CheckServices=chosen.Select(c=>c.Id).ToList(),CheckFamilies=new System.Collections.Generic.List<string>{"Свои"},ExcludedApplications=ApplicationExclusions.Normalize(state.ExcludedApplications).ToList()};
    report=new ZapretCheckReport{Settings=settings,ApplySettings=ZapretChecks.CopySettings(target.Settings),ProfileId=target.Id,ServiceIds=settings.CheckServices};render();
    try{
     var variants=generated.Select(d=>CustomStrategies.Build(d,catalog)).ToArray();var live=OpenLiveResults(report,"Автогенерация · проверка",cancellation);
     try{await zapret.Scan(report,variants,cancellation.Token,say,value=>{CustomStrategies.SaveChecked(zapret.DirectoryPath,generated,catalog,value);ZapretChecks.Save(System.IO.Path.Combine(store.Root,"autostrategy-results"),value);PersistZapretReport(value);});}finally{live.Finish();}
     var successful=report.Rows.Count(r=>!String.IsNullOrEmpty(r.StrategyId)&&String.IsNullOrEmpty(r.Error)&&r.Services.Any(v=>v.Access==ServiceAccess.Available));say("Проверка завершена. Вариантов с подтверждённым сервисом: "+successful+". Откройте результаты и выберите подходящий вариант для сервиса. Ничего не применено.");zapretReport=report;reportLoaded=true;
    }catch(OperationCanceledException){say("Поиск остановлен. Завершённые проверки сохранены; ничего не применено.");}
    catch(Exception ex){say("Проверка не завершена: "+ex.Message);}
    finally{if(zapretCancellation==cancellation)zapretCancellation=null;running=null;cancellation.Dispose();busy=false;if(!box.IsDisposed){back.Enabled=true;render();}if(!IsDisposed){blueDashboard.Invalidate();SyncTray();}}
   };render();
  }
 }
}
