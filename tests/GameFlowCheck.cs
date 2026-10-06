using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using SplifyWin;
class GameFlowCheck {
 const BindingFlags Flags=BindingFlags.Instance|BindingFlags.NonPublic;
 [DllImport("kernel32.dll")] static extern bool QueryPerformanceCounter(out long value);
 [DllImport("kernel32.dll")] static extern bool QueryPerformanceFrequency(out long value);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr LoadLibraryEx(string path,IntPtr file,uint flags);
 [DllImport("kernel32.dll",CharSet=CharSet.Ansi)] static extern IntPtr GetProcAddress(IntPtr module,string name);
 [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
 [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate bool CompileFn([MarshalAs(UnmanagedType.LPStr)]string filter,int layer,IntPtr result,uint length,out IntPtr error,out uint position);
 static bool sent;static int kind=1,protocol=17,pid;static long age;static string ip="::ffff:54.115.1.2";
 static bool Receive(IntPtr handle,IntPtr packet,uint length,out uint received,IntPtr address){
  received=0;if(sent)return false;sent=true;Marshal.Copy(new byte[80],0,address,80);long now,freq;QueryPerformanceCounter(out now);QueryPerformanceFrequency(out freq);
  Marshal.WriteInt64(address,now-age*freq);Marshal.WriteInt32(address,8,2|(kind<<8));Marshal.WriteInt32(address,32,pid);Marshal.WriteInt16(address,70,7777);Marshal.WriteByte(address,72,(byte)protocol);return true;
 }
 static bool Format(IntPtr address,StringBuilder text,uint length){text.Append(ip);return true;}
 static void SetDelegate(GameFlowObserver observer,string field,string method){var f=typeof(GameFlowObserver).GetField(field,Flags);f.SetValue(observer,Delegate.CreateDelegate(f.FieldType,typeof(GameFlowCheck).GetMethod(method,BindingFlags.Static|BindingFlags.NonPublic)));}
 static Dictionary<string,string>[] Event(string process){sent=false;using(var observer=new GameFlowObserver()){
  typeof(GameFlowObserver).GetField("names",Flags).SetValue(observer,new[]{process});typeof(GameFlowObserver).GetField("running",Flags).SetValue(observer,true);
  SetDelegate(observer,"receive","Receive");SetDelegate(observer,"format","Format");typeof(GameFlowObserver).GetMethod("Observe",Flags).Invoke(observer,new object[]{0});return observer.Drain();
 }}
 static void Check(bool condition,string name){if(!condition)throw new Exception(name);Console.WriteLine("PASS "+name);}
 static int Main(string[] args){try{
  using(var current=Process.GetCurrentProcess()){pid=current.Id;string name=Path.GetFileName(current.MainModule.FileName);
   var records=Event(name);Check(records.Length==1&&records[0]["destinationIP"]=="54.115.1.2"&&records[0]["network"]=="udp"&&records[0]["destinationPort"]=="7777","UDP metadata decoded and IPv4 mapped correctly");
   var recording=new GameRecording();recording.Start(new GamePreset{Name="Test",Processes=new[]{name}});recording.Observe(records);var route=recording.CreateRoute();Check(route.Text.Contains("app:"+name)&&route.Text.Contains("54.115.1.2/32")&&route.MatchMode=="in-apps","recorded route retains process and IP restriction");
   protocol=6;kind=4;Check(Event(name).Single()["network"]=="tcp","SOCKET TCP connect decoded");
   kind=6;Check(Event(name).Length==1,"SOCKET accept decoded");kind=2;Check(Event(name).Length==0,"deleted flow ignored");kind=1;
   Check(Event("unrelated.exe").Length==0,"other processes excluded");age=31;Check(Event(name).Length==0,"stale events rejected");age=0;
   ip="::ffff:127.0.0.1";Check(Event(name).Length==0,"loopback ignored");ip="::ffff:54.115.1.2";pid=int.MaxValue;Check(Event(name).Length==0,"exited or missing PID ignored");pid=current.Id;
  }
  var module=LoadLibraryEx(Path.GetFullPath(args[0]),IntPtr.Zero,8);Check(module!=IntPtr.Zero,"trusted DLL loads without starting driver");try{
   var compile=(CompileFn)Marshal.GetDelegateForFunctionPointer(GetProcAddress(module,"WinDivertHelperCompileFilter"),typeof(CompileFn));
   for(int layer=2;layer<=3;layer++){IntPtr error;uint position;bool valid=compile("(tcp or udp) and not loopback",layer,IntPtr.Zero,0,out error,out position);Check(valid,"observer filter valid at layer "+layer+(valid?"":" "+Marshal.PtrToStringAnsi(error)));}
  }finally{FreeLibrary(module);}
  return 0;
 }catch(Exception ex){Console.Error.WriteLine(ex);return 1;}}
}
