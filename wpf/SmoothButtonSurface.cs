using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
namespace Mcrf.Wpf {
 public sealed class SmoothButtonSurface:Border {
  // A one-DIP pen becomes 1.25/1.5 physical pixels and can be clipped by a cell.
  // Align the entire closed contour, including its origin, to physical pixels.
  protected override void OnRender(DrawingContext dc){
   var dpi=VisualTreeHelper.GetDpi(this);double sx=dpi.DpiScaleX,sy=dpi.DpiScaleY;Point origin=new Point();
   var source=PresentationSource.FromVisual(this);if(source!=null&&source.RootVisual!=this)try{origin=TransformToAncestor(source.RootVisual).Transform(origin);}catch(InvalidOperationException){}
   double ox=origin.X*sx,oy=origin.Y*sy;
   double left=(Math.Ceiling(ox)+.5-ox)/sx,top=(Math.Ceiling(oy)+.5-oy)/sy;
   double right=(Math.Floor(ox+ActualWidth*sx)-.5-ox)/sx,bottom=(Math.Floor(oy+ActualHeight*sy)-.5-oy)/sy;
   if(right<=left||bottom<=top)return;
   var rect=new Rect(left,top,right-left,bottom-top);double radius=Math.Min(CornerRadius.TopLeft,Math.Min(rect.Width,rect.Height)/2);
   var pen=BorderBrush==null||BorderThickness.Left==0?null:new Pen(BorderBrush,1/sx);
   dc.DrawRoundedRectangle(Background,pen,rect,radius,radius);
  }
 }
}
