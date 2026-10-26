using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
namespace Mcrf.Wpf {
 // Overlay shares the rail's center and the thumb's travel range.
 public sealed class StreamTrackPoints : FrameworkElement {
  protected override void OnRender(DrawingContext drawing){
   base.OnRender(drawing);var slider=TemplatedParent as Slider;if(slider==null||ActualWidth<=18)return;
   int intervals=(int)(slider.Maximum-slider.Minimum);if(intervals<1)return;
   var ink=TryFindResource("TooltipSurface") as Brush??Brushes.Black;
   int stride=Math.Max(1,(int)Math.Ceiling(intervals/Math.Max(1,(ActualWidth-18)/7)));
   for(int i=0;i<=intervals;i+=stride)drawing.DrawEllipse(ink,null,new Point(9+(ActualWidth-18)*i/intervals,ActualHeight/2),1.4,1.4);
   if(intervals%stride!=0)drawing.DrawEllipse(ink,null,new Point(ActualWidth-9,ActualHeight/2),1.4,1.4);
  }
 }
}
