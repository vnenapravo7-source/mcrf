using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed partial class MainForm {
  void FitRoutingColumns(){
   if(routesGrid==null||routesGrid.IsDisposed||routesGrid.Columns.Count<8)return;
   bool narrow=routesGrid.ClientSize.Width<1000;
   routesGrid.Columns[2].Visible=routesGrid.Columns[3].Visible=!narrow;
   routesGrid.Columns[0].Width=narrow?90:112;
   routesGrid.Columns[1].MinimumWidth=narrow?100:135;
   routesGrid.Columns[2].Width=70;routesGrid.Columns[3].Width=100;
   routesGrid.Columns[4].Width=narrow?155:210;
   routesGrid.Columns[5].Width=routesGrid.Columns[6].Width=narrow?60:70;
   routesGrid.Columns[7].Width=narrow?140:190;
  }
  void InstallRouteActionsAndDns(Panel box,Panel bar,Panel order,Button[] arrows){
   var sharedLabel=L("Общий DNS",10,true);box.Controls.Add(sharedLabel);var provider=new GlassPicker{Name="routesSharedDnsProvider"};provider.SetItems(DnsOptions.Providers);box.Controls.Add(provider);var protocol=new GlassPicker{Name="routesSharedDnsProtocol"};box.Controls.Add(protocol);var custom=T();custom.Name="routesSharedDnsCustom";custom.BorderStyle=BorderStyle.None;custom.BackColor=GlassFieldPanel.InputColor;var customHost=new GlassFieldPanel{Name="routesSharedDnsCustomSurface"};customHost.Controls.Add(custom);box.Controls.Add(customHost);bool loading=true;Action relayout=null;
   provider.SelectedIndexChanged+=(s,e)=>{protocol.SetItems(DnsOptions.Protocols(provider.SelectedItem));protocol.SelectedIndex=0;customHost.Visible=provider.SelectedItem=="Свой";if(relayout!=null)relayout();};provider.SelectedItem=state.DnsProvider;protocol.SelectedItem=state.DnsProtocol;custom.Text=state.DnsCustom??"";loading=false;
   var save=B("Применить DNS",async(s,e)=>{if(loading)return;try{DnsOptions.Build(new ClientState{DnsProvider=provider.SelectedItem,DnsProtocol=protocol.SelectedItem,DnsCustom=custom.Text.Trim()});if(!await OfferStopTun())return;state.DnsProvider=provider.SelectedItem;state.DnsProtocol=protocol.SelectedItem;state.DnsCustom=custom.Text.Trim();store.Save(state);Toast("Общий DNS сохранён · применится при подключении");}catch(Exception ex){GlassNotice.Show(this,ex.Message,"Общий DNS");}},true);save.Name="routesSharedDnsApply";box.Controls.Add(save);
   Action layout=()=>{int gap=10,cell=(bar.Width-3*gap)/4;var actions=bar.Controls.Cast<Control>().ToArray();for(int i=0;i<4;i++)actions[i].SetBounds(i*(cell+gap),0,cell,40);actions[4].SetBounds(0,50,cell,40);order.SetBounds(cell+gap,50,cell,40);actions[6].SetBounds(2*(cell+gap),50,bar.Width-2*(cell+gap),40);bar.Height=90;int footer=customHost.Visible?116:68;bar.Top=box.Height-bar.Height-footer-12;int half=(order.Width-6)/2;arrows[0].SetBounds(0,0,half,40);arrows[1].SetBounds(half+6,0,half,40);routesGrid.Height=Math.Max(70,bar.Top-routesGrid.Top-16);int line=box.Height-footer+10;sharedLabel.SetBounds(22,line+4,110,32);int available=box.Width-44;int providerWidth=Math.Max(140,Math.Min(215,available-110-190-163-30));provider.SetBounds(142,line,providerWidth,38);protocol.SetBounds(provider.Right+10,line,190,38);save.SetBounds(box.Width-185,line,163,38);customHost.SetBounds(22,line+48,box.Width-44,40);custom.SetBounds(12,10,customHost.Width-24,24);};relayout=layout;box.Resize+=(s,e)=>layout();layout();
  }
  async void EditRouteDns(RouteList list,string providerName=null,string protocolName=null){
   if(list==null||list.Target=="tgws"||list.Target=="block")return;
   using(var dialog=new Form{Text="DNS · "+list.Name,Size=new Size(650,350),StartPosition=FormStartPosition.CenterParent,BackColor=Bg,ForeColor=Ink,Font=Font}){
    var note=L("Для доменов в TUN; для IP DNS не нужен. Условия по приложениям\nи собственный DoH браузера нельзя надёжно назначить через этот DNS.",10,false,Muted);note.SetBounds(22,18,590,65);dialog.Controls.Add(note);
    var provider=new GlassPicker{Name="routeDnsProvider"};provider.SetItems(new[]{"Общий DNS"}.Concat(DnsOptions.Providers));provider.SetBounds(22,102,330,40);dialog.Controls.Add(provider);var protocol=new GlassPicker{Name="routeDnsProtocol"};protocol.SetBounds(366,102,245,40);dialog.Controls.Add(protocol);var custom=T();custom.SetBounds(22,165,590,40);dialog.Controls.Add(custom);
    provider.SelectedIndexChanged+=(s,e)=>{protocol.SetItems(provider.SelectedItem=="Общий DNS"?new[]{"—"}:DnsOptions.Protocols(provider.SelectedItem));protocol.SelectedIndex=0;protocol.Enabled=provider.SelectedItem!="Общий DNS"&&provider.SelectedItem!="Системный";custom.Visible=provider.SelectedItem=="Свой";};var previous=ExitDnsRouting.Effective(state,list);provider.SelectedItem=providerName??(previous==null?"Общий DNS":previous.Provider);if(protocolName!=null||previous!=null)protocol.SelectedItem=protocolName??previous.Protocol;custom.Text=previous==null?"":previous.Custom??"";
    ExitDnsSettings result=null;var apply=B("Сохранить",(s,e)=>{try{result=new ExitDnsSettings{Provider=provider.SelectedItem,Protocol=protocol.SelectedItem,Custom=custom.Text.Trim()};if(result.Provider!="Общий DNS")DnsOptions.Build(new ClientState{DnsProvider=result.Provider,DnsProtocol=result.Protocol,DnsCustom=result.Custom});dialog.DialogResult=DialogResult.OK;}catch(Exception ex){GlassNotice.Show(dialog,ex.Message,"DNS");}},true);apply.SetBounds(22,242,200,40);dialog.Controls.Add(apply);var cancel=B("Отмена",(s,e)=>dialog.Close());cancel.SetBounds(412,242,200,40);dialog.Controls.Add(cancel);
    if(dialog.ShowDialog(this)!=DialogResult.OK)return;if(!await OfferStopTun())return;list.Dns=result;store.Save(state);RefreshRoutes();Toast("DNS списка сохранён · применится при подключении");
   }
  }
 }
}
