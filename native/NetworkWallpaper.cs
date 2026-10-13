using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
namespace SplifyWin {
 // A single small transparent tile, reused by both frontends. No animation or full-window bitmap.
 public static class NetworkWallpaper {
  static readonly Bitmap tile=MakeTile();
  public static Bitmap CopyTile(){return (Bitmap)tile.Clone();}
  public static void Draw(Graphics graphics,Rectangle area){using(var brush=new TextureBrush(tile,WrapMode.Tile))graphics.FillRectangle(brush,area);}
  static Bitmap MakeTile(){var image=new Bitmap(420,300);using(var g=Graphics.FromImage(image)){g.Clear(Color.Transparent);g.SmoothingMode=SmoothingMode.AntiAlias;using(var pen=new Pen(Color.FromArgb(23,129,150,204),1.2f)){pen.LineJoin=LineJoin.Round;pen.StartCap=pen.EndCap=LineCap.Round;for(int row=0;row<4;row++)for(int col=0;col<6;col++){var saved=g.Save();g.TranslateTransform(35+col*70+(row%2)*12,35+row*75);g.RotateTransform(((col+row)%3-1)*14);DrawIcon(g,pen,(col+row*3)%8);g.Restore(saved);}}}for(int y=0;y<image.Height;y++)for(int x=0;x<image.Width;x++){var color=image.GetPixel(x,y);if(color.A>23)image.SetPixel(x,y,Color.FromArgb(23,color.R,color.G,color.B));}return image;}
  static void DrawIcon(Graphics g,Pen p,int icon){switch(icon){
   case 0: g.DrawPolygon(p,new[]{new Point(-14,-14),new Point(0,-19),new Point(14,-14),new Point(11,5),new Point(0,17),new Point(-11,5)});g.DrawLines(p,new[]{new Point(-6,0),new Point(-1,5),new Point(7,-5)});break;
   case 1: g.DrawArc(p,-8,-17,16,21,180,180);g.DrawRectangle(p,-13,-5,26,21);g.DrawEllipse(p,-2,1,4,4);g.DrawLine(p,0,5,0,10);break;
   case 2: g.DrawEllipse(p,-17,-17,34,34);g.DrawEllipse(p,-7,-17,14,34);g.DrawLine(p,-17,0,17,0);g.DrawArc(p,-17,-9,34,18,0,180);break;
   case 3: foreach(var point in new[]{new Point(-14,-12),new Point(15,-7),new Point(-5,16)})g.DrawEllipse(p,point.X-4,point.Y-4,8,8);g.DrawLine(p,-10,-11,11,-8);g.DrawLine(p,13,-3,-3,12);g.DrawLine(p,-7,12,-13,-8);break;
   case 4: for(int y=-14;y<=10;y+=12){g.DrawRectangle(p,-17,y,34,9);g.DrawEllipse(p,-12,y+3,2,2);g.DrawLine(p,0,y+4,11,y+4);}break;
   case 5: g.DrawPolygon(p,new[]{new Point(5,-19),new Point(-11,3),new Point(-1,3),new Point(-5,19),new Point(13,-5),new Point(3,-5)});break;
   case 6: g.DrawRectangle(p,-19,-14,38,26);g.DrawLine(p,-19,-6,19,-6);g.DrawEllipse(p,-14,-11,2,2);g.DrawLines(p,new[]{new Point(-8,3),new Point(-12,6),new Point(-8,9)});g.DrawLines(p,new[]{new Point(8,3),new Point(12,6),new Point(8,9)});g.DrawLine(p,2,1,-2,11);break;
   default: g.DrawArc(p,-19,-16,38,34,218,103);g.DrawArc(p,-12,-8,24,21,218,103);g.DrawEllipse(p,-2,10,4,4);break;
  }}
 }
 sealed class NetworkWallpaperPanel:Panel {
  public NetworkWallpaperPanel(){DoubleBuffered=true;}
  protected override void OnPaintBackground(PaintEventArgs e){base.OnPaintBackground(e);NetworkWallpaper.Draw(e.Graphics,ClientRectangle);}
 }
}
