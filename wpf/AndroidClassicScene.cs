using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  // RoundConnectionButton.kt / Loft.PowerSurface: deliberately no animation loop.
  UIElement AndroidClassicPowerScene(string phase){
   bool failed=!String.IsNullOrEmpty(session.Core.LastFailure)&&phase=="off",active=phase=="active",pending=phase=="starting"||phase=="stopping";
   var scene=new Grid{Width=104,Height=104};
   var color=Brush(failed?CompactError:active?"#32B875":CompactVisualAccent);
   scene.Children.Add(new Ellipse{Stroke=color,StrokeThickness=3,Fill=Brush(CompactSurface),Margin=new Thickness(1)});
   var glyph=new Canvas{Width=24,Height=24};glyph.Children.Add(new Path{Data=Geometry.Parse("M16.5886,5.4468 A8,8 0 1 1 7.4114,5.4468 M12,2 V11"),Stroke=Brush(failed?CompactError:active?"#32B875":CompactPrimary),StrokeThickness=1.6,StrokeStartLineCap=PenLineCap.Round,StrokeEndLineCap=PenLineCap.Round,StrokeLineJoin=PenLineJoin.Round});scene.Children.Add(new Viewbox{Child=glyph,Opacity=busy||closing||closeRequested||pending?100d/255d:1,Width=34,Height=34,HorizontalAlignment=HorizontalAlignment.Center,VerticalAlignment=VerticalAlignment.Center});
   if(pending)scene.Children.Add(new Path{Data=Geometry.Parse("M52,3 A49,49 0 0 1 100.8135,47.7294"),Stroke=Brush(CompactPrimary),StrokeThickness=3});
   return scene;
  }
 }
}
