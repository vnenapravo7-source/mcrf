using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Shell;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  ComboBox interfaceChoice;bool interfaceSync,drainingInterfaceMode,expandedWorkspace;int? queuedInterfaceMode;ComboBox minimizeChoice,closeChoice;
  int InterfaceMode{get{return session!=null&&session.State.CompactAdvancedFeatures?0:1;}}
  static readonly string[] InterfaceNames={"Расширенные функции","Основные функции"};
  UIElement InterfaceSelector(){
   interfaceChoice=new ComboBox{Name="interfaceModeChoice",ItemsSource=InterfaceNames,SelectedIndex=0,FontSize=11,Margin=new Thickness(0,0,0,10)};
   interfaceChoice.SelectionChanged+=async(s,e)=>{if(!interfaceSync&&interfaceChoice.SelectedIndex>=0)await ChangeInterfaceMode(interfaceChoice.SelectedIndex);};var panel=new StackPanel{Name="interfaceSelector"};panel.Children.Add(Field("Интерфейс",interfaceChoice));return panel;
  }
  string DefaultInterfacePage{get{return "Главная";}}
  bool PageAvailable(string name){if(IsCompactSettingsPage(name))return name!="Локальный прокси"||(session!=null&&session.State.CompactAdvancedFeatures);return new[]{"Главная","Конструктор","Журнал","Обновления","События","VK TURN"}.Contains(name)||(session!=null&&session.State.CompactAdvancedFeatures&&IsCompactAdvancedPage(name));}
  bool ModuleAvailable(string id){return InterfaceMode==0||id=="@tun";}
  bool SimpleUpdateAvailable(SplifyWin.UpdateItem item){if(InterfaceMode==0)return true;if(InterfaceMode==1)return item.Id=="app"||item.Id=="singbox"||item.Kind=="optional"||item.Group=="Списки маршрутизации";return item.Group!="Дополнительные движки";}
  async Task ChangeInterfaceMode(int value){if(session==null)return;queuedInterfaceMode=Math.Max(0,Math.Min(1,value));await DrainInterfaceMode();}
  void ScheduleInterfaceMode(){if(!closing&&queuedInterfaceMode.HasValue&&!drainingInterfaceMode)Dispatcher.BeginInvoke(new Action(async()=>await DrainInterfaceMode()));}
  async Task DrainInterfaceMode(){if(busy||closing||drainingInterfaceMode||!queuedInterfaceMode.HasValue)return;drainingInterfaceMode=true;try{while(queuedInterfaceMode.HasValue&&!closing){int value=queuedInterfaceMode.Value;queuedInterfaceMode=null;int previous=session.State.InterfaceMode;bool previousAdvanced=session.State.CompactAdvancedFeatures;await Run(async()=>{try{session.State.InterfaceMode=value;session.State.CompactAdvancedFeatures=value==0;await session.Save();}catch{session.State.InterfaceMode=previous;session.State.CompactAdvancedFeatures=previousAdvanced;throw;}});ApplyInterfaceMode();Page(PageAvailable(current)?current:DefaultInterfacePage);}}finally{drainingInterfaceMode=false;SyncInterfaceControls();}}
  void SyncInterfaceControls(){interfaceSync=true;try{if(interfaceChoice!=null){interfaceChoice.SelectedIndex=queuedInterfaceMode??InterfaceMode;interfaceChoice.ToolTip="Меняется только интерфейс. Работающие подключения сохраняются.";}if(session!=null){if(minimizeChoice!=null)minimizeChoice.SelectedIndex=session.State.MinimizeToTaskbar?1:0;if(closeChoice!=null)closeChoice.SelectedIndex=session.State.CloseToTray?0:1;}}finally{interfaceSync=false;}}
  void SetWorkspaceSize(bool expanded){
   const double compactWidth=400,compactHeight=728;expandedWorkspace=false;WindowState=WindowState.Normal;ResizeMode=ResizeMode.NoResize;MinWidth=MaxWidth=Width=compactWidth;MinHeight=MaxHeight=Height=compactHeight;
   if(mainArea!=null)mainArea.Margin=new Thickness(8,8,8,76);
  }
  void OpenExpandedWorkspace(){OpenExpandedWorkspace("Маршрутизация");}
  async void OpenExpandedWorkspace(string page){
   if(session==null)return;
   if(!session.State.CompactAdvancedFeatures){
    bool previous=session.State.CompactAdvancedFeatures;int previousMode=session.State.InterfaceMode;
    await Run(async()=>{try{session.State.CompactAdvancedFeatures=true;session.State.InterfaceMode=0;await session.Save();}catch{session.State.CompactAdvancedFeatures=previous;session.State.InterfaceMode=previousMode;throw;}});
    ApplyInterfaceMode();
   }
   Page(PageAvailable(page)?page:"Журнал");
  }
  async void ReturnCompactWorkspace(){if(session!=null&&session.State.CompactAdvancedFeatures)await ChangeInterfaceMode(1);Page("Главная");}
  void ApplyInterfaceMode(){SetWorkspaceSize(false);ApplyWorkspacePalette(false);if(maximizeButton!=null)maximizeButton.Visibility=Visibility.Collapsed;SyncInterfaceControls();navigation.Children.Clear();side.Visibility=Visibility.Collapsed;sidebarColumn.Width=new GridLength(0);if(compactNavigation!=null)compactNavigation.Visibility=Visibility.Visible;foreach(var p in moduleChecks)p.Value.Visibility=ModuleAvailable(p.Key)?Visibility.Visible:Visibility.Collapsed;SyncModules();if(session!=null)RegisterHotkeys();}
  UIElement WindowBehaviorControls(){
   var panel=new StackPanel{Name="windowBehavior",Margin=new Thickness(0,0,0,12)};
   var minimize=new ComboBox{Name="minimizeBehavior",ItemsSource=new[]{"Прятать в трей","Сворачивать в панель"},SelectedIndex=session!=null&&session.State.MinimizeToTaskbar?1:0,FontSize=11,Margin=new Thickness(0,0,0,10)};
   var close=new ComboBox{Name="closeBehavior",ItemsSource=new[]{"Прятать в трей","Завершать"},SelectedIndex=session!=null&&session.State.CloseToTray?0:1,FontSize=11,Margin=new Thickness(0,0,0,4)};minimizeChoice=minimize;closeChoice=close;
   Action sync=()=>{if(session==null)return;interfaceSync=true;try{minimize.SelectedIndex=session.State.MinimizeToTaskbar?1:0;close.SelectedIndex=session.State.CloseToTray?0:1;}finally{interfaceSync=false;}};
   panel.Children.Add(Field("При сворачивании",minimize));panel.Children.Add(Field("При закрытии",close));
   minimize.SelectionChanged+=async(s,e)=>{if(interfaceSync||session==null)return;if(busy||closing||closeRequested){sync();return;}bool desired=minimize.SelectedIndex==1;await Run(async()=>{try{await session.SaveWindowBehavior(desired,null,()=>closing||closeRequested);}finally{sync();}},sync);};
   close.SelectionChanged+=async(s,e)=>{if(interfaceSync||session==null)return;if(busy||closing||closeRequested){sync();return;}bool desired=close.SelectedIndex==0;await Run(async()=>{try{await session.SaveWindowBehavior(null,desired,()=>closing||closeRequested);}finally{sync();}},sync);};return panel;
  }
 }
}
