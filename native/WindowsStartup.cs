using System;
using System.IO;
using System.Security.Principal;
using Microsoft.Win32;
namespace SplifyWin {
 public static class WindowsStartup {
  const string Key=@"Software\Microsoft\Windows\CurrentVersion\Run";
  const string Owner="MCRF: user-approved autostart";
  static string TaskName {get{return "MCRF.Startup-"+WindowsIdentity.GetCurrent().User.Value;}}
  public static string Command(string executable){string path=Path.GetFullPath(executable);if(path.Contains("\"")||!File.Exists(path))throw new IOException("Файл приложения не найден");return "\""+path+"\"";}
  static dynamic Connect(){var type=Type.GetTypeFromProgID("Schedule.Service");if(type==null)throw new IOException("Планировщик заданий Windows недоступен");dynamic service=Activator.CreateInstance(type);service.Connect();return service;}
  static dynamic Existing(dynamic folder){try{return folder.GetTask(TaskName);}catch(FileNotFoundException){return null;}catch(System.Runtime.InteropServices.COMException ex){if(ex.ErrorCode==unchecked((int)0x80070002))return null;throw;}}
  static bool Owned(dynamic task){if(task==null||Convert.ToString(task.Definition.RegistrationInfo.Description)!=Owner)return false;string user=Convert.ToString(task.Definition.Principal.UserId);var identity=WindowsIdentity.GetCurrent();if(user.Equals(identity.Name,StringComparison.OrdinalIgnoreCase)||user.Equals(identity.User.Value,StringComparison.OrdinalIgnoreCase))return true;try{return new NTAccount(user).Translate(typeof(SecurityIdentifier)).Equals(identity.User);}catch(IdentityNotMappedException){return false;}}
  public static bool Enabled {get{try{dynamic service=Connect();dynamic task=Existing(service.GetFolder("\\"));if(Owned(task)&&Convert.ToBoolean(task.Enabled))return true;}catch{}try{using(var key=Registry.CurrentUser.OpenSubKey(Key))return key!=null&&!String.IsNullOrWhiteSpace(Convert.ToString(key.GetValue("MCRF","")));}catch(System.Security.SecurityException){return false;}catch(UnauthorizedAccessException){return false;}}}
  public static void Set(bool enabled,string executable){
   string path=enabled?Command(executable).Trim('"'):null;
   if(!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator))throw new UnauthorizedAccessException("Для автозапуска с правами администратора нужны права Windows");
   dynamic service=Connect();dynamic folder=service.GetFolder("\\");dynamic previous=Existing(folder);if(previous!=null&&!Owned(previous))throw new IOException("Имя задачи автозапуска занято другой программой; чужая задача не изменена");
   if(enabled)folder.RegisterTaskDefinition(TaskName,BuildDefinition(service,path),6,WindowsIdentity.GetCurrent().Name,null,3,null);
   else if(previous!=null)folder.DeleteTask(TaskName,0);
   using(var key=Registry.CurrentUser.OpenSubKey(Key,true))if(key!=null)key.DeleteValue("MCRF",false);
  }
  static dynamic BuildDefinition(dynamic service,string path){dynamic definition=service.NewTask(0);definition.RegistrationInfo.Description=Owner;definition.Principal.UserId=WindowsIdentity.GetCurrent().Name;definition.Principal.LogonType=3;definition.Principal.RunLevel=1;
    definition.Settings.Enabled=true;definition.Settings.DisallowStartIfOnBatteries=false;definition.Settings.StopIfGoingOnBatteries=false;definition.Settings.ExecutionTimeLimit="PT0S";definition.Settings.MultipleInstances=2;
    dynamic trigger=definition.Triggers.Create(9);trigger.UserId=WindowsIdentity.GetCurrent().Name;trigger.Enabled=true;
    dynamic action=definition.Actions.Create(0);action.Path=path;action.WorkingDirectory=Path.GetDirectoryName(path);
    return definition;
  }
 }
}
