using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  [DllImport("dwmapi.dll")]static extern int DwmSetWindowAttribute(IntPtr hwnd,int attribute,ref int value,int size);
  void InstallDarkWindowFrame(){
   // Own the client template; no light theme border may show behind the shell.
   var template=new ControlTemplate(typeof(Window));var surface=new FrameworkElementFactory(typeof(Border));
   surface.SetBinding(Border.BackgroundProperty,new System.Windows.Data.Binding("Background"){RelativeSource=new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)});
   surface.AppendChild(new FrameworkElementFactory(typeof(ContentPresenter)));template.VisualTree=surface;Template=template;
   SourceInitialized+=(s,e)=>{var handle=new WindowInteropHelper(this).Handle;
    try{int dark=1,color=0x001F110C,round=2;DwmSetWindowAttribute(handle,20,ref dark,4);DwmSetWindowAttribute(handle,34,ref color,4);DwmSetWindowAttribute(handle,35,ref color,4);DwmSetWindowAttribute(handle,33,ref round,4);}catch(DllNotFoundException){}catch(EntryPointNotFoundException){}
   };
  }
 }
}
