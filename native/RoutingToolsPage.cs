using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed partial class MainForm {
  void GameTemplatesBlue(){
   var box=Box();box.Dock=DockStyle.Fill;content.Controls.Add(box);
   var hint=L("Оставлен Dota 2: Steam возвращает для неё отдельный пул SDR.\nЭто ретрансляторы, не полный список входа, античита и матчей.\nСтратегия обхода не проверена. Подбор по AppID удалён.",10,false,Muted);hint.AutoSize=false;hint.SetBounds(24,20,box.Width-48,100);box.Controls.Add(hint);
   var grid=Grid();grid.Name="gameTemplatesTable";grid.SetBounds(24,132,box.Width-48,Math.Max(120,box.Height-220));grid.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;foreach(string label in new[]{"Шаблон","IP","Состояние"})grid.Columns.Add(label,label);grid.Columns[2].FillWeight=180;box.Controls.Add(grid);
   var candidate=SteamGameTemplates.Candidates().Single();int row=grid.Rows.Add(candidate.Name,0,candidate.Status);grid.Rows.Add("Wardogs · ALT11",SteamGameTemplates.WardogsEntries.Split('\n').Length-1,"Ваш список · ALT11 · Cloudflare UDP · TCP + UDP");bool busy=false;
   var fetch=B("Получить пул Dota 2",async(s,e)=>{if(busy)return;if(grid.CurrentRow!=null&&grid.CurrentRow.Index==1){Toast("Wardogs уже встроен — получение адресов не нужно");return;}busy=true;((Control)s).Enabled=false;try{candidate=await SteamGameTemplates.Fetch(core.DownloadRemoteTextAsync);if(!grid.IsDisposed){grid.Rows[row].Cells[1].Value=candidate.Text.Split('\n').Length;grid.Rows[row].Cells[2].Value=candidate.Status;}}catch(Exception ex){candidate.Text=null;if(!grid.IsDisposed){grid.Rows[row].Cells[1].Value=0;grid.Rows[row].Cells[2].Value=ex.Message;}}finally{busy=false;if(!((Control)s).IsDisposed)((Control)s).Enabled=true;}});fetch.SetBounds(24,box.Height-65,210,38);fetch.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(fetch);
   var add=B("Добавить в маршруты",async(s,e)=>{if(!busy&&grid.CurrentRow!=null&&grid.CurrentRow.Index==1){try{if(state.Lists.Any(r=>r.BuiltinPreset=="game:wardogs-alt11")){Toast("Wardogs уже добавлен; ваш маршрут не изменён");return;}zapret.Prepare();var template=SteamGameTemplates.Wardogs(ZapretCatalog.Load(zapret.DirectoryPath));if(!await OfferStopTun())return;if(await ChangeActiveProfiles(()=>{state.ZapretProfiles.Add(template.Item1);state.Lists.Add(template.Item2);},()=>{})){ShowPage("routes");Toast("Wardogs: ALT11 · TCP + UDP · Cloudflare UDP");}}catch(Exception ex){GlassNotice.Show(this,ex.Message,"Wardogs не добавлен");}return;}if(busy||String.IsNullOrWhiteSpace(candidate.Text)){Toast("Сначала получите отдельный пул Dota 2");return;}var draft=new RouteList{Name=candidate.Name,Text=candidate.Text,Target="direct",MatchMode="addresses",BuiltinPreset="steam-sdr:570"};using(var dialog=new RouteDialog(draft,core,state.ZapretProfiles,WarpOutputs.Profiles(state))){if(dialog.ShowDialog(this)==DialogResult.OK)SaveRoute(null,dialog.Result);}},true);add.SetBounds(246,box.Height-65,220,38);add.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(add);
   var back=B("Назад",(s,e)=>ShowPage("routes"));back.SetBounds(box.Width-180,box.Height-65,156,38);back.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;box.Controls.Add(back);
  }
  async void EditGlobalApplicationExclusions(){
   using(var dialog=new Form{Text="Исключить приложения из всех правил",StartPosition=FormStartPosition.CenterParent,Size=new Size(740,600),MinimumSize=new Size(620,540),BackColor=Bg,ForeColor=Ink,Font=Font}){
    var note=L("Эти приложения идут напрямую, без VPN, WARP и обработки Zapret.\nИсключение важнее любого правила. В локальном прокси и TUN полного\nобхода драйвера нет; Zapret пропускает пакеты до анализа и подмены.",10,false,Muted);note.SetBounds(22,18,670,90);dialog.Controls.Add(note);
    var running=ApplicationExclusions.Running();var selected=ApplicationExclusions.Normalize(state.ExcludedApplications);var pick=new GlassPicker{Name="excludedRunningApps",MultiSelect=true,EmptySelectionText="Выбрать запущенные приложения"};pick.SetItems(running);for(int i=0;i<running.Length;i++)pick.SetChecked(i,selected.Contains(running[i],StringComparer.OrdinalIgnoreCase));pick.SetBounds(22,118,670,40);dialog.Controls.Add(pick);
    var caption=L("Или впишите имена .exe — по одному в строке",10);caption.SetBounds(22,174,670,30);dialog.Controls.Add(caption);var input=T(true);input.Name="excludedAppsManual";input.Text=String.Join(Environment.NewLine,selected.Where(n=>!running.Contains(n,StringComparer.OrdinalIgnoreCase)));input.SetBounds(22,211,670,225);input.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;dialog.Controls.Add(input);
    string[] result=null;var save=B("Сохранить",(s,e)=>{try{result=ApplicationExclusions.Normalize(pick.SelectedIndices.Select(i=>running[i]).Concat(input.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)));dialog.DialogResult=DialogResult.OK;}catch(Exception ex){GlassNotice.Show(dialog,ex.Message,"Список исключений");}},true);save.SetBounds(22,480,180,40);save.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;dialog.Controls.Add(save);var cancel=B("Отмена",(s,e)=>dialog.Close());cancel.SetBounds(510,480,180,40);cancel.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;dialog.Controls.Add(cancel);
    if(dialog.ShowDialog(this)!=DialogResult.OK)return;if(!await OfferStopTun())return;if(!await ChangeActiveProfiles(()=>state.ExcludedApplications=result.ToList(),()=>{}))return;Toast("Исключения сохранены · для VPN переподключитесь");
   }
  }
 }
}
