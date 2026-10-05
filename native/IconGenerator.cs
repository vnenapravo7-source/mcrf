using System;
using System.Drawing;
using System.IO;

namespace SplifyWin {
  static class IconGenerator {
    static void Main(string[] args){if(args.Length!=1)throw new ArgumentException("Output ICO path required");var sizes=new[]{16,32,64,256};var images=new byte[sizes.Length][];for(int i=0;i<sizes.Length;i++){using(var image=BrandArt.RenderIcon(sizes[i]))using(var stream=new MemoryStream()){image.Save(stream,System.Drawing.Imaging.ImageFormat.Png);images[i]=stream.ToArray();}}using(var file=new BinaryWriter(File.Create(args[0]))){file.Write((ushort)0);file.Write((ushort)1);file.Write((ushort)sizes.Length);int offset=6+16*sizes.Length;for(int i=0;i<sizes.Length;i++){file.Write((byte)(sizes[i]==256?0:sizes[i]));file.Write((byte)(sizes[i]==256?0:sizes[i]));file.Write((byte)0);file.Write((byte)0);file.Write((ushort)1);file.Write((ushort)32);file.Write(images[i].Length);file.Write(offset);offset+=images[i].Length;}foreach(var image in images)file.Write(image);}}
  }
}
