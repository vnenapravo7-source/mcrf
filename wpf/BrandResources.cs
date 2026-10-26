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
  static readonly System.Collections.Generic.Dictionary<string,ImageSource> flagImages=new System.Collections.Generic.Dictionary<string,ImageSource>(StringComparer.Ordinal);
  static ImageSource FlagImage(string name){string country=ServerIdentity.Country(name)??"";lock(flagImages){ImageSource cached;if(flagImages.TryGetValue(country,out cached))return cached;cached=CreateFlagImage(name);flagImages[country]=cached;return cached;}}
  static ImageSource CreateFlagImage(string name){if(String.IsNullOrEmpty(ServerIdentity.Country(name))){var geometry=Geometry.Parse("M 12,2 A 10,10 0 1 1 12,22 A 10,10 0 1 1 12,2 M 2,12 L 22,12 M 12,2 C 5,7 5,17 12,22 M 12,2 C 19,7 19,17 12,22");var image=new DrawingImage(new GeometryDrawing(null,new Pen(new SolidColorBrush(Color.FromRgb(115,155,180)),1.5),geometry));image.Freeze();return image;}var bitmap=new System.Drawing.Bitmap(192,128);using(var g=System.Drawing.Graphics.FromImage(bitmap)){g.Clear(System.Drawing.Color.Transparent);g.SmoothingMode=System.Drawing.Drawing2D.SmoothingMode.AntiAlias;g.ScaleTransform(4f,4f);ServerIdentity.Flag(g,new System.Drawing.Rectangle(0,0,48,32),ServerIdentity.Country(name));}return BitmapSource(bitmap);}
  void SetBrand(){Icon=BitmapSource(BrandArt.RenderIcon(48));}
 }
}
