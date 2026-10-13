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
   var hint=L("Выберите вариант Wardogs. Alternative — набор доменов и процесса.\nВариант arhekog — точечная адаптация ALT9, не копия глобального перехвата архива.\nСуществующие маршруты не заменяются.",10,false,Muted);hint.AutoSize=false;hint.SetBounds(24,20,box.Width-48,100);box.Controls.Add(hint);
   var grid=Grid();grid.Name="gameTemplatesTable";grid.SetBounds(24,132,box.Width-48,Math.Max(120,box.Height-220));grid.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;foreach(string label in new[]{"Шаблон","Адресов","Состояние"})grid.Columns.Add(label,label);grid.Columns[2].FillWeight=180;box.Controls.Add(grid);
   grid.Rows.Add("Wardogs · ALT11",SteamGameTemplates.AddressCount(SteamGameTemplates.WardogsEntries),"ALT11 · Cloudflare UDP · TCP + UDP");grid.Rows.Add("Wardogs Alternative",SteamGameTemplates.AddressCount(SteamGameTemplates.WardogsAlternativeEntries),"ALT11 · Google UDP · TCP + UDP");grid.Rows.Add("Wardogs arhekog · точечный",SteamGameTemplates.AddressCount(SteamGameTemplates.WardogsArchiveEntries),"ALT9 · оригинальный движок · автопауза в матче · точечный");
   // Game templates are local; no remote SDR-pool discovery.
   var add=B("Добавить в маршруты",async(s,e)=>{var button=(Control)s;button.Enabled=false;try{int variant=grid.CurrentRow==null?0:grid.CurrentRow.Index;string preset=variant==1?"game:wardogs-alternative":variant==2?"game:wardogs-arhekog":"game:wardogs-alt11";if(state.Lists.Any(r=>r.BuiltinPreset==preset)){Toast("Wardogs уже добавлен; существующий маршрут не изменён");return;}var template=await Task.Run(()=>{zapret.Prepare();var catalog=ZapretCatalog.Load(zapret.DirectoryPath);return variant==1?SteamGameTemplates.WardogsAlternative(catalog):variant==2?SteamGameTemplates.WardogsArchive(catalog):SteamGameTemplates.Wardogs(catalog);});if(!await OfferStopTun())return;if(await ChangeActiveProfiles(()=>{state.ZapretProfiles.Add(template.Item1);state.Lists.Add(template.Item2);if(variant==2){state.Lists.Insert(0,new RouteList{Name="Wardogs arhekog · без подмены",Text=SteamGameTemplates.WardogsArchiveProtected,Target="direct",MatchMode="addresses",BuiltinPreset="game:wardogs-arhekog-protect",Enabled=true});}},()=>{})){ShowPage("routes");Toast(template.Item2.Name+" добавлен");}}catch(Exception ex){GlassNotice.Show(this,ex.Message,"Wardogs не добавлен");}finally{if(!button.IsDisposed)button.Enabled=true;}},true);add.SetBounds(24,box.Height-65,220,38);add.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(add);
   var back=B("Назад",(s,e)=>ShowPage("routes"));back.SetBounds(box.Width-180,box.Height-65,156,38);back.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;box.Controls.Add(back);
  }
  async void EditGlobalApplicationExclusions(){
   using(var dialog=new Form{Text="Исключить приложения из всех правил",StartPosition=FormStartPosition.CenterParent,Size=new Size(740,600),MinimumSize=new Size(620,540),BackColor=Bg,ForeColor=Ink,Font=Font}){
    var note=L("Эти приложения идут напрямую, без VPN, WARP и обработки Zapret.\nИсключение важнее любого правила. В локальном прокси и TUN полного\nобхода драйвера нет; Zapret пропускает пакеты до анализа и подмены.",10,false,Muted);note.SetBounds(22,18,670,90);dialog.Controls.Add(note);
    var running=ApplicationExclusions.Running();var selected=ApplicationExclusions.Normalize(state.ExcludedApplications);var pick=new GlassPicker{Name="excludedRunningApps",MultiSelect=true,EmptySelectionText="Выбрать запущенные приложения"};pick.SetItems(running);for(int i=0;i<running.Length;i++)pick.SetChecked(i,selected.Contains(running[i],StringComparer.OrdinalIgnoreCase));pick.SetBounds(22,118,670,40);dialog.Controls.Add(pick);
    var caption=L("Или впишите имена .exe — по одному в строке",10);caption.SetBounds(22,174,670,30);dialog.Controls.Add(caption);var input=new GlassLogBox{BorderStyle=BorderStyle.None,BackColor=GlassFieldPanel.InputColor,ForeColor=Ink,Font=Font,WordWrap=false,DetectUrls=false};input.Name="excludedAppsManual";input.Text=String.Join(Environment.NewLine,selected.Where(n=>!running.Contains(n,StringComparer.OrdinalIgnoreCase)));var field=new GlassFieldPanel{Name="excludedAppsField"};field.SetBounds(22,211,670,225);field.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;dialog.Controls.Add(field);input.SetBounds(12,10,field.Width-24,field.Height-20);input.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;field.Controls.Add(input);input.InstallRails(field);
    string[] result=null;var save=B("Сохранить",(s,e)=>{try{result=ApplicationExclusions.Normalize(pick.SelectedIndices.Select(i=>running[i]).Concat(input.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries)));dialog.DialogResult=DialogResult.OK;}catch(Exception ex){GlassNotice.Show(dialog,ex.Message,"Список исключений");}},true);save.SetBounds(22,480,180,40);save.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;dialog.Controls.Add(save);var cancel=B("Отмена",(s,e)=>dialog.Close());cancel.SetBounds(510,480,180,40);cancel.Anchor=AnchorStyles.Bottom|AnchorStyles.Right;dialog.Controls.Add(cancel);
    if(dialog.ShowDialog(this)!=DialogResult.OK)return;if(!await OfferStopTun())return;if(!await ChangeActiveProfiles(()=>state.ExcludedApplications=result.ToList(),()=>{}))return;Toast("Исключения сохранены · для VPN переподключитесь");
   }
  }
 }
}
