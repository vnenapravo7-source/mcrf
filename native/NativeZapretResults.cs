using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace SplifyWin {
  public sealed partial class MainForm {
    bool reportLoaded,trayConfigured;ContextMenuStrip trayPopup;
    void PersistZapretReport(ZapretCheckReport report){ZapretChecks.Save(store.Root,report);if(!String.IsNullOrEmpty(report.ProfileId)&&state.ZapretProfiles.Any(x=>x.Id==report.ProfileId)){string directory=System.IO.Path.Combine(store.Root,"zapret-reports",report.ProfileId);System.IO.Directory.CreateDirectory(directory);ZapretChecks.Save(directory,report);}}
    void EnsureZapretReport(){if(reportLoaded)return;reportLoaded=true;try{EnsureZapretProfiles();zapretReport=ZapretChecks.Load(System.IO.Path.Combine(store.Root,"zapret-reports",state.SelectedZapretProfileId));if(zapretReport==null){var latest=ZapretChecks.Load(store.Root);if(latest!=null&&(String.IsNullOrEmpty(latest.ProfileId)||latest.ProfileId==state.SelectedZapretProfileId))zapretReport=latest;}}catch(Exception ex){WriteLog("Ошибка чтения результатов Zapret: "+ex.Message);}}
    void ZapretResultsBlue(){
      EnsureZapretReport();var box=Box();box.SetBounds(8,8,content.Width-16,content.Height-16);box.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;content.Controls.Add(box);
      var report=zapretReport;
      var info=L(report==null?"Сохранённых проверок пока нет. Вернитесь в Zapret и запустите проверку.":report.StartedAt.ToString("dd.MM.yyyy HH:mm")+" · "+(report.Complete?"Перебор завершён":"Частичные результаты")+" · "+report.Rows.Count(x=>x.StrategyId!=null)+" / "+report.Planned+" стратегий · "+(report.FinishedAt==default(DateTime)?DateTime.Now-report.StartedAt:report.FinishedAt-report.StartedAt).ToString(@"hh\:mm\:ss"),10,false,Muted);info.Name="zapretReportInfo";info.AutoSize=false;info.SetBounds(22,15,box.Width-44,28);info.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(info);
      var legend=L("Зелёный — подтверждено · красный — ошибка · жёлтый — не подтверждено. Нажмите цветную ячейку, чтобы назначить стратегию сервису.",10,false,GlassInk.White);legend.Name="zapretReportLegend";legend.AutoSize=false;legend.SetBounds(22,45,box.Width-44,28);legend.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(legend);
      var grid=new ZapretResultTable();grid.SetBounds(22,83,box.Width-44,Math.Max(120,box.Height-212));grid.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;
      if(report!=null)grid.VisibleServices=ZapretChecks.Services.Where(v=>(report.ServiceIds??report.Settings.CheckServices??ZapretChecks.DefaultServices.ToList()).Contains(v.Id)).Concat(report.Rows.Any(r=>r.Services.Any(s=>s.Id=="discord-voice"))?new[]{DiscordVoiceScores.Service}:new ZapretService[0]).ToArray();
      grid.Rows=report==null?new ZapretCheckRow[0]:report.Rows.Where(x=>x.StrategyId==null).Concat(ZapretChecks.Rank(report.Rows)).ToArray();
      grid.IsApplied=row=>row.StrategyId!=null&&report!=null&&state.ZapretProfiles.Any(p=>zapret.ProfileApplied(p.Id,row.StrategyId));box.Controls.Add(grid);
      var status=L(String.IsNullOrEmpty(zapretResultsNotice)?report!=null&&!String.IsNullOrEmpty(report.StopReason)?report.StopReason:report!=null&&report.MediaProbeRevision<2?"Это сохранённый результат прежнего видеотеста. Для актуальных результатов запустите проверку заново.":"YouTube и Pornhub проверяются воспроизведением видео. HTTP-проверки других сервисов не подтверждают звонки и работу из аккаунта.":zapretResultsNotice,10,false,Muted);status.Name="zapretReportStatus";status.AutoSize=false;status.SetBounds(22,box.Height-116,box.Width-44,56);status.Anchor=AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(status);
      bool applying=false;
      grid.ApplyService+=async (row,service)=>{if(applying)return;applying=true;grid.Enabled=false;try{status.ForeColor=Muted;status.Text="Применяем "+row.Name+"…";if(!await AssignServiceStrategy(report,row,service)){status.Text="Назначение отменено";return;}if(!status.IsDisposed){status.ForeColor=GlassInk.Mint;status.Text=zapretResultsNotice;grid.Invalidate();}}catch(Exception ex){zapretResultsNotice="Не применено: "+ex.Message;WriteLog("Ошибка применения Zapret: "+ex.Message);if(!status.IsDisposed){status.ForeColor=Red;status.Text=zapretResultsNotice;}}finally{applying=false;if(!grid.IsDisposed)grid.Enabled=true;}};
      var back=B("Назад к Zapret",(s,e)=>ShowPage("zapret"));back.Name="zapretReportBack";back.SetBounds(22,box.Height-51,155,37);back.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(back);
      var voice=B("Проверить голос Discord",async(s,e)=>await ShowDiscordVoiceCheck());voice.Name="discordVoiceCheck";voice.SetBounds(189,box.Height-51,220,37);voice.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(voice);
      var best=B("Лучшие профили",async(s,e)=>await ShowBestServiceProfiles(report),true);best.Name="bestServiceProfilesOpen";best.SetBounds(421,box.Height-51,175,37);best.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;best.Enabled=BestServiceProfiles.Choose(report).Length>0;box.Controls.Add(best);
      var again=B("Проверить заново",(s,e)=>{ShowPage("zapret");Toast("Выберите сервисы и нажмите «Проверить стратегии».");},true);again.Name="zapretReportAgain";again.SetBounds(box.Width-190,box.Height-51,168,37);again.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;box.Controls.Add(again);
    }
    async Task ApplyCheckedZapret(ZapretCheckReport report,ZapretCheckRow row){
      if(report==null||row==null||row.StrategyId==null||!report.Rows.Contains(row))throw new InvalidOperationException("Нет проверенного конфига");
      if(zapretCancellation!=null)throw new InvalidOperationException("Дождитесь окончания проверки");
      if(!await OfferStopTun())return;
      zapret.Prepare();var strategy=ZapretCatalog.Load(zapret.DirectoryPath).FirstOrDefault(x=>x.Id==row.StrategyId&&x.Available);if(strategy==null)throw new InvalidOperationException("Эта стратегия больше недоступна. Повторите проверку.");
var settings=ZapretChecks.CopySettings(report.ApplySettings??report.Settings);settings.Strategy=strategy.Id;settings.Family=strategy.Family;EnsureZapretProfiles();var profile=String.IsNullOrEmpty(report.ProfileId)?CurrentZapretProfile():state.ZapretProfiles.FirstOrDefault(x=>x.Id==report.ProfileId);if(profile==null)throw new InvalidOperationException("Профиль проверки удалён. Создайте новую проверку.");var bound=ZapretRoutes.Lists(state,profile.Id);settings.Hosts=row.Services.Where(r=>bound.Any(list=>list.BuiltinPreset=="setup-service:"+r.Id||list.Name.IndexOf(ZapretChecks.Services.FirstOrDefault(s=>s.Id==r.Id)==null?"___":ZapretChecks.Services.First(s=>s.Id==r.Id).Name,StringComparison.OrdinalIgnoreCase)>=0)).SelectMany(r=>r.Hosts??new List<HostsCandidate>()).ToList();var previous=profile.Settings;bool enabled=profile.Enabled;var cancellation=new CancellationTokenSource();zapretCancellation=cancellation;
      try{settings=ZapretRoutes.Resolve(state,new ZapretProfile{Id=profile.Id,Settings=settings});ZapretScope.Validate(new RouteList{Text=settings.ScopeText,MatchMode=settings.MatchMode});profile.Settings=settings;profile.Enabled=true;try{await StartSavedZapretProfiles(cancellation.Token);}catch{profile.Settings=previous;profile.Enabled=enabled;throw;}if(profile.Id==state.SelectedZapretProfileId)state.Zapret=settings;store.Save(state);report.LastAppliedId=strategy.Id;report.LastAppliedAt=DateTime.Now;PersistZapretReport(report);zapretResultsNotice="Применён: "+strategy.Name+" · профиль «"+profile.Name+"» · область: "+ScopeDescription(settings)+". Остальные включённые профили сохранены.";WriteLog("Zapret: "+zapretResultsNotice);}
      finally{if(zapretCancellation==cancellation)zapretCancellation=null;cancellation.Dispose();if(!IsDisposed)blueDashboard.Invalidate();}
    }
    protected override void OnShown(EventArgs e){telegramBridge.SetPort(state.TelegramPort>=1024?state.TelegramPort:TelegramBridge.DefaultPort);ConfigureTray();SyncProfileHotkeys();base.OnShown(e);BeginInvoke(new Action(()=>AppUpdateInstaller.ConfirmStartup()));BeginInvoke(new Action(()=>{state.AdvancedMode=state.SimpleDesign;state.SettingsModeChosen=true;store.Save(state);ScheduleStartupSetup();ScheduleStartupUpdates();}));}
    async void ToggleMainZapret(){if(discordVoiceChecking||connecting||setupCancellation!=null||warpCancellation!=null){Toast("Дождитесь завершения текущей операции");return;}await ToggleZapretTray();}
    async void ToggleMainTelegram(){if(discordVoiceChecking||connecting||telegramStarting||setupCancellation!=null||zapretCancellation!=null){Toast("Дождитесь завершения текущей операции");return;}try{if(telegramBridge.Running)telegramBridge.Stop();else await StartTelegramWithConsent();WriteLog("Telegram WS: "+(telegramBridge.Running?"включён":"выключен"));blueDashboard.Invalidate();SyncTray();}catch(Exception ex){GlassNotice.Show(this,ex.Message,"Telegram WS");}}
    void ConfigureTray(){
      if(trayConfigured)return;trayConfigured=true;var menu=tray.ContextMenuStrip;trayPopup=menu;tray.ContextMenuStrip=null;tray.MouseUp+=(s,e)=>{if(e.Button!=MouseButtons.Right)return;SyncTray();var point=TrayPopupNative.IconAnchor(tray,Cursor.Position);TrayPopupNative.Foreground(Handle);menu.Show(point);menu.Location=TrayPopupNative.AboveIcon(point,menu.Size,Screen.FromPoint(point).WorkingArea);};menu.Closed+=(s,e)=>TrayPopupNative.AfterClose(Handle);menu.Items.Clear();
      menu.Items.Add(new ToolStripMenuItem("Открыть MCRF",null,(s,e)=>RestoreWindow()){Name="trayOpen"});
      menu.Items.Add(new ToolStripSeparator());
      menu.Items.Add(new ToolStripMenuItem("Включить VPN",null,(s,e)=>ToggleConnection(null,EventArgs.Empty)){Name="trayVpn"});
      menu.Items.Add(new ToolStripMenuItem("Включить WARP",null,(s,e)=>ToggleRoutedOutput(true)){Name="trayWarp"});
      menu.Items.Add(new ToolStripMenuItem("Включить Zapret",null,async(s,e)=>await ToggleZapretTray()){Name="trayZapret"});
      menu.Items.Add(new ToolStripMenuItem("Включить Telegram WS",null,async(s,e)=>{try{if(telegramBridge.Running)telegramBridge.Stop();else await StartTelegramWithConsent();WriteLog("Telegram WS: "+(telegramBridge.Running?"включён":"выключен"));blueDashboard.Invalidate();SyncTray();}catch(Exception ex){RestoreWindow();GlassNotice.Show(this,ex.Message,"Telegram WS",MessageBoxButtons.OK,MessageBoxIcon.Error);}}){Name="trayTelegram"});

      menu.Items.Add(new ToolStripMenuItem("Отменить проверку",null,(s,e)=>{if(zapretCancellation!=null)zapretCancellation.Cancel();if(warpCancellation!=null)warpCancellation.Cancel();}){Name="trayCancel"});
      menu.Items.Add(new ToolStripSeparator());
      menu.Items.Add(new ToolStripMenuItem("Автозапуск",null,(s,e)=>{SetWindowsStartup(!windowsStartupEnabled);SyncTray();}){Name="trayStartup"});
      menu.Items.Add(new ToolStripSeparator());menu.Items.Add(new ToolStripMenuItem("Выход",null,(s,e)=>Close()){Name="trayExit"});menu.Opening+=(s,e)=>SyncTray();SyncTray();
    }
    void SyncTray(){
      EnsureZapretReport();var menu=trayPopup??tray.ContextMenuStrip;if(menu==null||menu.Items["trayVpn"]==null)return;
      var vpn=(ToolStripMenuItem)menu.Items["trayVpn"];vpn.Checked=core.Running&&state.VpnEnabled;vpn.Text=connecting?"Подключение…":(core.Running&&state.VpnEnabled?"Отключить ":"Включить ")+(state.Mode=="proxy"?"прокси":"VPN");vpn.Enabled=!connecting&&!discordVoiceChecking;
      var wp=(ToolStripMenuItem)menu.Items["trayWarp"];wp.Checked=core.Running&&state.WarpEnabled;wp.Text=wp.Checked?"Выключить WARP":"Включить WARP";wp.Enabled=!connecting&&warpCancellation==null&&setupCancellation==null;
      var zp=(ToolStripMenuItem)menu.Items["trayZapret"];zp.Checked=zapret.Running;zp.Text=zapretCancellation!=null?"Zapret: проверка / запуск…":zapret.Running?"Выключить Zapret":"Включить Zapret";zp.Enabled=zapretCancellation==null&&!discordVoiceChecking;
      var tg=(ToolStripMenuItem)menu.Items["trayTelegram"];tg.Checked=telegramBridge.Running;tg.Text=telegramStarting?"Telegram WS: запуск…":telegramBridge.Running?"Выключить Telegram WS":"Включить Telegram WS";tg.Enabled=!telegramStarting&&setupCancellation==null&&!discordVoiceChecking;
      menu.Items["trayCancel"].Visible=zapretCancellation!=null||warpCancellation!=null;((ToolStripMenuItem)menu.Items["trayStartup"]).Checked=windowsStartupEnabled;
    }
    async Task ToggleZapretTray(){
      if(profileActionBusy||profileChangeBusy||connecting||setupCancellation!=null||warpCancellation!=null){Toast("Дождитесь завершения текущей операции");return;}
      if(byetubeCancellation!=null){Toast("Дождитесь завершения подбора ByeTube");return;}
      if(!zapret.Running&&!IsAdministrator()){RestoreWindow();if(await ConfirmChange("Для запуска Zapret нужны права администратора. Windows запросит разрешение на перезапуск приложения.","Получить права и перезапустить","Доступ Windows"))RequestElevation("--zapret");return;}
      if(discordVoiceChecking){Toast("Сначала закройте проверку голоса Discord");return;}if(zapretCancellation!=null)return;if(zapret.Running){zapret.Stop();WriteLog("Zapret остановлен из трея");blueDashboard.Invalidate();SyncTray();return;}
      zapret.Prepare();var strategy=ZapretCatalog.Load(zapret.DirectoryPath).FirstOrDefault(x=>x.Id==state.Zapret.Strategy&&x.Available);
      if(!state.ZapretProfiles.Any(p=>p.Enabled&&!String.IsNullOrEmpty(p.Settings.Strategy))){RestoreWindow();ShowPage("zapret");Toast("Сначала проверьте и примените стратегию.");return;}
      if(core.Running&&state.Mode=="tun"){RestoreWindow();GlassNotice.Show(this,"Выключите TUN перед запуском Zapret.","Zapret");return;}
      var cancellation=new CancellationTokenSource();zapretCancellation=cancellation;
      try{await StartSavedZapretProfiles(cancellation.Token);WriteLog("Zapret включён из трея · сохранённые профили");}
      catch(Exception ex){RestoreWindow();GlassNotice.Show(this,ex.Message,"Не удалось включить Zapret",MessageBoxButtons.OK,MessageBoxIcon.Error);}
      finally{if(zapretCancellation==cancellation)zapretCancellation=null;cancellation.Dispose();if(!IsDisposed){blueDashboard.Invalidate();SyncTray();}}
    }
  }
}
