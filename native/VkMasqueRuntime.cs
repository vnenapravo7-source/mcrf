using System;
using System.IO;
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
namespace SplifyWin {
 public sealed class VkMasqueRuntime : IDisposable {
  readonly object gate=new object();readonly string directory,encrypted;bool disposed;
  public readonly string Config;public readonly bool AllowEnrollment;
  public VkMasqueRuntime(string root,bool probe){encrypted=Path.Combine(root,"wdtt-masque.dat");AllowEnrollment=!probe;if(probe&&!File.Exists(encrypted))throw new InvalidOperationException("Сначала подключите WDTT с MASQUE вручную: фоновая проверка не создаёт регистрацию Cloudflare.");directory=Path.Combine(root,"masque-runtime-"+Guid.NewGuid().ToString("N"));var security=new DirectorySecurity();security.SetAccessRuleProtection(true,false);security.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User,FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid,null),FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));Directory.CreateDirectory(directory,security);Config=Path.Combine(directory,"account.json");if(File.Exists(encrypted))SecretStorage.WriteRuntime(Config,SecretStorage.Read(encrypted));}
  public void Checkpoint(){lock(gate){if(File.Exists(Config)){var text=File.ReadAllText(Config);if(text.Length==0||text.Length>1048576)throw new IOException("Некорректная запись регистрации MASQUE.");SecretStorage.Write(encrypted,text);}}}
  public void Attach(Process process){process.EnableRaisingEvents=true;process.Exited+=(s,e)=>{try{Dispose();}catch{ /* Keep private runtime data if encryption failed; never throw from Exited. */ }};if(process.HasExited)Dispose();}
  public void Dispose(){lock(gate){if(disposed)return;Checkpoint();if(File.Exists(Config))File.Delete(Config);if(Directory.Exists(directory))Directory.Delete(directory,false);disposed=true;}}
 }
}
