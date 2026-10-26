using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using System.Threading.Tasks;
using SplifyWin;

namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  bool detachedWorkspaceOpen;
  bool detachedWorkspaceClosing;
  Button SavedCheckResultsButton(){var button=AsyncButton("Результаты проверок",async()=>{reportFromEditor=false;lastReport=await Task.Run(()=>ZapretChecks.Load(root));ShowWorkspaceResults();});button.Name="zapretCheckResults";button.Margin=new Thickness(0,12,0,0);return button;}
  Window detachedWorkspaceWindow;Grid detachedWorkspaceContent;UIElement[] detachedWorkspaceOriginal;
  Button WorkspaceResultButton(string text,Func<Task> action,TextBlock feedback){return Button(text,async()=>await Run(async()=>{try{await action();feedback.Text=status.Text;}catch(Exception ex){feedback.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));throw;}},()=>{}));}
  void ApplyDetachedPalette(DependencyObject content){foreach(var button in CompactLogicalChildren<Button>(content).Where(b=>b.Content is string))StyleCompactButton(button);ApplyCompactControlPalette(content);}
  void ShowWorkspaceResults(){
   if(!detachedWorkspaceOpen){RefreshAfterOperation("Результаты");return;}
   var originalBody=body;UIElement[] captured;body=new Grid();
   try{ResultsPage();captured=body.Children.Cast<UIElement>().ToArray();}
   finally{body.Children.Clear();body=originalBody;}
   detachedWorkspaceContent.Children.Clear();var layout=new DockPanel();var back=Button("Назад",RestoreDetachedWorkspace);back.Name="detachedWorkspaceBack";back.HorizontalAlignment=HorizontalAlignment.Left;back.Margin=new Thickness(0,0,0,10);StyleCompactButton(back);DockPanel.SetDock(back,Dock.Top);layout.Children.Add(back);
   var results=new Grid();foreach(var child in captured){var card=child as Border;if(card!=null)StyleCompactCard(card);results.Children.Add(child);}layout.Children.Add(results);detachedWorkspaceContent.Children.Add(layout);ApplyDetachedPalette(detachedWorkspaceContent);
  }
  void RestoreDetachedWorkspace(){if(detachedWorkspaceContent==null)return;detachedWorkspaceContent.Children.Clear();foreach(var child in detachedWorkspaceOriginal)detachedWorkspaceContent.Children.Add(child);}
  void OpenDetachedWorkspace(string heading,Action build){
   if(detachedWorkspaceOpen||closing||closeRequested)return;
   bool originalReportMode=reportFromEditor;
   try{BuildDetachedWorkspace(heading,build);}finally{reportFromEditor=originalReportMode;}
  }
  void BuildDetachedWorkspace(string heading,Action build){
   if(detachedWorkspaceOpen)return;
   var originalBody=body;bool originalReportMode=reportFromEditor;
   var scroll=activePageScroll;var home=refreshHome;var servers=refreshServer;var logs=refreshLog;
   UIElement[] captured;
   body=new Grid();
   try{build();captured=body.Children.Cast<UIElement>().ToArray();}
   finally{body.Children.Clear();body=originalBody;activePageScroll=scroll;refreshHome=home;refreshServer=servers;refreshLog=logs;}
   var content=new Grid{Margin=new Thickness(14)};
   foreach(var item in captured){var card=item as Border;if(card!=null)StyleCompactCard(card);content.Children.Add(item);}
   ApplyDetachedPalette(content);
   var window=Dialog(heading,960,720);
   window.Width=Math.Min(960,SystemParameters.WorkArea.Width-48);
   window.Height=Math.Min(720,SystemParameters.WorkArea.Height-48);
   window.MinWidth=Math.Min(640,window.Width);window.MinHeight=Math.Min(480,window.Height);
   window.Content=content;
   detachedWorkspaceWindow=window;detachedWorkspaceContent=content;detachedWorkspaceOriginal=captured;
   detachedWorkspaceClosing=false;bool closeRequested=false;var closeTimer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(100)};
   closeTimer.Tick+=(s,e)=>{if(scanCancellation!=null||busy)return;closeTimer.Stop();window.Close();};
   window.Closing+=(s,e)=>{if(scanCancellation==null&&!busy)return;e.Cancel=true;if(closeRequested)return;closeRequested=true;detachedWorkspaceClosing=true;content.IsEnabled=false;window.Title=heading+(scanCancellation==null?" · завершение операции":" · остановка проверки");if(scanCancellation!=null)scanCancellation.Cancel();closeTimer.Start();};
   window.Closed+=(s,e)=>closeTimer.Stop();
   detachedWorkspaceOpen=true;
   try{window.ShowDialog();}finally{closeTimer.Stop();detachedWorkspaceOpen=false;detachedWorkspaceClosing=false;detachedWorkspaceWindow=null;detachedWorkspaceContent=null;detachedWorkspaceOriginal=null;reportFromEditor=originalReportMode;}
  }
 }
}
