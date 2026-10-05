using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Windows.Forms;
namespace SplifyWin {
  internal sealed class NeonServerMap : Control {
    Image atlas;readonly Func<ServerNode> selected;
    public NeonServerMap(Func<ServerNode> source){selected=source;DoubleBuffered=true;SetStyle(ControlStyles.SupportsTransparentBackColor,true);BackColor=Color.Transparent;Name="serverNeonMap";using(var stream=GetType().Assembly.GetManifestResourceStream("SplifyWin.UI.NeonGlobe.png"))if(stream!=null)using(var image=Image.FromStream(stream))atlas=new Bitmap(image);}
    protected override void OnPaint(PaintEventArgs e){base.OnPaint(e);var g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;int h=Math.Min(Height-95,Width*2/3);var rect=new Rectangle(0,12,Width,Math.Max(1,h));if(atlas!=null)using(var attrs=new ImageAttributes()){var m=new ColorMatrix();m.Matrix33=.30f;attrs.SetColorMatrix(m);g.DrawImage(atlas,rect,0,0,atlas.Width,atlas.Height,GraphicsUnit.Pixel,attrs);}var node=selected();if(node==null)return;GlassInk.Text(g,ServerIdentity.Display(node.Name),new Rectangle(14,Height-78,Width-28,34),17,GlassInk.White,true);GlassInk.Text(g,node.TransportLabel+" · "+(node.Latency<0?"Задержка не проверена":node.Latency+" мс"),new Rectangle(14,Height-40,Width-28,30),10,GlassInk.Cyan);}
    protected override void Dispose(bool disposing){if(disposing&&atlas!=null)atlas.Dispose();base.Dispose(disposing);}
  }
}
