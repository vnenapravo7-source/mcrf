using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed class ProfileHotkey {
  public uint Modifiers;public Keys Key;
  public static readonly Keys[] KeysAllowed=Enumerable.Range((int)Keys.A,26).Concat(Enumerable.Range((int)Keys.D0,10)).Concat(Enumerable.Range((int)Keys.F1,24)).Select(k=>(Keys)k).Where(k=>k!=Keys.F12).Concat(new[]{Keys.Space,Keys.Enter,Keys.Tab,Keys.Home,Keys.End,Keys.PageUp,Keys.PageDown,Keys.Insert,Keys.Delete,Keys.Left,Keys.Right,Keys.Up,Keys.Down}).ToArray();
  public static string KeyLabel(Keys key){return key>=Keys.D0&&key<=Keys.D9?((int)key-(int)Keys.D0).ToString():key.ToString();}
  public override string ToString(){return String.Join(" + ",new[]{(Modifiers&2)!=0?"Ctrl":null,(Modifiers&4)!=0?"Shift":null,(Modifiers&1)!=0?"Alt":null,KeyLabel(Key)}.Where(x=>x!=null));}
  public static ProfileHotkey Parse(string value){
   if(String.IsNullOrWhiteSpace(value))return null;uint modifiers=0;Keys key=Keys.None;
   foreach(var part in value.Split('+').Select(x=>x.Trim())){uint bit=part.Equals("Ctrl",StringComparison.OrdinalIgnoreCase)?2u:part.Equals("Shift",StringComparison.OrdinalIgnoreCase)?4u:part.Equals("Alt",StringComparison.OrdinalIgnoreCase)?1u:0u;
    if(bit!=0){if((modifiers&bit)!=0)throw new FormatException("Модификатор указан дважды.");modifiers|=bit;}
    else{Keys found=KeysAllowed.FirstOrDefault(k=>KeyLabel(k).Equals(part,StringComparison.OrdinalIgnoreCase));if(found==Keys.None||key!=Keys.None)throw new FormatException("Выберите одну обычную клавишу. F12 зарезервирована Windows.");key=found;}
   }
   if(modifiers==0||key==Keys.None)throw new FormatException("Нужен хотя бы один модификатор Ctrl / Shift / Alt и одна обычная клавиша.");return new ProfileHotkey{Modifiers=modifiers,Key=key};
  }
 }
 public sealed class ProfileHotkeyWindow : NativeWindow,IDisposable {
  [DllImport("user32.dll",SetLastError=true)]static extern bool RegisterHotKey(IntPtr window,int id,uint modifiers,uint key);
  [DllImport("user32.dll",SetLastError=true)]static extern bool UnregisterHotKey(IntPtr window,int id);
  sealed class Binding {public int Id;public string Target,Chord;}
  readonly Dictionary<string,Binding> bindings=new Dictionary<string,Binding>();int nextId=100;bool disposed;
  public event Action<string> Pressed;
  public ProfileHotkeyWindow(){CreateHandle(new CreateParams{Caption="MCRF hotkeys",Parent=new IntPtr(-3)});}
  public string Set(string target,string value){
   if(disposed)throw new ObjectDisposedException("ProfileHotkeyWindow");var spec=ProfileHotkey.Parse(value);string chord=spec==null?null:spec.ToString();Binding old;bindings.TryGetValue(target,out old);if(old!=null&&old.Chord==chord)return null;
   if(spec==null){if(old!=null){UnregisterHotKey(Handle,old.Id);bindings.Remove(target);}return null;}
   if(bindings.Values.Any(b=>b.Target!=target&&b.Chord==chord))return "Это сочетание уже назначено другому действию MCRF.";
   if(nextId>0xBFFF)return "Исчерпан лимит регистраций. Перезапустите MCRF.";int id=nextId++;
   if(!RegisterHotKey(Handle,id,spec.Modifiers|0x4000,(uint)spec.Key))return "Сочетание занято другой программой или недоступно Windows ("+Marshal.GetLastWin32Error()+"). Выберите другое.";
   if(old!=null)UnregisterHotKey(Handle,old.Id);bindings[target]=new Binding{Id=id,Target=target,Chord=chord};return null;
  }
  public void Retain(IEnumerable<string> targets){var keep=new HashSet<string>(targets);foreach(var id in bindings.Keys.Where(id=>!keep.Contains(id)).ToArray())Set(id,null);}
  public bool Dispatch(int id){var binding=bindings.Values.FirstOrDefault(b=>b.Id==id);if(binding==null)return false;var handler=Pressed;if(handler!=null)handler(binding.Target);return true;}
  protected override void WndProc(ref Message message){if(message.Msg==0x0312&&Dispatch(message.WParam.ToInt32())){message.Result=IntPtr.Zero;return;}base.WndProc(ref message);}
  public void Dispose(){if(disposed)return;disposed=true;foreach(var b in bindings.Values)UnregisterHotKey(Handle,b.Id);bindings.Clear();DestroyHandle();}
 }
 public sealed partial class MainForm {
  static readonly string[] GlobalHotkeyIds={"@telegram","@warp","@tun"};
  static readonly string[] GlobalHotkeyNames={"Telegram WS прокси","WARP","TUN"};
  ProfileHotkeyWindow profileHotkeys;readonly Dictionary<string,string> hotkeyErrors=new Dictionary<string,string>();bool profileActionBusy,hotkeyEditorOpen,profileChangeBusy;
  string HotkeyValue(string id){if(!id.StartsWith("@")){var profile=state.ZapretProfiles.FirstOrDefault(p=>p.Id==id);return profile==null?null:profile.Hotkey;}string value;return state.GlobalHotkeys!=null&&state.GlobalHotkeys.TryGetValue(id,out value)?value:null;}
  string HotkeyName(string id){int index=Array.IndexOf(GlobalHotkeyIds,id);return index>=0?GlobalHotkeyNames[index]:state.ZapretProfiles.First(p=>p.Id==id).Name;}
  void SetHotkeyValue(string id,string value){if(!id.StartsWith("@"))state.ZapretProfiles.First(p=>p.Id==id).Hotkey=value;else{if(state.GlobalHotkeys==null)state.GlobalHotkeys=new Dictionary<string,string>();if(value==null)state.GlobalHotkeys.Remove(id);else state.GlobalHotkeys[id]=value;}}
  IEnumerable<string> HotkeyTargets(){return state.ZapretProfiles.Select(p=>p.Id).Concat(GlobalHotkeyIds);}
  void SyncProfileHotkeys(){
   if(IsDisposed||!IsHandleCreated||state==null)return;
   if(profileHotkeys==null){profileHotkeys=new ProfileHotkeyWindow();profileHotkeys.Pressed+=id=>{if(!IsDisposed)BeginInvoke(new Action(async()=>{if(hotkeyEditorOpen||profileActionBusy||profileChangeBusy)return;await RunGlobalHotkey(id);}));};}
   profileHotkeys.Retain(HotkeyTargets());hotkeyErrors.Clear();
   foreach(var id in HotkeyTargets()){try{string error=profileHotkeys.Set(id,HotkeyValue(id));if(error!=null)hotkeyErrors[id]=error;}catch(Exception ex){hotkeyErrors[id]=ex.Message;}}
   foreach(var error in hotkeyErrors)WriteLog("Горячая клавиша "+error.Key+": "+error.Value);
  }
  protected override void OnHandleDestroyed(EventArgs e){if(profileHotkeys!=null){profileHotkeys.Dispose();profileHotkeys=null;}base.OnHandleDestroyed(e);}
  bool HotkeyActionBlocked(){return profileActionBusy||profileChangeBusy||routeInlineBusy||zapretCancellation!=null||setupCancellation!=null||byetubeCancellation!=null||discordVoiceChecking||connecting||warpCancellation!=null||telegramStarting;}
  async Task RunGlobalHotkey(string id){
   if(HotkeyActionBlocked()){Toast("Дождитесь завершения текущей операции");return;}
   if(id=="@telegram"){if(!telegramBridge.Running)RestoreWindow();ToggleMainTelegram();return;}
   if(id=="@warp"){ToggleRoutedOutput(true);return;}
   if(id=="@tun"){
    if(state.Mode!="tun"){RestoreWindow();GlassNotice.Show(this,"Выберите режим TUN в настройках. Горячая клавиша TUN не переключает локальный прокси и не меняет режим подключения.","TUN");return;}
    ToggleConnection(null,EventArgs.Empty);return;
   }
   await SetProfileEnabled(id,null,true);
  }
  async Task SetProfileEnabled(string id,bool? enabled,bool fromHotkey){
   if(HotkeyActionBlocked()){Toast("Дождитесь завершения текущей операции");return;}
   var profile=state.ZapretProfiles.FirstOrDefault(p=>p.Id==id);if(profile==null)return;bool desired=enabled??!profile.Enabled;bool previous=profile.Enabled;profileActionBusy=true;
   try{
    if(desired&&(ZapretRoutes.Lists(state,id).Length==0||String.IsNullOrEmpty(profile.Settings.Strategy))){if(fromHotkey)RestoreWindow();GlassNotice.Show(this,"Сначала выберите стратегию и пресеты. Изменения сохраняются автоматически.","Zapret");return;}
    if(desired&&!IsAdministrator()){RestoreWindow();if(await ConfirmChange("Для включения Zapret нужны права Windows. Приложение перезапустится с запросом разрешения.","Получить права и перезапустить","Доступ Windows"))RequestElevation("--zapret");return;}
    if(core.Running&&state.Mode=="tun"&&fromHotkey)RestoreWindow();if(!await OfferStopTun())return;bool wasRunning=zapret.Running;
    if(await ChangeActiveProfiles(()=>state.ZapretProfiles.First(p=>p.Id==id).Enabled=desired,()=>{})){
     if(desired&&!wasRunning)await StartSavedZapretProfiles(CancellationToken.None);
     WriteLog((fromHotkey?"Горячая клавиша: ":"")+profile.Name+" · "+(desired?"включён":"выключен"));
     if(!Visible&&tray.Visible)tray.ShowBalloonTip(1500,"MCRF",profile.Name+" · "+(desired?"Вкл":"Выкл"),ToolTipIcon.Info);
    }
   }catch(Exception ex){var restored=state.ZapretProfiles.FirstOrDefault(p=>p.Id==id);if(restored!=null)restored.Enabled=previous;store.Save(state);if(fromHotkey)RestoreWindow();GlassNotice.Show(this,ex.Message,"Профиль не переключён");}
   finally{profileActionBusy=false;if(!IsDisposed){SyncTray();if(Visible&&page=="zapret")ShowPage("zapret");}}
  }
  bool SaveProfileHotkey(string id,string value){
   if(profileActionBusy||profileChangeBusy||routeInlineBusy)throw new InvalidOperationException("Дождитесь завершения текущей операции.");if(!HotkeyTargets().Contains(id))throw new InvalidOperationException("Действие уже удалено.");
   var spec=ProfileHotkey.Parse(value);string chord=spec==null?null:spec.ToString();
   foreach(var other in HotkeyTargets().Where(x=>x!=id)){ProfileHotkey existing=null;try{existing=ProfileHotkey.Parse(HotkeyValue(other));}catch{}if(chord!=null&&existing!=null&&existing.ToString()==chord)throw new InvalidOperationException("Это сочетание уже назначено другому действию MCRF.");}
   SyncProfileHotkeys();string old=HotkeyValue(id);string error=profileHotkeys.Set(id,chord);if(error!=null)throw new InvalidOperationException(error);
   try{SetHotkeyValue(id,chord);store.Save(state);hotkeyErrors.Remove(id);return true;}catch{SetHotkeyValue(id,old);profileHotkeys.Set(id,old);throw;}
  }
  async Task EditProfileHotkey(string id){
   if(hotkeyEditorOpen||profileActionBusy||profileChangeBusy)return;if(!HotkeyTargets().Contains(id))return;hotkeyEditorOpen=true;
   var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(590,ClientSize.Width-40),360)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
   Add(popup,L("Горячая клавиша · "+ZapretStrategy.CleanLabel(HotkeyName(id)),16,true),24,20);var help=L("Глобальное Вкл / Выкл, даже в трее.\nВыберите один или несколько модификаторов и обычную клавишу.",10,false,Muted);help.AutoSize=false;help.SetBounds(24,64,popup.Width-48,52);popup.Controls.Add(help);
   ProfileHotkey old=null;try{old=ProfileHotkey.Parse(HotkeyValue(id));}catch{}
   var ctrl=new GlassToggle{Name="hotkeyCtrl",Text="Ctrl",Checked=old!=null&&(old.Modifiers&2)!=0,Location=new Point(24,122),Width=140};var shift=new GlassToggle{Name="hotkeyShift",Text="Shift",Checked=old!=null&&(old.Modifiers&4)!=0,Location=new Point(182,122),Width=140};var alt=new GlassToggle{Name="hotkeyAlt",Text="Alt",Checked=old!=null&&(old.Modifiers&1)!=0,Location=new Point(340,122),Width=140};popup.Controls.AddRange(new Control[]{ctrl,shift,alt});
   var key=new GlassPicker{Name="hotkeyKey",FlatSurface=true,Location=new Point(24,178),Width=popup.Width-48};key.SetItems(ProfileHotkey.KeysAllowed.Select(ProfileHotkey.KeyLabel));key.SelectedIndex=old==null?Array.IndexOf(ProfileHotkey.KeysAllowed,Keys.P):Array.IndexOf(ProfileHotkey.KeysAllowed,old.Key);popup.Controls.Add(key);
   var error=L(hotkeyErrors.ContainsKey(id)?hotkeyErrors[id]:"Все сочетания необязательны. F12 зарезервирована Windows.",10,false,Muted);error.AutoSize=false;error.SetBounds(24,225,popup.Width-48,58);popup.Controls.Add(error);
   Action<string> save=value=>{try{SaveProfileHotkey(id,value);done.TrySetResult(true);veil.Dispose();Toast(String.IsNullOrEmpty(value)?"Горячая клавиша удалена":"Горячая клавиша сохранена");}catch(Exception ex){error.Text=ex.Message;error.ForeColor=Red;}};
   var apply=B("Назначить",(s,e)=>{var spec=new ProfileHotkey{Modifiers=(ctrl.Checked?2u:0u)|(shift.Checked?4u:0u)|(alt.Checked?1u:0u),Key=key.SelectedIndex<0?Keys.None:ProfileHotkey.KeysAllowed[key.SelectedIndex]};save(spec.ToString());},true);apply.Name="hotkeyApply";apply.SetBounds(24,298,155,38);popup.Controls.Add(apply);
   var clear=B("Убрать",(s,e)=>save(null));clear.Name="hotkeyClear";clear.SetBounds(192,298,130,38);popup.Controls.Add(clear);var cancel=B("Отмена",(s,e)=>veil.Dispose());cancel.Name="hotkeyCancel";cancel.SetBounds(popup.Width-154,298,130,38);popup.Controls.Add(cancel);veil.Disposed+=(s,e)=>done.TrySetResult(false);Controls.Add(veil);veil.BringToFront();layout();
   try{await done.Task;}finally{hotkeyEditorOpen=false;if(!IsDisposed&&Visible&&(page=="zapret"||page=="settings"))ShowPage(page);}
  }
  void ShowGlobalHotkeys(){
   var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(610,ClientSize.Width-40),340)};veil.Controls.Add(popup);Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();Add(popup,L("Глобальные горячие клавиши",16,true),24,20);
   for(int i=0;i<GlobalHotkeyIds.Length;i++){string id=GlobalHotkeyIds[i];var label=L(GlobalHotkeyNames[i],11,true);label.SetBounds(24,84+i*60,220,30);popup.Controls.Add(label);var button=B(HotkeyValue(id)??"Не назначена",async(s,e)=>{veil.Dispose();await EditProfileHotkey(id);});button.Name="globalHotkey_"+id.Substring(1);button.SetBounds(250,76+i*60,popup.Width-274,40);popup.Controls.Add(button);}
   var hint=L("Необязательно. Работает, пока MCRF запущен, в том числе в трее.",10,false,Muted);hint.SetBounds(24,258,popup.Width-48,30);popup.Controls.Add(hint);var close=B("Закрыть",(s,e)=>veil.Dispose());close.SetBounds(popup.Width-154,294,130,34);popup.Controls.Add(close);Controls.Add(veil);veil.BringToFront();layout();
  }
 }
}
