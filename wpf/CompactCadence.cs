using System.Linq;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static int[] CompactCadenceValues(int current,int minimum){return new[]{15,30,45,60,120,180,240,300,600,900,current}.Where(v=>v>=minimum&&v<=3600).Distinct().OrderBy(v=>v).ToArray();}
  static string CompactCadenceLabel(int seconds){return seconds>=60&&seconds%60==0?(seconds/60)+" мин":seconds+" с";}
 }
}
