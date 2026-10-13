using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Collections.Generic;
using System.Security.Cryptography;
namespace SplifyWin {
 public sealed class CoreEngineCatalog {public int Version{get;set;}public string Platform{get;set;}public List<EnginePackage> Packages{get;set;}public Dictionary<string,List<string>> Features{get;set;}}
 public static class CoreEngineUpdates {
  public static string MainFile(string id){return id=="singbox"?"sing-box.exe":id=="telegram"?"tgws.exe":id=="warp"?"warpscout.exe":id=="byetube"?"ciadpi.exe":null;}
  public static void Validate(CoreEngineCatalog catalog){if(catalog==null||catalog.Version!=1||catalog.Platform!="windows-amd64"||catalog.Packages==null||catalog.Packages.Count>4||catalog.Packages.Select(p=>p.Id).Distinct().Count()!=catalog.Packages.Count)throw new InvalidDataException("Некорректный каталог основных движков");foreach(var package in catalog.Packages){ValidatePackage(package);List<string> features;if(package.Id=="singbox"&&(catalog.Features==null||!catalog.Features.TryGetValue(package.Id,out features)||!features.Contains("amneziawg")))throw new InvalidDataException("Пакет sing-box без подтверждённой поддержки AmneziaWG не подходит MCRF");}}
  public static void ValidatePackage(EnginePackage package){if(package==null||MainFile(package.Id)==null||String.IsNullOrWhiteSpace(package.Version)||package.Version.Length>80||package.Files==null||package.Files.Count<1||package.Files.Count>30||package.Files.Sum(f=>f.Size)>250000000||!package.Files.Any(f=>f.Name==MainFile(package.Id))||package.Files.Select(f=>f.Name).Distinct(StringComparer.OrdinalIgnoreCase).Count()!=package.Files.Count||package.Files.Any(f=>f.Name!=Path.GetFileName(f.Name)||f.Name.Contains("..")||f.Name.IndexOfAny(new[]{':','\\','/'})>=0||f.Size<1||f.Size>160000000||!System.Text.RegularExpressions.Regex.IsMatch(f.Sha256??"",@"^[a-fA-F0-9]{64}$")))throw new InvalidDataException("Неожиданный состав пакета движка");if(!AppUpdates.SafeAsset(new UpdateAsset{browser_download_url=package.Url,size=package.Size,digest="sha256:"+package.Sha256},"vnenapravo7-source/mcrf"))throw new InvalidDataException("Пакет движка не из репозитория MCRF или нет SHA-256");}
  public static void Extract(string archive,string directory,EnginePackage package){ValidatePackage(package);AppUpdates.ExtractSelected(archive,directory,package.Files.Select(f=>f.Name).ToArray());foreach(var file in package.Files){string path=Path.Combine(directory,file.Name);if(!File.Exists(path)||new FileInfo(path).Length!=file.Size)throw new InvalidDataException("Не совпал размер файла движка");using(var sha=SHA256.Create())using(var stream=File.OpenRead(path))if(BitConverter.ToString(sha.ComputeHash(stream)).Replace("-","").ToLowerInvariant()!=file.Sha256.ToLowerInvariant())throw new InvalidDataException("Не совпал SHA-256 файла движка");}}
 }
}
