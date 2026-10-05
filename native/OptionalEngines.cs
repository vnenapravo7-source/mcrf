using System;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
namespace SplifyWin {
  public sealed class EngineFile {public string Name{get;set;}public long Size{get;set;}public string Sha256{get;set;}}
  public sealed class EnginePackage {public string Id{get;set;}public string Name{get;set;}public string Version{get;set;}public string Url{get;set;}public long Size{get;set;}public string Sha256{get;set;}public List<EngineFile> Files{get;set;}}
  public sealed class EngineCatalog {public int Version{get;set;}public List<EnginePackage> Engines{get;set;}}
  public sealed class EngineDownloadState {public bool Busy;public int Percent;public string Text;public bool Error;}
  public sealed class OptionalEngines {
    readonly string root,legacy;EngineCatalog catalog;readonly object catalogSync=new object();
    readonly ConcurrentDictionary<string,EngineDownloadState> statuses=new ConcurrentDictionary<string,EngineDownloadState>();
    readonly ConcurrentDictionary<string,CancellationTokenSource> jobs=new ConcurrentDictionary<string,CancellationTokenSource>();
    readonly ConcurrentDictionary<string,string> verified=new ConcurrentDictionary<string,string>();
    public IEnumerable<EnginePackage> Packages{get{return catalog.Engines;}}
    public string StorageRoot{get{return root;}}
    public OptionalEngines(string dataRoot):this(dataRoot,ReadActiveCatalog(dataRoot)){}
    static EngineCatalog ReadActiveCatalog(string dataRoot){try{var data=new JavaScriptSerializer().Deserialize<EngineCatalog>(File.ReadAllText(Path.Combine(dataRoot,"optional-engines-v2","active-catalog.json")));ValidateCatalog(data);return data;}catch(IOException){}catch(ArgumentException){}catch(InvalidDataException){}return ReadCatalog();}
    public static void ValidateCatalog(EngineCatalog data){if(data==null||data.Version!=1||data.Engines==null||data.Engines.Count!=3||!data.Engines.Select(p=>p.Id).OrderBy(x=>x).SequenceEqual(new[]{"csqtt","openflux","wdtt"}))throw new InvalidDataException("Некорректный каталог движков");foreach(var p in data.Engines)Validate(p);}
    public OptionalEngines(string dataRoot,EngineCatalog data){root=Path.Combine(dataRoot,"optional-engines-v2");legacy=Path.Combine(dataRoot,"core");catalog=data;if(data==null||data.Version!=1||data.Engines==null)throw new InvalidDataException("Некорректный каталог движков");foreach(var p in Packages)Validate(p);}
    static EngineCatalog ReadCatalog(){using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("SplifyWin.Core.engine-catalog.json"))using(var reader=new StreamReader(stream))return new JavaScriptSerializer().Deserialize<EngineCatalog>(reader.ReadToEnd());}
    static bool SafeName(string name){return !String.IsNullOrEmpty(name)&&Char.IsLetterOrDigit(name[0])&&name==Path.GetFileName(name)&&name.All(c=>Char.IsLetterOrDigit(c)||c=='-'||c=='_'||c=='.')&&!name.Contains("..");}
    static void Validate(EnginePackage p){Uri uri;if(!SafeName(p.Id)||!SafeName(p.Version)||p.Sha256==null||p.Sha256.Length!=64||p.Size<1||p.Size>80000000||p.Files==null||p.Files.Count<1||p.Files.Count>20||p.Files.Any(f=>!SafeName(f.Name)||f.Size<1||f.Size>100000000||f.Sha256==null||f.Sha256.Length!=64)||p.Files.Select(f=>f.Name).Distinct(StringComparer.OrdinalIgnoreCase).Count()!=p.Files.Count||!Uri.TryCreate(p.Url,UriKind.Absolute,out uri)||uri.Scheme!="https"||uri.Host!="github.com"||!uri.AbsolutePath.StartsWith("/vnenapravo7-source/mcrf/releases/download/",StringComparison.Ordinal))throw new InvalidDataException("Небезопасный каталог движков");}
    public EnginePackage Package(string id){return Packages.Single(p=>p.Id==id);}
    string DirectoryFor(EnginePackage p){return Path.Combine(root,p.Id,p.Version+"-"+p.Sha256.Substring(0,12));}
    static string Hash(string path){using(var sha=SHA256.Create())using(var stream=File.OpenRead(path))return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-","").ToLowerInvariant();}
    public bool Verify(string directory,EnginePackage p){foreach(var file in p.Files){string path=Path.Combine(directory,file.Name);if(!File.Exists(path)||new FileInfo(path).Length!=file.Size||Hash(path)!=file.Sha256)return false;}return true;}
    bool CachedVerify(string directory,EnginePackage p){if(!Directory.Exists(directory))return false;string signature=String.Join("|",p.Files.Select(f=>{var info=new FileInfo(Path.Combine(directory,f.Name));return info.Exists?f.Name+":"+info.Length+":"+info.LastWriteTimeUtc.Ticks:"missing";}));string previous;if(verified.TryGetValue(directory,out previous)&&previous==signature)return true;if(!Verify(directory,p)){string ignored;verified.TryRemove(directory,out ignored);return false;}verified[directory]=signature;return true;}
    public string Executable(string id,string name){var p=Package(id);if(!p.Files.Any(f=>f.Name==name))return null;string installed=DirectoryFor(p);if(CachedVerify(installed,p))return Path.Combine(installed,name);
      // v2 starts clean: older bundled/cache engines are preserved, but never used.
      return null;
    }
    public bool Installed(string id){return Package(id).Files.Where(f=>f.Name.EndsWith(".exe")||f.Name.EndsWith(".dll")).All(f=>Executable(id,f.Name)!=null);}
    public EngineDownloadState State(string id){EngineDownloadState value;return statuses.TryGetValue(id,out value)?value:new EngineDownloadState{Text=Installed(id)?"Установлен · готов к подключению":"Не установлен"};}
    void Report(string id,string text,int percent,bool busy,bool error=false){statuses[id]=new EngineDownloadState{Text=text,Percent=percent,Busy=busy,Error=error};}
    public void Cancel(string id){CancellationTokenSource job;if(jobs.TryGetValue(id,out job))try{job.Cancel();}catch(ObjectDisposedException){}}
    public void CancelAll(){foreach(var id in jobs.Keys)Cancel(id);}
    public Task Install(string id){return InstallPackage(Package(id),false);}
    public Task InstallUpdate(EnginePackage package){Validate(package);Package(package.Id);return InstallPackage(package,true);}
    async Task InstallPackage(EnginePackage p,bool update){string id=p.Id;var cancel=new CancellationTokenSource();if(!jobs.TryAdd(id,cancel))throw new InvalidOperationException("Движок уже скачивается");cancel.CancelAfter(180000);try{
        Report(id,"Соединяемся с GitHub…",0,true);
        await Task.Run(async()=>{Directory.CreateDirectory(root);string staging=Path.Combine(root,".download-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(staging);try{
            string archive=Path.Combine(staging,"package.zip");await Download(p,archive,cancel.Token);cancel.Token.ThrowIfCancellationRequested();Report(id,"Проверяем SHA-256 и распаковываем…",95,true);InstallArchiveCore(p,archive,staging,cancel.Token);
          }finally{CleanStaging(staging);}},cancel.Token);
        if(update)lock(catalogSync){var next=new EngineCatalog{Version=1,Engines=catalog.Engines.Select(item=>item.Id==id?p:item).ToList()};AppUpdates.AtomicText(Path.Combine(root,"active-catalog.json"),new JavaScriptSerializer().Serialize(next));catalog=next;}
        Report(id,"Установлен · готов к подключению",100,false);
      }catch(OperationCanceledException){Report(id,"Загрузка отменена · можно повторить",0,false);throw;}
      catch(Exception ex){Report(id,"Не установлен: "+ex.Message,0,false,true);throw;}
      finally{CancellationTokenSource ignored;jobs.TryRemove(id,out ignored);cancel.Dispose();}
    }
    async Task Download(EnginePackage p,string destination,CancellationToken token){
      string curl=Path.Combine(legacy,"curl.exe"),cert=Path.Combine(legacy,"curl-ca-bundle.crt");if(!File.Exists(curl)||!File.Exists(cert))throw new FileNotFoundException("Встроенный HTTPS-загрузчик не найден");
      string args="--silent --show-error --fail --location --connect-timeout 12 --max-time 180 --max-filesize "+p.Size+" --noproxy \"*\" --proto =https --proto-redir =https --cacert "+ZapretRuntime.Quote(cert)+" --output "+ZapretRuntime.Quote(destination)+" --write-out \"%{http_code}\" "+ZapretRuntime.Quote(p.Url);
      using(var process=OwnedJob.Start(new System.Diagnostics.ProcessStartInfo(curl,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true})){
        var output=process.StandardOutput.ReadToEndAsync();var errors=process.StandardError.ReadToEndAsync();
        try{while(!process.HasExited){token.ThrowIfCancellationRequested();long received=File.Exists(destination)?new FileInfo(destination).Length:0;if(received>p.Size)throw new InvalidDataException("Превышен ожидаемый размер пакета");Report(p.Id,String.Format("Загрузка: {0:0.0} / {1:0.0} МБ",received/1000000d,p.Size/1000000d),(int)(received*94/p.Size),true);await Task.Delay(100,token);}
          token.ThrowIfCancellationRequested();string code=await output;await errors;if(process.ExitCode!=0){if(code=="404")throw new IOException("Пакет этой версии ещё не опубликован в релизе MCRF");throw new IOException("Не удалось скачать пакет. Проверьте доступ к GitHub. Код "+process.ExitCode);}
          if(!File.Exists(destination)||new FileInfo(destination).Length!=p.Size)throw new InvalidDataException("Пакет скачан не полностью");
        }finally{if(!process.HasExited){try{process.Kill();process.WaitForExit(2000);}catch{}}}
      }
    }
    public void InstallArchive(EnginePackage p,string archive,string staging,CancellationToken token){
      if(p!=Package(p.Id))throw new InvalidDataException("Пакет отсутствует в закреплённом каталоге");InstallArchiveCore(p,archive,staging,token);
    }
    void InstallArchiveCore(EnginePackage p,string archive,string staging,CancellationToken token){
      Validate(p);string prefix=Path.GetFullPath(root)+Path.DirectorySeparatorChar+".download-";if(!Path.GetFullPath(staging).StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("Небезопасный каталог распаковки");
      if(new FileInfo(archive).Length!=p.Size||Hash(archive)!=p.Sha256)throw new InvalidDataException("SHA-256 пакета не совпал. Движок не установлен");string unpacked=Path.Combine(staging,"verified");Directory.CreateDirectory(unpacked);
      using(var zip=ZipFile.OpenRead(archive)){if(zip.Entries.Count!=p.Files.Count)throw new InvalidDataException("Неожиданный состав пакета");foreach(var file in p.Files){token.ThrowIfCancellationRequested();var entries=zip.Entries.Where(e=>e.FullName==file.Name).ToArray();if(entries.Length!=1||entries[0].Length!=file.Size||((entries[0].ExternalAttributes>>16)&0xF000)==0xA000)throw new InvalidDataException("Некорректный файл пакета");entries[0].ExtractToFile(Path.Combine(unpacked,file.Name));}}
      if(!Verify(unpacked,p))throw new InvalidDataException("SHA-256 распакованного файла не совпал");token.ThrowIfCancellationRequested();string target=DirectoryFor(p);Directory.CreateDirectory(Path.GetDirectoryName(target));
      if(Directory.Exists(target)&&Verify(target,p))return;
      string backup=null;if(Directory.Exists(target)){backup=target+".previous-"+Guid.NewGuid().ToString("N");Directory.Move(target,backup);}
      try{Directory.Move(unpacked,target);}catch{if(backup!=null&&!Directory.Exists(target))Directory.Move(backup,target);throw;}
    }
    void CleanStaging(string path){string prefix=Path.GetFullPath(root)+Path.DirectorySeparatorChar+".download-";if(!Path.GetFullPath(path).StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new IOException("Небезопасный путь временных файлов");try{if(Directory.Exists(path))Directory.Delete(path,true);}catch(IOException){}catch(UnauthorizedAccessException){}}
  }
}
