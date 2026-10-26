using System;
using System.Windows.Media;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static double CompactLuminance(Color color){Func<byte,double> channel=v=>{double n=v/255d;return n<=.04045?n/12.92:Math.Pow((n+.055)/1.055,2.4);};return .2126*channel(color.R)+.7152*channel(color.G)+.0722*channel(color.B);}
  static double CompactContrast(Color a,Color b){double x=CompactLuminance(a),y=CompactLuminance(b);return (Math.Max(x,y)+.05)/(Math.Min(x,y)+.05);}
  static Color CompactMix(Color a,Color b,double amount){return Color.FromRgb((byte)Math.Round(a.R*(1-amount)+b.R*amount),(byte)Math.Round(a.G*(1-amount)+b.G*amount),(byte)Math.Round(a.B*(1-amount)+b.B*amount));}
  static Color CompactColor(string value){return (Color)ColorConverter.ConvertFromString(value);}
  static string CompactSafeDarkColor(string value){var color=CompactColor(value);var ink=CompactColor("#F2F5F7");for(int i=0;i<50&&CompactContrast(color,ink)<7;i++)color=CompactMix(color,Colors.Black,.1);return color.ToString();}
  Color[] CompactAccentSurfaces(){var raw=CompactColor(CompactAccentColor);if(CompactLight)return new[]{CompactColor("#D7DFE5"),CompactColor("#E2E7EB"),Colors.White,CompactMix(CompactColor("#D7DFE5"),raw,54/255d)};string background=session==null?"#0E1217":session.State.CompactBackgroundColor??"#0E1217";string card=session==null?"#171C22":session.State.CompactCardColor??"#171C22";var field=CompactColor("#20262D");return new[]{field,CompactMix(CompactColor(CompactSafeDarkColor(background)),CompactColor(CompactSafeDarkColor(card)),198/255d),CompactMix(field,raw,65/255d)};}
  string CompactThemedAccent(){var original=CompactColor(CompactAccentColor);var target=CompactLight?Colors.Black:Colors.White;var backgrounds=CompactAccentSurfaces();for(int step=0;step<=40;step++){var accent=CompactMix(original,target,step/40d);if(System.Linq.Enumerable.All(backgrounds,background=>CompactContrast(accent,background)>=4.5))return accent.ToString();}return target.ToString();}
 }
}
