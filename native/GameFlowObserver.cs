using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
namespace SplifyWin {
 // FLOW/SOCKET sniffing observes metadata only. It never diverts or sends packets.
 public sealed class GameFlowObserver : IDisposable {
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr LoadLibraryEx(string path,IntPtr file,uint flags);
  [DllImport("kernel32.dll",CharSet=CharSet.Ansi)] static extern IntPtr GetProcAddress(IntPtr module,string name);
  [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool QueryFullProcessImageName(IntPtr process,int flags,StringBuilder path,ref int length);
  [DllImport("kernel32.dll")] static extern bool QueryPerformanceCounter(out long value);
  [DllImport("kernel32.dll")] static extern bool QueryPerformanceFrequency(out long value);
  [UnmanagedFunctionPointer(CallingConvention.Winapi,SetLastError=true)] delegate IntPtr OpenFn([MarshalAs(UnmanagedType.LPStr)]string filter,int layer,short priority,ulong flags);
  [UnmanagedFunctionPointer(CallingConvention.Winapi,SetLastError=true)] delegate bool RecvFn(IntPtr handle,IntPtr packet,uint length,out uint received,IntPtr address);
  [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate bool HandleFn(IntPtr handle);
  [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate bool ShutdownFn(IntPtr handle,int how);
  [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate bool FormatFn(IntPtr address,[Out]StringBuilder buffer,uint length);
  readonly object gate=new object();readonly Queue<Dictionary<string,string>> pending=new Queue<Dictionary<string,string>>();
  IntPtr module;readonly IntPtr[] handles={IntPtr.Zero,IntPtr.Zero};readonly Thread[] workers=new Thread[2];
  RecvFn receive;HandleFn close;ShutdownFn shutdown;FormatFn format;string[] names;volatile bool running;
  public bool Running{get{return running;}} public string Error{get;private set;}
  T Function<T>(string name) where T:class {var address=GetProcAddress(module,name);if(address==IntPtr.Zero)throw new IOException("Нет функции наблюдения "+name);return Marshal.GetDelegateForFunctionPointer(address,typeof(T)) as T;}
  public void Start(string directory,GamePreset game){
   Stop();if(workers.Any(t=>t!=null&&t.IsAlive))throw new IOException("Предыдущая запись ещё завершается. Повторите позже.");Error=null;lock(gate)pending.Clear();names=game.Processes.ToArray();
   module=LoadLibraryEx(Path.GetFullPath(Path.Combine(directory,"WinDivert.dll")),IntPtr.Zero,8);
   if(module==IntPtr.Zero)throw new IOException("Не удалось загрузить наблюдение соединений: "+Marshal.GetLastWin32Error());
   try{
    var open=Function<OpenFn>("WinDivertOpen");receive=Function<RecvFn>("WinDivertRecv");close=Function<HandleFn>("WinDivertClose");shutdown=Function<ShutdownFn>("WinDivertShutdown");format=Function<FormatFn>("WinDivertHelperFormatIPv6Address");
    // SNIFF | RECV_ONLY. Both layers require administrator rights, no TUN/VPN.
    for(int i=0;i<2;i++){handles[i]=open("(tcp or udp) and not loopback",i==0?2:3,0,5);if(handles[i]==IntPtr.Zero||handles[i]==new IntPtr(-1)){handles[i]=IntPtr.Zero;throw new IOException("Не удалось начать запись соединений ("+Marshal.GetLastWin32Error()+"). Нужны права администратора Windows.");}}
    running=true;for(int i=0;i<2;i++){int slot=i;workers[i]=new Thread(()=>Observe(slot)){IsBackground=true,Name="MCRF flow recording"};workers[i].Start();}
   }catch{Stop();throw;}
  }
  void Observe(int slot){IntPtr address=Marshal.AllocHGlobal(80);try{
   uint length;while(running&&receive(handles[slot],IntPtr.Zero,0,out length,address)){
    if(!running)break;int bits=Marshal.ReadInt32(address,8),kind=(bits>>8)&255;
    if(kind!=1&&kind!=4&&kind!=6)continue;
    int protocol=Marshal.ReadByte(address,72),port=(ushort)Marshal.ReadInt16(address,70),pid=Marshal.ReadInt32(address,32);
    if((protocol!=6&&protocol!=17)||port==0||pid<=0)continue;
    long now,freq;QueryPerformanceCounter(out now);QueryPerformanceFrequency(out freq);long stamp=Marshal.ReadInt64(address);double elapsed=freq>0?(now-stamp)/(double)freq:-1;if(elapsed<0||elapsed>30)continue;
    try{using(var process=Process.GetProcessById(pid)){
     var handle=process.Handle;if(process.HasExited||process.StartTime.ToUniversalTime()>DateTime.UtcNow.AddSeconds(-elapsed).AddMilliseconds(-10))continue;
     var path=new StringBuilder(2048);int capacity=path.Capacity;if(!QueryFullProcessImageName(handle,0,path,ref capacity)||process.HasExited)continue;
     if(!names.Contains(Path.GetFileName(path.ToString()),StringComparer.OrdinalIgnoreCase))continue;
     var ipText=new StringBuilder(80);if(!format(IntPtr.Add(address,52),ipText,80))continue;IPAddress ip;if(!IPAddress.TryParse(ipText.ToString(),out ip))continue;if(ip.IsIPv4MappedToIPv6)ip=ip.MapToIPv4();if(IPAddress.IsLoopback(ip)||ip.Equals(IPAddress.Any)||ip.Equals(IPAddress.IPv6Any))continue;
     lock(gate){if(pending.Count<4096)pending.Enqueue(new Dictionary<string,string>{{"processPath",path.ToString()},{"destinationIP",ip.ToString()},{"destinationPort",port.ToString()},{"network",protocol==6?"tcp":"udp"}});else Error="Очередь записи переполнена; часть соединений пропущена.";}
    }}catch(System.ComponentModel.Win32Exception){}catch(ArgumentException){}catch(InvalidOperationException){}
   }
   if(running){Error="Наблюдение соединений остановилось: "+Marshal.GetLastWin32Error();running=false;}
  }catch(Exception ex){Error="Ошибка записи: "+ex.Message;running=false;}finally{Marshal.FreeHGlobal(address);}}
  public Dictionary<string,string>[] Drain(){lock(gate){var result=pending.ToArray();pending.Clear();return result;}}
  public void Stop(){running=false;for(int i=0;i<2;i++)if(handles[i]!=IntPtr.Zero){if(shutdown!=null)shutdown(handles[i],1);if(close!=null)close(handles[i]);handles[i]=IntPtr.Zero;}
   bool joined=true;for(int i=0;i<2;i++)if(workers[i]!=null){if(workers[i].IsAlive&&!workers[i].Join(2500))joined=false;else workers[i]=null;}if(joined&&module!=IntPtr.Zero){FreeLibrary(module);module=IntPtr.Zero;}}
  public void Dispose(){Stop();}
 }
}
