using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
namespace SplifyWin {
  public static class BundledResources {
    public static Stream Open(string name){var assembly=Assembly.GetExecutingAssembly();var packed=assembly.GetManifestResourceStream(name+".deflate");return packed==null?assembly.GetManifestResourceStream(name):new DeflateStream(packed,CompressionMode.Decompress);}
    public static void Install(string name,string target){
      using(var input=Open(name)){if(input==null)return;var temp=target+"."+Guid.NewGuid().ToString("N")+".new";
        try{using(var output=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None))input.CopyTo(output);
          bool same=false;if(File.Exists(target)&&new FileInfo(target).Length==new FileInfo(temp).Length)using(var sha=SHA256.Create())using(var a=File.OpenRead(temp))using(var b=File.OpenRead(target))same=BitConverter.ToString(sha.ComputeHash(a))==BitConverter.ToString(sha.ComputeHash(b));
          if(same)return;if(File.Exists(target))File.Replace(temp,target,null);else File.Move(temp,target);
        }finally{if(File.Exists(temp))File.Delete(temp);}
      }
    }
  }
}
