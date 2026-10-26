using System;
using System.IO;
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
namespace SplifyWin {
 public sealed class VkMasqueRuntime : IDisposable {
  readonly object gate=new object();readonly string directory,encrypted;bool disposed;
  public readonly string Config;public readonly bool AllowEnrollment;
  static void ValidateRegistration(string text){if(text.Length==0||text.Length>1048576)throw new IOException("Некорректная запись регистрации MASQUE.");var value=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=1048576}.DeserializeObject(text);if(!(value is System.Collections.Generic.Dictionary<string,object>))throw new IOException("Некорректная запись регистрации MASQUE.");}
  public VkMasqueRuntime(string root,bool probe){
   encrypted=Path.Combine(root,"wdtt-masque.dat");AllowEnrollment=!probe;if(probe&&!File.Exists(encrypted))throw new InvalidOperationException("Сначала подключите WDTT с MASQUE вручную: фоновая проверка не создаёт регистрацию Cloudflare.");
   // Validate before migration or creating private runtime material. The account
   // schema belongs to the engine; only validate its JSON envelope here.
   string saved=File.Exists(encrypted)?SecretStorage.ReadValidated(encrypted,ValidateRegistration):null;
   directory=Path.Combine(root,"masque-runtime-"+Guid.NewGuid().ToString("N"));Config=Path.Combine(directory,"account.json");
   var security=new DirectorySecurity();security.SetAccessRuleProtection(true,false);security.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User,FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid,null),FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));
   try{Directory.CreateDirectory(directory,security);if(saved!=null)SecretStorage.WriteRuntime(Config,saved);}catch{if(File.Exists(Config))File.Delete(Config);if(Directory.Exists(directory))Directory.Delete(directory,false);throw;}
  }
  public void Checkpoint(){lock(gate){if(File.Exists(Config)){var text=File.ReadAllText(Config);ValidateRegistration(text);SecretStorage.Write(encrypted,text);}}}
  public void Attach(Process process){process.EnableRaisingEvents=true;process.Exited+=(s,e)=>{try{Dispose();}catch{ /* Keep private runtime data if encryption failed; never throw from Exited. */ }};if(process.HasExited)Dispose();}
  public void Dispose(){lock(gate){if(disposed)return;Checkpoint();if(File.Exists(Config))File.Delete(Config);if(Directory.Exists(directory))Directory.Delete(directory,false);disposed=true;}}
 }
}
