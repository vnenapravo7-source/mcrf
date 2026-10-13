using System;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static readonly ImageSource brandLogo=LoadBrandLogo();
  static ImageSource LoadBrandLogo(){using(var stream=typeof(BrandArt).Assembly.GetManifestResourceStream(BrandArt.LogoResourceName)){if(stream==null)throw new IOException("В EXE отсутствует логотип MCRF.");var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.StreamSource=stream;image.EndInit();image.Freeze();return image;}}
  static ImageSource BrandLogo(){return brandLogo;}
  static ImageSource BitmapSource(System.Drawing.Bitmap bitmap){using(bitmap)using(var stream=new MemoryStream()){bitmap.Save(stream,System.Drawing.Imaging.ImageFormat.Png);stream.Position=0;var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.StreamSource=stream;image.EndInit();image.Freeze();return image;}}
  static ImageSource FlagImage(string name){var bitmap=new System.Drawing.Bitmap(32,22);using(var g=System.Drawing.Graphics.FromImage(bitmap)){g.Clear(System.Drawing.Color.Transparent);ServerIdentity.Flag(g,new System.Drawing.Rectangle(0,0,32,22),ServerIdentity.Country(name));}return BitmapSource(bitmap);}
  void SetBrand(){Icon=BitmapSource(BrandArt.RenderIcon(48));}
 }
}
