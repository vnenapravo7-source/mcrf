using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  async Task CaptureProcess(){
   var processes=await Task.Run(()=>ApplicationExclusions.Running());var window=Dialog("Захват процесса",940,690);var panel=new DockPanel{Margin=new Thickness(24)};var header=new StackPanel();var search=new TextBox{Name="processSearch"};header.Children.Add(Field("Поиск процесса",search));header.Children.Add(Text("Выберите процесс, начните запись и переподключите приложение. Записываются IP новых TCP/UDP-соединений. Домены и уже открытые соединения могут быть пропущены. VPN не включается; для наблюдения нужны права администратора."));DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);
   var list=ProcessList(processes);var footer=new StackPanel();var progress=Text("Запись не начата");footer.Children.Add(progress);var start=new Button{Content="Записать процесс"};Accent(start);var save=new Button{Content="Готово",IsEnabled=false};var cancel=Button("Отмена",()=>window.Close(),true);footer.Children.Add(Toolbar(start,save,cancel));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);panel.Children.Add(list);window.Content=panel;
   var recording=new GameRecording();GameFlowObserver observer=null;var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(400)};bool loading=false,accepted=false;search.TextChanged+=(s,e)=>list.ItemsSource=processes.Where(p=>p.IndexOf(search.Text,StringComparison.OrdinalIgnoreCase)>=0).ToArray();
   timer.Tick+=(s,e)=>{if(observer==null)return;recording.Observe(observer.Drain());progress.Text="Записано IP: "+recording.IpCount+". "+(observer.Error??"Используйте приложение, затем нажмите «Готово».");};
   start.Click+=async(s,e)=>{if(loading)return;if(list.SelectedItem==null){progress.Text="Выберите процесс";return;}loading=true;start.IsEnabled=false;list.IsEnabled=search.IsEnabled=false;var next=new GameFlowObserver();try{var process=new GamePreset{Name=System.IO.Path.GetFileNameWithoutExtension(Convert.ToString(list.SelectedItem)),Processes=new[]{Convert.ToString(list.SelectedItem)}};await Task.Run(()=>{session.Zapret.Prepare();next.Start(session.Zapret.DirectoryPath,process);});if(!window.IsVisible){next.Dispose();return;}observer=next;recording.Start(process);save.IsEnabled=true;timer.Start();}catch(Exception ex){next.Dispose();if(window.IsVisible){progress.Text=ex.Message;start.IsEnabled=true;list.IsEnabled=search.IsEnabled=true;}}finally{loading=false;}};
   save.Click+=(s,e)=>{if(observer==null)return;recording.Observe(observer.Drain());if(recording.IpCount+recording.DomainCount==0){progress.Text="Адресов пока нет. Переподключите выбранное приложение.";return;}accepted=true;window.DialogResult=true;};window.Closed+=(s,e)=>timer.Stop();window.ShowDialog();if(observer!=null){await Task.Run(()=>observer.Stop());recording.Observe(observer.Drain());observer.Dispose();}recording.Stop();if(!accepted)return;var route=recording.CreateRoute();route.Target="";route.ZapretProfileId=null;route.Enabled=false;string name=Input("Название пресета процесса","Укажите понятное название",false,recording.Game.Name);if(String.IsNullOrWhiteSpace(name))return;route.Name=name.Trim();route.PresetNames=new System.Collections.Generic.List<string>{route.Name};await RouteChange(()=>session.State.Lists.Add(route));
  }
 }
}
