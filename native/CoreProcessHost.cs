using System;
using System.IO;
using System.Diagnostics;
using System.Collections.Concurrent;
using System.Text;
namespace SplifyWin {
 // No console is allocated. The pinned Go core closes Box/Wintun itself when
 // its inherited private stdin receives STOP or EOF.
 public static class CoreProcessHost {
  public const string Switch="--mcrf-core-host";
  public const string ControlFlag="--mcrf-stdin-control";
  public const int StopTimeout=8000;
  static readonly ConcurrentDictionary<string,bool> supported=new ConcurrentDictionary<string,bool>();
  public static bool SupportsControl(string executable){if(String.IsNullOrEmpty(executable)||!File.Exists(executable))return false;var file=new FileInfo(executable);string key=file.FullName+"|"+file.Length+"|"+file.LastWriteTimeUtc.Ticks;return supported.GetOrAdd(key,ignored=>{var needle=Encoding.ASCII.GetBytes("mcrf-stdin-control");var buffer=new byte[65536];int matched=0;using(var stream=File.OpenRead(executable)){int read;while((read=stream.Read(buffer,0,buffer.Length))>0)for(int i=0;i<read;i++){matched=buffer[i]==needle[matched]?matched+1:buffer[i]==needle[0]?1:0;if(matched==needle.Length)return true;}}return false;});}
  public static ProcessStartInfo StartInfo(string executable,string config){if(!SupportsControl(executable))throw new InvalidOperationException("Для безопасной остановки TUN требуется совместимое ядро MCRF с приватным каналом управления.");return new ProcessStartInfo(executable,"--disable-color run "+ControlFlag+" -c "+ZapretRuntime.Quote(Path.GetFullPath(config))){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(executable),RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};}
  public static int Run(string[] args){return 2;}
 }
}
