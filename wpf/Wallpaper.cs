using System;
using System.Windows;
using System.Windows.Media;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static readonly Brush wallpaper=MakeWallpaper();
  static Brush Wallpaper(){return wallpaper;}
  static Brush MakeWallpaper(){
   var icons=new[]{"M0,-16 L14,-10 11,7 0,17 -11,7 -14,-10 Z M-6,0 L-1,5 7,-5","M-7,-4 L-7,-10 A7,7 0 0 1 7,-10 L7,-4 M-12,-4 L12,-4 12,15 -12,15 Z","M-17,0 A17,17 0 1 0 17,0 A17,17 0 1 0 -17,0 M0,-17 A7,17 0 1 0 0,17 A7,17 0 1 0 0,-17 M-17,0 L17,0","M-16,-12 L16,-8 -5,16 Z M-20,-12 A4,4 0 1 0 -12,-12 A4,4 0 1 0 -20,-12","M-17,-14 L17,-14 17,-5 -17,-5 Z M-17,-2 L17,-2 17,7 -17,7 Z M-17,10 L17,10 17,19 -17,19 Z","M5,-18 L-11,3 -1,3 -5,18 13,-5 3,-5 Z","M-18,-13 L18,-13 18,13 -18,13 Z M-18,-5 L18,-5 M-6,0 L-11,5 -6,10 M6,0 L11,5 6,10","M-16,-6 A22,20 0 0 1 16,-6 M-10,1 A14,12 0 0 1 10,1 M-3,12 A3,3 0 1 0 3,12 A3,3 0 1 0 -3,12"};
   var group=new DrawingGroup();using(var dc=group.Open()){dc.DrawRectangle(Brushes.Transparent,null,new Rect(0,0,480,320));var pen=new Pen(Brush("#178196CC"),1.1);for(int row=0;row<5;row++)for(int col=0;col<7;col++){double x=col*70+((row%2)*24)-10,y=row*68-12;var geometry=Geometry.Parse(icons[(row*5+col)%icons.Length]);geometry.Freeze();foreach(double dx in new[]{0.0,480.0,-480.0})foreach(double dy in new[]{0.0,320.0,-320.0}){dc.PushTransform(new TranslateTransform(x+dx,y+dy));dc.PushTransform(new RotateTransform((row*7+col*13)%37-18));dc.DrawGeometry(null,pen,geometry);dc.Pop();dc.Pop();}}}group.Freeze();var brush=new DrawingBrush(group){TileMode=TileMode.Tile,ViewportUnits=BrushMappingMode.Absolute,Viewport=new Rect(0,0,320,213.333),ViewboxUnits=BrushMappingMode.Absolute,Viewbox=new Rect(0,0,480,320),Stretch=Stretch.Fill};brush.Freeze();return brush;
  }
 }
}
