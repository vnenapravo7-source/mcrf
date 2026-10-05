using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace SplifyWin {
  public static class BrandArt {
    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr handle);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr handle,int attribute,ref int value,int size);
    public static void ApplyFramelessChrome(IntPtr handle){ApplyChrome(handle);try{int policy=1;DwmSetWindowAttribute(handle,2,ref policy,4);int corners=1;DwmSetWindowAttribute(handle,33,ref corners,4);int noBorder=-2;DwmSetWindowAttribute(handle,34,ref noBorder,4);}catch{}}
    public static void DrawPlane(Graphics graphics,Rectangle bounds,Color fill){var state=graphics.Save();graphics.SmoothingMode=SmoothingMode.AntiAlias;graphics.TranslateTransform(bounds.Left,bounds.Top);graphics.ScaleTransform(bounds.Width/64f,bounds.Height/64f);using(var brush=new SolidBrush(fill))using(var path=new GraphicsPath()){path.AddLines(new[]{new PointF(6,30),new PointF(58,7),new PointF(43,57),new PointF(30,41),new PointF(20,50),new PointF(23,36)});path.CloseFigure();graphics.FillPath(brush,path);}using(var pen=new Pen(Color.FromArgb(Math.Min((int)fill.A,180),16,24,43),2.5f)){graphics.DrawLine(pen,23,36,47,18);graphics.DrawLine(pen,30,41,47,18);}graphics.Restore(state);}
    public static Bitmap RenderIcon(int size){var bitmap=new Bitmap(size,size);using(var g=Graphics.FromImage(bitmap)){g.SmoothingMode=SmoothingMode.AntiAlias;g.Clear(Color.Transparent);using(var background=new SolidBrush(Color.FromArgb(23,34,42)))using(var path=new GraphicsPath()){int inset=Math.Max(1,size/32);int diameter=size-inset*2;int radius=Math.Max(3,size/5);path.AddArc(inset,inset,radius,radius,180,90);path.AddArc(inset+diameter-radius,inset,radius,radius,270,90);path.AddArc(inset+diameter-radius,inset+diameter-radius,radius,radius,0,90);path.AddArc(inset,inset+diameter-radius,radius,radius,90,90);path.CloseFigure();g.FillPath(background,path);}DrawPlane(g,new Rectangle(size/8,size/8,size*3/4,size*3/4),Color.FromArgb(132,164,178));}return bitmap;}
    public static Icon CreateIcon(){using(var bitmap=RenderIcon(64)){var handle=bitmap.GetHicon();try{return (Icon)Icon.FromHandle(handle).Clone();}finally{DestroyIcon(handle);}}}
    public static void ApplyChrome(IntPtr handle){try{int enabled=1;if(DwmSetWindowAttribute(handle,20,ref enabled,4)!=0)DwmSetWindowAttribute(handle,19,ref enabled,4);var caption=ColorTranslator.ToWin32(Color.FromArgb(23,34,42));DwmSetWindowAttribute(handle,35,ref caption,4);var border=ColorTranslator.ToWin32(Color.FromArgb(23,34,42));DwmSetWindowAttribute(handle,34,ref border,4);var title=ColorTranslator.ToWin32(Color.FromArgb(237,242,242));DwmSetWindowAttribute(handle,36,ref title,4);}catch{} }
  }
}
