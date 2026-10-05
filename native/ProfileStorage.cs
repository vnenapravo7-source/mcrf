using System;
using System.IO;
using System.Collections.Generic;
using System.Linq;
namespace SplifyWin {
 public static class ProfileReset {
  public static void Restore(StateStore store,string folder,bool restoreHosts=true){
   folder=Path.GetFullPath(folder);if(String.Equals(folder,store.Root,StringComparison.OrdinalIgnoreCase)||!File.Exists(Path.Combine(folder,"client.json")))throw new IOException("Выберите корректный бэкап, не текущий профиль.");Validate(folder);var restored=new StateStore(folder).Load();byte[] hostsBefore=restoreHosts?File.ReadAllBytes(HostsEditor.PathName):null;string hostsFile=Path.Combine(folder,"hosts-before.bin");string entries=restoreHosts?HostsEditor.Managed(File.Exists(hostsFile)?File.ReadAllBytes(hostsFile):hostsBefore):null;
   string current=Reset(store,restoreHosts,true);
   try{CopyPayload(folder,store.Root);new StateStore(store.Root).Save(restored);if(restoreHosts)HostsRepair.Apply(store.Root,entries,HostsRepair.Compose(hostsBefore,null));}
   catch{Reset(store,false,false);CopyPayload(current,store.Root);new StateStore(store.Root).Save(new StateStore(current).Load());if(restoreHosts)HostsRepair.Apply(store.Root,HostsEditor.Managed(hostsBefore));throw;}
  }
  static void CopyPayload(string source,string target){Directory.CreateDirectory(target);foreach(string file in Directory.GetFiles(source)){string name=Path.GetFileName(file);if(name=="client.json"||name=="client.json.protected-backup"||name=="hosts-before.bin")continue;File.Copy(file,Path.Combine(target,name),true);}foreach(string dir in Directory.GetDirectories(source))CopyPayload(dir,Path.Combine(target,Path.GetFileName(dir)));}
  public static ClientState Fresh(){return new ClientState{VpnEnabled=false,DnsProvider="Системный",DnsProtocol="Системный"};}
public static string Reset(StateStore store,bool cleanHosts=false,bool keepBackup=true){
   string root=Path.GetFullPath(store.Root),backup=keepBackup?Path.Combine(root,"reset-backups",DateTime.Now.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N")):Path.Combine(root,".reset-"+Guid.NewGuid().ToString("N"));
   var names=new HashSet<string>(new[]{"client.json","client.json.protected-backup","sing-box.json","discord-voice-result.json","zapret-results.json","zapret-results.json.bak","geo-rules-cache.db"},StringComparer.OrdinalIgnoreCase);
   var targets=Directory.GetFiles(root).Where(p=>names.Contains(Path.GetFileName(p))||Path.GetFileName(p).StartsWith("warp-account",StringComparison.OrdinalIgnoreCase)&&p.EndsWith(".json",StringComparison.OrdinalIgnoreCase)||Path.GetFileName(p).StartsWith("openflux-",StringComparison.OrdinalIgnoreCase)&&(p.EndsWith(".conf",StringComparison.OrdinalIgnoreCase)||p.EndsWith(".txt",StringComparison.OrdinalIgnoreCase))).ToList();
foreach(string name in new[]{"setup-results","byetube-results","zapret-reports","hosts-backups"}){string p=Path.Combine(root,name);if(Directory.Exists(p))targets.Add(p);}
   Validate(root);foreach(string p in targets)Validate(p);string parent=Path.GetDirectoryName(backup);if(Directory.Exists(parent))Validate(parent);Directory.CreateDirectory(backup);
byte[] hostsBefore=cleanHosts?File.ReadAllBytes(HostsEditor.PathName):null;if(cleanHosts&&keepBackup)File.WriteAllBytes(Path.Combine(backup,"hosts-before.bin"),hostsBefore);if(cleanHosts)HostsRepair.Apply(root,null,hostsBefore,false);
var moved=new List<string>();try{foreach(string p in targets){string dest=Path.Combine(backup,Path.GetFileName(p));if(Directory.Exists(p))Directory.Move(p,dest);else File.Move(p,dest);moved.Add(p);}new StateStore(root).Save(Fresh());}
catch{foreach(string p in moved.AsEnumerable().Reverse()){string archived=Path.Combine(backup,Path.GetFileName(p));if(Directory.Exists(archived))Directory.Move(archived,p);else if(File.Exists(archived))File.Move(archived,p);}if(cleanHosts)HostsRepair.Apply(root,HostsEditor.Managed(hostsBefore),HostsRepair.Compose(hostsBefore,null),false);throw;}
   if(keepBackup)return backup;Validate(backup);if(!Path.GetFullPath(backup).StartsWith(root+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new IOException("Недопустимая папка сброса");Directory.Delete(backup,true);return root;
  }
  static void Validate(string path){if((File.GetAttributes(path)&FileAttributes.ReparsePoint)!=0)throw new IOException("Сброс остановлен: папка данных содержит ссылку.");if(Directory.Exists(path))foreach(string child in Directory.GetFileSystemEntries(path))Validate(child);}
 }
 public static class ProfileStorage {
  static readonly object sync=new object();
  public static string DefaultRoot(){return Migrate(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData));}
  public static string Migrate(string local){lock(sync){
   string source=Path.GetFullPath(Path.Combine(local,"splify-win")),target=Path.GetFullPath(Path.Combine(local,"mcrf"));string config=Path.Combine(target,"client.json");
   if(File.Exists(config)||!File.Exists(Path.Combine(source,"client.json")))return target;
   try{Copy(source,target,true);CopyFile(Path.Combine(source,"client.json"),config);return target;}
   catch(Exception ex){throw new IOException("Не удалось перенести настройки из splify-win в mcrf. Старая папка сохранена; запуск остановлен, чтобы не создать пустые настройки.",ex);}
  }}
  static void Copy(string source,string target,bool root){
   if((File.GetAttributes(source)&FileAttributes.ReparsePoint)!=0)throw new IOException("В папке данных обнаружена ссылка на другую папку");Directory.CreateDirectory(target);
   foreach(string file in Directory.GetFiles(source)){if(root&&Path.GetFileName(file)=="client.json")continue;CopyFile(file,Path.Combine(target,Path.GetFileName(file)));}
   foreach(string directory in Directory.GetDirectories(source)){string name=Path.GetFileName(directory);if(root&&(name=="core"||name=="video-browser"))continue;Copy(directory,Path.Combine(target,name),false);}
  }
  static void CopyFile(string source,string target){
   if(File.Exists(target))return;if((File.GetAttributes(source)&FileAttributes.ReparsePoint)!=0)throw new IOException("В папке данных обнаружена ссылка на файл");string temporary=target+".migration-"+Guid.NewGuid().ToString("N");
   try{File.Copy(source,temporary,false);File.Move(temporary,target);}finally{if(File.Exists(temporary))File.Delete(temporary);}
  }
 }
}
