using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Grid modalVeil;int modalDepth;
  // Freeze one low-resolution frame. Never blur the live visual tree during resize.
  internal void EnterModal(){
   if(modalDepth++>0)return;
   if(shell.ActualWidth<1||shell.ActualHeight<1)return;
   shell.UpdateLayout();double scale=Math.Min(1,900.0/shell.ActualWidth);
   var frame=new RenderTargetBitmap(Math.Max(1,(int)Math.Ceiling(shell.ActualWidth*scale)),Math.Max(1,(int)Math.Ceiling(shell.ActualHeight*scale)),96*scale,96*scale,PixelFormats.Pbgra32);frame.Render(shell);frame.Freeze();
   modalVeil=new Grid{IsHitTestVisible=true,CacheMode=new BitmapCache()};
   modalVeil.Children.Add(new Image{Source=frame,Stretch=Stretch.Fill,Effect=new BlurEffect{Radius=12,RenderingBias=RenderingBias.Performance}});
   modalVeil.Children.Add(new Border{Background=Brush("#6610162D")});
   Grid.SetRowSpan(modalVeil,2);Grid.SetColumnSpan(modalVeil,2);Panel.SetZIndex(modalVeil,100);shell.Children.Add(modalVeil);
  }
  internal void ExitModal(){if(modalDepth==0)return;if(--modalDepth>0)return;if(modalVeil!=null)shell.Children.Remove(modalVeil);modalVeil=null;}
 }
}
