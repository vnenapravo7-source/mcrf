using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
class EmbeddedInventoryCheck20261013 {
 static string Hash(Stream input){using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(input)).Replace("-", "").ToLowerInvariant();}
 static int Main(string[] args){try{
  var assembly=Assembly.ReflectionOnlyLoadFrom(Path.GetFullPath(args[0]));
  string packed=Path.Combine(Path.GetFullPath(args[1]),"work","packed-resources");
  int count=0;
  foreach(string name in assembly.GetManifestResourceNames().OrderBy(n=>n)){
   if(!new[]{".exe.deflate",".dll.deflate",".sys",".zip"}.Any(s=>name.EndsWith(s,StringComparison.OrdinalIgnoreCase)))continue;
   string packedHash;
   using(var raw=assembly.GetManifestResourceStream(name))packedHash=Hash(raw);
   string original=Path.Combine(packed,name);
   if(name.EndsWith(".deflate",StringComparison.OrdinalIgnoreCase)){
    if(!File.Exists(original))throw new Exception("Missing cached build input: "+name);
    using(var cached=File.OpenRead(original))if(Hash(cached)!=packedHash)throw new Exception("Embedded bytes differ from build input: "+name);
   }
   string payloadHash;
   using(var raw=assembly.GetManifestResourceStream(name)){
    if(name.EndsWith(".deflate",StringComparison.OrdinalIgnoreCase))using(var plain=new DeflateStream(raw,CompressionMode.Decompress))payloadHash=Hash(plain);
    else payloadHash=Hash(raw);
   }
   Console.WriteLine(name+" | SHA256="+payloadHash+" | "+(name.EndsWith(".deflate")?"matches cached compressed build input":"raw resource inventoried"));count++;
  }
  if(count==0)throw new Exception("No executable resources found.");
  Console.WriteLine("OK "+count+" binary/archive resources read without executing the application or payloads; this is NOT a malware verdict.");return 0;
 }catch(Exception ex){Console.WriteLine(ex);return 1;}}
}
