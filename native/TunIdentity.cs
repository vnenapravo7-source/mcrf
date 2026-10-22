using System;
using System.IO;
using System.Text;
using System.Security.Cryptography;
namespace SplifyWin {
 public static class TunIdentity {
  public static string ForProfile(string root){using(var hash=SHA256.Create()){var bytes=hash.ComputeHash(Encoding.UTF8.GetBytes(Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar).ToUpperInvariant()));return "mcrf-"+BitConverter.ToString(bytes).Replace("-","").Substring(0,10).ToLowerInvariant();}}
 }
}
