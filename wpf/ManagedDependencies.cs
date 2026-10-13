using System;
using System.IO;
using System.Reflection;
using SplifyWin;
namespace Mcrf.Wpf {
 internal static class ManagedDependencies {
  static bool initialized;
  internal static void Ensure(){if(initialized)return;initialized=true;AppDomain.CurrentDomain.AssemblyResolve+=(s,e)=>{string name=new AssemblyName(e.Name).Name;if(name.IndexOfAny(new[]{'/','\\'})>=0)return null;using(var stream=BundledResources.Open("Mcrf.Managed."+name+".dll")){if(stream==null)return null;using(var bytes=new MemoryStream()){stream.CopyTo(bytes);return Assembly.Load(bytes.ToArray());}}};}
 }
}
