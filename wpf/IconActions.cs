using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  const double IconActionSize=30,IconActionMargin=2;
  // Include both five-DIP cell paddings plus room for fractional-DPI rounding.
  static double IconCellWidth(int count){return 14+count*(IconActionSize+2*IconActionMargin);}
  Button IconButton(string kind,string hint,Func<Task> action){
   var button=AsyncButton("",action);button.Style=(Style)FindResource("IconAction");button.ToolTip=hint;button.Width=IconActionSize;button.Height=IconActionSize;button.MinHeight=IconActionSize;button.Padding=new Thickness(7);button.Margin=new Thickness(IconActionMargin,0,IconActionMargin,0);
   string data=kind=="plus"?"M8,2 L8,14 M2,8 L14,8":kind=="minus"?"M2,8 L14,8":kind=="info"?"M8,1 A7,7 0 1 1 8,15 A7,7 0 1 1 8,1 M8,7 L8,11 M8,4 L8,4.1":kind=="choose"?"M4,6 L8,10 12,6":kind=="folder"?"M1,4 L6,4 8,6 15,6 15,13 1,13 Z":kind=="copy"?"M4,1 L13,1 13,10 M1,4 L10,4 10,13 1,13 Z":kind=="ping"?"M1,8 L4,8 6,2 9,13 11,6 15,6":kind=="edit"?"M2,10 L10,2 13,5 5,13 1,14 Z M8,4 L11,7":"M3,3 L13,13 M13,3 L3,13";
   button.Content=new System.Windows.Shapes.Path{Data=Geometry.Parse(data),Stroke=Brush("#DCE6F8"),StrokeThickness=1.5,StrokeStartLineCap=PenLineCap.Round,StrokeEndLineCap=PenLineCap.Round,Stretch=Stretch.Uniform,Width=14,Height=14};if(kind=="info"){button.Padding=new Thickness(6);var symbol=new Grid{Width=16,Height=16};symbol.Children.Add(new System.Windows.Shapes.Ellipse{Stroke=Brush("#DCE6F8"),StrokeThickness=1.2,Margin=new Thickness(1)});symbol.Children.Add(new TextBlock{Text="i",FontSize=11,FontWeight=FontWeights.SemiBold,Foreground=Brush("#DCE6F8"),HorizontalAlignment=HorizontalAlignment.Center,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(0,-1,0,0)});button.Content=symbol;}return button;
  }
  static TextBlock CellMark(string text){var label=CellText(text);label.HorizontalAlignment=HorizontalAlignment.Center;label.TextAlignment=TextAlignment.Center;label.Margin=new Thickness(0);return label;}
 }
}
