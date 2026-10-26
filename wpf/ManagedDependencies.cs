using System;
using System.IO;
using System.Reflection;
using SplifyWin;
namespace Mcrf.Wpf {
 internal static class ManagedDependencies {
  static bool initialized;
  static readonly System.Collections.Generic.Dictionary<string,Assembly> loaded=new System.Collections.Generic.Dictionary<string,Assembly>(StringComparer.OrdinalIgnoreCase);
  internal static void Ensure(){if(initialized)return;initialized=true;AppDomain.CurrentDomain.AssemblyResolve+=(s,e)=>{string name=new AssemblyName(e.Name).Name;if(name.IndexOfAny(new[]{'/','\\'})>=0)return null;lock(loaded){Assembly existing;if(loaded.TryGetValue(name,out existing))return existing;using(var stream=BundledResources.Open((name=="Microsoft.Web.WebView2.Core"||name=="Microsoft.Web.WebView2.WinForms"?"SplifyWin.Video.":"Mcrf.Managed.")+name+".dll")){if(stream==null)return null;using(var bytes=new MemoryStream()){stream.CopyTo(bytes);var assembly=Assembly.Load(bytes.ToArray());loaded[name]=assembly;return assembly;}}}};}
 }
}
