using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal void ApplyCellPalette(DependencyObject content){
   if(session==null||content==null)return;
   foreach(var button in CompactLogicalChildren<Button>(content)){
    if(button.Style==(Style)FindResource("CompactIconAction")||button.Style==(Style)FindResource("IconAction")||button.Style==(Style)FindResource("BareButton")){button.Foreground=Brush(CompactPrimary);continue;}
    if(!(button.Content is string))continue;
    var padding=button.Padding;bool small=!Double.IsNaN(button.Height)&&button.Height<36;StyleCompactButton(button,button.ReadLocalValue(Control.BackgroundProperty) is LinearGradientBrush);if(small)button.Padding=padding;
   }
   ApplyCompactControlPalette(content);
  }
 }
}
