using System;
using System.IO;
using System.Text;
namespace SplifyWin {
 public sealed class ProfileRecoveryRequiredException : IOException {
  public readonly string RecoveryFolder;
  internal ProfileRecoveryRequiredException(string folder):base("Профиль заблокирован после незавершённого отката. Обычное сохранение и подключение запрещены. Восстановите резервную копию: "+folder){RecoveryFolder=folder;}
 }
 public static class ProfileRecovery {
  public const string MarkerName="profile-recovery-required.txt";
  static string Marker(string root){return Path.Combine(Path.GetFullPath(root),MarkerName);}
  public static bool Required(string root){try{File.GetAttributes(Marker(root));return true;}catch(FileNotFoundException){return false;}catch(DirectoryNotFoundException){return false;}catch{return true;}}
  public static void DemandHealthy(string root){if(!Required(root))return;string file=Marker(root),folder="путь указан в "+file;try{if((File.GetAttributes(file)&FileAttributes.ReparsePoint)==0&&new FileInfo(file).Length<=8192){string text=File.ReadAllText(file,Encoding.UTF8).Trim();if(!String.IsNullOrWhiteSpace(text))folder=text;}}catch{}throw new ProfileRecoveryRequiredException(folder);}
  internal static void Mark(string root,string recoveryFolder){string marker=Marker(root);if(Required(root)){if((File.GetAttributes(marker)&FileAttributes.ReparsePoint)!=0)throw new IOException("Файл блокировки профиля содержит ссылку.");return;}string temporary=marker+"."+Guid.NewGuid().ToString("N")+".tmp";try{File.WriteAllText(temporary,Path.GetFullPath(recoveryFolder),new UTF8Encoding(false));File.Move(temporary,marker);}finally{if(File.Exists(temporary))File.Delete(temporary);}}
  internal static void Clear(string root){string marker=Marker(root);if(!Required(root))return;if((File.GetAttributes(marker)&FileAttributes.ReparsePoint)!=0)throw new IOException("Файл блокировки профиля содержит ссылку.");File.Delete(marker);}
 }
}
