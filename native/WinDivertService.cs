using System;
using System.IO;
using System.Security.Cryptography;
namespace SplifyWin {
  public static class WinDivertService {
    public const string Name="MRCF_WD";
    public const string SignedDriverHash="8da085332782708d8767bcace5327a6ec7283c17cfb85e40b03cd2323a90ddc2";
    public static void Prepare(string directory){
      string sys=Path.Combine(directory,"WinDivert64.sys");
      using(var sha=SHA256.Create())using(var input=File.OpenRead(sys)){
        string hash=BitConverter.ToString(sha.ComputeHash(input)).Replace("-","").ToLowerInvariant();
        if(hash!=SignedDriverHash)throw new InvalidDataException("Версия драйвера Zapret не совместима со службой "+Name+". Драйвер не изменён; запуск остановлен.");
      }
      BundledResources.Install("SplifyWin.Zapret.apps.WinDivert.dll",Path.Combine(directory,"WinDivert.dll"));
      BundledResources.Install("SplifyWin.Zapret.MRCF_WD.NOTICES.md",Path.Combine(directory,"MRCF_WD.NOTICES.md"));
    }
  }
}
