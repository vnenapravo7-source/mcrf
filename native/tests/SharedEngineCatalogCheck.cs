using System;
using System.IO;
using System.Linq;
using System.Web.Script.Serialization;
using SplifyWin;
class SharedEngineCatalogCheck {
 static int checks;
 static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 static int Main(string[] args){try{
  string root=Path.GetFullPath(args[0]);if(Directory.Exists(root))throw new Exception("Fresh isolated fixture required");Directory.CreateDirectory(root);
  var json=new JavaScriptSerializer();var clean=new OptionalEngines(root);
  Check(clean.Packages.Count()==4,"four embedded engines");
  foreach(var p in clean.Packages)Check(p.Url.Contains("/"+OptionalEngines.SharedRelease+"/"),"fixed catalogue "+p.Id);
  var old=json.Deserialize<EngineCatalog>(json.Serialize(new EngineCatalog{Version=1,Engines=clean.Packages.ToList()}));
  foreach(var p in old.Engines.Where(p=>p.Id!="qwdtt"))p.Url=p.Url.Replace(OptionalEngines.SharedRelease,"engines-2026-10-02-1");
  string before=json.Serialize(old);string cached=Path.Combine(root,"optional-engines-v2","active-catalog.json");Directory.CreateDirectory(Path.GetDirectoryName(cached));File.WriteAllText(cached,before);
  var migrated=new OptionalEngines(root);
  foreach(var p in migrated.Packages){var original=old.Engines.Single(x=>x.Id==p.Id);
   Check(p.Url.Contains("/"+OptionalEngines.SharedRelease+"/"),"cached URL migrated "+p.Id);
   Check(p.Version==original.Version&&p.Sha256==original.Sha256&&p.Size==original.Size&&json.Serialize(p.Files)==json.Serialize(original.Files),"installed identity preserved "+p.Id);
  }
  Check(File.ReadAllText(cached)==before,"saved catalogue untouched");
  var custom=json.Deserialize<EngineCatalog>(before);custom.Engines[0].Url=custom.Engines[0].Url.Replace("engines-2026-10-02-1","custom-valid-release");
  string customUrl=custom.Engines[0].Url;OptionalEngines.RelocateLegacyCatalog(custom);Check(custom.Engines[0].Url==customUrl,"unrelated valid URL not rewritten");
  Check(!Directory.Exists(Path.Combine(root,"core")),"no unpack or network started");
  if(args.Length>1)foreach(var p in migrated.Packages){
   string stage=Path.Combine(migrated.StorageRoot,".download-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(stage);
   migrated.InstallArchive(p,Path.Combine(Path.GetFullPath(args[1]),"mcrf-"+p.Id+"-windows-amd64.zip"),stage,System.Threading.CancellationToken.None);
   Check(migrated.Installed(p.Id),"real shared archive verifies and installs "+p.Id);
  }
  Console.WriteLine("SHARED_CATALOG_OK "+checks);return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
