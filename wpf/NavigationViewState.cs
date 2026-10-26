using System;
using System.Windows.Controls;
using System.Windows.Threading;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  ScrollViewer pendingScrollRestore;
  void RememberPageScroll(){if(activePageScroll!=null&&activePageScroll!=pendingScrollRestore)pageScrollPositions[current]=activePageScroll.VerticalOffset;pendingScrollRestore=null;}
  void RestorePageScroll(){if(activePageScroll==null)return;double offset;pageScrollPositions.TryGetValue(current,out offset);var restore=activePageScroll;pendingScrollRestore=restore;Dispatcher.BeginInvoke(new Action(()=>{if(closing||closeRequested||activePageScroll!=restore||pendingScrollRestore!=restore)return;restore.ScrollToVerticalOffset(offset);pendingScrollRestore=null;}),DispatcherPriority.Loaded);}
 }
}
