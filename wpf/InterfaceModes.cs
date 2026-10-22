using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Shell;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  ComboBox interfaceChoice;bool interfaceSync,drainingInterfaceMode;int? queuedInterfaceMode;ComboBox minimizeChoice,closeChoice;
  int InterfaceMode{get{return session==null?0:session.State.InterfaceMode==1?1:0;}}
  static readonly string[] InterfaceNames={"Весь функционал","Только VPN"};
  UIElement InterfaceSelector(){
   interfaceChoice=new ComboBox{Name="interfaceModeChoice",ItemsSource=InterfaceNames,SelectedIndex=0,FontSize=11,Margin=new Thickness(0,0,0,10)};
   interfaceChoice.SelectionChanged+=async(s,e)=>{if(!interfaceSync&&interfaceChoice.SelectedIndex>=0)await ChangeInterfaceMode(interfaceChoice.SelectedIndex);};var panel=new StackPanel{Name="interfaceSelector"};panel.Children.Add(Field("Интерфейс",interfaceChoice));return panel;
  }
  string DefaultInterfacePage{get{return InterfaceMode==1?"Серверы":"Главная";}}
  bool PageAvailable(string name){if(InterfaceMode==0)return name!="Конструктор"&&name!="WARP";return new[]{"Серверы","Обновления","Конструктор"}.Contains(name);}
  bool ModuleAvailable(string id){return InterfaceMode==0||id=="@tun";}
  bool SimpleUpdateAvailable(SplifyWin.UpdateItem item){if(InterfaceMode==0)return true;if(InterfaceMode==1)return item.Id=="app"||item.Id=="singbox"||item.Kind=="optional"||item.Group=="Списки маршрутизации";return item.Group!="Дополнительные движки";}
  async Task ChangeInterfaceMode(int value){if(session==null)return;queuedInterfaceMode=Math.Max(0,Math.Min(1,value));await DrainInterfaceMode();}
  void ScheduleInterfaceMode(){if(!closing&&queuedInterfaceMode.HasValue&&!drainingInterfaceMode)Dispatcher.BeginInvoke(new Action(async()=>await DrainInterfaceMode()));}
  async Task DrainInterfaceMode(){if(busy||closing||drainingInterfaceMode||!queuedInterfaceMode.HasValue)return;drainingInterfaceMode=true;try{while(queuedInterfaceMode.HasValue&&!closing){int value=queuedInterfaceMode.Value;queuedInterfaceMode=null;int previous=session.State.InterfaceMode;await Run(async()=>{try{session.State.InterfaceMode=value;await session.Save();}catch{session.State.InterfaceMode=previous;throw;}});ApplyInterfaceMode();Page(PageAvailable(current)?current:DefaultInterfacePage);}}finally{drainingInterfaceMode=false;SyncInterfaceControls();}}
  void SyncInterfaceControls(){interfaceSync=true;try{if(interfaceChoice!=null){interfaceChoice.SelectedIndex=queuedInterfaceMode??InterfaceMode;interfaceChoice.ToolTip="Меняется только интерфейс. Работающие подключения сохраняются.";}if(session!=null){if(minimizeChoice!=null)minimizeChoice.SelectedIndex=session.State.MinimizeToTaskbar?1:0;if(closeChoice!=null)closeChoice.SelectedIndex=session.State.CloseToTray?0:1;}}finally{interfaceSync=false;}}
  void ApplyInterfaceMode(){SyncInterfaceControls();navigation.Children.Clear();foreach(var name in new[]{"Главная","Маршрутизация","Серверы","VK TURN","Zapret & Byetube","Мастер настройки","Журнал","Конструктор","Обновления","Автодеплой","WARP"}.Where(PageAvailable))navigation.Children.Add(NavButton(name));foreach(var p in moduleChecks)p.Value.Visibility=ModuleAvailable(p.Key)?Visibility.Visible:Visibility.Collapsed;SyncModules();if(session!=null)RegisterHotkeys();}
  UIElement WindowBehaviorControls(){
   var panel=new StackPanel{Name="windowBehavior",Margin=new Thickness(0,0,0,12)};
   panel.Children.Add(InterfaceSelector());
   minimizeChoice=new ComboBox{Name="minimizeBehavior",ItemsSource=new[]{"Прятать в трей","Сворачивать в панель"},SelectedIndex=0,FontSize=11,Margin=new Thickness(0,0,0,10)};
   closeChoice=new ComboBox{Name="closeBehavior",ItemsSource=new[]{"Прятать в трей","Завершать"},SelectedIndex=1,FontSize=11,Margin=new Thickness(0,0,0,4)};
   panel.Children.Add(Field("При сворачивании",minimizeChoice));panel.Children.Add(Field("При закрытии",closeChoice));
   minimizeChoice.SelectionChanged+=async(s,e)=>{if(interfaceSync||session==null)return;if(busy){SyncInterfaceControls();return;}await Run(async()=>{bool previous=session.State.MinimizeToTaskbar;try{session.State.MinimizeToTaskbar=minimizeChoice.SelectedIndex==1;await session.Save();}catch{session.State.MinimizeToTaskbar=previous;throw;}finally{SyncInterfaceControls();}});};
   closeChoice.SelectionChanged+=async(s,e)=>{if(interfaceSync||session==null)return;if(busy){SyncInterfaceControls();return;}await Run(async()=>{bool previous=session.State.CloseToTray;try{session.State.CloseToTray=closeChoice.SelectedIndex==0;await session.Save();}catch{session.State.CloseToTray=previous;throw;}finally{SyncInterfaceControls();}});};return panel;
  }
 }
}
