using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static UIElement VkStreamSlider(string name,string label,string kind,int current,bool inherit,Action<int> change){
   int[] values=(inherit?new[]{0}:new int[0]).Concat(VkTurnSettings.Values(kind)).ToArray();
   int index=Array.IndexOf(values,current);if(index<0)index=inherit?0:Array.IndexOf(values,kind=="qwdtt"?9:18);
   var panel=new StackPanel{Margin=new Thickness(0,8,0,12)};
   var caption=Text("");caption.Margin=new Thickness(0,0,0,6);
   var slider=new Slider{Name=name,Style=(Style)Application.Current.FindResource("VkStreamBar"),Minimum=0,Maximum=values.Length-1,Value=index,TickFrequency=1,IsSnapToTickEnabled=true,IsMoveToPointEnabled=true,MinHeight=32,Margin=new Thickness(4,0,4,0)};
   Action show=()=>caption.Text=label+" · "+(values[(int)slider.Value]==0?"по умолчанию":values[(int)slider.Value]+" потоков");
   slider.ValueChanged+=(s,e)=>{show();change(values[(int)slider.Value]);};show();
   slider.ToolTip="Стрелки — изменить число потоков. Изменения действуют со следующего подключения.";
   panel.Children.Add(caption);panel.Children.Add(slider);return panel;
  }
  // Local spacing only: do not mutate the theme or other pages.
  static void SpaceVkTurn(DependencyObject root){
   var panel=root as Panel;
   if(panel!=null)foreach(UIElement child in panel.Children){var f=child as FrameworkElement;if(f!=null){var m=f.Margin;f.Margin=new Thickness(m.Left,m.Top,m.Right,Math.Max(10,m.Bottom));}SpaceVkTurn(child);}
   var border=root as Border;if(border!=null&&border.Child!=null)SpaceVkTurn(border.Child);
  }
 }
}
