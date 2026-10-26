using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static UIElement VkStreamSlider(string name,string label,string kind,int current,bool inherit,Action<int> change){
   Action<int> ignored;return VkStreamSlider(name,label,kind,current,inherit,change,false,out ignored);
  }
  static UIElement VkStreamSlider(string name,string label,string kind,int current,bool inherit,Action<int> change,bool exact,out Action<int> select){
   int[] choices=exact&&kind=="csqtt25"?Enumerable.Range(3,98).ToArray():VkTurnSettings.Values(kind);
   int[] allowed=(inherit?new[]{0}:new int[0]).Concat(choices).ToArray();
   int[] values=allowed.Contains(current)?allowed:allowed.Concat(new[]{current}).OrderBy(n=>n).ToArray();int index=Array.IndexOf(values,current);
   var panel=new StackPanel{Margin=new Thickness(0,8,0,12)};
   var caption=Text("");caption.Margin=new Thickness(0,0,0,6);
   var slider=new Slider{Name=name,Tag=values,Style=(Style)Application.Current.FindResource("VkStreamBar"),Minimum=0,Maximum=values.Length-1,Value=index,TickFrequency=1,IsSnapToTickEnabled=true,IsMoveToPointEnabled=true,MinHeight=32,Margin=new Thickness(4,0,4,0)};
   Action show=()=>{int n=values[(int)slider.Value],tail=n%100,digit=n%10;string unit=tail>=11&&tail<=14?"потоков":digit==1?"поток":digit>=2&&digit<=4?"потока":"потоков";caption.Text=label+" · "+(!allowed.Contains(n)?"недопустимое число: "+n:n==0?"по умолчанию":n+" "+unit);};
   slider.ValueChanged+=(s,e)=>{show();change(values[(int)slider.Value]);};show();
   select=n=>{int selected=Array.IndexOf(values,n);if(selected>=0)slider.Value=selected;};
   slider.ToolTip="Стрелки — изменить число потоков. Изменения действуют со следующего подключения.";
   panel.Children.Add(caption);panel.Children.Add(slider);return panel;
  }
  // Local spacing only: do not mutate the theme or other pages.
  static void SpaceVkTurn(DependencyObject root){
   if(root is FrameworkElement&&Equals(((FrameworkElement)root).Tag,"CompactPreserveGrid"))return;
   var panel=root as Panel;
   if(panel!=null)foreach(UIElement child in panel.Children){var f=child as FrameworkElement;if(f!=null){var m=f.Margin;f.Margin=new Thickness(m.Left,m.Top,m.Right,Math.Max(10,m.Bottom));}SpaceVkTurn(child);}
   var border=root as Border;if(border!=null&&border.Child!=null)SpaceVkTurn(border.Child);
  }
 }
}
