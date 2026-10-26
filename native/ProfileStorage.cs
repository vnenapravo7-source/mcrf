using System;
using System.IO;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;
namespace SplifyWin {
 public static class ProfileReset {
  public sealed class ResetFailure : IOException {
   public readonly bool Applied,RecoveryRequired;public readonly string RecoveryFolder;
   internal ResetFailure(string message,Exception cause,bool applied,bool recovery,string folder):base(message,cause){Applied=applied;RecoveryRequired=recovery;RecoveryFolder=folder;}
  }
  public sealed class RestoreFailure : IOException {
   public readonly bool Applied,RecoveryRequired;public readonly string RecoveryFolder;
   internal RestoreFailure(string message,Exception cause,bool applied,bool recovery,string folder):base(message,cause){Applied=applied;RecoveryRequired=recovery;RecoveryFolder=folder;}
  }
  const string ManifestName="backup-manifest.json";
  static Exception MarkRecovery(string root,string backup,Exception failure){try{ProfileRecovery.Mark(root,backup);return failure;}catch(Exception marker){return new AggregateException("Не удалось сохранить блокировку повреждённого профиля. Закройте приложение и восстановите резервную копию вручную.",failure,marker);}}
  sealed class BackupManifest { public int Version;public string Kind;public List<BackupEntry> Files; }
  sealed class BackupEntry { public string Path;public long Length;public string Sha256; }
  static readonly HashSet<string> ProfileFiles=new HashSet<string>(new[]{"client.json","client.json.protected-backup","hosts-before.bin","custom-strategies.json","vk-turn.dat","wdtt-masque.dat","discord-voice-result.json","zapret-results.json","zapret-results.json.bak"},StringComparer.OrdinalIgnoreCase);
  static readonly HashSet<string> ResetOnlyFiles=new HashSet<string>(new[]{"sing-box.json","geo-rules-cache.db"},StringComparer.OrdinalIgnoreCase);
  static readonly HashSet<string> ProfileDirectories=new HashSet<string>(new[]{"setup-results","byetube-results","zapret-reports","hosts-backups"},StringComparer.OrdinalIgnoreCase);
  static readonly HashSet<string> ForbiddenDirectories=new HashSet<string>(new[]{"updates","installed","core","temp","tmp","logs","video-browser","reset-backups"},StringComparer.OrdinalIgnoreCase);
  static bool AllowedPath(string relative,bool reset){
   relative=relative.Replace(Path.AltDirectorySeparatorChar,Path.DirectorySeparatorChar);string[] parts=relative.Split(new[]{Path.DirectorySeparatorChar},StringSplitOptions.RemoveEmptyEntries);if(parts.Length==0||parts.Any(p=>p=="."||p==".."))return false;if(parts.Any(ForbiddenDirectories.Contains))return false;
   if(parts.Length==1){string name=parts[0];return ProfileFiles.Contains(name)||reset&&ResetOnlyFiles.Contains(name)||name.StartsWith("warp-account",StringComparison.OrdinalIgnoreCase)&&name.EndsWith(".json",StringComparison.OrdinalIgnoreCase)||name.StartsWith("openflux-",StringComparison.OrdinalIgnoreCase)&&(name.EndsWith(".conf",StringComparison.OrdinalIgnoreCase)||name.EndsWith(".txt",StringComparison.OrdinalIgnoreCase));}
   if(!ProfileDirectories.Contains(parts[0]))return false;string extension=Path.GetExtension(parts[parts.Length-1]);return new[]{".json",".txt",".log",".bak",".bin",".html",".csv"}.Contains(extension,StringComparer.OrdinalIgnoreCase);
  }
  static bool AllowedProfilePath(string relative){return AllowedPath(relative,false);}
  static bool AllowedResetPath(string relative){return AllowedPath(relative,true);}
  internal static bool AllowedMigrationPath(string relative){return AllowedResetPath(relative);}
  static string Relative(string root,string path){string prefix=Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar,full=Path.GetFullPath(path);if(!full.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new IOException("Файл бэкапа находится вне выбранной папки.");return full.Substring(prefix.Length);}
  static string HashFile(string path){using(var stream=File.OpenRead(path))using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-","").ToLowerInvariant();}
  static string[] PayloadFiles(string root){Validate(root);return Directory.GetFiles(root,"*",SearchOption.AllDirectories).Where(p=>!String.Equals(Path.GetFileName(p),ManifestName,StringComparison.OrdinalIgnoreCase)).OrderBy(p=>p,StringComparer.OrdinalIgnoreCase).ToArray();}
  static void WriteManifest(string root,string kind){var entries=PayloadFiles(root).Select(path=>new BackupEntry{Path=Relative(root,path).Replace(Path.DirectorySeparatorChar,'/'),Length=new FileInfo(path).Length,Sha256=HashFile(path)}).ToList();var manifest=new BackupManifest{Version=1,Kind=kind,Files=entries};File.WriteAllText(Path.Combine(root,ManifestName),new JavaScriptSerializer().Serialize(manifest),new UTF8Encoding(false));}
  static string[] ValidateRestorePayload(string root,string requiredKind=null){
   root=Path.GetFullPath(root);Validate(root);var actual=PayloadFiles(root);string manifestPath=Path.Combine(root,ManifestName),kind=null;BackupManifest manifest=null;
   if(File.Exists(manifestPath)){try{manifest=new JavaScriptSerializer().Deserialize<BackupManifest>(File.ReadAllText(manifestPath,Encoding.UTF8));}catch(Exception ex){throw new InvalidDataException("Манифест бэкапа повреждён.",ex);}if(manifest==null||manifest.Version!=1||manifest.Files==null||(manifest.Kind!="profile"&&manifest.Kind!="reset")||requiredKind!=null&&manifest.Kind!=requiredKind)throw new InvalidDataException("Неподдерживаемый формат бэкапа.");kind=manifest.Kind;}
   else{if(requiredKind!=null)throw new InvalidDataException("Внутренний снимок не содержит манифест.");kind=actual.Any(file=>ResetOnlyFiles.Contains(Path.GetFileName(file)))?"reset":"profile";}
   Func<string,bool> allowed=relative=>kind=="reset"?AllowedResetPath(relative):AllowedProfilePath(relative);long total=0;foreach(string file in actual){string relative=Relative(root,file);if(!allowed(relative))throw new InvalidDataException("Бэкап содержит неподдерживаемый файл: "+relative);long length=new FileInfo(file).Length;if(length>64L*1024*1024||(total+=length)>256L*1024*1024)throw new InvalidDataException("Бэкап превышает допустимый размер.");}
   if(manifest==null)return actual;
   var expected=new Dictionary<string,BackupEntry>(StringComparer.OrdinalIgnoreCase);foreach(var entry in manifest.Files){if(entry==null||String.IsNullOrWhiteSpace(entry.Path))throw new InvalidDataException("Манифест бэкапа содержит пустой путь.");string normalized=entry.Path.Replace('/',Path.DirectorySeparatorChar);if(!allowed(normalized)||expected.ContainsKey(normalized))throw new InvalidDataException("Манифест бэкапа содержит недопустимый путь: "+entry.Path);expected.Add(normalized,entry);}
   var actualRelative=actual.Select(path=>Relative(root,path)).ToArray();if(actualRelative.Length!=expected.Count||actualRelative.Any(path=>!expected.ContainsKey(path)))throw new InvalidDataException("Содержимое бэкапа не соответствует манифесту.");foreach(string path in actual){var entry=expected[Relative(root,path)];if(entry.Length!=new FileInfo(path).Length||!String.Equals(entry.Sha256,HashFile(path),StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("Файл бэкапа изменён после создания: "+entry.Path);}return actual;
  }
  static void CopyFiles(string source,string target,IEnumerable<string> files){foreach(string file in files){string relative=Relative(source,file);string destination=Path.Combine(target,relative);Directory.CreateDirectory(Path.GetDirectoryName(destination));File.Copy(file,destination,true);}}
  public static string Backup(StateStore store,bool includeHosts=false){return Backup(store,includeHosts,null);}
  internal static string Backup(StateStore store,bool includeHosts,Action<string> finishStage){
   ProfileRecovery.DemandHealthy(store.Root);
   string root=Path.GetFullPath(store.Root),parent=Path.Combine(Path.GetDirectoryName(root),Path.GetFileName(root)+"-backups");
   if((File.GetAttributes(root)&FileAttributes.ReparsePoint)!=0)throw new IOException("Папка данных содержит ссылку.");
   string id=Guid.NewGuid().ToString("N"),backup=Path.Combine(parent,DateTime.Now.ToString("yyyyMMdd-HHmmss")+"-manual-"+id),stage=Path.Combine(parent,".partial-"+id);
   var names=new HashSet<string>(ProfileFiles,StringComparer.OrdinalIgnoreCase);names.Remove("client.json.protected-backup");names.Remove("hosts-before.bin");
   var files=Directory.GetFiles(root).Where(file=>names.Contains(Path.GetFileName(file))||Path.GetFileName(file).StartsWith("warp-account",StringComparison.OrdinalIgnoreCase)&&file.EndsWith(".json",StringComparison.OrdinalIgnoreCase)||Path.GetFileName(file).StartsWith("openflux-",StringComparison.OrdinalIgnoreCase)&&(file.EndsWith(".conf",StringComparison.OrdinalIgnoreCase)||file.EndsWith(".txt",StringComparison.OrdinalIgnoreCase))).ToList();
   foreach(string name in ProfileDirectories){string source=Path.Combine(root,name);if(!Directory.Exists(source))continue;Validate(source);files.AddRange(Directory.GetFiles(source,"*",SearchOption.AllDirectories).Where(file=>AllowedProfilePath(Relative(root,file))));}
   foreach(string file in files)Validate(file);
   var relative=files.Select(file=>Relative(root,file)).Concat(new[]{"client.json."+new string('0',32)+".tmp","client.json.protected-backup",ManifestName,"hosts-before.bin"});
   foreach(string name in relative)if(Path.Combine(stage,name).Length>=260||Path.Combine(backup,name).Length>=260)throw new IOException("Путь резервной копии слишком длинный для этой версии Windows/.NET. Выберите более короткую папку данных.");
   if(Directory.Exists(parent))Validate(parent);Directory.CreateDirectory(parent);
   try{
    Directory.CreateDirectory(stage);CopyFiles(root,stage,files);
    var stagedStore=new StateStore(stage);if(File.Exists(stagedStore.FilePath))stagedStore.Load();else stagedStore.Save(ClientState.Initial());
    if(includeHosts)File.WriteAllBytes(Path.Combine(stage,"hosts-before.bin"),File.ReadAllBytes(HostsEditor.PathName));
    if(finishStage!=null)finishStage(stage);
    WriteManifest(stage,"profile");ValidateRestorePayload(stage,"profile");Directory.Move(stage,backup);return backup;
   }catch(Exception failure){
    try{if(Directory.Exists(stage)){string prefix=Path.GetFullPath(parent).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;if(!Path.GetFullPath(stage).StartsWith(prefix,StringComparison.OrdinalIgnoreCase)||Path.GetFileName(stage)!=".partial-"+id)throw new IOException("Недопустимая временная папка бэкапа.");Validate(stage);Directory.Delete(stage,true);}}
    catch(Exception cleanup){throw new IOException("Резервная копия не создана; не удалось удалить временные данные: "+stage,new AggregateException(failure,cleanup));}
    throw;
   }
  }
  public sealed class PreparedRestore : IDisposable {
   internal readonly string Root,Staging;internal readonly ClientState State;
   internal bool Attempted;internal RestoreFailure Failure;public bool Applied{get;internal set;}public string RecoveryFolder{get;internal set;}
   internal PreparedRestore(string root,string staging,ClientState state){Root=root;Staging=staging;State=state;}
   public void Dispose(){try{if(!Directory.Exists(Staging))return;string prefix=Root.TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;if(!Path.GetFullPath(Staging).StartsWith(prefix,StringComparison.OrdinalIgnoreCase)||!Path.GetFileName(Staging).StartsWith(".restore-",StringComparison.Ordinal))throw new IOException("Недопустимая папка подготовки восстановления.");Validate(Staging);Directory.Delete(Staging,true);}catch(Exception ex){string message=Applied?"Настройки восстановлены, но не удалось удалить временную копию: "+Staging:"Не удалось удалить временную копию восстановления: "+Staging;if(Failure!=null)message=Failure.Message+" "+message;throw new RestoreFailure(message,Failure==null?ex:new AggregateException(Failure,ex),Applied,Failure!=null&&Failure.RecoveryRequired,RecoveryFolder);}}
  }
  public static PreparedRestore PrepareRestore(StateStore store,string folder){
   string root=Path.GetFullPath(store.Root).TrimEnd(Path.DirectorySeparatorChar);
   if(String.Equals(root,Path.GetPathRoot(root).TrimEnd(Path.DirectorySeparatorChar),StringComparison.OrdinalIgnoreCase))throw new IOException("Корень диска не может быть профилем.");
   folder=Path.GetFullPath(folder);
   string leaf=Path.GetFileName(folder.TrimEnd(Path.DirectorySeparatorChar));if(leaf.StartsWith(".partial-",StringComparison.OrdinalIgnoreCase)||leaf.StartsWith(".restore-",StringComparison.OrdinalIgnoreCase))throw new IOException("Временная незавершённая копия не подходит для восстановления.");
   if(String.Equals(folder.TrimEnd(Path.DirectorySeparatorChar),root,StringComparison.OrdinalIgnoreCase)||!File.Exists(Path.Combine(folder,"client.json")))throw new IOException("Выберите корректный бэкап, не текущий профиль.");
   var sourceFiles=ValidateRestorePayload(folder);Validate(root);
   string staging=Path.Combine(root,".restore-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(staging);
   var prepared=new PreparedRestore(root,staging,null);
   try{CopyFiles(folder,staging,sourceFiles);string manifest=Path.Combine(folder,ManifestName);if(File.Exists(manifest)){File.Copy(manifest,Path.Combine(staging,ManifestName));ValidateRestorePayload(staging);}var state=new StateStore(staging).Load();WriteManifest(staging,sourceFiles.Any(file=>ResetOnlyFiles.Contains(Path.GetFileName(file)))?"reset":"profile");return new PreparedRestore(root,staging,state);}
   catch{prepared.Dispose();throw;}
  }
  public static void Restore(StateStore store,string folder,bool restoreHosts=true){
   using(var prepared=PrepareRestore(store,folder))RestorePrepared(store,prepared,restoreHosts);
  }
  public static void RestorePrepared(StateStore store,PreparedRestore prepared,bool restoreHosts=true){
   RestorePrepared(store,prepared,restoreHosts,null,null);
  }
  internal static void RestorePrepared(StateStore store,PreparedRestore prepared,bool restoreHosts,Action<string,string,string[]> copyPrepared,Action<string,string,string[]> copyRecovery){
   if(prepared==null||!String.Equals(Path.GetFullPath(store.Root).TrimEnd(Path.DirectorySeparatorChar),prepared.Root,StringComparison.OrdinalIgnoreCase)||!Directory.Exists(prepared.Staging))throw new IOException("Подготовленная копия не относится к этому профилю.");
   if(prepared.Attempted)throw new IOException("Эта подготовленная копия уже применялась. Подготовьте восстановление заново.");
   string staging=prepared.Staging;var restored=prepared.State;
   var files=ValidateRestorePayload(staging);
   byte[] hostsBefore=restoreHosts?File.ReadAllBytes(HostsEditor.PathName):null;
   string hostsFile=Path.Combine(staging,"hosts-before.bin");
   string entries=restoreHosts?HostsEditor.Managed(File.Exists(hostsFile)?File.ReadAllBytes(hostsFile):hostsBefore):null;
   string current=Reset(store,restoreHosts,true,null,null,true);prepared.RecoveryFolder=current;prepared.Attempted=true;
   try{if(copyPrepared==null)CopyFiles(staging,store.Root,files);else copyPrepared(staging,store.Root,files);new StateStore(store.Root,true).Save(restored);if(restoreHosts)HostsRepair.Apply(store.Root,entries,HostsRepair.Compose(hostsBefore,null));prepared.Applied=true;}
   catch(Exception failure){
    try{Reset(store,false,false,null,null,true);var recoveryFiles=ValidateRestorePayload(current,"reset");if(copyRecovery==null)CopyFiles(current,store.Root,recoveryFiles);else copyRecovery(current,store.Root,recoveryFiles);new StateStore(store.Root,true).Load();if(restoreHosts)HostsRepair.Apply(store.Root,HostsEditor.Managed(hostsBefore));}
    catch(Exception recovery){prepared.Failure=new RestoreFailure("Восстановление прервано; автоматический откат не завершён. Не повторяйте сброс. Резервная копия предыдущего профиля: "+current,MarkRecovery(store.Root,current,new AggregateException(failure,recovery)),false,true,current);throw prepared.Failure;}
    prepared.Failure=new RestoreFailure("Восстановление не выполнено; предыдущий профиль возвращён. "+failure.Message,failure,false,false,current);throw prepared.Failure;
   }
   try{ProfileRecovery.Clear(store.Root);}catch(Exception ex){prepared.Failure=new RestoreFailure("Настройки восстановлены, но блокировка профиля не снята. Закройте приложение; данные сохранены. "+ex.Message,ex,true,true,current);throw prepared.Failure;}
  }
  public static ClientState Fresh(){return new ClientState{VpnEnabled=false,DnsProvider="Системный",DnsProtocol="Системный"};}
public static string Reset(StateStore store,bool cleanHosts=false,bool keepBackup=true){return Reset(store,cleanHosts,keepBackup,null,null);}
internal static string Reset(StateStore store,bool cleanHosts,bool keepBackup,Action<string> saveFresh,Action<string> manifest,bool allowRecovery=false){
   if(!allowRecovery)ProfileRecovery.DemandHealthy(store.Root);
   string checkedRoot=Path.GetFullPath(store.Root).TrimEnd(Path.DirectorySeparatorChar);if(String.Equals(checkedRoot,Path.GetPathRoot(checkedRoot).TrimEnd(Path.DirectorySeparatorChar),StringComparison.OrdinalIgnoreCase))throw new IOException("Корень диска не может быть профилем.");
   string root=Path.GetFullPath(store.Root),backup=keepBackup?Path.Combine(root,"reset-backups",DateTime.Now.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N")):Path.Combine(root,".reset-"+Guid.NewGuid().ToString("N"));
   var names=new HashSet<string>(new[]{"client.json","client.json.protected-backup","sing-box.json","discord-voice-result.json","zapret-results.json","zapret-results.json.bak","geo-rules-cache.db","custom-strategies.json","vk-turn.dat","wdtt-masque.dat"},StringComparer.OrdinalIgnoreCase);
   var targets=Directory.GetFiles(root).Where(p=>names.Contains(Path.GetFileName(p))||Path.GetFileName(p).StartsWith("warp-account",StringComparison.OrdinalIgnoreCase)&&p.EndsWith(".json",StringComparison.OrdinalIgnoreCase)||Path.GetFileName(p).StartsWith("openflux-",StringComparison.OrdinalIgnoreCase)&&(p.EndsWith(".conf",StringComparison.OrdinalIgnoreCase)||p.EndsWith(".txt",StringComparison.OrdinalIgnoreCase))).ToList();
foreach(string name in new[]{"setup-results","byetube-results","zapret-reports","hosts-backups"}){string p=Path.Combine(root,name);if(Directory.Exists(p))targets.Add(p);}
   Validate(root);foreach(string p in targets){Validate(p);foreach(string file in Directory.Exists(p)?Directory.GetFiles(p,"*",SearchOption.AllDirectories):new[]{p})if(!AllowedResetPath(Relative(root,file)))throw new InvalidDataException("Профиль содержит неподдерживаемый файл для резервной копии: "+Relative(root,file));}string parent=Path.GetDirectoryName(backup);if(Directory.Exists(parent))Validate(parent);Directory.CreateDirectory(backup);
byte[] hostsBefore=cleanHosts?File.ReadAllBytes(HostsEditor.PathName):null;if(cleanHosts&&keepBackup)File.WriteAllBytes(Path.Combine(backup,"hosts-before.bin"),hostsBefore);if(cleanHosts)HostsRepair.Apply(root,null,hostsBefore,false);
var moved=new List<string>();bool freshAttempted=false;
try{foreach(string p in targets){string dest=Path.Combine(backup,Path.GetFileName(p));if(Directory.Exists(p))Directory.Move(p,dest);else File.Move(p,dest);moved.Add(p);}if(keepBackup){if(manifest==null)WriteManifest(backup,"reset");else manifest(backup);}freshAttempted=true;if(saveFresh==null)new StateStore(root,allowRecovery).Save(Fresh());else saveFresh(root);}
catch(Exception failure){
 try{if(freshAttempted)foreach(string name in new[]{"client.json","client.json.protected-backup"}){string generated=Path.Combine(root,name);if(File.Exists(generated)){Validate(generated);File.Delete(generated);}}foreach(string p in moved.AsEnumerable().Reverse()){string archived=Path.Combine(backup,Path.GetFileName(p));if(Directory.Exists(archived))Directory.Move(archived,p);else if(File.Exists(archived))File.Move(archived,p);}if(cleanHosts)HostsRepair.Apply(root,HostsEditor.Managed(hostsBefore),HostsRepair.Compose(hostsBefore,null),false);}
 catch(Exception recovery){throw new ResetFailure("Сброс прерван; автоматический откат не завершён. Не повторяйте сброс. Предыдущие данные: "+backup,MarkRecovery(root,backup,new AggregateException(failure,recovery)),false,true,backup);}
 throw new ResetFailure("Сброс не выполнен; предыдущий профиль возвращён. "+failure.Message,failure,false,false,backup);
}
   if(keepBackup)return backup;
   try{Validate(backup);if(!Path.GetFullPath(backup).StartsWith(root+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new IOException("Недопустимая папка сброса");Directory.Delete(backup,true);}catch(Exception ex){throw new ResetFailure("Настройки сброшены, но не удалось удалить временную копию прежних данных: "+backup,ex,true,false,backup);}return root;
  }
  static void Validate(string path){if((File.GetAttributes(path)&FileAttributes.ReparsePoint)!=0)throw new IOException("Сброс остановлен: папка данных содержит ссылку.");if(Directory.Exists(path))foreach(string child in Directory.GetFileSystemEntries(path))Validate(child);}
 }
 public static class ProfileStorage {
  static readonly object sync=new object();
  public static string DefaultRoot(){return Migrate(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData));}
  public static string Migrate(string local){lock(sync){
   string source=Path.GetFullPath(Path.Combine(local,"splify-win")),target=Path.GetFullPath(Path.Combine(local,"mcrf"));string config=Path.Combine(target,"client.json");
   if(File.Exists(config)||!File.Exists(Path.Combine(source,"client.json")))return target;
   try{if((File.GetAttributes(source)&FileAttributes.ReparsePoint)!=0)throw new IOException("В папке данных обнаружена ссылка на другую папку");Directory.CreateDirectory(target);foreach(string file in Directory.GetFiles(source)){string name=Path.GetFileName(file);if(name!="client.json"&&ProfileReset.AllowedMigrationPath(name))CopyFile(file,Path.Combine(target,name));}foreach(string name in new[]{"setup-results","byetube-results","zapret-reports","hosts-backups"})CopyUserDirectory(source,target,name);CopyFile(Path.Combine(source,"client.json"),config);return target;}
   catch(Exception ex){throw new IOException("Не удалось перенести настройки из splify-win в mcrf. Старая папка сохранена; запуск остановлен, чтобы не создать пустые настройки.",ex);}
  }}
  static void CopyUserDirectory(string sourceRoot,string targetRoot,string name){
   string source=Path.Combine(sourceRoot,name);if(!Directory.Exists(source))return;if((File.GetAttributes(source)&FileAttributes.ReparsePoint)!=0)throw new IOException("В папке данных обнаружена ссылка на другую папку");foreach(string directory in Directory.GetDirectories(source,"*",SearchOption.AllDirectories))if((File.GetAttributes(directory)&FileAttributes.ReparsePoint)!=0)throw new IOException("В папке данных обнаружена ссылка на другую папку");foreach(string file in Directory.GetFiles(source,"*",SearchOption.AllDirectories)){string relative=name+Path.DirectorySeparatorChar+file.Substring(source.Length).TrimStart(Path.DirectorySeparatorChar);if(!ProfileReset.AllowedMigrationPath(relative))continue;CopyFile(file,Path.Combine(targetRoot,relative));}
  }
  static void CopyFile(string source,string target){
   if(File.Exists(target))return;if((File.GetAttributes(source)&FileAttributes.ReparsePoint)!=0)throw new IOException("В папке данных обнаружена ссылка на файл");string temporary=target+".migration-"+Guid.NewGuid().ToString("N");
   Directory.CreateDirectory(Path.GetDirectoryName(target));try{File.Copy(source,temporary,false);File.Move(temporary,target);}finally{if(File.Exists(temporary))File.Delete(temporary);}
  }
 }
}
