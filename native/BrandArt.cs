using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

namespace SplifyWin {
  public static class BrandArt {
    public const string LogoResourceName="SplifyWin.UI.Logo.png";
    public const string IconResourceName="SplifyWin.UI.AppIcon.ico";
    static readonly Bitmap logo=LoadLogo();
    static Bitmap LoadLogo(){using(var stream=typeof(BrandArt).Assembly.GetManifestResourceStream(LogoResourceName)){if(stream==null)throw new InvalidOperationException("The approved MCRF logo is missing from the application.");using(var image=Image.FromStream(stream))return new Bitmap(image);}}
    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr handle);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr handle,int attribute,ref int value,int size);
    public static void ApplyFramelessChrome(IntPtr handle){ApplyChrome(handle);try{int policy=1;DwmSetWindowAttribute(handle,2,ref policy,4);int corners=1;DwmSetWindowAttribute(handle,33,ref corners,4);int noBorder=-2;DwmSetWindowAttribute(handle,34,ref noBorder,4);}catch{}}
    public static void SetResizeTransitions(IntPtr handle,bool enabled){try{int disabled=enabled?0:1;DwmSetWindowAttribute(handle,3,ref disabled,4);}catch{}}
    // Keep the approved artwork's palette; decorative callers supply only opacity.
    public static void DrawLogo(Graphics graphics,Rectangle bounds,Color opacity){DrawLogo(graphics,bounds,opacity.A);}
    public static void DrawLogo(Graphics graphics,Rectangle bounds,byte opacity=255){if(bounds.Width<=0||bounds.Height<=0)return;var state=graphics.Save();try{graphics.InterpolationMode=InterpolationMode.HighQualityBicubic;graphics.PixelOffsetMode=PixelOffsetMode.HighQuality;graphics.CompositingQuality=CompositingQuality.HighQuality;using(var attributes=new ImageAttributes()){attributes.SetWrapMode(WrapMode.Clamp);var matrix=new ColorMatrix();matrix.Matrix33=opacity/255f;attributes.SetColorMatrix(matrix);lock(logo){graphics.DrawImage(logo,bounds,0,0,logo.Width,logo.Height,GraphicsUnit.Pixel,attributes);}}}finally{graphics.Restore(state);}}
    public static Bitmap RenderIcon(int size){if(size<1||size>1024)throw new ArgumentOutOfRangeException("size");var bitmap=new Bitmap(size,size,PixelFormat.Format32bppPArgb);bitmap.SetResolution(96,96);using(var graphics=Graphics.FromImage(bitmap)){graphics.Clear(Color.Transparent);DrawLogo(graphics,new Rectangle(0,0,size,size));}return bitmap;}
    public static Icon CreateIcon(int size=32){if(size<1||size>1024)throw new ArgumentOutOfRangeException("size");using(var stream=typeof(BrandArt).Assembly.GetManifestResourceStream(IconResourceName)){if(stream==null)throw new InvalidOperationException("The MCRF application icon is missing.");using(var icon=new Icon(stream,new Size(size,size))){if(icon.Width==size&&icon.Height==size)return (Icon)icon.Clone();}}/* Framework Icon can select 128px for a 256px PNG entry; keep requested dimensions. */using(var bitmap=RenderIcon(size)){var handle=bitmap.GetHicon();try{return (Icon)Icon.FromHandle(handle).Clone();}finally{DestroyIcon(handle);}}}
    public static void ApplyChrome(IntPtr handle){try{int enabled=1;if(DwmSetWindowAttribute(handle,20,ref enabled,4)!=0)DwmSetWindowAttribute(handle,19,ref enabled,4);var caption=ColorTranslator.ToWin32(Color.FromArgb(23,34,42));DwmSetWindowAttribute(handle,35,ref caption,4);var border=ColorTranslator.ToWin32(Color.FromArgb(23,34,42));DwmSetWindowAttribute(handle,34,ref border,4);var title=ColorTranslator.ToWin32(Color.FromArgb(237,242,242));DwmSetWindowAttribute(handle,36,ref title,4);}catch{} }
  }
}
